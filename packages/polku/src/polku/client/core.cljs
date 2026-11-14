(ns polku.client.core
  "Public entry point of the Polku browser client."
  (:require [clojure.string :as str]
            [polku.client.recorder :as recorder]
            [polku.client.widget :as widget]))

(def ^:private pending-key "polku:pending")
(def ^:private poll-ms 1500)

(def ^:private defaults
  {:endpoint       "http://localhost:4777"
   :screen         true
   :audio          true
   :frame-interval 2000})

(defn- remember!
  "Stores the id of the session awaiting an agent, or clears it when `id` is nil."
  [id]
  (try
    (if id
      (js/sessionStorage.setItem pending-key id)
      (js/sessionStorage.removeItem pending-key))
    ;; Storage can be unavailable; the widget then forgets a pending session on reload
    (catch :default _ nil)))

(defn- recall []
  (try (js/sessionStorage.getItem pending-key)
       (catch :default _ nil)))

(defn- options
  "Normalizes a JavaScript options object or a ClojureScript map into the config map."
  [opts]
  (let [m    (if (or (nil? opts) (map? opts)) opts (js->clj opts :keywordize-keys true))
        m    (cond-> m
               (contains? m :frameInterval) (assoc :frame-interval (:frameInterval m)))
        conf (merge defaults
                    {:lang (or (not-empty js/navigator.language) "en-US")}
                    (select-keys m [:endpoint :screen :audio :frame-interval :lang]))]
    (update conf :endpoint str/replace #"/$" "")))

(defn- start-recorder!
  [{:keys [config !state !recorder set-state! host]} overrides]
  (when-not (#{:recording :starting} (:view @!state))
    (set-state! {:view :starting})
    (let [rec (recorder/create
               {:endpoint       (:endpoint config)
                :lang           (:lang config)
                :frame-interval (:frame-interval config)
                :ignore         host
                :on-update      (fn [{:keys [heard] :as update}]
                                  (when (= :recording (:view @!state))
                                    (let [capture (dissoc update :heard)]
                                      (set-state! (cond-> {:capture (merge (:capture @!state) capture)}
                                                    (some? heard) (assoc :heard heard))
                                                  (empty? capture)))))})
          flags (merge (select-keys config [:screen :audio]) overrides)]
      (reset! !recorder rec)
      (-> ((:start rec) flags)
          (.then (fn [{:keys [id capture]}]
                   (set-state! {:view :recording :id id :capture capture :heard "" :started-at (js/Date.now)})
                   id))
          (.catch (fn [^js err]
                    (reset! !recorder nil)
                    (set-state! {:view :error :message (.-message err)})
                    nil))))))

(defn- poll!
  "Polls the server for the session's status until the agent marks it done."
  [{:keys [config !state !poll set-state!] :as ctx} id]
  (js/clearTimeout @!poll)
  (reset! !poll
          (js/setTimeout
           (fn []
             (-> (js/fetch (str (:endpoint config) "/sessions/" id))
                 (.then (fn [^js res]
                          (if (= 404 (.-status res))
                            :gone
                            (.json res))))
                 (.then (fn [^js s]
                          (cond
                            (= :gone s)
                            (do (remember! nil) (set-state! {:view :idle}))

                            (= "done" (.-status s))
                            (do (remember! nil) (set-state! {:view :done :message (.-message s)}))

                            :else
                            (let [status   (.-status s)
                                  progress (mapv #(.-text ^js %) (or (.-progress s) #js []))]
                              (when (and (= :waiting (:view @!state))
                                         (not= [status progress]
                                               [(:status @!state) (:progress @!state)]))
                                (set-state! {:status status :progress progress}))
                              (poll! ctx id)))))
                 ;; The server may be restarting, so the next poll tries again
                 (.catch (fn [_] (poll! ctx id)))))
           poll-ms)))

(defn- stop-recorder!
  [{:keys [!state !recorder set-state!] :as ctx} note]
  (when (= :recording (:view @!state))
    (let [rec @!recorder]
      (set-state! {:view :sending})
      (reset! !recorder nil)
      (-> ((:stop rec) note)
          (.then (fn [id]
                   (remember! id)
                   (set-state! {:view :waiting :id id :status "complete" :progress []})
                   (poll! ctx id)
                   id))
          (.catch (fn [^js err]
                    (set-state! {:view :error :message (str "Sending failed: " (.-message err))})
                    nil))))))

(defn- cancel! [{:keys [!recorder set-state!]}]
  (when-let [rec @!recorder]
    ((:cancel rec)))
  (reset! !recorder nil)
  (set-state! {:view :idle}))

(defn- dismiss! [{:keys [!poll set-state!]}]
  (js/clearTimeout @!poll)
  (remember! nil)
  (set-state! {:view :idle}))

(defn init
  "Mounts the Polku recording widget in the page and returns its controller object.
  Takes a ClojureScript map or a JavaScript object with `endpoint`, `screen`, `audio`,
  `lang` and `frameInterval`. A repeated call returns the existing controller, which
  makes it safe to call from code that hot-reloads."
  [opts]
  (or (unchecked-get js/window "__polku")
      (let [!state    (atom {:view :idle})
            !widget   (atom nil)
            ctx       {:config     (options opts)
                       :!state     !state
                       :!recorder  (atom nil)
                       :!poll      (atom nil)
                       :set-state! (fn set-state!
                                     ([next] (set-state! next false))
                                     ([next heard-only?]
                                      ((:render @!widget) (swap! !state merge next) heard-only?)))}
            w         (widget/mount {:start   #(start-recorder! (assoc ctx :host (:host @!widget)) nil)
                                     :stop    #(stop-recorder! ctx %)
                                     :cancel  #(cancel! ctx)
                                     :dismiss #(dismiss! ctx)})
            ctx       (assoc ctx :host (:host w))
            api       #js {:start    (fn [overrides]
                                       (start-recorder! ctx (js->clj overrides :keywordize-keys true)))
                           :stop     (fn [note] (stop-recorder! ctx note))
                           :cancel   (fn [] (cancel! ctx))
                           :dismiss  (fn [] (dismiss! ctx))
                           :getState (fn [] (clj->js @!state))
                           :destroy  (fn []
                                       (cancel! ctx)
                                       (js/clearTimeout @(:!poll ctx))
                                       ((:destroy w))
                                       (js-delete js/window "__polku"))}]
        (reset! !widget w)
        ((:render w) @!state false)
        ;; A full page reload during an agent run resumes showing that run's status
        (when-let [pending (recall)]
          ((:set-state! ctx) {:view :waiting :id pending :status "complete" :progress []})
          (poll! ctx pending))
        (unchecked-set js/window "__polku" api)
        api)))
