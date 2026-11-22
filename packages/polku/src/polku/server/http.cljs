(ns polku.server.http
  "Ingest server that the browser client posts recordings to."
  (:require ["node:http" :as http]
            [polku.server.approvals :as approvals]
            [polku.server.git :as git]
            [polku.server.store :as store]))

(def ^:private max-body (* 25 1024 1024))
(def ^:private local-origin #"https?://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?")

(defn- read-body
  "Resolves with the request body as a Buffer, or rejects when it exceeds `max-body`."
  [^js req]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js []
           size   (volatile! 0)]
       (.on req "data" (fn [^js chunk]
                         (if (> (vswap! size + (.-length chunk)) max-body)
                           (do (reject (ex-info "payload too large" {:status 413}))
                               (.destroy req))
                           (.push chunks chunk))))
       (.on req "end" #(resolve (js/Buffer.concat chunks)))
       (.on req "error" reject)))))

(defn- read-json
  "Resolves with the parsed JSON body, or an empty object for an empty body."
  [req]
  (.then (read-body req)
         (fn [^js body]
           (if (zero? (.-length body))
             #js {}
             (try (js/JSON.parse (.toString body "utf8"))
                  (catch :default _ (throw (ex-info "invalid JSON" {:status 400}))))))))

(defn- public-status [m]
  (select-keys m [:id :status :message :counts :progress]))

(def ^:private routes
  [["GET" #"/health"
    (fn [_] {:ok true})]

   ["GET" #"/git/log"
    (fn [{:keys [root]}] (git/history root))]

   ["POST" #"/git/jump"
    (fn [{:keys [root req log]}]
      (.then (read-json req)
             (fn [^js body]
               (let [result (git/jump! root (.-hash body))]
                 (log (str "switched to new branch " (:branch result) " at " (.-hash body)))
                 result))))]

   ["POST" #"/git/main"
    (fn [{:keys [root log]}]
      (let [result (git/to-main! root)]
        (log (str "switched to " (:branch result)))
        result))]

   ["GET" #"/approvals"
    (fn [_] {:pending (approvals/pending)})]

   ["POST" #"/approvals"
    (fn [{:keys [req log]}]
      (.then (read-json req)
             (fn [body]
               (let [r (approvals/create! (js->clj body :keywordize-keys true))]
                 (log (str "approval " (:id r) " requested for " (:tool r)))
                 r))))]

   ["GET" #"/approvals/([^/]+)"
    (fn [{:keys [id]}] (approvals/fetch id))]

   ["POST" #"/approvals/([^/]+)/decision"
    (fn [{:keys [req id log]}]
      (.then (read-json req)
             (fn [^js body]
               (let [r (approvals/decide! id (.-status body))]
                 (log (str "approval " id " " (:status r)))
                 r))))]

   ["POST" #"/sessions"
    (fn [{:keys [root req log]}]
      (.then (read-json req)
             (fn [meta]
               (let [m (store/create-session! root (js->clj meta :keywordize-keys true))]
                 (log (str "session " (:id m) " started (" (or (:url m) "unknown page") ")"))
                 (public-status m)))))]

   ["GET" #"/sessions/([^/]+)"
    (fn [{:keys [root id]}]
      (public-status (store/read-manifest root id)))]

   ["POST" #"/sessions/([^/]+)/events"
    (fn [{:keys [root id req]}]
      (store/read-manifest root id)
      (.then (read-json req) (fn [events] {:appended (store/append-events! root id events)})))]

   ["POST" #"/sessions/([^/]+)/audio"
    (fn [{:keys [root id req ^js url]}]
      (store/read-manifest root id)
      (.then (read-body req)
             (fn [chunk]
               (store/append-audio! root id chunk (.get (.-searchParams url) "ext"))
               {:ok true})))]

   ["POST" #"/sessions/([^/]+)/frames"
    (fn [{:keys [root id req ^js url]}]
      (store/read-manifest root id)
      (let [t (js/Number (or (.get (.-searchParams url) "t") js/NaN))]
        (when-not (js/Number.isFinite t)
          (throw (ex-info "missing t" {:status 400})))
        (.then (read-body req) (fn [jpeg] {:file (store/write-frame! root id t jpeg)}))))]

   ["POST" #"/sessions/([^/]+)/dom"
    (fn [{:keys [root id req]}]
      (store/read-manifest root id)
      (.then (read-body req) (fn [html] (store/write-dom! root id html) {:ok true})))]

   ["POST" #"/sessions/([^/]+)/complete"
    (fn [{:keys [root id req log]}]
      (.then (read-json req)
             (fn [^js body]
               (let [m (store/complete-session! root id (.-durationMs body))]
                 (log (str "session " id " complete: " (get-in m [:counts :events]) " events, "
                           (get-in m [:counts :frames]) " frames"))
                 (public-status m)))))]])

(defn- match-route [method pathname]
  (some (fn [[route-method re handler]]
          (when (= method route-method)
            (when-let [m (re-matches re pathname)]
              {:handler handler :id (when (vector? m) (second m))})))
        routes))

(defn- send! [^js res status body]
  (.writeHead res status #js {"Content-Type" "application/json"})
  (.end res (js/JSON.stringify (clj->js body))))

(defn create-server
  "Creates the ingest server without starting it.
  `origins` lists extra allowed browser origins; localhost origins are always allowed."
  [{:keys [root origins log] :or {origins [] log (fn [_])}}]
  (let [origin-ok? (fn [o] (or (re-matches local-origin o) (some #{o "*"} origins)))]
    (http/createServer
     (fn [^js req ^js res]
       (let [origin (.. req -headers -origin)
             url    (js/URL. (.-url req) "http://localhost")]
         (when (and origin (origin-ok? origin))
           (doto res
             (.setHeader "Access-Control-Allow-Origin" origin)
             (.setHeader "Vary" "Origin")
             (.setHeader "Access-Control-Allow-Methods" "GET, POST, OPTIONS")
             (.setHeader "Access-Control-Allow-Headers" "Content-Type")
             (.setHeader "Access-Control-Max-Age" "600")))
         (cond
           (and origin (not (origin-ok? origin)))
           (send! res 403 {:error "origin not allowed"})

           (= "OPTIONS" (.-method req))
           (do (.writeHead res 204) (.end res))

           :else
           (if-let [{:keys [handler id]} (match-route (.-method req) (.-pathname url))]
             (-> (js/Promise.resolve nil)
                 (.then (fn [_] (handler {:root root :req req :url url :id id :log log})))
                 (.then (fn [body] (send! res 200 body)))
                 (.catch (fn [err]
                           (let [status (:status (ex-data err))]
                             (when-not status
                               (log (str "error on " (.-method req) " " (.-pathname url) ": " (.-stack err))))
                             (send! res (or status 500) {:error (ex-message err)})))))
             (send! res 404 {:error "not found"}))))))))

(defn serve
  "Starts the ingest server on the loopback interface and resolves once it is listening."
  [{:keys [root port log] :or {port 4777 log js/console.log} :as opts}]
  (let [^js server (create-server (assoc opts :log log))]
    (js/Promise.
     (fn [resolve reject]
       (.once server "error" reject)
       (.listen server port "127.0.0.1"
                (fn []
                  (log (str "polku server listening on http://127.0.0.1:" (.-port (.address server))
                            ", writing to " (store/sessions-dir root)))
                  (resolve server)))))))
