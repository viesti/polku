(ns roboland.game
  "Isometric 3D world rendered with three.js: terrain, trees, rocks and a robot that walks
  towards the pointer while the mouse button is held down."
  (:require ["three" :as THREE]))

;; ---------------------------------------------------------------------------
;; Terrain height

(def world-size 80)
(def segments 96)
(def water-level 0.0)

(defn- hash2 [x y]
  (let [s (* (js/Math.sin (+ (* x 127.1) (* y 311.7))) 43758.5453)]
    (- s (js/Math.floor s))))

(defn- smooth [t] (* t t (- 3 (* 2 t))))

(defn- value-noise [x y]
  (let [xi (js/Math.floor x) yi (js/Math.floor y)
        xf (- x xi) yf (- y yi)
        a (hash2 xi yi) b (hash2 (inc xi) yi)
        c (hash2 xi (inc yi)) d (hash2 (inc xi) (inc yi))
        u (smooth xf) v (smooth yf)]
    (+ a (* (- b a) u) (* (- c a) v) (* (+ (- a b c) d) u v))))

(defn- fbm [x y]
  (loop [i 0 amp 1.0 freq 1.0 sum 0.0]
    (if (= i 4)
      sum
      (recur (inc i) (* amp 0.5) (* freq 2.0)
             (+ sum (* amp (value-noise (* x freq) (* y freq))))))))

(defn height-at
  "Terrain height at world position x, z."
  [x z]
  (let [n (fbm (* x 0.06) (* z 0.06))
        r (/ (js/Math.sqrt (+ (* x x) (* z z))) (* 0.5 world-size))
        ;; lower the edges so the island is surrounded by water
        edge (* 6 (js/Math.pow (max 0 (- r 0.6)) 1.5))]
    (- (* (- n 0.75) 9) edge -1.2)))

(def ^:private deep-water (THREE/Color. 0x0f3a66))

(defn- terrain-color [h ^js out]
  (cond
    ;; under water the bottom fades from sand to deep blue, so the surface reads as flat
    (< h water-level) (do (.setHex out 0xc9b98a)
                          (.lerp out deep-water (min 1 (+ 0.35 (* 0.45 (- water-level h))))))
    (< h 0.25) (.setHex out 0xd9c78f) ; sand
    (< h 2.6)  (.setHex out 0x5fae4a) ; grass
    (< h 4.2)  (.setHex out 0x3f8a3a) ; dark grass
    (< h 5.6)  (.setHex out 0x8a8580) ; rock
    :else      (.setHex out 0xf2f2f2)) ; snow
  out)

