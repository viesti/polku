(ns polku.server.git
  "Reads the project's commit history and uncommitted changes, saves the changes as a
  commit that is pushed to origin, and moves the working tree to an earlier commit."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private max-commits 60)
(def ^:private field-sep "\u001f")
(def ^:private record-sep "\u001e")
(def ^:private push-timeout-ms 60000)

(defn- git-raw
  "Runs git in `root` and returns its untrimmed output, throwing git's own message on
  failure. `opts` may give `:timeout` in milliseconds and extra `:env` variables."
  [root opts & args]
  (try
    (cp/execFileSync "git" (clj->js args)
                     (cond-> #js {:cwd root :encoding "utf8" :stdio #js ["ignore" "pipe" "pipe"]}
                       (:timeout opts) (doto (unchecked-set "timeout" (:timeout opts)))
                       ;; `process.env` is not a plain object, so it is copied before adding to it
                       (:env opts) (doto (unchecked-set "env" (js/Object.assign #js {} js/process.env
                                                                                (clj->js (:env opts)))))))
    (catch :default e
      (let [msg (str/trim (str (.-stderr ^js e)))]
        (throw (ex-info (if (seq msg) msg (ex-message e)) {:status 409}))))))

(defn- git
  "Runs git in `root` and returns its trimmed output, throwing git's own message on failure."
  [root & args]
  (str/trim (apply git-raw root {} args)))

(defn- ref-exists? [root ref]
  (try (git root "rev-parse" "--verify" "--quiet" ref) true
       (catch :default _ false)))

(defn- main-branch
  "Names the branch that history is shown from besides HEAD, or nil when neither main nor master exists."
  [root]
  (some #(when (ref-exists? root (str "refs/heads/" %)) %) ["main" "master"]))

;; Uncommitted changes and the agent's summary of them

(defn- summary-file
  "Returns the file that holds the agent's summary of the uncommitted changes."
  [root]
  (path/join root "changes.json"))

(defn- read-summary
  "Returns the stored summary of the uncommitted changes when it was written on top of
  the commit `head`, or nil. A summary from before the last commit describes changes
  that have been committed since, so it is left out."
  [root head]
  (try
    (let [s (js->clj (js/JSON.parse (fs/readFileSync (summary-file root) "utf8")) :keywordize-keys true)]
      (when (and (= head (:head s)) (seq (:text s)))
        (select-keys s [:text :updatedAt])))
    (catch :default _ nil)))

(defn write-summary!
  "Stores `text` as the summary of the uncommitted changes, tied to the current commit."
  [root text]
  (fs/mkdirSync root #js {:recursive true})
  (fs/writeFileSync (summary-file root)
                    (js/JSON.stringify (clj->js {:text      text
                                                 :head      (git root "rev-parse" "HEAD")
                                                 :updatedAt (.toISOString (js/Date.))})
                                       nil 2)))

(defn- clear-summary! [root]
  (try (fs/unlinkSync (summary-file root)) (catch :default _ nil)))

(def ^:private status-names
  {\M "modified" \T "modified" \A "added" \D "deleted" \R "renamed" \C "copied" \U "conflict" \? "new"})

(defn- ignored?
  "Checks whether git ignores the path `p`."
  [root p]
  (try (git root "check-ignore" "--quiet" p) true
       (catch :default _ false)))

(defn- workspace-exclude
  "Returns a pathspec that leaves the Polku workspace directory `root` out of git's
  view, or nil when the workspace lies outside the repository or git already ignores
  it. `git add` refuses a pathspec that names an ignored path, even to exclude it."
  [root]
  (let [top (git root "rev-parse" "--show-toplevel")
        rel (path/relative top root)]
    (when (and (seq rel) (not (str/starts-with? rel "..")) (not (ignored? root root)))
      (str ":(top,exclude)" (str/replace rel "\\" "/")))))

(defn- whole-tree
  "Returns the pathspec arguments that cover the whole repository except the workspace."
  [root]
  (into ["--" ":/"] (keep identity [(workspace-exclude root)])))

(defn- changed-files
  "Lists the files that differ from the last commit, untracked ones included, with the
  number of lines added and removed where git can count them. The Polku workspace is
  left out."
  [root]
  (let [spec    (whole-tree root)
        counts  (into {}
                      (for [line (str/split-lines (apply git-raw root {} "diff" "HEAD" "--numstat" "--no-renames" spec))
                            :let [[added removed file] (str/split line #"\t" 3)
                                  added   (js/parseInt added 10)
                                  removed (js/parseInt removed 10)]
                            ;; binary files have "-" for both counts
                            :when (and file (js/Number.isFinite added))]
                        [file {:added added :removed removed}]))
        entries (str/split (apply git-raw root {} "status" "--porcelain=v1" "-z" "--untracked-files=all" spec)
                           #"\u0000")]
    (loop [[e & more] entries
           out        []]
      (cond
        (and (nil? e) (empty? more)) out
        (< (count e) 4)              (recur more out)
        :else
        (let [x    (nth e 0)
              y    (nth e 1)
              file (subs e 3)
              code (if (= \space x) y x)]
          ;; a rename or copy is followed by the old name as an entry of its own
          (recur (if (#{\R \C} x) (rest more) more)
                 (conj out (merge {:path file :status (get status-names code "modified")}
                                  (get counts file)))))))))

(defn changes
  "Returns the uncommitted changes: whether there are any, the changed files and the
  agent's summary of them."
  [root]
  (let [files (changed-files root)]
    {:dirty   (boolean (seq files))
     :files   files
     :summary (when (seq files) (read-summary root (git root "rev-parse" "HEAD")))}))

;; Guards against a stale summary: an agent that writes the new summary from memory
;; after the user saved tends to repeat the changes that are already committed

(defn- first-sentence
  "Returns the first line of `text` up to the end of its first sentence, lower-cased
  and with runs of whitespace collapsed, or nil when it is blank."
  [text]
  (let [line (first (str/split-lines (str/trim (or text ""))))
        line (or (second (re-find #"^(.*?[.!?])(\s|$)" (or line ""))) line)]
    (some-> line (str/replace #"\s+" " ") str/trim str/lower-case not-empty)))

(defn last-commit
  "Returns the short hash and subject of the commit at HEAD, or nil when there is none."
  [root]
  (try
    (let [[short subject] (str/split (git root "log" "-1" "--format=%h%x1f%s") #"\u001f" 2)]
      {:short short :subject subject})
    (catch :default _ nil)))

(defn repeats-last-commit?
  "Checks whether summary `text` starts the way the message of the last commit does,
  which means it describes changes that the last commit already holds."
  [root text]
  (let [a (first-sentence text)
        b (some-> (last-commit root) :subject first-sentence)]
    (boolean (and a b (= a b)))))

(defn- newest-edit
  "Returns the newest modification time in milliseconds among the changed files that
  still exist, or nil."
  [root files]
  (let [top (git root "rev-parse" "--show-toplevel")]
    (some->> files
             (keep (fn [{p :path}]
                     (try (.-mtimeMs (fs/statSync (path/join top p))) (catch :default _ nil))))
             seq
             (apply max))))

(defn summary-state
  "Says whether the stored summary covers the uncommitted changes: `:none` when there
  are no changes, `:missing` when there is no summary for them, `:stale` when a changed
  file was edited after the summary was written, and `:current` otherwise. Returns it
  with the changed files and the summary."
  [root]
  (let [{:keys [files summary]} (changes root)
        edited (newest-edit root files)
        state (cond
                (empty? files) :none
                (nil? summary) :missing
                (and edited (> edited (.getTime (js/Date. (:updatedAt summary))))) :stale
                :else :current)]
    {:state state :files files :summary summary}))

(defn history
  "Lists the newest commits reachable from HEAD or the main branch, with the current
  branch and commit and the uncommitted changes."
  [root]
  (let [head   (git root "rev-parse" "HEAD")
        branch (git root "branch" "--show-current")
        main   (main-branch root)
        out    (apply git root "log" (str "-n" max-commits) "--topo-order"
                      (str "--format=%H" field-sep "%h" field-sep "%an" field-sep "%aI" field-sep
                           "%s" field-sep "%b" record-sep)
                      (cond-> ["HEAD"] main (conj main)))
        chg    (changes root)]
    {:branch  branch
     :main    main
     :head    head
     :dirty   (:dirty chg)
     :changes chg
     :commits (vec (for [rec (str/split out (re-pattern record-sep))
                         :let [rec (str/replace rec #"^\n+" "")]
                         :when (seq rec)
                         :let [[hash short author date subject body] (str/split rec (re-pattern field-sep) -1)]]
                     {:hash hash :short short :author author :date date
                      :subject subject :body (str/trim (or body ""))}))}))

(defn save!
  "Commits every uncommitted change with `message` and pushes the current branch to
  origin. The commit stays when the push fails; the result then has `:pushed false` and
  git's message as `:push-error`."
  [root message]
  (let [message (some-> message str str/trim not-empty)]
    (when-not message
      (throw (ex-info "Write a message that describes the changes." {:status 400})))
    (when-not (seq (changed-files root))
      (throw (ex-info "There are no unsaved changes." {:status 409})))
    (apply git root "add" "--all" (whole-tree root))
    (git root "commit" "--quiet" "-m" message)
    (clear-summary! root)
    (let [hash   (git root "rev-parse" "--short" "HEAD")
          branch (git root "branch" "--show-current")]
      (if-not (seq branch)
        {:hash hash :pushed false :push-error "The commit is not on a branch, so there is nothing to push."}
        (try
          (git-raw root {:timeout push-timeout-ms :env {"GIT_TERMINAL_PROMPT" "0"}}
                   "push" "--set-upstream" "origin" branch)
          {:hash hash :branch branch :pushed true}
          (catch :default e
            {:hash hash :branch branch :pushed false :push-error (ex-message e)}))))))

(defn- free-branch-name [root base]
  (->> (cons base (map #(str base "-" %) (range 2 100)))
       (remove #(ref-exists? root (str "refs/heads/" %)))
       first))

(defn jump!
  "Creates a new branch at `hash` and switches to it. Uncommitted changes come along when git
  can carry them; otherwise git refuses and its message is thrown."
  [root hash]
  (when-not (and (string? hash) (re-matches #"[0-9a-f]{7,40}" hash))
    (throw (ex-info "invalid commit" {:status 400})))
  (let [short  (git root "rev-parse" "--short" hash)
        branch (free-branch-name root (str "history-" short))]
    (git root "switch" "-c" branch hash)
    {:branch branch}))

(defn to-main!
  "Switches to the main branch. Uncommitted changes come along as with `jump!`."
  [root]
  (let [main (or (main-branch root)
                 (throw (ex-info "This project has no main branch." {:status 409})))]
    (git root "switch" main)
    {:branch main}))
