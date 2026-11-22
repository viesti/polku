(ns polku.client.core
  "Public entry point of the Polku browser client."
  (:require [clojure.string :as str]
            [polku.client.recorder :as recorder]
            [polku.client.widget :as widget]))

(def ^:private pending-key "polku:pending")
(def ^:private lang-key "polku:lang")
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

(defn- remember-lang! [lang]
  (try (js/localStorage.setItem lang-key lang)
       (catch :default _ nil)))

(defn- recall-lang []
  (try (js/localStorage.getItem lang-key)
       (catch :default _ nil)))

(defn- options
  "Normalizes a JavaScript options object or a ClojureScript map into the config map.
  `:languages` lists the speech languages the user can choose between. It defaults to `:lang`
  followed by the browser's preferred languages."
  [opts]
  (let [m         (if (or (nil? opts) (map? opts)) opts (js->clj opts :keywordize-keys true))
        m         (cond-> m
                    (contains? m :frameInterval) (assoc :frame-interval (:frameInterval m)))
        lang      (or (not-empty (:lang m)) (not-empty js/navigator.language) "en-US")
        languages (vec (distinct (or (seq (:languages m))
                                     (cons lang (js->clj (or js/navigator.languages #js []))))))
        chosen    (recall-lang)
        conf      (merge defaults
                         (select-keys m [:endpoint :screen :audio :frame-interval])
                         {:languages languages
                          :lang      (cond
                                       (some #{chosen} languages) chosen
                                       (some #{lang} languages)   lang
                                       :else                      (first languages))})]
    (update conf :endpoint str/replace #"/$" "")))

(defn- start-recorder!
  [{:keys [config !state !recorder set-state! host]} overrides]
  (when-not (#{:recording :starting} (:view @!state))
    (set-state! {:view :starting})
    (let [rec (recorder/create
               {:endpoint       (:endpoint config)
                :lang           (:lang @!state)
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
  "Polls the server for the session's status until the agent marks it done. A run whose
  panel is hidden keeps its status in `:hidden` and shows it on the pill in the bar."
  [{:keys [config !state !poll set-state!] :as ctx} id]
  (js/clearTimeout @!poll)
  (reset! !poll
          (js/setTimeout
           (fn []
             (let [shown? #(= :waiting (:view @!state))
                   ;; A redraw during a recording would take focus away from the note field
                   apply! (fn [changes]
                            (if (#{:waiting :idle} (:view @!state))
                              (set-state! changes)
                              (swap! !state merge changes)))]
               (-> (js/fetch (str (:endpoint config) "/sessions/" id))
                   (.then (fn [^js res]
                            (if (= 404 (.-status res))
                              :gone
                              (.json res))))
                   (.then (fn [^js s]
                            (cond
                              (= :gone s)
                              (do (remember! nil)
                                  (apply! (if (shown?) {:view :idle :hidden nil} {:hidden nil})))

                              (= "done" (.-status s))
                              (do (remember! nil)
                                  (apply! (merge {:reply (.-message s)}
                                                 (if (shown?) {:view :done} {:hidden :done}))))

                              :else
                              (let [status   (.-status s)
                                    progress (mapv #(.-text ^js %) (or (.-progress s) #js []))]
                                (when (and (or (shown?) (= :waiting (:hidden @!state)))
                                           (not= [status progress]
                                                 [(:status @!state) (:progress @!state)]))
                                  (apply! {:status status :progress progress}))
                                (poll! ctx id)))))
                   ;; The server may be restarting, so the next poll tries again
                   (.catch (fn [_] (poll! ctx id))))))
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
                   (set-state! {:view :waiting :hidden nil :id id :status "complete" :progress []})
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

(defn- set-lang!
  "Makes `lang` the speech language for the current recording and the ones after it."
  [{:keys [!recorder set-state!]} lang]
  (remember-lang! lang)
  (set-state! {:lang lang})
  (when-let [rec @!recorder]
    ((:set-lang rec) lang)))

(defn- check-languages!
  "Drops the languages that the browser's speech recognition reports as unavailable.
  Browsers without `SpeechRecognition.available` keep the whole list, and so does a check
  that finds no language available."
  [{:keys [!state !recorder set-state!]}]
  (let [^js recognition (or (unchecked-get js/window "SpeechRecognition")
                            (unchecked-get js/window "webkitSpeechRecognition"))]
    (when (and recognition (fn? (.-available recognition)))
      (-> (js/Promise.all
           (clj->js (for [tag (:languages @!state)]
                      ;; The executor turns a synchronous throw into a rejection
                      (-> (js/Promise. (fn [resolve _]
                                         (resolve (.available recognition #js {:langs #js [tag]}))))
                          (.then (fn [status] (when-not (= "unavailable" status) tag)))
                          (.catch (fn [_] tag))))))
          (.then (fn [tags]
                   (let [usable (vec (remove nil? tags))
                         lang   (:lang @!state)
                         lang'  (if (some #{lang} usable) lang (first usable))]
                     (when (and (seq usable) (not= usable (:languages @!state)))
                       (set-state! {:languages usable :lang lang'})
                       (when-let [rec (and (not= lang lang') @!recorder)]
                         ((:set-lang rec) lang'))))))))))

(defn- set-history! [{:keys [!state set-state!]} changes]
  (set-state! {:history (merge (:history @!state) changes)}))

(defn- fetch-json
  "Resolves with the parsed body of a successful response and rejects with the server's error otherwise."
  [url init]
  (-> (js/fetch url init)
      (.then (fn [^js res]
               (.then (.json res)
                      (fn [^js body]
                        (if (.-ok res)
                          (js->clj body :keywordize-keys true)
                          (throw (js/Error. (or (.-error body) (str "HTTP " (.-status res))))))))))))

(defn- load-history! [{:keys [config] :as ctx}]
  (set-history! ctx {:loading? true})
  (-> (fetch-json (str (:endpoint config) "/git/log") nil)
      (.then (fn [log] (set-history! ctx (assoc log :loading? false :error nil))))
      (.catch (fn [^js err]
                (set-history! ctx {:loading? false
                                   :error    (if (= "not found" (.-message err))
                                               "The Polku server is older than this widget. Restart it to see the history."
                                               (str "Could not read the history: " (.-message err)))})))))

(defn- toggle-history! [{:keys [!state] :as ctx}]
  (if (get-in @!state [:history :open?])
    (set-history! ctx {:open? false :notice nil :error nil})
    (do (set-history! ctx {:open? true})
        (load-history! ctx))))

(defn- toggle-commit! [{:keys [!state] :as ctx} hash]
  (let [expanded (or (get-in @!state [:history :expanded]) #{})]
    (set-history! ctx {:expanded (if (expanded hash) (disj expanded hash) (conj expanded hash))})))

(defn- jump!
  "Asks the server to make a new branch at the commit and switch the working tree to it."
  [{:keys [config] :as ctx} hash]
  (set-history! ctx {:busy hash :notice nil :error nil})
  (-> (fetch-json (str (:endpoint config) "/git/jump")
                  #js {:method  "POST"
                       :headers #js {"Content-Type" "application/json"}
                       :body    (js/JSON.stringify #js {:hash hash})})
      (.then (fn [{:keys [branch]}]
               (set-history! ctx {:busy nil :notice (str "Switched to the new branch " branch ".")})
               (load-history! ctx)))
      (.catch (fn [^js err]
                (set-history! ctx {:busy nil :error (.-message err)})))))

(defn- to-main! [{:keys [config] :as ctx}]
  (set-history! ctx {:busy :main :notice nil :error nil})
  (-> (fetch-json (str (:endpoint config) "/git/main") #js {:method "POST"})
      (.then (fn [{:keys [branch]}]
               (set-history! ctx {:busy nil :notice (str "Switched back to " branch ".")})
               (load-history! ctx)))
      (.catch (fn [^js err]
                (set-history! ctx {:busy nil
                                   :error (if (= "not found" (.-message err))
                                            "The Polku server is older than this widget. Restart it to switch branches."
                                            (.-message err))})))))

(defn- watch-approvals!
  "Polls the server for the agent's unanswered permission requests and redraws when they change."
  [{:keys [config !state set-state!] :as ctx}]
  (-> (fetch-json (str (:endpoint config) "/approvals") nil)
      (.then (fn [{:keys [pending]}]
               (when (not= (map :id pending) (map :id (:approvals @!state)))
                 (set-state! {:approvals pending}))))
      ;; An older server or one that is down shows no requests, and the next poll tries again
      (.catch (fn [_]
                (when (seq (:approvals @!state))
                  (set-state! {:approvals []}))))
      (.finally #(js/setTimeout (fn [] (watch-approvals! ctx)) poll-ms))))

(defn- decide! [{:keys [config !state set-state!]} id status]
  (set-state! {:approvals (vec (remove #(= id (:id %)) (:approvals @!state)))})
  (fetch-json (str (:endpoint config) "/approvals/" id "/decision")
              #js {:method  "POST"
                   :headers #js {"Content-Type" "application/json"}
                   :body    (js/JSON.stringify #js {:status status})}))

(defn- dismiss!
  "Returns to the bar. A run's panel stays reachable from a pill in the bar until the next request is sent."
  [{:keys [!state set-state!]}]
  (let [v (:view @!state)]
    (set-state! (cond-> {:view :idle}
                  (#{:waiting :done} v) (assoc :hidden v)))))

(defn- show-task! [{:keys [!state set-state!]}]
  (when-let [v (and (= :idle (:view @!state)) (:hidden @!state))]
    (set-state! {:view v :hidden nil})))

(defn init
  "Mounts the Polku recording widget in the page and returns its controller object.
  Takes a ClojureScript map or a JavaScript object with `endpoint`, `screen`, `audio`,
  `lang`, `languages` and `frameInterval`. A repeated call returns the existing controller,
  which makes it safe to call from code that hot-reloads."
  [opts]
  (or (unchecked-get js/window "__polku")
      (let [config    (options opts)
            !state    (atom {:view :idle :lang (:lang config) :languages (:languages config)})
            !widget   (atom nil)
            ctx       {:config     config
                       :!state     !state
                       :!recorder  (atom nil)
                       :!poll      (atom nil)
                       :set-state! (fn set-state!
                                     ([next] (set-state! next false))
                                     ([next heard-only?]
                                      ((:render @!widget) (swap! !state merge next) heard-only?)))}
            w         (widget/mount {:start    #(start-recorder! (assoc ctx :host (:host @!widget)) nil)
                                     :stop     #(stop-recorder! ctx %)
                                     :cancel   #(cancel! ctx)
                                     :dismiss  #(dismiss! ctx)
                                     :show-task #(show-task! ctx)
                                     :set-lang #(set-lang! ctx %)
                                     :toggle-history #(toggle-history! ctx)
                                     :toggle-commit  #(toggle-commit! ctx %)
                                     :jump           #(jump! ctx %)
                                     :to-main        #(to-main! ctx)
                                     :decide         #(decide! ctx %1 %2)})
            ctx       (assoc ctx :host (:host w))
            api       #js {:start    (fn [overrides]
                                       (start-recorder! ctx (js->clj overrides :keywordize-keys true)))
                           :stop     (fn [note] (stop-recorder! ctx note))
                           :cancel   (fn [] (cancel! ctx))
                           :dismiss  (fn [] (dismiss! ctx))
                           :setLang  (fn [lang] (set-lang! ctx lang))
                           :getState (fn [] (clj->js @!state))
                           :destroy  (fn []
                                       (cancel! ctx)
                                       (js/clearTimeout @(:!poll ctx))
                                       ((:destroy w))
                                       (js-delete js/window "__polku"))}]
        (reset! !widget w)
        ((:render w) @!state false)
        (check-languages! ctx)
        (watch-approvals! ctx)
        ;; A full page reload during an agent run resumes showing that run's status
        (when-let [pending (recall)]
          ((:set-state! ctx) {:view :waiting :id pending :status "complete" :progress []})
          (poll! ctx pending))
        (unchecked-set js/window "__polku" api)
        api)))
