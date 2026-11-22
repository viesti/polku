(ns polku.server.git
  "Reads the project's commit history and moves the working tree to an earlier commit."
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(def ^:private max-commits 60)
(def ^:private field-sep "\u001f")
(def ^:private record-sep "\u001e")

(defn- git
  "Runs git in `root` and returns its trimmed output, throwing git's own message on failure."
  [root & args]
  (try
    (str/trim (cp/execFileSync "git" (clj->js args)
                               #js {:cwd root :encoding "utf8" :stdio #js ["ignore" "pipe" "pipe"]}))
    (catch :default e
      (let [msg (str/trim (str (.-stderr ^js e)))]
        (throw (ex-info (if (seq msg) msg (ex-message e)) {:status 409}))))))

(defn- ref-exists? [root ref]
  (try (git root "rev-parse" "--verify" "--quiet" ref) true
       (catch :default _ false)))

(defn- main-branch
  "Names the branch that history is shown from besides HEAD, or nil when neither main nor master exists."
  [root]
  (some #(when (ref-exists? root (str "refs/heads/" %)) %) ["main" "master"]))

(defn history
  "Lists the newest commits reachable from HEAD or the main branch, with the current branch and commit."
  [root]
  (let [head   (git root "rev-parse" "HEAD")
        branch (git root "branch" "--show-current")
        main   (main-branch root)
        out    (apply git root "log" (str "-n" max-commits) "--topo-order"
                      (str "--format=%H" field-sep "%h" field-sep "%an" field-sep "%aI" field-sep
                           "%s" field-sep "%b" record-sep)
                      (cond-> ["HEAD"] main (conj main)))]
    {:branch  branch
     :main    main
     :head    head
     :dirty   (boolean (seq (git root "status" "--porcelain" "--untracked-files=no")))
     :commits (vec (for [rec (str/split out (re-pattern record-sep))
                         :let [rec (str/replace rec #"^\n+" "")]
                         :when (seq rec)
                         :let [[hash short author date subject body] (str/split rec (re-pattern field-sep) -1)]]
                     {:hash hash :short short :author author :date date
                      :subject subject :body (str/trim (or body ""))}))}))

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