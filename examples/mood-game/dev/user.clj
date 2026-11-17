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
  "Stops the system, reloads changed namespaces with clj-reload and starts it again.
  `demo.db` is loaded again first, because clj-reload does not track the SQL file it reads
  and a changed namespace may already call a query that was just added."
  []
  (halt)
  (when (find-ns 'demo.db)
    (require 'demo.db :reload))
  (let [{:keys [loaded]} (reload/reload)]
    (go)
    {:reloaded loaded}))

(defn migrate
  "Applies pending migrations to the running system's database."
  []
  ((requiring-resolve 'demo.migrations/migrate!) (:demo/db system)))
