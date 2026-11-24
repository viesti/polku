(ns demo.system
  "Integrant system for the demo application."
  (:require [demo.handler]
            [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]))

(defn config
  "Returns the Integrant configuration, reading overrides from the environment."
  []
  {:demo/handler {}
   :demo/server  {:handler (ig/ref :demo/handler)
                  :port    (parse-long (or (System/getenv "PORT") "3000"))}})

(defmethod ig/init-key :demo/server [_ {:keys [handler port]}]
  (jetty/run-jetty handler {:port port :join? false}))

(defmethod ig/halt-key! :demo/server [_ server]
  (.stop server))

(defn start
  "Starts the whole system and returns it."
  []
  (ig/init (config)))
