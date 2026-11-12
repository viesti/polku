(ns demo.app
  (:require [polku.client.core :as polku]
            [uix.core :refer [$ defui use-effect use-ref use-state]]
            [uix.dom]))

(def max-mood 10)

(def figure-scale 1.6)

(def slot-count 6)

(def reveal-ms 700)

(def options
  [{:label "Do homework" :emoji "📚" :delta -3}
   {:label "Eat candy" :emoji "🍬" :delta 2}
   {:label "Clean your room" :emoji "🧹" :delta -2}
   {:label "Play a game" :emoji "🎮" :delta 3}
   {:label "Go to bed early" :emoji "🛏️" :delta -2}
   {:label "Eat ice cream" :emoji "🍦" :delta 2}
   {:label "Wash the dishes" :emoji "🍽️" :delta -2}
   {:label "Go to the dentist" :emoji "🦷" :delta -5}
   {:label "Eat broccoli" :emoji "🥦" :delta -1}
   {:label "Take out the trash" :emoji "🗑️" :delta -1}
   {:label "Get a puppy" :emoji "🐶" :delta 6}
   {:label "Go to the beach" :emoji "🏖️" :delta 4}
   {:label "Watch cartoons" :emoji "📺" :delta 1}
   {:label "Have a birthday party" :emoji "🎂" :delta 5}])

(defn- random-option [slots]
  (rand-nth (remove (set slots) options)))

(defn- mood-color [mood]
  (cond (<= mood -5) "#dc2626"
        (neg? mood) "#f97316"
        (pos? mood) "#16a34a"
        :else "#1f2937"))

(defn- draw-figure [canvas mood]
  (let [dpr (or js/window.devicePixelRatio 1)
        w js/window.innerWidth
        h js/window.innerHeight
        ctx (.getContext canvas "2d")
        cx 0
        cy 0
        r 36
        color (mood-color mood)]
    (set! (.-width canvas) (* w dpr))
    (set! (.-height canvas) (* h dpr))
    (.setTransform ctx dpr 0 0 dpr 0 0)
    (.clearRect ctx 0 0 w h)
    (.translate ctx (/ w 2) (- (/ h 2) 30 (* 75 figure-scale)))
    (.scale ctx figure-scale figure-scale)
    (set! (.-lineWidth ctx) 4)
    (set! (.-lineCap ctx) "round")
    (set! (.-strokeStyle ctx) color)
    (set! (.-fillStyle ctx) color)
    (.beginPath ctx)
    (.arc ctx cx cy r 0 (* 2 js/Math.PI))
    (.stroke ctx)
    (let [neck (+ cy r)
          hip (+ neck 80)
          line (fn [x1 y1 x2 y2]
                 (.beginPath ctx)
                 (.moveTo ctx x1 y1)
                 (.lineTo ctx x2 y2)
                 (.stroke ctx))
          arm-y (if (pos? mood) -30 25)]
      (line cx neck cx hip)
      (line cx (+ neck 20) (- cx 45) (+ neck 20 arm-y))
      (line cx (+ neck 20) (+ cx 45) (+ neck 20 arm-y))
      (line cx hip (- cx 35) (+ hip 70))
      (line cx hip (+ cx 35) (+ hip 70)))
    (doseq [dx [-13 13]]
      (.beginPath ctx)
      (.arc ctx (+ cx dx) (- cy 8) 3.5 0 (* 2 js/Math.PI))
      (.fill ctx))
    ;; Eyebrows slant inward when angry
    (when (<= mood -5)
      (doseq [s [-1 1]]
        (.beginPath ctx)
        (.moveTo ctx (+ cx (* s 22)) (- cy 22))
        (.lineTo ctx (+ cx (* s 6)) (- cy 15))
        (.stroke ctx)))
    ;; Mouth curves up for positive mood and down for negative mood
    (let [my (+ cy 14)]
      (.beginPath ctx)
      (.moveTo ctx (- cx 14) my)
      (.quadraticCurveTo ctx cx (+ my (* (/ mood max-mood) 18)) (+ cx 14) my)
      (.stroke ctx))))

