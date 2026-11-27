(ns polku.server.publish
  "Runs the project's publish command, which the person sets in the
  POLKU_PUBLISH_COMMAND environment variable so that it stays out of version control,
  and remembers how the latest run went."
  (:require ["node:child_process" :as cp]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private max-lines 40)

(defonce ^:private !run (atom nil))

(defn command
  "Returns the publish command from POLKU_PUBLISH_COMMAND, or nil when it is not set."
  []
  (some-> (.. js/process -env -POLKU_PUBLISH_COMMAND) str/trim not-empty))

(defn status
  "Tells whether publishing is set up and how the latest run went: whether it is still
  running, when it started and finished, whether it succeeded and its last output lines."
  []
  (merge {:configured (boolean (command))}
         (select-keys @!run [:running :startedAt :finishedAt :ok :code :output])))

(defn- add-output [run ^js chunk]
  (let [lines (str/split (str (:partial run) (.toString chunk "utf8")) #"\r?\n|\r" -1)]
    (-> run
        (assoc :partial (last lines))
        (update :output #(vec (take-last max-lines (into % (remove str/blank? (butlast lines)))))))))

(defn start!
  "Starts the publish command in the project directory, the parent of the workspace
  `root`, and returns the status. Throws when publishing is not set up or a run is
  still going."
  [root log]
  (let [cmd (or (command)
                (throw (ex-info "Publishing is not set up. Start the Polku server with POLKU_PUBLISH_COMMAND set."
                                {:status 409})))]
    (when (:running @!run)
      (throw (ex-info "Publishing is already running." {:status 409})))
    (reset! !run {:running true :startedAt (.toISOString (js/Date.)) :output [] :partial ""})
    (log "publishing: started")
    (let [^js child (cp/spawn "sh" #js ["-c" cmd] #js {:cwd (path/dirname root)
                                                      :stdio #js ["ignore" "pipe" "pipe"]})
          finish (fn [ok code extra]
                   (swap! !run (fn [run]
                                 (-> run
                                     (update :output #(cond-> %
                                                        (seq (str/trim (:partial run))) (conj (:partial run))
                                                        extra (conj extra)))
                                     (update :output #(vec (take-last max-lines %)))
                                     (assoc :running false :ok ok :code code :partial ""
                                            :finishedAt (.toISOString (js/Date.))))))
                   (log (str "publishing: " (if ok "done" (str "failed" (when code (str " with exit code " code)))))))]
      (.on (.-stdout child) "data" #(swap! !run add-output %))
      (.on (.-stderr child) "data" #(swap! !run add-output %))
      (.on child "error" (fn [^js err] (finish false nil (str "Could not start the command: " (.-message err)))))
      (.on child "close" (fn [code]
                           (when (:running @!run)
                             (finish (zero? code) code nil)))))
    (status)))
