(ns dev
  "Entry point for `clojure -M:dev`: an nREPL server plus the running system."
  (:require [nrepl.server :as nrepl]
            [user]))

(defn -main [& _]
  (let [server (nrepl/start-server :bind "127.0.0.1")]
    (spit ".nrepl-port" (:port server))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(.delete (java.io.File. ".nrepl-port"))))
    (println "nREPL listening on port" (:port server))
    (user/go)
    (println "Demo app running at http://localhost:3000")
    @(promise)))
