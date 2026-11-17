(ns demo.main
  (:require [demo.system :as system])
  (:gen-class))

(defn -main [& _]
  (system/start)
  @(promise))
