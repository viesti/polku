(ns polku.client.snap
  "Takes screenshots of the current tab for chat messages, with the user's drawing on top.")

(def ^:private max-width 1600)
(def ^:private settle-ms 500)

(defn- open-stream!
  "Resolves with a playing video of the shared tab, asking to share it only the first time."
  [!snap]
  (if-let [video (:video @!snap)]
    (js/Promise.resolve video)
    (let [^js devices (.-mediaDevices js/navigator)]
      (if-not (and devices (.-getDisplayMedia devices))
        (js/Promise.reject (js/Error. "This browser cannot take screenshots of the page."))
        (-> (.getDisplayMedia devices #js {:video              #js {:displaySurface "browser" :cursor "never"}
                                           :audio              false
                                           :preferCurrentTab   true
                                           :selfBrowserSurface "include"})
            (.catch (fn [_] (throw (js/Error. "Sharing the tab was not allowed, so no screenshot was taken."))))
            (.then (fn [^js stream]
                     (let [^js video (js/document.createElement "video")
                           ^js track (aget (.getVideoTracks stream) 0)]
                       (set! (.-muted video) true)
                       (set! (.-srcObject video) stream)
                       (.addEventListener track "ended" (fn [_] (reset! !snap {})))
                       (reset! !snap {:stream stream :video video})
                       (.then (.play video) (fn [_] video))))))))))

(defn- fresh-frame
  "Resolves once the video has shown two new frames, or after `settle-ms` on a page that does not repaint."
  [^js video]
  (js/Promise.
   (fn [resolve _]
     (js/setTimeout resolve settle-ms)
     (if (.-requestVideoFrameCallback video)
       (.requestVideoFrameCallback video (fn [] (.requestVideoFrameCallback video (fn [] (resolve nil)))))
       (js/setTimeout resolve 200)))))

(defn capture!
  "Resolves with a JPEG data URL of the tab with `drawing` (a viewport-sized canvas) on top.
  The `host` element is hidden while the frame is taken, so the widget does not show in it."
  [!snap ^js host ^js drawing]
  (-> (open-stream! !snap)
      (.then (fn [^js video]
               (set! (.. host -style -visibility) "hidden")
               (.then (fresh-frame video) (fn [_] video))))
      (.then (fn [^js video]
               (let [vw     (.-videoWidth video)
                     vh     (.-videoHeight video)
                     scale  (min 1 (/ max-width vw))
                     w      (js/Math.round (* scale vw))
                     h      (js/Math.round (* scale vh))
                     canvas (js/document.createElement "canvas")
                     ctx    (.getContext canvas "2d")
                     ;; The frame covers the viewport, so CSS pixels map onto it by width
                     css    (/ w js/window.innerWidth)]
                 (set! (.-width canvas) w)
                 (set! (.-height canvas) h)
                 (.drawImage ctx video 0 0 w h)
                 (when drawing
                   (.drawImage ctx drawing 0 0
                               (* css (/ (.-width drawing) js/window.devicePixelRatio))
                               (* css (/ (.-height drawing) js/window.devicePixelRatio))))
                 (.toDataURL canvas "image/jpeg" 0.85))))
      (.finally (fn [] (set! (.. host -style -visibility) "")))))

(defn stop!
  "Ends the tab sharing, so that the next screenshot asks again."
  [!snap]
  (when-let [^js stream (:stream @!snap)]
    (doseq [^js track (.getTracks stream)] (.stop track)))
  (reset! !snap {}))