(defn- option-style [i]
  (let [angle (- (* i (/ (* 2 js/Math.PI) slot-count)) (/ js/Math.PI 2))
        rx (min 340 (* 0.38 js/window.innerWidth))
        ry (min 260 (* 0.36 js/window.innerHeight))]
    {:left (str "calc(50% + " (* rx (js/Math.cos angle)) "px)")
     :top (str "calc(50% - 30px + " (* ry (js/Math.sin angle)) "px)")}))

(defn- mood-text [mood]
  (cond (<= mood -5) "Angry!"
        (neg? mood) "Grumpy"
        (>= mood 5) "Super happy!"
        (pos? mood) "Happy"
        :else "Okay"))

(defui app []
  (let [[mood set-mood!] (use-state 0)
        [slots set-slots!] (use-state #(vec (take slot-count options)))
        [revealed set-revealed!] (use-state nil)
        [_ set-size!] (use-state nil)
        canvas (use-ref nil)]
    (use-effect
     (fn []
       (let [on-resize #(set-size! [js/window.innerWidth js/window.innerHeight])]
         (.addEventListener js/window "resize" on-resize)
         #(.removeEventListener js/window "resize" on-resize)))
     [])
    (use-effect
     (fn [] (draw-figure @canvas mood)))
    ($ :main {:class "relative h-screen w-screen overflow-hidden bg-sky-50 select-none"}
       ($ :canvas {:ref canvas :class "absolute inset-0 h-full w-full"})
       ($ :div {:class "absolute top-6 left-1/2 -translate-x-1/2 text-center"}
          ($ :h1 {:class "text-2xl font-bold text-slate-800"} "Mood Maker")
          ($ :p {:class "text-slate-600"} "Pick things for the stick figure to do and watch how they feel.")
          ($ :p {:class "mt-2 text-lg font-semibold"
                 :style {:color (mood-color mood)}}
             (mood-text mood)))
       (for [[i {:keys [label emoji delta]}] (map-indexed vector slots)]
         ($ :button
            {:key i
             :class (cond
                      (not= i revealed) "absolute -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-slate-300 bg-white px-4 py-2 text-slate-800 shadow hover:bg-slate-50 active:scale-95 transition"
                      (pos? delta) "absolute -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-green-500 bg-green-100 px-4 py-2 text-green-900 shadow transition"
                      :else "absolute -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-red-500 bg-red-100 px-4 py-2 text-red-900 shadow transition")
             :style (option-style i)
             :on-click (fn []
                         (when-not revealed
                           (set-mood! (fn [m] (max (- max-mood) (min max-mood (+ m delta)))))
                           (set-revealed! i)
                           (js/setTimeout
                            (fn []
                              (set-slots! (fn [s] (assoc s i (random-option s))))
                              (set-revealed! nil))
                            reveal-ms)))}
            (str emoji " " label)))
       ($ :div {:class "absolute bottom-14 left-1/2 w-80 max-w-[80vw] -translate-x-1/2"}
          ($ :div {:class "mb-1 flex justify-between text-sm font-medium text-slate-600"}
             ($ :span "Angriness")
             ($ :span (str (js/Math.round (* 100 (/ (- max-mood mood) (* 2 max-mood)))) "%")))
          ($ :div {:class "h-4 overflow-hidden rounded-full bg-slate-200"}
             ($ :div {:class "h-full rounded-full transition-all duration-500"
                      :style {:width (str (* 100 (/ (- max-mood mood) (* 2 max-mood))) "%")
                              :background-color (mood-color mood)}})))
       ($ :button {:class "absolute bottom-4 left-1/2 -translate-x-1/2 text-sm text-slate-500 underline"
                   :on-click #(set-mood! 0)}
          "Start over"))))

(defonce root
  (uix.dom/create-root (js/document.getElementById "app")))

(defn ^:dev/after-load render []
  (uix.dom/render-root ($ app) root))

(defn init []
  (when ^boolean goog.DEBUG
    (polku/init {:endpoint  "http://localhost:4777"
                 :lang      "en-US"
                 :languages ["en-US" "fi-FI"]}))
  (render))
