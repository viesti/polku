(ns demo.handler
  "HTTP routes: the JSON API under /api and the single-page frontend everywhere else."
  (:require [clojure.string :as string]
            [demo.db :as db]
            [integrant.core :as ig]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.util.response :as response]))

(defn- save-summary [{:keys [id name created-at]}]
  {:id id :name name :created-at (str (.toInstant created-at))})

(defn- api-routes [{:keys [db]}]
  ["/api"
   ["/health" {:get (fn [_]
                      (response/response {:status "ok" :db-time (str (:now (db/db-now db)))}))}]
   ["/saves"
    ["" {:get  (fn [_]
                 (response/response (mapv save-summary (db/list-saves db))))
         :post (fn [{{:keys [name data]} :body-params}]
                 (if (string? data)
                   (response/response
                    (save-summary (db/insert-save! db {:name (not-empty (some-> name str string/trim))
                                                       :data data})))
                   (response/bad-request {:error "data is required"})))}]
    ["/:id" {:get (fn [{{:keys [id]} :path-params}]
                    (if-let [save (some->> (parse-long id) (hash-map :id) (db/get-save db))]
                      (response/response (assoc (save-summary save) :data (:data save)))
                      (response/not-found {:error "no such save"})))}]]])

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
