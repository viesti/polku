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
                      (response/response {:status "ok" :db-time (str (:now (db/db-now db)))}))}]
   ["/lists"
    ["" {:get  (fn [_] (response/response (db/lists db)))
         :post (fn [{{:keys [name]} :body-params}]
                 (response/response (db/create-list! db {:name name})))}]
    ["/:id"
     ["" {:delete (fn [{{:keys [id]} :path-params}]
                    (db/delete-list! db {:id (parse-long id)})
                    (response/response {:ok true}))}]
     ["/items" {:get  (fn [{{:keys [id]} :path-params}]
                        (response/response (db/list-items db {:list-id (parse-long id)})))
                :post (fn [{{:keys [id]} :path-params {:keys [name]} :body-params}]
                        (response/response
                         (db/create-item! db {:list-id (parse-long id) :name name})))}]]]
   ["/items/:id" {:put    (fn [{{:keys [id]} :path-params {:keys [done]} :body-params}]
                            (response/response
                             (db/update-item! db {:id (parse-long id) :done (boolean done)})))
                  :delete (fn [{{:keys [id]} :path-params}]
                            (db/delete-item! db {:id (parse-long id)})
                            (response/response {:ok true}))}]])

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
