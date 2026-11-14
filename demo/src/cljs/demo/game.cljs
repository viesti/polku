(ns demo.game
  "Side-scrolling tile world with a player moved by A, D and Space who digs with a
  mouse-aimed pickaxe and collects items from chests in caves."
  (:require [uix.core :refer [$ defui use-effect use-ref use-state]]))

(def T 16)
(def W 480)
(def H 120)

(def air 0)
(def grass 1)
(def dirt 2)
(def stone 3)
(def trunk 4)
(def leaves 5)

(def player-w 18)
(def player-h 38)
(def gravity 0.45)
(def jump-v -9.2)
(def run-v 3.4)
(def max-fall 11)
(def swing-frames 14)
(def reach (* 4.5 T))

(defn- hash2 [x y]
  (let [h (+ (js/Math.imul x 374761393) (js/Math.imul y 668265263))
        h (js/Math.imul (bit-xor h (unsigned-bit-shift-right h 13)) 1274126177)
        h (bit-xor h (unsigned-bit-shift-right h 16))]
    (/ (bit-and h 0xffff) 65536)))

(defn- smooth [t] (* t t (- 3 (* 2 t))))

(defn- noise2 [x y]
  (let [xi (js/Math.floor x) yi (js/Math.floor y)
        fx (smooth (- x xi)) fy (smooth (- y yi))
        a (hash2 xi yi) b (hash2 (inc xi) yi)
        c (hash2 xi (inc yi)) d (hash2 (inc xi) (inc yi))
        top (+ a (* fx (- b a)))
        bot (+ c (* fx (- d c)))]
    (+ top (* fy (- bot top)))))

(defn- idx [x y] (+ x (* y W)))

(defn- tile-at [^js tiles x y]
  (cond
    (or (< x 0) (>= x W)) stone
    (< y 0) air
    (>= y H) stone
    :else (aget tiles (idx x y))))

(defn- solid? [t] (<= grass t stone))

(defn- surface-height [x]
  (js/Math.floor (+ (* H 0.32)
                    (* 10 (noise2 (/ x 40) 0.5))
                    (* 4 (noise2 (/ x 12) 7.5))
                    (* 1.5 (noise2 (/ x 4) 13.5)))))

(defn- cave? [x y]
  (> (/ (+ (noise2 (/ x 14) (/ y 9)) (* 0.5 (noise2 (/ x 5) (/ y 5)))) 1.5)
     0.64))

(defn- plant-tree! [^js tiles x s]
  (let [th (+ 5 (js/Math.floor (* 5 (hash2 x 99))))
        top (- s th)]
    (doseq [y (range top s)]
      (aset tiles (idx x y) trunk))
    (doseq [dx (range -3 4) dy (range -4 3)
            :let [tx (+ x dx) ty (+ top dy)]
            :when (and (<= (+ (* dx dx) (* dy dy 1.1)) 8)
                       (>= ty 0)
                       (= air (aget tiles (idx tx ty))))]
      (aset tiles (idx tx ty) leaves))))

(def loot
  [{:id :torch :name "Torch" :icon "🔥" :min 2 :max 8}
   {:id :rope :name "Rope" :icon "🪢" :min 3 :max 10}
   {:id :bar :name "Iron Bar" :icon "🔩" :min 1 :max 4}
   {:id :potion :name "Healing Potion" :icon "🧪" :min 1 :max 2}
   {:id :ruby :name "Ruby" :icon "💎" :min 1 :max 1}
   {:id :mirror :name "Magic Mirror" :icon "🪞" :min 1 :max 1}])

(def coin {:id :coin :name "Gold Coin" :icon "🪙" :min 5 :max 30})

(defn- roll [{:keys [min max] :as item} r]
  (assoc (select-keys item [:id :name :icon])
         :count (+ min (js/Math.floor (* r (inc (- max min)))))))