(defn- make-terrain []
  (let [geom (.toNonIndexed (THREE/PlaneGeometry. world-size world-size segments segments))
        _ (.rotateX geom (- (/ js/Math.PI 2)))
        pos (.. geom -attributes -position)
        n (.-count pos)
        colors (js/Float32Array. (* 3 n))
        c (THREE/Color.)]
    (dotimes [i n]
      (.setY pos i (height-at (.getX pos i) (.getZ pos i))))
    ;; one colour per triangle for a low-poly look
    (doseq [t (range (/ n 3))]
      (let [i (* 3 t)
            h (/ (+ (.getY pos i) (.getY pos (+ i 1)) (.getY pos (+ i 2))) 3)
            jitter (if (< h water-level) 0 (* 0.06 (- (hash2 t 7) 0.5)))]
        (terrain-color h c)
        (.offsetHSL c 0 0 jitter)
        (dotimes [k 3]
          (aset colors (+ (* 3 (+ i k)) 0) (.-r c))
          (aset colors (+ (* 3 (+ i k)) 1) (.-g c))
          (aset colors (+ (* 3 (+ i k)) 2) (.-b c)))))
    (.setAttribute geom "color" (THREE/BufferAttribute. colors 3))
    (.computeVertexNormals geom)
    (doto (THREE/Mesh. geom (THREE/MeshLambertMaterial. #js {:vertexColors true :flatShading true}))
      (set! -receiveShadow true))))

(defn- make-water []
  (let [geom (doto (THREE/PlaneGeometry. 400 400) (.rotateX (- (/ js/Math.PI 2))))
        mat (THREE/MeshPhongMaterial. #js {:color 0x2f86d0 :transparent true :opacity 0.6
                                           :depthWrite false :shininess 80 :specular 0x335577})]
    (doto (THREE/Mesh. geom mat)
      (-> .-position (.set 0 water-level 0))
      (set! -receiveShadow true))))

(defn- make-sea-floor
  "Deep blue floor under the open sea beyond the island."
  []
  (let [geom (doto (THREE/PlaneGeometry. 400 400) (.rotateX (- (/ js/Math.PI 2))))]
    (doto (THREE/Mesh. geom (THREE/MeshBasicMaterial. #js {:color 0x0f3a66}))
      (-> .-position (.set 0 -8 0)))))

;; ---------------------------------------------------------------------------
;; Decorations

(defn- lambert [color] (THREE/MeshLambertMaterial. #js {:color color :flatShading true}))

(defn- shadowed [^js mesh]
  (set! (.-castShadow mesh) true)
  mesh)

(defn- make-tree [x z s]
  (let [g (THREE/Group.)
        trunk (shadowed (THREE/Mesh. (THREE/CylinderGeometry. (* 0.15 s) (* 0.2 s) (* 0.8 s) 6)
                                     (lambert 0x7a4f2a)))
        crown (shadowed (THREE/Mesh. (THREE/ConeGeometry. (* 0.8 s) (* 1.8 s) 7)
                                     (lambert 0x2f7d32)))]
    (.set (.-position trunk) 0 (* 0.4 s) 0)
    (.set (.-position crown) 0 (* 1.6 s) 0)
    (.add g trunk crown)
    (.set (.-position g) x (height-at x z) z)
    g))

(defn- make-rock [x z s]
  (let [m (shadowed (THREE/Mesh. (THREE/DodecahedronGeometry. (* 0.5 s) 0) (lambert 0x9a958f)))]
    (.set (.-position m) x (+ (height-at x z) (* 0.15 s)) z)
    (.set (.-rotation m) (* 3 (hash2 x z)) (* 3 (hash2 z x)) 0)
    (.set (.-scale m) 1 (+ 0.6 (* 0.4 (hash2 x 1))) 1)
    m))

(defn- scatter [scene]
  (let [obstacles (array)]
    (dotimes [i 900]
      (let [x (* world-size (- (hash2 i 1.3) 0.5) 0.9)
            z (* world-size (- (hash2 i 2.7) 0.5) 0.9)
            h (height-at x z)
            roll (hash2 i 5.1)]
        (cond
          (and (< 0.4 h 4.0) (< roll 0.16))
          (let [s (+ 0.7 (* 0.6 (hash2 i 9.9)))]
            (.add scene (make-tree x z s))
            (.push obstacles #js [x z (* 0.45 s)]))

          (and (> h 0.2) (< roll 0.21))
          (let [s (+ 0.5 (* 0.9 (hash2 i 4.4)))]
            (.add scene (make-rock x z s))
            (.push obstacles #js [x z (* 0.45 s)])))))
    obstacles))

;; ---------------------------------------------------------------------------
;; Robot

(defn- make-robot []
  (let [g (THREE/Group.)
        metal (lambert 0xb8c0c8)
        dark (lambert 0x4a5560)
        body (shadowed (THREE/Mesh. (THREE/BoxGeometry. 0.8 0.8 0.55) metal))
        head (shadowed (THREE/Mesh. (THREE/BoxGeometry. 0.6 0.45 0.5) metal))
        eye-mat (THREE/MeshBasicMaterial. #js {:color 0x40e0ff})
        eye-l (THREE/Mesh. (THREE/BoxGeometry. 0.12 0.1 0.02) eye-mat)
        eye-r (THREE/Mesh. (THREE/BoxGeometry. 0.12 0.1 0.02) eye-mat)
        stalk (THREE/Mesh. (THREE/CylinderGeometry. 0.02 0.02 0.3 5) dark)
        bulb (THREE/Mesh. (THREE/SphereGeometry. 0.07 8 6) (THREE/MeshBasicMaterial. #js {:color 0xff4040}))
        leg (fn [x]
              (let [pivot (THREE/Group.)
                    m (shadowed (THREE/Mesh. (THREE/BoxGeometry. 0.22 0.45 0.25) dark))]
                (.set (.-position m) 0 -0.22 0)
                (.add pivot m)
                (.set (.-position pivot) x 0.45 0)
                pivot))
        arm (fn [x]
              (let [pivot (THREE/Group.)
                    m (shadowed (THREE/Mesh. (THREE/BoxGeometry. 0.15 0.5 0.15) dark))]
                (.set (.-position m) 0 -0.22 0)
                (.add pivot m)
                (.set (.-position pivot) x 1.1 0)
                pivot))
        leg-l (leg -0.2) leg-r (leg 0.2)
        arm-l (arm -0.5) arm-r (arm 0.5)]
    (.set (.-position body) 0 0.85 0)
    (.set (.-position head) 0 1.5 0)
    (.set (.-position eye-l) -0.13 1.53 0.26)
    (.set (.-position eye-r) 0.13 1.53 0.26)
    (.set (.-position stalk) 0 1.87 0)
    (.set (.-position bulb) 0 2.05 0)
    (.add g body head eye-l eye-r stalk bulb leg-l leg-r arm-l arm-r)
    {:group g :head head :bulb bulb :metal metal :legs [leg-l leg-r] :arms [arm-l arm-r]}))

(defn- make-marker []
  (let [m (THREE/Mesh. (doto (THREE/RingGeometry. 0.35 0.5 24) (.rotateX (- (/ js/Math.PI 2))))
                       (THREE/MeshBasicMaterial. #js {:color 0xffe066 :transparent true :opacity 0.9
                                                      :depthTest false}))]
    (set! (.-visible m) false)
    (set! (.-renderOrder m) 10)
    m))

;; ---------------------------------------------------------------------------
;; Game loop

(def camera-offset (THREE/Vector3. 18 20 18))
(def walk-speed 5.0)
(def max-health 100)
(def ^:private metal-color (THREE/Color. 0xb8c0c8))
(def ^:private rust-color (THREE/Color. 0x8b4513))

(defn- water-depth [x z]
  (max 0 (- water-level (height-at x z))))

(defn- blocked? [obstacles x z]
  (or (> (js/Math.abs x) (- (/ world-size 2) 1))
      (> (js/Math.abs z) (- (/ world-size 2) 1))
      (some (fn [o]
              (let [dx (- x (aget o 0)) dz (- z (aget o 1)) r (+ 0.35 (aget o 2))]
                (< (+ (* dx dx) (* dz dz)) (* r r))))
            obstacles)))

(defn- make-hud
  "Health bar and speech bubble that float above the robot."
  []
  (let [root (js/document.createElement "div")
        bubble (js/document.createElement "div")
        bar (js/document.createElement "div")
        fill (js/document.createElement "div")]
    (set! (.-className root) "pointer-events-none absolute left-0 top-0 flex flex-col items-center gap-1")
    (set! (.-className bubble) "relative whitespace-nowrap rounded-xl border-2 border-slate-800 bg-white px-3 py-1 text-sm font-bold text-slate-900 shadow-lg")
    (set! (.-className bar) "h-2.5 w-20 overflow-hidden rounded-full border border-slate-900/70 bg-slate-900/60")
    (set! (.-className fill) "h-full rounded-full")
    (.append bar fill)
    (.append root bubble bar)
    {:root root :bubble bubble :bar bar :fill fill}))

(defn- start-position [obstacles]
  (or (first (for [r (range 0 30 0.5) a (range 0 6.3 0.4)
                   :let [x (* r (js/Math.cos a)) z (* r (js/Math.sin a))]
                   :when (and (> (height-at x z) 0.5) (not (blocked? obstacles x z)))]
               [x z]))
      [0 0]))

(defn start!
  "Starts the game in the container element. Returns a function that stops it."
  [^js container]
  (let [renderer (THREE/WebGLRenderer. #js {:antialias true})
        scene (THREE/Scene.)
        camera (THREE/OrthographicCamera. -1 1 1 -1 0.1 500)
        sun (THREE/DirectionalLight. 0xffffff 2.2)
        terrain (make-terrain)
        obstacles (scatter scene)
        start (start-position obstacles)
        {:keys [group head bulb legs arms ^js metal]} (make-robot)
        {:keys [^js root ^js bubble ^js bar ^js fill]} (make-hud)
        head-pos (THREE/Vector3.)
        marker (make-marker)
        raycaster (THREE/Raycaster.)
        pointer (THREE/Vector2.)
        state #js {:holding false :zoom 1.0 :t 0 :walk 0 :raf nil :last nil
                   :health max-health :respawned -10}
        respawn! (fn []
                   (.set (.-position group) (first start) (height-at (first start) (second start)) (second start))
                   (set! (.-health state) max-health)
                   (set! (.-respawned state) (.-t state))
                   (set! (.-holding state) false)
                   (set! (.-visible marker) false))
        view-size 14
        resize (fn []
                 (let [w (.-clientWidth container) h (.-clientHeight container)
                       aspect (/ w (max h 1))]
                   (.setSize renderer w h)
                   (set! (.-left camera) (* (- view-size) aspect 0.5))
                   (set! (.-right camera) (* view-size aspect 0.5))
                   (set! (.-top camera) (* view-size 0.5))
                   (set! (.-bottom camera) (* (- view-size) 0.5))
                   (.updateProjectionMatrix camera)))
        set-pointer (fn [^js e]
                      (let [r (.getBoundingClientRect (.-domElement renderer))]
                        (.set pointer
                              (- (* 2 (/ (- (.-clientX e) (.-left r)) (.-width r))) 1)
                              (+ (* -2 (/ (- (.-clientY e) (.-top r)) (.-height r))) 1))))
        on-down (fn [^js e]
                  (when (= 0 (.-button e))
                    (set-pointer e)
                    (set! (.-holding state) true)
                    (.setPointerCapture (.-domElement renderer) (.-pointerId e))))
        on-move (fn [e] (set-pointer e))
        on-up (fn [_]
                (set! (.-holding state) false)
                (set! (.-visible marker) false))
        on-wheel (fn [^js e]
                   (.preventDefault e)
                   (set! (.-zoom camera)
                         (-> (* (.-zoom camera) (js/Math.exp (* -0.001 (.-deltaY e))))
                             (max 0.4) (min 3.5)))
                   (.updateProjectionMatrix camera))
        frame (fn frame [now]
                (let [dt (if (.-last state) (min 0.05 (/ (- now (.-last state)) 1000)) 0)
                      _ (set! (.-last state) now)
                      t (+ (.-t state) dt)
                      pos (.-position group)
                      moving
                      (when (.-holding state)
                        (.setFromCamera raycaster pointer camera)
                        (when-let [hit (aget (.intersectObject raycaster terrain) 0)]
                          (let [p (.-point hit)
                                dx (- (.-x p) (.-x pos)) dz (- (.-z p) (.-z pos))
                                dist (js/Math.sqrt (+ (* dx dx) (* dz dz)))]
                            (.set (.-position marker) (.-x p) (+ (.-y p) 0.05) (.-z p))
                            (set! (.-visible marker) true)
                            (when (> dist 0.15)
                              (let [depth (water-depth (.-x pos) (.-z pos))
                                    speed (* walk-speed (if (> depth 0.1) (max 0.4 (- 0.75 (* 0.1 depth))) 1))
                                    step (min dist (* speed dt))
                                    nx (+ (.-x pos) (* step (/ dx dist)))
                                    nz (+ (.-z pos) (* step (/ dz dist)))
                                    target-rot (js/Math.atan2 dx dz)
                                    diff (-> (- target-rot (.. group -rotation -y))
                                             (+ js/Math.PI) (mod (* 2 js/Math.PI)) (- js/Math.PI))]
                                (set! (.. group -rotation -y)
                                      (+ (.. group -rotation -y) (* diff (min 1 (* 12 dt)))))
                                (cond
                                  (not (blocked? obstacles nx nz)) (do (set! (.-x pos) nx) (set! (.-z pos) nz) true)
                                  ;; slide along obstacles and shores
                                  (not (blocked? obstacles nx (.-z pos))) (do (set! (.-x pos) nx) true)
                                  (not (blocked? obstacles (.-x pos) nz)) (do (set! (.-z pos) nz) true)
                                  :else false))))))
                      walk (if moving (+ (.-walk state) (* dt 11)) (* (.-walk state) 0.85))
                      swing (if moving (* 0.7 (js/Math.sin walk)) (* (.. (first legs) -rotation -x) 0.8))]
                  (set! (.-t state) t)
                  (set! (.-walk state) walk)
                  (set! (.-y pos) (+ (height-at (.-x pos) (.-z pos))
                                     (if moving (* 0.06 (js/Math.abs (js/Math.sin walk))) 0)))
                  (set! (.. (first legs) -rotation -x) swing)
                  (set! (.. (second legs) -rotation -x) (- swing))
                  (set! (.. (first arms) -rotation -x) (- swing))
                  (set! (.. (second arms) -rotation -x) swing)
                  (set! (.. head -rotation -z) (* 0.05 (js/Math.sin (* t 1.7))))
                  ;; water rusts the robot; dry land slowly repairs it
                  (let [depth (water-depth (.-x pos) (.-z pos))
                        wet (> depth 0.1)
                        health (-> (.-health state)
                                   (+ (* dt (if wet (- (+ 6 (* 10 (min depth 2.5)))) 4)))
                                   (min max-health))
                        frac (/ (max health 0) max-health)]
                    (set! (.-health state) health)
                    (.lerpColors (.-color metal) metal-color rust-color (- 1 frac))
                    ;; health bar and speech bubble follow the robot's head on screen
                    (.project (.set head-pos (.-x pos) (+ (.-y pos) 2.3) (.-z pos)) camera)
                    (let [w (.-clientWidth container) h (.-clientHeight container)
                          sx (* (+ 1 (.-x head-pos)) 0.5 w)
                          sy (* (- 1 (.-y head-pos)) 0.5 h)
                          just-respawned (< (- t (.-respawned state)) 2.5)]
                      (set! (.. root -style -transform)
                            (str "translate(" sx "px," sy "px) translate(-50%,-100%)"))
                      (set! (.. bar -style -visibility) (if (or wet (< frac 1)) "visible" "hidden"))
                      (set! (.. fill -style -width) (str (* 100 frac) "%"))
                      (set! (.. fill -style -background)
                            (str "hsl(" (js/Math.round (* 120 frac)) ",80%,45%)"))
                      (set! (.-textContent bubble)
                            (cond just-respawned "Käynnistyn uudelleen…"
                                  wet "Apua, virtapiirini ruostuvat!"
                                  :else ""))
                      (set! (.. bubble -style -display) (if (or wet just-respawned) "block" "none")))
                    (when (<= health 0) (respawn!)))
                  (.setHex ^js (.-color (.-material ^js bulb)) (if (< (mod t 1.2) 0.6) 0xff4040 0x661818))
                  (.set (.. marker -scale) 1 1 1)
                  (.multiplyScalar (.. marker -scale) (+ 1 (* 0.15 (js/Math.sin (* t 8)))))
                  ;; camera follows the robot smoothly
                  (let [cam-target (.add (.clone pos) camera-offset)]
                    (.lerp (.-position camera) cam-target (min 1 (* 5 dt)))
                    (.set (.-position sun) (+ (.-x pos) 15) 30 (+ (.-z pos) 8))
                    (.set (.. sun -target -position) (.-x pos) (.-y pos) (.-z pos))
                    (.lookAt camera (.sub (.clone (.-position camera)) camera-offset)))
                  (.render renderer scene camera)
                  (set! (.-raf state) (js/requestAnimationFrame frame))))]
    (.setPixelRatio renderer (min 2 js/devicePixelRatio))
    (set! (.. renderer -shadowMap -enabled) true)
    (set! (.. renderer -shadowMap -type) THREE/PCFSoftShadowMap)
    (set! (.-background scene) (THREE/Color. 0x9fd4ef))
    (.add scene (THREE/HemisphereLight. 0xdff3ff 0x4a6a3a 1.1))
    (set! (.-castShadow sun) true)
    (.set (.. sun -shadow -mapSize) 2048 2048)
    (let [sc (.. sun -shadow -camera)]
      (set! (.-left sc) -25) (set! (.-right sc) 25)
      (set! (.-top sc) 25) (set! (.-bottom sc) -25)
      (set! (.-far sc) 100))
    (.add scene sun (.-target sun) terrain (make-water) (make-sea-floor) group marker)
    (.set (.-position group) (first start) (height-at (first start) (second start)) (second start))
    (.copy (.-position camera) (.add (.clone (.-position group)) camera-offset))
    (let [el (.-domElement renderer)]
      (set! (.. el -style -display) "block")
      (set! (.. el -style -touchAction) "none")
      (.appendChild container el)
      (.appendChild container root)
      (.addEventListener el "pointerdown" on-down)
      (.addEventListener el "pointermove" on-move)
      (.addEventListener el "pointerup" on-up)
      (.addEventListener el "pointercancel" on-up)
      (.addEventListener el "wheel" on-wheel #js {:passive false})
      (.addEventListener el "contextmenu" (fn [e] (.preventDefault e))))
    (.addEventListener js/window "resize" resize)
    (resize)
    (set! (.-raf state) (js/requestAnimationFrame frame))
    (fn stop! []
      (js/cancelAnimationFrame (.-raf state))
      (.removeEventListener js/window "resize" resize)
      (.dispose renderer)
      (.remove root)
      (.remove (.-domElement renderer)))))
