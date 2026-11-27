(ns polku.server.cli
  "The `polku` command line program."
  (:require ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:util" :as util]
            [clojure.string :as str]
            [polku.server.git :as git]
            [polku.server.http :as http]
            [polku.server.mcp :as mcp]
            [polku.server.store :as store]
            [polku.server.summary :as summary]))

(def ^:private usage "polku <command> [options]

  serve                 Start the local ingest server that the browser client posts to
      --port <n>        Port to listen on (default 4777)
      --host <addr>     Address to listen on (default 127.0.0.1, or $POLKU_HOST). Use 0.0.0.0
                        to reach it from other machines; the CLI commands still use 127.0.0.1
      --origin <url>    Extra allowed browser origin; repeatable. Localhost is always allowed.
                        $POLKU_ORIGIN adds more, separated by commas
  wait                  Block until a session needs the agent, claim it, print its task brief.
                        A new session asks for a plan; an approved plan asks for the change
      --timeout <s>     Exit with code 2 if nothing arrives within this many seconds
  watch -- <cmd...>     For each claimed session, run <cmd> with the task brief on stdin
                        e.g. polku watch -- claude -p --permission-mode acceptEdits
  plan [id] -m <text>   Show <text> in the user's browser as the plan for them to approve or reject
  progress [id] -m <text>
                        Show <text> in the user's browser as the step the agent is on
  done [id] -m <text>   Mark a session finished and show <text> in the user's browser
  changes -m <text>     Store <text> as the summary of the uncommitted changes that the widget's
                        History panel shows and offers as the message when the user saves them
  changes               Print the uncommitted files and their current summary
  summary [id]          Print the brief for a session (default: the latest)
  events [id]           Print the raw events of a session as JSON lines
      --type <t>        Only events of this type; repeatable
  list                  List sessions and their status
  permission-hook       Claude Code PermissionRequest hook: ask in the browser widget and
                        print the answer; falls back to the terminal prompt if unanswered
      --port <n>        Port of the ingest server (default 4777)
      --timeout <s>     Seconds to wait for an answer (default 300)
  mcp                   Serve sessions to an agent as MCP tools over stdio
  init                  Install the Claude Code skill, MCP server entry and gitignore entry in this project

Common options:
      --dir <path>      Workspace directory for session artifacts (default .polku)
")

(def ^:private arg-spec
  {:dir     {:type "string" :default ".polku"}
   :port    {:type "string" :default "4777"}
   :host    {:type "string"}
   :origin  {:type "string" :multiple true :default []}
   :timeout {:type "string"}
   :message {:type "string" :short "m"}
   :type    {:type "string" :multiple true :default []}
   :help    {:type "boolean" :short "h"}})

(def ^:private version
  (.-version (js/JSON.parse (fs/readFileSync (path/join js/__dirname ".." "package.json") "utf8"))))

(defn- fail! [message]
  (js/console.error (str "polku: " message))
  (js/process.exit 1))

(defn- ensure-dirs! [root]
  (fs/mkdirSync (store/sessions-dir root) #js {:recursive true}))

(defn- latest
  "Returns the id of the newest session, optionally only among those with `status`."
  ([root] (latest root nil))
  ([root status]
   (->> (store/list-sessions root)
        (filter #(or (nil? status) (= status (:status %))))
        last
        :id)))

(def ^:private active #{"claimed" "building"})

(defn- latest-active
  "Returns the id of the newest session the agent is planning or building."
  [root]
  (:id (last (filter #(active (:status %)) (store/list-sessions root)))))

(defn- claim-next!
  "Claims the next session that needs the agent and returns its id, or nil when none is ready.
  An approved plan goes first, since its user has already waited for the plan. It becomes
  `building`; a newly completed session becomes `claimed` for planning."
  [root]
  (let [sessions (store/list-sessions root)
        now      (.toISOString (js/Date.))]
    (if-let [id (:id (first (filter #(= "approved" (:status %)) sessions)))]
      (do (store/update-manifest! root id assoc :status "building" :buildingAt now)
          id)
      (when-let [id (:id (first (filter #(= "complete" (:status %)) sessions)))]
        (store/update-manifest! root id assoc :status "claimed" :claimedAt now)
        id))))

(defn- wait-for-session
  "Resolves with the id of the next claimed session, or nil after `timeout-s` seconds."
  [root timeout-s]
  (let [deadline (if timeout-s (+ (js/Date.now) (* 1000 timeout-s)) js/Infinity)]
    (js/Promise.
     (fn [resolve _]
       ((fn poll []
          (if-let [id (claim-next! root)]
            (resolve id)
            (if (> (js/Date.now) deadline)
              (resolve nil)
              (js/setTimeout poll 1000)))))))))

(defn- write-task! [root id]
  (let [task (summary/build-task root id)]
    (fs/writeFileSync (path/join root "CURRENT_TASK.md") task)
    task))

(defn- mark-done! [root id message]
  (store/update-manifest! root id assoc
                          :status "done"
                          :doneAt (.toISOString (js/Date.))
                          :message message))

(defn- run-with-task
  "Runs the command with the task on stdin and resolves with its exit code."
  [[cmd & cmd-args] task]
  (js/Promise.
   (fn [resolve _]
     (let [^js child (child-process/spawn cmd (clj->js (vec cmd-args))
                                          #js {:stdio #js ["pipe" "inherit" "inherit"]})]
       (.on child "error" (fn [^js err]
                            (js/console.error (str "polku: could not run " cmd ": " (.-message err)))
                            (resolve 1)))
       (.on child "exit" resolve)
       (.on (.-stdin child) "error" (fn [_]))
       (.end (.-stdin child) task)))))

(defn- read-stdin []
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js []]
       (.on js/process.stdin "data" #(.push chunks %))
       (.on js/process.stdin "end" #(resolve (.toString (js/Buffer.concat chunks) "utf8")))
       (.on js/process.stdin "error" reject)))))

(defn- post-json [url body]
  (-> (js/fetch url #js {:method  "POST"
                         :headers #js {"Content-Type" "application/json"}
                         :body    (js/JSON.stringify body)})
      (.then (fn [^js res]
               (if (.-ok res) (.json res) (throw (js/Error. (str "HTTP " (.-status res)))))))))

(defn- await-decision
  "Resolves with \"allow\" or \"deny\" once the user answers request `id`, or nil after `timeout-s` seconds."
  [base id timeout-s]
  (let [deadline (+ (js/Date.now) (* 1000 timeout-s))]
    (js/Promise.
     (fn [resolve _]
       ((fn poll []
          (-> (js/fetch (str base "/approvals/" id))
              (.then (fn [^js res] (if (.-ok res) (.json res) #js {:status "gone"})))
              (.then (fn [^js r]
                       (cond
                         (#{"allow" "deny"} (.-status r)) (resolve (.-status r))
                         (not= "pending" (.-status r))    (resolve nil)
                         (> (js/Date.now) deadline)       (resolve nil)
                         :else                            (js/setTimeout poll 500))))
              (.catch (fn [_] (resolve nil))))))))))

(defn- permission-hook
  "Shows a Claude Code permission request in the browser widget and prints the user's answer
  as hook output. Prints nothing when the server is unreachable or nobody answers in time,
  which leaves the decision to the agent's own prompt."
  [{:keys [port timeout]}]
  (let [base     (str "http://127.0.0.1:" port)
        !id      (atom nil)
        withdraw (fn []
                   (if-let [id @!id]
                     (-> (post-json (str base "/approvals/" id "/decision") #js {:status "withdrawn"})
                         (.catch (fn [_]))
                         (.finally #(js/process.exit 0)))
                     (js/process.exit 0)))]
    (.on js/process "SIGTERM" withdraw)
    (.on js/process "SIGINT" withdraw)
    (-> (read-stdin)
        (.then (fn [text]
                 (let [^js hook (js/JSON.parse text)]
                   (post-json (str base "/approvals")
                              #js {:tool (.-tool_name hook) :input (.-tool_input hook)}))))
        (.then (fn [^js r]
                 (reset! !id (.-id r))
                 (await-decision base (.-id r) (js/Number (or timeout 300)))))
        (.then (fn [decision]
                 (if decision
                   (println (js/JSON.stringify
                             (clj->js {:hookSpecificOutput
                                       {:hookEventName "PermissionRequest"
                                        :decision (if (= "allow" decision)
                                                    {:behavior "allow"}
                                                    {:behavior "deny"
                                                     :message  "The user denied this in the browser."})}})))
                   (withdraw))))
        (.catch (fn [_] (withdraw))))))

(defn- watch-loop [root command]
  (.then (wait-for-session root nil)
         (fn [id]
           (js/console.error (str "polku: session " id " → " (str/join " " command)))
           (.then (run-with-task command (write-task! root id))
                  (fn [code]
                    (case (:status (store/read-manifest root id))
                      "claimed"  (mark-done! root id "The agent stopped without proposing a plan.")
                      "building" (mark-done! root id (if (zero? code)
                                                       "The change is in."
                                                       "The agent stopped before finishing this request."))
                      nil)
                    (watch-loop root command))))))

(defn- install-skill! []
  (let [src    (path/join js/__dirname ".." "skill" "SKILL.md")
        dest   (path/resolve ".claude/skills/polku/SKILL.md")
        ignore (path/resolve ".gitignore")
        current (if (fs/existsSync ignore) (fs/readFileSync ignore "utf8") "")]
    (fs/mkdirSync (path/dirname dest) #js {:recursive true})
    (fs/copyFileSync src dest)
    (println "wrote" (path/relative (js/process.cwd) dest))
    (when-not (some #(= ".polku" (str/replace (str/trim %) #"/$" "")) (str/split-lines current))
      (fs/writeFileSync ignore (str current
                                    (when (and (seq current) (not (str/ends-with? current "\n"))) "\n")
                                    ".polku/\n"))
      (println "added .polku/ to .gitignore"))))

(defn- install-mcp-entry!
  "Adds the Polku server to the project's .mcp.json, keeping the entries that are already there."
  []
  (let [file   (path/resolve ".mcp.json")
        config (if (fs/existsSync file)
                 (js->clj (js/JSON.parse (fs/readFileSync file "utf8")))
                 {})]
    (when-not (get-in config ["mcpServers" "polku"])
      (fs/writeFileSync file (str (js/JSON.stringify
                                   (clj->js (assoc-in config ["mcpServers" "polku"]
                                                      {"command" "npx" "args" ["polku" "mcp"]}))
                                   nil 2)
                                  "\n"))
      (println "added the polku server to .mcp.json"))))

(def ^:private commands
  {"serve"   (fn [{:keys [root opts]}]
               (ensure-dirs! root)
               (http/serve {:root    root
                            :port    (js/Number (:port opts))
                            :host    (or (:host opts) (not-empty (.. js/process -env -POLKU_HOST)) "127.0.0.1")
                            :origins (into (vec (:origin opts))
                                           (remove str/blank?)
                                           (some-> (.. js/process -env -POLKU_ORIGIN) (str/split #",") (->> (map str/trim))))}))
   "wait"    (fn [{:keys [root opts]}]
               (ensure-dirs! root)
               (.then (wait-for-session root (some-> (:timeout opts) js/Number))
                      (fn [id]
                        (if id
                          (js/process.stdout.write (write-task! root id))
                          (set! (.-exitCode js/process) 2)))))
   "watch"   (fn [{:keys [root args]}]
               (when (empty? args)
                 (fail! "watch needs a command, e.g. polku watch -- claude -p"))
               (ensure-dirs! root)
               (js/console.error (str "polku: watching " (store/sessions-dir root)))
               (watch-loop root args))
   "plan"    (fn [{:keys [root opts args]}]
               (let [id   (or (first args) (latest root "claimed"))
                     text (some-> (:message opts) str/trim not-empty)]
                 (when-not text (fail! "plan needs a message: polku plan [id] -m <text>"))
                 (when-not id (fail! "no claimed session to plan"))
                 (store/set-plan! root id text)
                 (println "plan for" id "sent; polku wait returns the session once the user approves it")))
   "progress" (fn [{:keys [root opts args]}]
                (let [id   (or (first args) (latest-active root))
                      text (some-> (:message opts) str/trim not-empty)]
                  (when-not text (fail! "progress needs a message: polku progress [id] -m <text>"))
                  (when-not id (fail! "no claimed session to report progress on"))
                  (let [status (:status (store/read-manifest root id))]
                    (when-not (active status)
                      (fail! (str "session " id " is " status ", so the user no longer sees progress for it"))))
                  (store/add-progress! root id text)))
   "done"    (fn [{:keys [root opts args]}]
               (let [id (or (first args) (latest-active root))]
                 (when-not id (fail! "no claimed session to finish"))
                 (mark-done! root id (:message opts))
                 (println "session" id "marked done")))
   "changes" (fn [{:keys [root opts]}]
               (if-let [text (some-> (:message opts) str/trim not-empty)]
                 (do (git/write-summary! root text)
                     (println "summary of the uncommitted changes stored; the History panel shows it"))
                 (let [{:keys [files summary]} (git/changes root)]
                   (if (empty? files)
                     (println "no uncommitted changes")
                     (do (doseq [{:keys [path status added removed]} files]
                           (println (str (.padEnd status 9) " " path
                                         (when added (str "  +" added " -" removed)))))
                         (println)
                         (println (if summary
                                    (str "Summary (" (:updatedAt summary) "):\n" (:text summary))
                                    "No summary yet; store one with: polku changes -m <text>")))))))
   "summary" (fn [{:keys [root args]}]
               (let [id (or (first args) (latest root))]
                 (when-not id (fail! "no sessions recorded yet"))
                 (js/process.stdout.write (summary/build-summary root id))))
   "events"  (fn [{:keys [root opts args]}]
               (let [id    (or (first args) (latest root))
                     types (set (:type opts))]
                 (when-not id (fail! "no sessions recorded yet"))
                 (doseq [e (store/read-events root id)
                         :when (or (empty? types) (types (:type e)))]
                   (println (js/JSON.stringify (clj->js e))))))
   "list"    (fn [{:keys [root]}]
               (doseq [m (store/list-sessions root)]
                 (println (str (:id m) "  " (.padEnd (:status m) 9) "  " (:url m)))))
   "permission-hook" (fn [{:keys [opts]}] (permission-hook opts))
   "mcp"     (fn [{:keys [root]}]
               (mcp/serve {:root root :version version}))
   "init"    (fn [_]
               (install-skill!)
               (install-mcp-entry!))})

(defn main [& _]
  (let [^js parsed (util/parseArgs (clj->js {:allowPositionals true :options arg-spec}))
        ;; `parseArgs` returns a null-prototype object, which `js->clj` leaves unconverted
        opts       (js->clj (js/Object.assign #js {} (.-values parsed)) :keywordize-keys true)
        [command & args] (js->clj (.-positionals parsed))
        run        (get commands command)]
    (if (or (:help opts) (nil? run))
      (do (js/process.stdout.write usage)
          (when (and command (not (:help opts)))
            (set! (.-exitCode js/process) 1)))
      (let [on-error (fn [^js err]
                       (fail! (if (= "EADDRINUSE" (.-code err))
                                (str "port " (:port opts) " is already in use")
                                (ex-message err))))]
        (try
          (-> (js/Promise.resolve (run {:root (path/resolve (:dir opts)) :opts opts :args args}))
              (.catch on-error))
          (catch :default err (on-error err)))))))