(defn- chest-items [tx ty]
  (let [n (inc (js/Math.floor (* 3 (hash2 tx (+ ty 7)))))
        picks (->> (range n)
                   (map #(nth loot (js/Math.floor (* (count loot) (hash2 (+ tx %) (* 31 ty))))))
                   distinct)]
    (into [(roll coin (hash2 ty tx))]
          (map-indexed (fn [i item] (roll item (hash2 (+ tx i) (+ ty 3))))
                       picks))))

(defn- chest-spot
  "Returns the first row in column `x` deep in the stone where a two-tile chest fits
  on a cave floor, or nil."
  [tiles x s]
  (some (fn [y]
          (when (and (every? #(= air (tile-at tiles % y)) [x (inc x)])
                     (every? #(= air (tile-at tiles % (dec y))) [x (inc x)])
                     (every? #(solid? (tile-at tiles % (inc y))) [x (inc x)]))
            y))
        (range (+ s 9) (- H 2))))

(defn- place-chests [tiles ^js surface]
  (loop [x 4 last -100 acc []]
    (if (>= x (- W 5))
      acc
      (let [y (when (and (> (- x last) 14) (< (hash2 x 777) 0.08))
                (chest-spot tiles x (aget surface x)))]
        (if y
          (recur (inc x) x (conj acc {:tx x :ty y :open? false :items (chest-items x y)}))
          (recur (inc x) last acc))))))

(defn generate []
  (let [tiles (js/Uint8Array. (* W H))
        surface (js/Int32Array. W)]
    (doseq [x (range W)]
      (let [s (surface-height x)
            dirt-depth (+ 4 (js/Math.floor (* 4 (noise2 (/ x 8) 21.5))))]
        (aset surface x s)
        (doseq [y (range s H)]
          (aset tiles (idx x y)
                (cond
                  (and (> y (+ s 6)) (< y (- H 2)) (cave? x y)) air
                  (= y s) grass
                  (< y (+ s dirt-depth)) dirt
                  :else stone)))))
    (loop [x 6 last -10]
      (when (< x (- W 6))
        (let [s (aget surface x)
              plant? (and (> (- x last) 5)
                          (< (hash2 x 42) 0.18)
                          (= grass (aget tiles (idx x s)))
                          ;; keep the spawn point clear
                          (> (js/Math.abs (- x (quot W 2))) 3))]
          (when plant? (plant-tree! tiles x s))
          (recur (inc x) (if plant? x last)))))
    {:tiles tiles :surface surface :chests (place-chests tiles surface)}))

(defn- box-hits? [tiles x y]
  (let [x0 (js/Math.floor (/ x T))
        x1 (js/Math.floor (/ (+ x player-w -0.01) T))
        y0 (js/Math.floor (/ y T))
        y1 (js/Math.floor (/ (+ y player-h -0.01) T))]
    (some (fn [[tx ty]] (solid? (tile-at tiles tx ty)))
          (for [tx (range x0 (inc x1)) ty (range y0 (inc y1))] [tx ty]))))

(defn- move-x [tiles {:keys [x y vx ground?] :as p} dx]
  (let [nx (+ x dx)]
    (cond
      (not (box-hits? tiles nx y)) (assoc p :x nx)
      ;; walk up single-tile steps
      (and ground? (not (box-hits? tiles nx (- y T))))
      (assoc p :x nx :y (- y T))
      (pos? dx)
      (assoc p :x (- (* T (js/Math.floor (/ (+ nx player-w) T))) player-w 0.001) :vx 0)
      :else
      (assoc p :x (* T (inc (js/Math.floor (/ nx T)))) :vx (if (neg? vx) 0 vx)))))

(defn- move-y [tiles {:keys [x y] :as p} dy]
  (let [ny (+ y dy)]
    (cond
      (not (box-hits? tiles x ny)) (assoc p :y ny :ground? false)
      (pos? dy)
      (assoc p :y (- (* T (js/Math.floor (/ (+ ny player-h) T))) player-h 0.001)
             :vy 0 :ground? true)
      :else
      (assoc p :y (* T (inc (js/Math.floor (/ ny T)))) :vy 0))))

(defn step-player [tiles p keys dt]
  (let [dir (- (if (keys "KeyD") 1 0) (if (keys "KeyA") 1 0))
        jump? (keys "Space")
        vx (+ (:vx p) (* (- (* dir run-v) (:vx p)) (min 1 (* 0.25 dt))))
        vy (cond
             (and jump? (:ground? p)) jump-v
             ;; letting go of Space early gives a shorter jump
             (and (not jump?) (< (:vy p) -3)) -3
             :else (:vy p))
        vy (min max-fall (+ vy (* gravity dt)))
        dx (* vx dt) dy (* vy dt)
        n (js/Math.ceil (/ (max (js/Math.abs dx) (js/Math.abs dy) 1) 6))
        p (assoc p :vx vx :vy vy
                 :facing (cond (pos? dir) 1 (neg? dir) -1 :else (:facing p))
                 :walked (+ (:walked p) (js/Math.abs dx)))]
    (reduce (fn [p _]
              (as-> p p
                (move-x tiles p (/ dx n))
                (move-y tiles p (/ (* (:vy p) dt) n))))
            p
            (range n))))

(def palette
  {grass ["#5cb83a" "#52aa33"]
   dirt ["#8b5a2b" "#7f5226"]
   stone ["#7d7d85" "#71717a"]
   trunk ["#7a4a24" "#6c4120"]
   leaves ["#2f9e44" "#288a3b"]})

(defn- hand [{:keys [x y]}]
  [(+ x (/ player-w 2)) (+ y 22)])

(defn dig-targets
  "Returns the first solid tile within `reach` on the line from the player's hand
  toward `:aim-at`, the mouse position in world coordinates."
  [tiles {:keys [aim-at] :as p}]
  (when aim-at
    (let [[hx hy] (hand p)
          [ax ay] aim-at
          dx (- ax hx) dy (- ay hy)
          d (js/Math.hypot dx dy)]
      (when (pos? d)
        (loop [step 0]
          (when (<= step reach)
            (let [tx (js/Math.floor (/ (+ hx (* dx (/ step d))) T))
                  ty (js/Math.floor (/ (+ hy (* dy (/ step d))) T))]
              (if (solid? (tile-at tiles tx ty))
                [[tx ty]]
                (recur (+ step 2))))))))))

(defn- in-world? [tx ty] (and (< -1 tx W) (< -1 ty (- H 1))))

(defn- swing!
  "Advances the pickaxe swing and breaks the targeted tiles when Shift starts a new one."
  [state ^js tiles dt]
  (let [{:keys [player keys mouse-down?]} @state
        left (max 0 (- (:swing player 0) dt))]
    (if (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight")))
      (let [hits (filterv (fn [[tx ty]] (in-world? tx ty)) (dig-targets tiles player))
            debris (for [[tx ty] hits
                         :let [color (get-in palette [(aget tiles (idx tx ty)) 0])]
                         i (range 7)]
                     {:x (+ (* tx T) (* T (hash2 (+ tx i) ty)))
                      :y (+ (* ty T) (* T (hash2 tx (+ ty i))))
                      :vx (- (* 4 (hash2 (* 3 i) tx)) 2)
                      :vy (- (* -3 (hash2 ty (* 5 i))) 1)
                      :life 40 :color color})]
        (doseq [[tx ty] hits] (aset tiles (idx tx ty) air))
        (swap! state #(-> %
                          (assoc-in [:player :swing] swing-frames)
                          (assoc-in [:player :facing]
                                    (if (< (first (:aim-at player)) (first (hand player))) -1 1))
                          (update :particles into debris))))
      (swap! state assoc-in [:player :swing] left))))

(defn- step-particles [ps dt]
  (into [] (comp (map (fn [p] (-> p
                                  (update :x + (* (:vx p) dt))
                                  (update :y + (* (:vy p) dt))
                                  (update :vy + (* 0.3 dt))
                                  (update :life - dt))))
                 (filter #(pos? (:life %))))
        ps))

(defn- draw-sky [^js ctx vw vh cam-x t]
  (let [g (.createLinearGradient ctx 0 0 0 vh)]
    (.addColorStop g 0 "#4f9fe6")
    (.addColorStop g 1 "#c4e6ff")
    (set! (.-fillStyle ctx) g)
    (.fillRect ctx 0 0 vw vh))
  (set! (.-fillStyle ctx) "#fff6c2")
  (.beginPath ctx)
  (.arc ctx (- vw 160) 110 42 0 (* 2 js/Math.PI))
  (.fill ctx)
  (set! (.-fillStyle ctx) "rgba(255,255,255,0.85)")
  (doseq [i (range 9)]
    (let [span (+ vw 400)
          cx (- (mod (- (* i 260) (* cam-x 0.2) (* t 0.01)) span) 200)
          cy (+ 60 (* 90 (hash2 i 5)))
          s (+ 0.7 (* 0.6 (hash2 i 9)))]
      (doseq [[ox oy rx ry] [[0 0 60 18] [30 -10 36 18] [-26 -6 30 14]]]
        (.beginPath ctx)
        (.ellipse ctx (+ cx (* ox s)) (+ cy (* oy s)) (* rx s) (* ry s) 0 0 (* 2 js/Math.PI))
        (.fill ctx)))))

(defn- draw-hills [^js ctx vw vh cam-x cam-y]
  (doseq [[par color amp base] [[0.15 "#8fc7a5" 70 0.30] [0.35 "#6fb487" 45 0.36]]]
    (set! (.-fillStyle ctx) color)
    (.beginPath ctx)
    (.moveTo ctx 0 vh)
    (doseq [sx (range 0 (+ vw 24) 24)]
      (let [wx (+ sx (* cam-x par))
            hy (- (* H T base) (* amp (noise2 (/ wx 300) (* par 100))))]
        (.lineTo ctx sx (- hy cam-y))))
    (.lineTo ctx vw vh)
    (.closePath ctx)
    (.fill ctx)))

(defn- draw-tiles [^js ctx tiles ^js surface vw vh cam-x cam-y]
  (let [x0 (max 0 (js/Math.floor (/ cam-x T)))
        x1 (min (dec W) (js/Math.floor (/ (+ cam-x vw) T)))
        y0 (max 0 (js/Math.floor (/ cam-y T)))
        y1 (min (dec H) (js/Math.floor (/ (+ cam-y vh) T)))]
    (doseq [tx (range x0 (inc x1)) ty (range y0 (inc y1))]
      (let [t (aget tiles (idx tx ty))
            sx (js/Math.round (- (* tx T) cam-x))
            sy (js/Math.round (- (* ty T) cam-y))
            shade (if (< (hash2 tx ty) 0.5) 0 1)]
        (cond
          (not= t air)
          (do
            (set! (.-fillStyle ctx)
                  (if (= t grass) (get-in palette [dirt shade]) (get-in palette [t shade])))
            (.fillRect ctx sx sy T T)
            (cond
              (= t grass)
              (do (set! (.-fillStyle ctx) (get-in palette [grass shade]))
                  (.fillRect ctx sx sy T 6)
                  (.fillRect ctx (+ sx (* 4 (js/Math.floor (* 3 (hash2 tx 3))))) (+ sy 6) 4 3))
              (= t trunk)
              (do (set! (.-fillStyle ctx) "#5a361a")
                  (.fillRect ctx (+ sx 5) sy 2 T))
              (= t stone)
              (when (< (hash2 tx (+ ty 500)) 0.3)
                (set! (.-fillStyle ctx) "#8e8e96")
                (.fillRect ctx (+ sx 3) (+ sy 4) 5 3))))
          (> ty (aget surface tx))
          (do (set! (.-fillStyle ctx) (if (zero? shade) "#3d2a1b" "#382618"))
              (.fillRect ctx sx sy T T)))))))

(defn- draw-depth [^js ctx ^js surface vw vh cam-y]
  (let [top (- (* T (+ 14 (aget surface (quot W 2)))) cam-y)
        bot (- (* T H) cam-y)
        g (.createLinearGradient ctx 0 top 0 bot)]
    (.addColorStop g 0 "rgba(0,0,0,0)")
    (.addColorStop g 1 "rgba(0,0,0,0.65)")
    (set! (.-fillStyle ctx) g)
    (.fillRect ctx 0 (max 0 top) vw vh)))

(defn- draw-player [^js ctx {:keys [x y facing walked ground? vx]} cam-x cam-y]
  (let [sx (js/Math.round (- x cam-x))
        sy (js/Math.round (- y cam-y))
        moving? (and ground? (> (js/Math.abs vx) 0.4))
        swing (if moving? (js/Math.round (* 3 (js/Math.sin (/ walked 6)))) 0)
        rect (fn [color rx ry rw rh]
               (set! (.-fillStyle ctx) color)
               (.fillRect ctx (if (pos? facing) (+ sx rx) (- (+ sx player-w) rx rw)) (+ sy ry) rw rh))]
    (rect "#3b2f8f" 3 28 5 10)
    (rect "#3b2f8f" 10 28 5 10)
    (rect "#2a2266" (+ 3 swing) 34 5 4)
    (rect "#2a2266" (- 10 swing) 34 5 4)
    (rect "#c0392b" 2 15 14 14)
    (rect "#f1c27d" (+ 7 (- swing)) 17 4 11)
    (rect "#f1c27d" 3 3 13 13)
    (rect "#6b3e1f" 2 0 15 5)
    (rect "#6b3e1f" 2 0 4 10)
    (rect "#1d1d1d" 12 8 2 3)))

(defn- draw-target [^js ctx tiles player cam-x cam-y]
  (set! (.-strokeStyle ctx) "rgba(255,255,255,0.8)")
  (set! (.-lineWidth ctx) 2)
  (doseq [[tx ty] (dig-targets tiles player)]
    (.strokeRect ctx (+ 1 (- (* tx T) cam-x)) (+ 1 (- (* ty T) cam-y)) (- T 2) (- T 2))))

(defn- draw-pickaxe [^js ctx {:keys [facing swing aim-at] :as p} cam-x cam-y]
  (let [[hx hy] (hand p)
        base (if aim-at
               (js/Math.atan2 (- (second aim-at) hy) (* facing (- (first aim-at) hx)))
               0)
        ;; the swing sweeps from raised back to the aim direction
        lift (if (pos? swing) (* 1.6 (/ swing swing-frames)) 0)]
    (.save ctx)
    (.translate ctx (js/Math.round (- hx cam-x)) (js/Math.round (- hy cam-y)))
    (.scale ctx facing 1)
    (.rotate ctx (- base lift))
    (set! (.-fillStyle ctx) "#8a5a2b")
    (.fillRect ctx 0 -2 24 4)
    (set! (.-fillStyle ctx) "#b8c2cc")
    (.beginPath ctx)
    (.moveTo ctx 20 -12)
    (.quadraticCurveTo ctx 30 0 20 12)
    (.lineTo ctx 24 0)
    (.closePath ctx)
    (.fill ctx)
    (set! (.-fillStyle ctx) "#6f7a85")
    (.fillRect ctx 19 -4 6 8)
    (.restore ctx)))

(defn- draw-particles [^js ctx ps cam-x cam-y]
  (doseq [{:keys [x y color]} ps]
    (set! (.-fillStyle ctx) color)
    (.fillRect ctx (js/Math.round (- x cam-x)) (js/Math.round (- y cam-y)) 3 3)))

(def chest-w 28)
(def chest-h 20)

(defn- chest-box [{:keys [tx ty]}]
  [(+ (* tx T) 2) (- (* (inc ty) T) chest-h)])

(defn- near-chest? [{:keys [x y]} chest]
  (let [[cx cy] (chest-box chest)
        gap 6]
    (and (< (- cx gap) (+ x player-w)) (< x (+ cx chest-w gap))
         (< (- cy gap) (+ y player-h)) (< y (+ cy chest-h gap)))))

(defn- add-items [inventory items]
  (reduce (fn [inv {:keys [id count] :as item}]
            (if-let [i (first (keep-indexed #(when (= id (:id %2)) %1) inv))]
              (update-in inv [i :count] + count)
              (conj inv item)))
          inventory
          items))

(defn- open-chests!
  "Opens every closed chest the player is next to and returns the new inventory, or nil."
  [state]
  (let [{:keys [player chests]} @state
        hits (keep-indexed (fn [i c] (when (and (not (:open? c)) (near-chest? player c)) i)) chests)]
    (when (seq hits)
      (:inventory
       (swap! state
              (fn [st]
                (reduce (fn [st i]
                          (let [c (get-in st [:chests i])
                                [cx cy] (chest-box c)]
                            (-> st
                                (assoc-in [:chests i :open?] true)
                                (update :inventory add-items (:items c))
                                (update :popups into
                                        (map-indexed (fn [n {:keys [icon name count]}]
                                                       {:text (str "+" count " " icon " " name)
                                                        :x (+ cx (/ chest-w 2))
                                                        :y (- cy 8 (* n 18))
                                                        :life 120})
                                                     (:items c))))))
                        st hits)))))))

(defn- step-popups [ps dt]
  (into [] (comp (map #(-> % (update :y - (* 0.35 dt)) (update :life - dt)))
                 (filter #(pos? (:life %))))
        ps))

(defn- draw-chests [^js ctx chests vw vh cam-x cam-y]
  (doseq [{:keys [open?] :as c} chests
          :let [[wx wy] (chest-box c)
                sx (js/Math.round (- wx cam-x))
                sy (js/Math.round (- wy cam-y))]
          :when (and (< -40 sx (+ vw 40)) (< -40 sy (+ vh 40)))]
    (set! (.-fillStyle ctx) "#6b3f1d")
    (.fillRect ctx sx (+ sy 7) chest-w (- chest-h 7))
    (set! (.-fillStyle ctx) "#8b5a2b")
    (.fillRect ctx (+ sx 2) (+ sy 9) (- chest-w 4) (- chest-h 11))
    (set! (.-fillStyle ctx) "#c9a227")
    (.fillRect ctx sx (+ sy 7) chest-w 2)
    (.fillRect ctx sx (- (+ sy chest-h) 2) chest-w 2)
    (if open?
      (do (set! (.-fillStyle ctx) "#2a1a0c")
          (.fillRect ctx (+ sx 2) (+ sy 7) (- chest-w 4) 3)
          (set! (.-fillStyle ctx) "#6b3f1d")
          (.fillRect ctx sx (- sy 3) chest-w 8)
          (set! (.-fillStyle ctx) "#c9a227")
          (.fillRect ctx sx (- sy 3) chest-w 2))
      (do (set! (.-fillStyle ctx) "#7a4a24")
          (.fillRect ctx sx sy chest-w 8)
          (set! (.-fillStyle ctx) "#c9a227")
          (.fillRect ctx sx sy chest-w 2)
          (.fillRect ctx (- (+ sx (/ chest-w 2)) 2) (+ sy 5) 4 6)))))

(defn- draw-popups [^js ctx ps cam-x cam-y]
  (set! (.-font ctx) "bold 13px system-ui, sans-serif")
  (set! (.-textAlign ctx) "center")
  (set! (.-lineWidth ctx) 3)
  (set! (.-strokeStyle ctx) "rgba(0,0,0,0.7)")
  (doseq [{:keys [text x y life]} ps]
    (set! (.-globalAlpha ctx) (min 1 (/ life 30)))
    (.strokeText ctx text (- x cam-x) (- y cam-y))
    (set! (.-fillStyle ctx) "#ffe680")
    (.fillText ctx text (- x cam-x) (- y cam-y)))
  (set! (.-globalAlpha ctx) 1))

(defn- spawn [{:keys [surface]}]
  (let [x (quot W 2)]
    {:x (+ (* x T) (/ (- T player-w) 2))
     :y (- (* T (aget surface x)) player-h 1)
     :vx 0 :vy 0 :facing 1 :walked 0 :ground? false :swing 0}))

(defn- typing? [^js e]
  (let [tag (some-> e .-target .-tagName)]
    (or (= tag "INPUT") (= tag "TEXTAREA") (some-> e .-target .-isContentEditable))))

(def game-keys #{"KeyA" "KeyD" "Space" "ShiftLeft" "ShiftRight"})

(defui inventory-panel [{:keys [items]}]
  ($ :div {:class "absolute top-4 right-4 w-60 rounded-md bg-black/50 p-3 text-sm text-white select-none"}
     ($ :div {:class "mb-2 font-semibold tracking-wide"} "Items")
     (if (empty? items)
       ($ :div {:class "text-white/70"} "Nothing yet. Dig into caves to find chests.")
       ($ :ul {:class "space-y-1"}
          (for [{:keys [id icon name count]} items]
            ($ :li {:key (str id) :class "flex items-center gap-2 rounded bg-white/10 px-2 py-1"}
               ($ :span {:class "text-base"} icon)
               ($ :span {:class "flex-1"} name)
               ($ :span {:class "font-mono tabular-nums"} count)))))))

(defui game []
  (let [canvas-ref (use-ref nil)
        [items set-items!] (use-state [])]
    (use-effect
     (fn []
       (let [^js canvas @canvas-ref
             ctx (.getContext canvas "2d")
             world (generate)
             state (atom {:player (spawn world) :keys #{} :particles []
                          :chests (:chests world) :inventory [] :popups []})
             raf (atom nil)
             last-t (atom nil)
             resize (fn []
                      (let [dpr (or js/window.devicePixelRatio 1)]
                        (set! (.-width canvas) (* dpr js/window.innerWidth))
                        (set! (.-height canvas) (* dpr js/window.innerHeight))))
             on-down (fn [^js e]
                       (when (and (game-keys (.-code e)) (not (typing? e)))
                         (.preventDefault e)
                         (swap! state update :keys conj (.-code e))))
             on-up (fn [^js e] (swap! state update :keys disj (.-code e)))
             on-blur (fn [] (swap! state assoc :keys #{} :mouse-down? false))
             on-move (fn [^js e] (swap! state assoc :mouse [(.-clientX e) (.-clientY e)]))
             on-press (fn [^js e]
                        (when (zero? (.-button e))
                          (swap! state assoc :mouse-down? true
                                 :mouse [(.-clientX e) (.-clientY e)])))
             on-release (fn [] (swap! state assoc :mouse-down? false))
             frame (fn frame [t]
                     (let [dt (if @last-t (min 2 (/ (- t @last-t) 16.667)) 1)
                           _ (reset! last-t t)
                           player (:player (swap! state update :player
                                                  #(step-player (:tiles world) % (:keys @state) dt)))
                           dpr (or js/window.devicePixelRatio 1)
                           vw js/window.innerWidth
                           vh js/window.innerHeight
                           cam-x (-> (- (+ (:x player) (/ player-w 2)) (/ vw 2))
                                     (max 0) (min (- (* W T) vw)))
                           cam-y (-> (- (:y player) (* vh 0.42))
                                     (max 0) (min (- (* H T) vh)))
                           _ (when-let [[mx my] (:mouse @state)]
                               (swap! state update :player
                                      (fn [p]
                                        (let [aim [(+ mx cam-x) (+ my cam-y)]
                                              side (if (< (first aim) (first (hand p))) -1 1)]
                                          (cond-> (assoc p :aim-at aim)
                                            (< (js/Math.abs (:vx p)) 0.4) (assoc :facing side))))))
                           _ (swing! state (:tiles world) dt)
                           _ (some-> (open-chests! state) set-items!)
                           {:keys [player particles chests popups]}
                           (swap! state #(-> %
                                             (update :particles step-particles dt)
                                             (update :popups step-popups dt)))]
                       (.setTransform ctx dpr 0 0 dpr 0 0)
                       (set! (.-imageSmoothingEnabled ctx) false)
                       (draw-sky ctx vw vh cam-x t)
                       (draw-hills ctx vw vh cam-x cam-y)
                       (draw-tiles ctx (:tiles world) (:surface world) vw vh cam-x cam-y)
                       (draw-chests ctx chests vw vh cam-x cam-y)
                       (draw-target ctx (:tiles world) player cam-x cam-y)
                       (draw-player ctx player cam-x cam-y)
                       (draw-pickaxe ctx player cam-x cam-y)
                       (draw-particles ctx particles cam-x cam-y)
                       (draw-depth ctx (:surface world) vw vh cam-y)
                       (draw-popups ctx popups cam-x cam-y)
                       (reset! raf (js/requestAnimationFrame frame))))]
         (resize)
         (.addEventListener js/window "resize" resize)
         (.addEventListener js/window "keydown" on-down)
         (.addEventListener js/window "keyup" on-up)
         (.addEventListener js/window "blur" on-blur)
         (.addEventListener js/window "mousemove" on-move)
         (.addEventListener canvas "mousedown" on-press)
         (.addEventListener js/window "mouseup" on-release)
         (reset! raf (js/requestAnimationFrame frame))
         (fn []
           (js/cancelAnimationFrame @raf)
           (.removeEventListener js/window "resize" resize)
           (.removeEventListener js/window "keydown" on-down)
           (.removeEventListener js/window "keyup" on-up)
           (.removeEventListener js/window "blur" on-blur)
           (.removeEventListener js/window "mousemove" on-move)
           (.removeEventListener canvas "mousedown" on-press)
           (.removeEventListener js/window "mouseup" on-release))))
     [])
    ($ :div {:class "fixed inset-0 overflow-hidden bg-sky-300"}
       ($ :canvas {:ref canvas-ref :class "block w-full h-full"})
       ($ :div {:class "absolute top-4 left-4 rounded-md bg-black/40 px-3 py-2 text-sm text-white select-none"}
          "A D move · Space jump · mouse aims pickaxe · Shift or click to dig")
       ($ inventory-panel {:items items}))))
