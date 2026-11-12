(ns polku.client.recorder
  "Records one session in the browser and streams it to the Polku server."
  (:require [clojure.string :as str]))

(def ^:private move-ms 50)
(def ^:private dwell-ms 500)
(def ^:private flush-ms 1000)
(def ^:private frame-width 1280)
(def ^:private dom-limit (* 2 1024 1024))
(def ^:private thumb-w 32)
(def ^:private thumb-h 18)
(def ^:private thumb-delta 40)

(defn- css-escape [s]
  (if (and (exists? js/CSS) (.-escape js/CSS))
    (js/CSS.escape s)
    (str/replace s #"[^\w-]" "\\$&")))

(defn selector-for
  "Builds a short CSS selector that locates `el` in the current document."
  [^js el]
  (loop [^js node el
         parts ()]
    (if (or (nil? node) (not= 1 (.-nodeType node)) (>= (count parts) 5))
      (str/join " > " parts)
      (let [tag     (str/lower-case (.-tagName node))
            id      (.getAttribute node "id")
            test-id (.getAttribute node "data-testid")
            anchor  (cond
                      (seq id)               (str "#" (css-escape id))
                      (#{"body" "html"} tag) tag
                      (seq test-id)          (str "[data-testid=\"" test-id "\"]"))]
        (if anchor
          (str/join " > " (cons anchor parts))
          (let [parent (.-parentElement node)
                same   (if parent
                         (.filter (js/Array.from (.-children parent))
                                  (fn [^js c] (= (.-tagName c) (.-tagName node))))
                         #js [])
                part   (if (> (.-length same) 1)
                         (str tag ":nth-of-type(" (inc (.indexOf same node)) ")")
                         tag)]
            (recur parent (cons part parts))))))))

(defn- describe
  "Describes the element as plain data for an event, or returns nil for a non-element."
  [^js el]
  (when (and el (= 1 (.-nodeType el)))
    (let [r    (.getBoundingClientRect el)
          raw  (or (not-empty (.-innerText el))
                   (let [v (.-value el)] (when (string? v) (not-empty v)))
                   (.getAttribute el "aria-label")
                   "")
          text (-> raw str/trim (str/replace #"\s+" " "))]
      {:selector (selector-for el)
       :tag      (str/lower-case (.-tagName el))
       :text     (if (> (count text) 80) (str (subs text 0 77) "...") text)
       :rect     {:x (js/Math.round (.-x r))
                  :y (js/Math.round (.-y r))
                  :w (js/Math.round (.-width r))
                  :h (js/Math.round (.-height r))}})))

(defn- serialize-dom [^js ignore]
  (let [^js clone (.cloneNode js/document.documentElement true)]
    (doseq [^js n (js/Array.from (.querySelectorAll clone (str "script, noscript, " (.-tagName ignore))))]
      (.remove n))
    (let [html (str "<!doctype html>\n" (.-outerHTML clone))]
      (if (> (count html) dom-limit) (subs html 0 dom-limit) html))))

(defn- audio-type []
  (or (when (exists? js/MediaRecorder)
        (some #(when (js/MediaRecorder.isTypeSupported %) %)
              ["audio/webm;codecs=opus" "audio/webm" "audio/mp4"]))
      ""))

(defn- now [!s]
  (js/Math.round (- (js/performance.now) (:t0 @!s))))

(defn- push! [!s type data]
  (swap! !s update :buffer conj (assoc data :t (now !s) :type type)))

(defn- post!
  "Queues a POST behind the earlier ones, so that audio chunks and events are appended in order."
  [{:keys [!s endpoint]} path body content-type]
  (let [q (.then (:queue @!s)
                 (fn [_]
                   (when-not (:failed @!s)
                     (-> (js/fetch (str endpoint path)
                                   #js {:method  "POST"
                                        :headers (if content-type #js {"Content-Type" content-type} #js {})
                                        :body    body})
                         (.then (fn [^js res]
                                  (if (.-ok res)
                                    (.json res)
                                    (throw (js/Error. (str path " returned " (.-status res)))))))
                         (.catch (fn [err] (swap! !s assoc :failed err) nil))))))]
    (swap! !s assoc :queue q)
    q))

(defn- session-path [!s suffix]
  (str "/sessions/" (:id @!s) suffix))

(defn- flush! [{:keys [!s] :as ctx}]
  (let [events (:buffer @!s)]
    (if (empty? events)
      (:queue @!s)
      (do (swap! !s assoc :buffer [])
          (post! ctx (session-path !s "/events") (js/JSON.stringify (clj->js events)) "application/json")))))

(defn- thumb-changed?
  "Compares a thumbnail of the current video frame with the last one that was kept."
  [!s ^js video]
  (let [c    (js/document.createElement "canvas")
        _    (set! (.-width c) thumb-w)
        _    (set! (.-height c) thumb-h)
        ctx  (.getContext c "2d" #js {:willReadFrequently true})
        _    (.drawImage ctx video 0 0 thumb-w thumb-h)
        data (.-data (.getImageData ctx 0 0 thumb-w thumb-h))
        prev (:thumb @!s)
        diff (if prev
               (loop [i 0 acc 0]
                 (if (< i (.-length data))
                   (recur (+ i 4) (+ acc (js/Math.abs (- (aget data i) (aget prev i)))))
                   acc))
               js/Infinity)
        changed? (> diff thumb-delta)]
    (when changed? (swap! !s assoc :thumb data))
    changed?))

(defn- grab-frame!
  "Uploads the current screen frame when it differs from the previous one, or always when `force?`."
  [{:keys [!s] :as ctx} force?]
  (let [^js video (:video @!s)]
    (if-not (and video (>= (.-readyState video) 2) (pos? (.-videoWidth video)))
      (js/Promise.resolve nil)
      (let [changed? (thumb-changed? !s video)]
        (if-not (or changed? force?)
          (js/Promise.resolve nil)
          (let [t      (now !s)
                scale  (min 1 (/ frame-width (.-videoWidth video)))
                canvas (js/document.createElement "canvas")
                w      (js/Math.round (* scale (.-videoWidth video)))
                h      (js/Math.round (* scale (.-videoHeight video)))]
            (set! (.-width canvas) w)
            (set! (.-height canvas) h)
            (.drawImage (.getContext canvas "2d") video 0 0 w h)
            (js/Promise.
             (fn [resolve _]
               (.toBlob canvas
                        (fn [blob]
                          (when blob
                            (post! ctx (session-path !s (str "/frames?t=" t)) blob "image/jpeg"))
                          (resolve nil))
                        "image/jpeg" 0.72)))))))))

(defn- listen! [!s ^js target type handler opts]
  (.addEventListener target type handler opts)
  (swap! !s update :cleanups conj #(.removeEventListener target type handler opts)))

(defn- attach-listeners! [{:keys [!s ^js ignore] :as ctx}]
  (let [opts         #js {:capture true :passive true}
        in-widget?   (fn [^js e] (.includes (.composedPath e) ignore))
        last-move    (volatile! 0)
        dwell-timer  (volatile! nil)
        last-dwell   (volatile! nil)
        scroll-timer (volatile! nil)
        nav          (fn [_] (push! !s "nav" {:url js/location.href}))]
    (listen! !s js/window "mousemove"
             (fn [^js e]
               (when-not (in-widget? e)
                 (let [t (js/performance.now)
                       x (.-clientX e)
                       y (.-clientY e)]
                   (when (>= (- t @last-move) move-ms)
                     (vreset! last-move t)
                     (push! !s "move" {:x x :y y}))
                   (js/clearTimeout @dwell-timer)
                   (vreset! dwell-timer
                            (js/setTimeout
                             (fn []
                               (let [el (js/document.elementFromPoint x y)]
                                 (when (and el (not= el ignore) (not= el @last-dwell))
                                   (vreset! last-dwell el)
                                   (push! !s "dwell" {:x x :y y :target (describe el)}))))
                             dwell-ms)))))
             opts)
    (listen! !s js/window "click"
             (fn [^js e]
               (when-not (in-widget? e)
                 (push! !s "click" {:x (.-clientX e) :y (.-clientY e) :target (describe (.-target e))})
                 ;; The page needs a moment to react before the frame shows the click's result
                 (js/setTimeout #(grab-frame! ctx true) 250)))
             opts)
    (listen! !s js/window "input"
             (fn [^js e]
               (when-not (in-widget? e)
                 (let [^js el  (.-target e)
                       secret? (= "password" (.-type el))]
                   (push! !s "input" {:target (assoc (describe el) :text "")
                                      :length (if secret? 0 (count (str (.-value el))))}))))
             opts)
    (listen! !s js/window "scroll"
             (fn [_]
               (js/clearTimeout @scroll-timer)
               (vreset! scroll-timer
                        (js/setTimeout
                         #(push! !s "scroll" {:x (js/Math.round js/window.scrollX)
                                              :y (js/Math.round js/window.scrollY)})
                         200)))
             opts)
    (listen! !s js/window "resize"
             (fn [_] (push! !s "resize" {:w js/window.innerWidth :h js/window.innerHeight}))
             opts)
    (listen! !s js/window "popstate" nav opts)
    (listen! !s js/window "hashchange" nav opts)
    (swap! !s update :cleanups conj
           #(do (js/clearTimeout @dwell-timer)
                (js/clearTimeout @scroll-timer)))))

(defn- start-screen!
  "Asks to share the current tab and resolves with whether sharing started."
  [!s]
  (let [^js devices (.-mediaDevices js/navigator)]
    (if-not (and devices (.-getDisplayMedia devices))
      (js/Promise.resolve false)
      (-> (.getDisplayMedia devices #js {:video               #js {:displaySurface "browser" :cursor "always"}
                                         :audio               false
                                         :preferCurrentTab    true
                                         :selfBrowserSurface  "include"})
          (.then (fn [stream]
                   (let [^js video (js/document.createElement "video")]
                     (set! (.-muted video) true)
                     (set! (.-srcObject video) stream)
                     (swap! !s assoc :display stream :video video)
                     (-> (.play video)
                         (.catch (fn [_] nil))
                         (.then (fn [_] true))))))
          (.catch (fn [_] false))))))

(defn- start-mic!
  "Asks for the microphone and resolves with whether it was granted."
  [!s]
  (let [^js devices (.-mediaDevices js/navigator)]
    (if-not (and devices (exists? js/MediaRecorder))
      (js/Promise.resolve false)
      (-> (.getUserMedia devices #js {:audio true})
          (.then (fn [stream] (swap! !s assoc :mic stream) true))
          (.catch (fn [_] false))))))

(defn- start-audio-upload! [{:keys [!s] :as ctx}]
  (let [mime (audio-type)
        ext  (if (str/includes? mime "mp4") "mp4" "webm")
        ^js rec (if (seq mime)
                  (js/MediaRecorder. (:mic @!s) #js {:mimeType mime})
                  (js/MediaRecorder. (:mic @!s)))]
    (set! (.-ondataavailable rec)
          (fn [^js e]
            (when (pos? (.-size (.-data e)))
              (post! ctx (session-path !s (str "/audio?ext=" ext)) (.-data e) "application/octet-stream"))))
    (.start rec flush-ms)
    (swap! !s assoc :media-recorder rec)))

(defn- start-speech!
  "Starts browser speech recognition and returns whether it started."
  [{:keys [!s lang on-update]}]
  (if-let [Recognition (or (unchecked-get js/window "SpeechRecognition")
                           (unchecked-get js/window "webkitSpeechRecognition"))]
    (let [^js rec  (new Recognition)
          ;; Offset at which each utterance was first heard, by result index
          heard-at (volatile! {})]
      (set! (.-continuous rec) true)
      (set! (.-interimResults rec) true)
      (set! (.-lang rec) lang)
      (set! (.-onresult rec)
            (fn [^js e]
              (let [^js results (.-results e)
                    interim     (volatile! [])]
                (doseq [i (range (.-resultIndex e) (.-length results))]
                  (let [^js result (aget results i)
                        ^js alt    (aget result 0)
                        text       (str/trim (.-transcript alt))]
                    (when-not (contains? @heard-at i)
                      (vswap! heard-at assoc i (now !s)))
                    (if (.-isFinal result)
                      (do (when (seq text)
                            (swap! !s update :buffer conj
                                   {:t (get @heard-at i) :type "speech" :text text :confidence (.-confidence alt)}))
                          (vswap! heard-at dissoc i))
                      (vswap! interim conj text))))
                (on-update {:heard (str/join " " @interim)}))))
      (set! (.-onerror rec)
            (fn [^js e]
              (when (#{"not-allowed" "service-not-allowed" "network"} (.-error e))
                (swap! !s assoc :recognizing? false)
                (on-update {:speech false}))))
      ;; Chrome ends recognition after a stretch of silence
      (set! (.-onend rec)
            (fn [_]
              (when (:recognizing? @!s)
                (try (.start rec)
                     (catch :default _ (swap! !s assoc :recognizing? false))))))
      (try
        (.start rec)
        (swap! !s assoc :recognition rec :recognizing? true)
        true
        (catch :default _ false)))
    false))

(defn- stop-speech!
  "Stops recognition and resolves once the last result has arrived, or after a short timeout."
  [!s]
  (let [^js rec (:recognition @!s)]
    (if-not (and rec (:recognizing? @!s))
      (js/Promise.resolve nil)
      (do (swap! !s assoc :recognizing? false)
          (js/Promise.
           (fn [resolve _]
             (let [timer (js/setTimeout resolve 1500)]
               (set! (.-onend rec) (fn [_] (js/clearTimeout timer) (resolve nil)))
               (.stop rec))))))))

(defn- abort-speech! [!s]
  (swap! !s assoc :recognizing? false)
  (when-let [^js rec (:recognition @!s)]
    (.abort rec)))

(defn- stop-audio!
  "Stops the microphone recorder and resolves after its last chunk has been queued."
  [!s]
  (let [^js rec (:media-recorder @!s)]
    (if-not (and rec (not= "inactive" (.-state rec)))
      (js/Promise.resolve nil)
      (js/Promise.
       (fn [resolve _]
         (set! (.-onstop rec) (fn [_] (resolve nil)))
         (.stop rec))))))

(defn- detach! [!s]
  (run! js/clearInterval (:timers @!s))
  (doseq [f (:cleanups @!s)] (f))
  (swap! !s assoc :timers [] :cleanups []))

(defn- release! [!s]
  (detach! !s)
  (doseq [^js stream [(:display @!s) (:mic @!s)]
          :when stream
          ^js track (.getTracks stream)]
    (.stop track))
  (swap! !s assoc :display nil :mic nil :video nil))

(defn- open-session!
  "Creates the session on the server and resolves with its id."
  [{:keys [!s endpoint lang]} started-at capture]
  (-> (js/fetch (str endpoint "/sessions")
                #js {:method  "POST"
                     :headers #js {"Content-Type" "application/json"}
                     :body    (js/JSON.stringify
                               (clj->js {:startedAtMs started-at
                                         :url         js/location.href
                                         :title       js/document.title
                                         :viewport    {:w   js/window.innerWidth
                                                       :h   js/window.innerHeight
                                                       :dpr js/window.devicePixelRatio}
                                         :userAgent   js/navigator.userAgent
                                         :lang        lang
                                         :capture     capture}))})
      (.catch (fn [_] nil))
      (.then (fn [^js res]
               (if (and res (.-ok res))
                 (.json res)
                 (do (abort-speech! !s)
                     (release! !s)
                     (throw (js/Error. (str "The Polku server is not reachable at " endpoint)))))))
      (.then (fn [^js body] (.-id body)))))

(defn- start!
  [{:keys [!s frame-interval on-update] :as ctx} {:keys [screen audio]}]
  ;; `getDisplayMedia` needs the user gesture, so it runs before any other async step
  (-> (if screen (start-screen! !s) (js/Promise.resolve false))
      (.then (fn [screen?]
               (-> (if audio (start-mic! !s) (js/Promise.resolve false))
                   (.then (fn [mic?] [screen? mic?])))))
      (.then (fn [[screen? mic?]]
               (swap! !s assoc :t0 (js/performance.now))
               (let [started-at (js/Date.now)
                     capture    {:screen screen?
                                 :audio  mic?
                                 :speech (boolean (and mic? (start-speech! ctx)))}]
                 (-> (open-session! ctx started-at capture)
                     (.then (fn [id]
                              (swap! !s assoc :id id)
                              (when mic? (start-audio-upload! ctx))
                              (attach-listeners! ctx)
                              (swap! !s update :timers conj (js/setInterval #(flush! ctx) flush-ms))
                              (when screen?
                                ;; The first frames after the share prompt closes can still be blank
                                (js/setTimeout #(grab-frame! ctx true) 300)
                                (swap! !s update :timers conj
                                       (js/setInterval #(grab-frame! ctx false) frame-interval))
                                (let [^js track (aget (.getVideoTracks ^js (:display @!s)) 0)]
                                  (.addEventListener track "ended"
                                                     (fn [_]
                                                       (swap! !s assoc :video nil)
                                                       (on-update {:screen false})))))
                              {:id id :capture capture}))))))))

(defn- stop!
  [{:keys [!s ignore] :as ctx} note]
  (let [duration (now !s)
        note     (str/trim (or note ""))]
    (detach! !s)
    (-> (stop-speech! !s)
        (.then (fn [_] (grab-frame! ctx true)))
        (.then (fn [_] (stop-audio! !s)))
        (.then (fn [_]
                 (when (seq note)
                   (swap! !s update :buffer conj {:t duration :type "note" :text note}))
                 (flush! ctx)
                 (post! ctx (session-path !s "/dom") (serialize-dom ignore) "text/html")
                 (post! ctx (session-path !s "/complete")
                        (js/JSON.stringify #js {:durationMs duration})
                        "application/json")))
        (.then (fn [_]
                 (release! !s)
                 (when-let [err (:failed @!s)] (throw err))
                 (:id @!s))))))

(defn- cancel! [{:keys [!s]}]
  (abort-speech! !s)
  (when-let [^js rec (:media-recorder @!s)]
    (when (not= "inactive" (.-state rec)) (.stop rec)))
  (swap! !s update :failed #(or % (js/Error. "cancelled")))
  (release! !s))

(defn create
  "Creates a recorder for one session from `:endpoint`, `:lang`, `:frame-interval`, `:ignore` and `:on-update`.
  `:ignore` is an element whose own events and markup are left out of the recording.
  `:on-update` receives maps with `:heard` (interim speech) or changed capture flags."
  [opts]
  (let [ctx (assoc opts :!s (atom {:id       nil
                                   :t0       0
                                   :buffer   []
                                   :queue    (js/Promise.resolve nil)
                                   :timers   []
                                   :cleanups []}))]
    {:start  (fn [flags] (start! ctx flags))
     :stop   (fn [note] (stop! ctx note))
     :cancel (fn [] (cancel! ctx))}))
