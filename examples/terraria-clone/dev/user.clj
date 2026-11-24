(ns user
  "REPL helpers for running and reloading the system during development."
  (:require [clj-reload.core :as reload]
            [integrant.core :as ig]))

(reload/init {:dirs ["src/clj" "dev"] :no-reload '#{user dev}})

(defonce system nil)

(defn halt
  "Stops the running system."
  []
  (alter-var-root #'system (fn [s] (when s (ig/halt! s)) nil))
  :halted)

(defn go
  "Starts the system if it is not running."
  []
  (alter-var-root #'system (fn [s] (or s ((requiring-resolve 'demo.system/start)))))
  :started)

(defn reset
  "Stops the system, reloads changed namespaces with clj-reload and starts it again."
  []
  (halt)
  (let [{:keys [loaded]} (reload/reload)]
    (go)
    {:reloaded loaded}))
