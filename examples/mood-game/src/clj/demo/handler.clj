(ns demo.handler
  "HTTP routes: the JSON API under /api and the single-page frontend everywhere else."
  (:require [demo.db :as db]
            [integrant.core :as ig]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.util.response :as response]))

(defn- api-routes [{:keys [db]}]
  ["/api"
   ["/health" {:get (fn [_]
                      (response/response {:status "ok" :db-time (str (:now (db/db-now db)))}))}]])

(defn- index [_]
  (-> (response/resource-response "public/index.html")
      (response/content-type "text/html; charset=utf-8")
      (response/header "Cache-Control" "no-store")))

(defmethod ig/init-key :demo/handler [_ deps]
  (ring/ring-handler
   (ring/router
    [(api-routes deps)]
    {:data {:muuntaja   m/instance
            :middleware [parameters/parameters-middleware
                         muuntaja/format-middleware]}})
   (ring/routes
    (ring/create-resource-handler {:path "/" :root "public"})
    ;; Any other path is a client-side route, so it gets the page shell
    (ring/create-default-handler {:not-found index}))))
