(ns polku.server.cli
  "The `polku` command line program."
  (:require ["node:child_process" :as child-process]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:util" :as util]
            [clojure.string :as str]
            [polku.server.http :as http]
            [polku.server.mcp :as mcp]
            [polku.server.store :as store]
            [polku.server.summary :as summary]))

(def ^:private usage "polku <command> [options]

  serve                 Start the local ingest server that the browser client posts to
      --port <n>        Port to listen on (default 4777)
      --origin <url>    Extra allowed browser origin; repeatable. Localhost is always allowed
  wait                  Block until a recorded session is ready, claim it, print its task brief
      --timeout <s>     Exit with code 2 if nothing arrives within this many seconds
  watch -- <cmd...>     For each new session, run <cmd> with the task brief on stdin
                        e.g. polku watch -- claude -p --permission-mode acceptEdits
  progress [id] -m <text>
                        Show <text> in the user's browser as the step the agent is on
  done [id] -m <text>   Mark a session finished and show <text> in the user's browser
  summary [id]          Print the brief for a session (default: the latest)
  events [id]           Print the raw events of a session as JSON lines
      --type <t>        Only events of this type; repeatable
  list                  List sessions and their status
  mcp                   Serve sessions to an agent as MCP tools over stdio
  init                  Install the Claude Code skill, MCP server entry and gitignore entry in this project

Common options:
      --dir <path>      Workspace directory for session artifacts (default .polku)
")

(def ^:private arg-spec
  {:dir     {:type "string" :default ".polku"}
   :port    {:type "string" :default "4777"}
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

(defn- claim-next!
  "Claims the oldest complete session and returns its id, or nil when none is ready."
  [root]
  (when-let [id (:id (first (filter #(= "complete" (:status %)) (store/list-sessions root))))]
    (store/update-manifest! root id assoc :status "claimed" :claimedAt (.toISOString (js/Date.)))
    id))

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

(defn- watch-loop [root command]
  (.then (wait-for-session root nil)
         (fn [id]
           (js/console.error (str "polku: session " id " → " (str/join " " command)))
           (.then (run-with-task command (write-task! root id))
                  (fn [code]
                    (when-not (= "done" (:status (store/read-manifest root id)))
                      (mark-done! root id (if (zero? code)
                                            "The change is in."
                                            "The agent stopped before finishing this request.")))
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
               (http/serve {:root root :port (js/Number (:port opts)) :origins (:origin opts)}))
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
   "progress" (fn [{:keys [root opts args]}]
                (let [id   (or (first args) (latest root "claimed"))
                      text (some-> (:message opts) str/trim not-empty)]
                  (when-not text (fail! "progress needs a message: polku progress [id] -m <text>"))
                  (when-not id (fail! "no claimed session to report progress on"))
                  (let [status (:status (store/read-manifest root id))]
                    (when-not (= "claimed" status)
                      (fail! (str "session " id " is " status ", so the user no longer sees progress for it"))))
                  (store/add-progress! root id text)))
   "done"    (fn [{:keys [root opts args]}]
               (let [id (or (first args) (latest root "claimed"))]
                 (when-not id (fail! "no claimed session to finish"))
                 (mark-done! root id (:message opts))
                 (println "session" id "marked done")))
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
