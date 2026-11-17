(ns demo.app
  (:require [polku.client.core :as polku]
            [uix.core :refer [$ defui use-effect use-ref use-state]]
            [uix.dom]))

(def max-mood 10)

(def figure-scale 1.6)

(def slot-count 6)

(def reveal-ms 700)

(def eat-ms 1800)

(def options
  [{:label "Do homework" :emoji "📚" :delta -3}
   {:anim :eat :label "Eat candy" :emoji "🍬" :delta 2}
   {:label "Clean your room" :emoji "🧹" :delta -2}
   {:label "Play a game" :emoji "🎮" :delta 3}
   {:label "Go to bed early" :emoji "🛏️" :delta -2}
   {:anim :eat :label "Eat ice cream" :emoji "🍦" :delta 2}
   {:label "Wash the dishes" :emoji "🍽️" :delta -2}
   {:label "Go to the dentist" :emoji "🦷" :delta -5}
   {:anim :eat :label "Eat broccoli" :emoji "🥦" :delta -1}
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

(def outline "#1f2937")

(def skin "#fcd9b6")

(def hair "#7c4a1e")

(def pants "#1e3a8a")

(defn- shirt-color [mood]
  (if (zero? mood) "#3b82f6" (mood-color mood)))

(defn- circle [ctx x y r]
  (.beginPath ctx)
  (.arc ctx x y r 0 (* 2 js/Math.PI)))

(defn- paint [ctx fill]
  (set! (.-fillStyle ctx) fill)
  (.fill ctx)
  (.stroke ctx))

(defn- limb
  "Strokes an outlined thick segment through the given points."
  [ctx width fill points]
  (doseq [[w c] [[(+ width 5) outline] [width fill]]]
    (set! (.-lineWidth ctx) w)
    (set! (.-strokeStyle ctx) c)
    (.beginPath ctx)
    (let [[[x y] & more] points]
      (.moveTo ctx x y)
      (doseq [[x y] more] (.lineTo ctx x y)))
    (.stroke ctx))
  (set! (.-lineWidth ctx) 3)
  (set! (.-strokeStyle ctx) outline))

(defn- arm-pose
  "Returns the elbow and hand of the right arm; the left arm mirrors it."
  [mood]
  (cond (pos? mood) [[40 34] [52 2]]
        (<= mood -5) [[46 80] [24 106]]
        :else [[34 78] [40 110]]))

(defn- lerp [[x1 y1] [x2 y2] t]
  [(+ x1 (* t (- x2 x1))) (+ y1 (* t (- y2 y1)))])

(defn- bite-amount
  "Rises from 0 to 1 and back three times as t runs from 0 to 1."
  [t]
  (let [b (js/Math.sin (* t js/Math.PI 3))]
    (* b b)))

(defn- eating-arm [bite]
  [(lerp [40 80] [46 52] bite) (lerp [48 74] [14 24] bite)])

(defn- draw-arm [ctx shirt s [[ex ey] [hx hy]]]
  (limb ctx 9 skin [[(* s ex) ey] [(* s hx) hy]])
  (limb ctx 12 shirt [[(* s 20) 50] [(* s ex) ey]])
  (circle ctx (* s hx) hy 7)
  (paint ctx skin))

(defn- draw-body [ctx mood bite]
  (let [shirt (shirt-color mood)
        pose (arm-pose mood)]
    (doseq [s [-1 1]]
      (limb ctx 14 pants [[(* s 11) 116] [(* s 24) 182]])
      (.beginPath ctx)
      (.ellipse ctx (* s 30) 188 14 7 0 0 (* 2 js/Math.PI))
      (paint ctx "#374151"))
    (.beginPath ctx)
    (.roundRect ctx -21 104 42 18 4)
    (paint ctx pants)
    (doseq [s (if bite [-1] [-1 1])]
      (draw-arm ctx shirt s pose))
    (.beginPath ctx)
    (.rect ctx -7 30 14 16)
    (paint ctx skin)
    (.beginPath ctx)
    (.roundRect ctx -24 42 48 68 [14 14 6 6])
    (paint ctx shirt)
    (.beginPath ctx)
    (.moveTo ctx -9 42)
    (.quadraticCurveTo ctx 0 54 9 42)
    (paint ctx skin)))

(defn- draw-head [ctx mood bite]
  (let [t (/ mood max-mood)]
    (doseq [s [-1 1]]
      (circle ctx (* s 35) 2 7)
      (paint ctx skin))
    (circle ctx 0 0 36)
    (paint ctx skin)
    (.beginPath ctx)
    (.arc ctx 0 -2 38 (* js/Math.PI 1.02) (* js/Math.PI 1.98))
    (doseq [[cx x] [[28 19] [10 0] [-10 -19] [-28 -38]]]
      (.quadraticCurveTo ctx cx -12 x (if (= x -38) -4 -24)))
    (.closePath ctx)
    (paint ctx hair)
    (when (pos? mood)
      (set! (.-globalAlpha ctx) (* 0.6 t))
      (doseq [s [-1 1]]
        (circle ctx (* s 22) 10 6)
        (set! (.-fillStyle ctx) "#f472b6")
        (.fill ctx))
      (set! (.-globalAlpha ctx) 1))
    (doseq [s [-1 1]
            :let [x (* s 13)]]
      (if (>= mood 5)
        (do (.beginPath ctx)
            (.arc ctx x -2 6 (* js/Math.PI 1.1) (* js/Math.PI 1.9))
            (.stroke ctx))
        (do (.beginPath ctx)
            (.ellipse ctx x -4 6.5 8 0 0 (* 2 js/Math.PI))
            (paint ctx "white")
            (circle ctx x -3 3.5)
            (set! (.-fillStyle ctx) outline)
            (.fill ctx)
            (circle ctx (+ x 1.2) -4.5 1.2)
            (set! (.-fillStyle ctx) "white")
            (.fill ctx))))
    ;; Brows lift when happy and pinch down toward the nose when grumpy
    (doseq [s [-1 1]]
      (.beginPath ctx)
      (.moveTo ctx (* s 21) (- -17 (* 3 (max t 0))))
      (.lineTo ctx (* s 7) (if (neg? t) (- -17 (* 8 t)) (- -17 (* 3 t))))
      (.stroke ctx))
    (.beginPath ctx)
    (.arc ctx 0 5 3.5 (* js/Math.PI 0.1) (* js/Math.PI 0.9))
    (.stroke ctx)
    (cond
      (and bite (> bite 0.5))
      (do (.beginPath ctx)
          (.ellipse ctx 0 19 7 (* 7 bite) 0 0 (* 2 js/Math.PI))
          (paint ctx "#991b1b"))

      (>= mood 5)
      (do (.beginPath ctx)
          (.moveTo ctx -15 15)
          (.quadraticCurveTo ctx 0 40 15 15)
          (.closePath ctx)
          (paint ctx "#991b1b"))

      (<= mood -5)
      (do (.beginPath ctx)
          (.roundRect ctx -13 14 26 10 3)
          (paint ctx "white")
          (doseq [x [-4.5 4.5]]
            (.beginPath ctx)
            (.moveTo ctx x 14)
            (.lineTo ctx x 24)
            (.stroke ctx)))

      :else
      (let [my 18]
        (.beginPath ctx)
        (.moveTo ctx -13 my)
        (.quadraticCurveTo ctx 0 (+ my (* t 18)) 13 my)
        (.stroke ctx)))))

(defn- draw-food [ctx emoji bite]
  (let [[_ [hx hy]] (eating-arm bite)]
    (set! (.-font ctx) "24px serif")
    (set! (.-textAlign ctx) "center")
    (set! (.-textBaseline ctx) "bottom")
    (.fillText ctx emoji (- hx 2) (+ hy 6))))

(defn- draw-figure [canvas mood action]
  (let [dpr (or js/window.devicePixelRatio 1)
        w js/window.innerWidth
        h js/window.innerHeight
        ctx (.getContext canvas "2d")]
    (set! (.-width canvas) (* w dpr))
    (set! (.-height canvas) (* h dpr))
    (.setTransform ctx dpr 0 0 dpr 0 0)
    (.clearRect ctx 0 0 w h)
    (.translate ctx (/ w 2) (- (/ h 2) 30 (* 75 figure-scale)))
    (.scale ctx figure-scale figure-scale)
    (set! (.-lineWidth ctx) 3)
    (set! (.-lineCap ctx) "round")
    (set! (.-lineJoin ctx) "round")
    (set! (.-strokeStyle ctx) outline)
    (let [bite (when action (bite-amount (:t action)))]
      (draw-body ctx mood bite)
      (draw-head ctx mood bite)
      (when action
        (draw-arm ctx (shirt-color mood) 1 (eating-arm bite))
        (draw-food ctx (:emoji action) bite)))))

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
        [eating set-eating!] (use-state nil)
        canvas (use-ref nil)]
    (use-effect
     (fn []
       (let [on-resize #(set-size! [js/window.innerWidth js/window.innerHeight])]
         (.addEventListener js/window "resize" on-resize)
         #(.removeEventListener js/window "resize" on-resize)))
     [])
    (use-effect
     (fn []
       (if-let [{:keys [emoji start]} eating]
         (let [frame (atom nil)
               tick (fn tick []
                      (let [t (min 1 (/ (- (js/performance.now) start) eat-ms))]
                        (draw-figure @canvas mood {:emoji emoji :t t})
                        (when (< t 1)
                          (reset! frame (js/requestAnimationFrame tick)))))]
           (tick)
           #(js/cancelAnimationFrame @frame))
         (draw-figure @canvas mood nil))))
    ($ :main {:class "relative h-screen w-screen overflow-hidden bg-sky-50 select-none"}
       ($ :canvas {:ref canvas :class "absolute inset-0 h-full w-full"})
       ($ :div {:class "absolute top-6 left-1/2 -translate-x-1/2 text-center"}
          ($ :h1 {:class "text-2xl font-bold text-slate-800"} "Mood Maker")
          ($ :p {:class "text-slate-600"} "Pick things for the stick figure to do and watch how they feel.")
          ($ :p {:class "mt-2 text-lg font-semibold"
                 :style {:color (mood-color mood)}}
             (mood-text mood)))
       (for [[i {:keys [label emoji delta anim]}] (map-indexed vector slots)]
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
                           (when (= anim :eat)
                             (set-eating! {:emoji emoji :start (js/performance.now)}))
                           (js/setTimeout
                            (fn []
                              (set-slots! (fn [s] (assoc s i (random-option s))))
                              (set-revealed! nil)
                              (set-eating! nil))
                            (if (= anim :eat) eat-ms reveal-ms))))}
            (str emoji " " label)))
       ($ :div {:class "absolute bottom-14 left-1/2 w-80 max-w-[80vw] -translate-x-1/2"}
          ($ :div {:class "mb-1 flex justify-between text-sm font-medium text-slate-600"}
             ($ :span "Angriness")
             ($ :span (str (js/Math.round (* 100 (/ (- max-mood mood) (* 2 max-mood)))) "%")))
          ($ :div {:class "h-4 overflow-hidden rounded-full bg-slate-200"}
             ($ :div {:class "h-full rounded-full transition-all duration-500"
                      :style {:width (str (* 100 (/ (- max-mood mood) (* 2 max-mood))) "%")
                              :background-color (mood-color mood)}})))
       ($ :button {:class "absolute top-6 right-8 rounded-full border-2 border-slate-300 bg-white px-5 py-2 text-lg font-medium text-slate-700 shadow hover:bg-slate-50 active:scale-95 transition"
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
