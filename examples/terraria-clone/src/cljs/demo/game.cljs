(ns demo.game
  "Side-scrolling tile world with a player moved by A, D and Space who digs with a
  mouse-aimed pickaxe and collects items from chests in caves."
  (:require [cljs.reader :as reader]
            [demo.icons :as icons]
            [demo.music :as music]
            [uix.core :refer [$ defui use-effect use-ref use-state]]))

(def T 16)
(def W 480)
(def H 120)

(def air 0)
(def grass 1)
(def dirt 2)
(def stone 3)
(def trunk 4)
(def leaves 5)
(def rope 6)
(def ooze 7)
(def slimestone 8)
(def rope-len 10)

(def blocks
  {:dirt {:id :dirt :name "Dirt" :icon "🟫" :tile dirt}
   :stone {:id :stone :name "Stone" :icon "🪨" :tile stone}})

(def stack-max {:dirt 100 :stone 100})
(def climb-v 2.2)

(def player-w 18)
(def player-h 38)
(def gravity 0.45)
(def jump-v -9.2)
(def run-v 3.4)
(def max-fall 11)
(def swing-frames 14)
(def reach (* 4.5 T))
(def max-hp 10)
;; 60 frames a second, so health refills one hit point every 30 seconds
(def regen (/ 1 (* 30 60)))
(def potion-heal 5)

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

(defn- solid? [t] (or (<= grass t stone) (= t slimestone)))

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

(def amulet {:id :amulet :name "Dimension Amulet" :icon "🧿"})
(def amulet-chance 0.05)

(def arena-key {:id :key :name "Arena Key" :icon "🗝️"})
(def key-chance 0.05)

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

(def shop-w 7)
(def shop-h 5)

(def prices
  {:torch 2 :rope 3 :bar 10 :potion 15 :ruby 50 :mirror 40})

(defn- place-shop!
  "Levels the ground under a shop at a random surface spot away from the spawn point
  and clears trees around it."
  [^js tiles ^js surface]
  (let [mid (quot W 2)
        x (loop []
            (let [x (+ 20 (js/Math.floor (* (js/Math.random) (- W 40 shop-w))))]
              (if (< (js/Math.abs (- (+ x (quot shop-w 2)) mid)) 25) (recur) x)))
        floor (apply min (map #(aget surface %) (range x (+ x shop-w))))]
    (doseq [tx (range (- x 3) (+ x shop-w 3)) ty (range H)
            :when (#{trunk leaves} (aget tiles (idx tx ty)))]
      (aset tiles (idx tx ty) air))
    (doseq [tx (range x (+ x shop-w))]
      (doseq [ty (range 0 H)]
        (cond
          (< ty floor) (aset tiles (idx tx ty) air)
          (= ty floor) (aset tiles (idx tx ty) grass)
          (< ty (+ floor 4)) (aset tiles (idx tx ty) dirt)))
      (aset surface tx floor))
    {:tx x :ty floor}))

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
    (let [shop (place-shop! tiles surface)]
      {:tiles tiles :surface surface :shop shop :chests (place-chests tiles surface)})))

(def portal-w 26)
(def portal-h 44)

(defn- slime-cave? [x y]
  (> (/ (+ (noise2 (/ x 16) (+ 300 (/ y 10))) (* 0.5 (noise2 (/ x 6) (+ 300 (/ y 6))))) 1.5)
     0.5))

(def pool-max 260)

(defn- flood-pool!
  "Fills the basin under the cave floor tile above `x`, `y` with ooze up to two tiles
  above the floor. Leaves the tiles as they were when the ooze would cover more than
  `pool-max` tiles or reach a tile for which `keep-dry?` is true."
  [^js tiles x y keep-dry?]
  (let [level (- y 2)]
    (loop [todo [[x y]] seen #{[x y]}]
      (cond
        (> (count seen) pool-max) nil
        (empty? todo) (doseq [[tx ty] seen] (aset tiles (idx tx ty) ooze))
        :else
        (let [[[tx ty] & more] todo]
          (if (keep-dry? tx ty)
            nil
            (let [next (for [[nx ny] [[(dec tx) ty] [(inc tx) ty] [tx (dec ty)] [tx (inc ty)]]
                             :when (and (>= ny level) (< 0 nx (dec W)) (< ny H)
                                        (not (seen [nx ny]))
                                        (= air (aget tiles (idx nx ny))))]
                         [nx ny])]
              (recur (into (vec more) next) (into seen next)))))))))

(def gate-w 32)
(def gate-h 48)
(def gate-tx 390)
(def gate-floor 64)

(defn- gate-room? [x y]
  (and (<= (- gate-tx 7) x (+ gate-tx 8)) (<= (- gate-floor 8) y (inc gate-floor))))

(defn- carve-gate!
  "Carves the chamber that holds the arena gate in the Slime Dimension and returns the
  gate's position in world pixels."
  [^js tiles ^js levels]
  (doseq [x (range (- gate-tx 6) (+ gate-tx 8))]
    (doseq [y (range (- gate-floor 7) gate-floor)]
      (aset tiles (idx x y) air)
      (some-> levels (aset (idx x y) 0)))
    (aset tiles (idx x gate-floor) slimestone))
  {:x (* T gate-tx) :y (* T (- gate-floor 3))})

(defn generate-slime
  "Builds the slime dimension: a world of caves with no sky, an arrival chamber with a
  portal back in the middle, a chamber with the arena gate far to the east, and pools
  of ooze at the bottom of some caves."
  []
  (let [tiles (js/Uint8Array. (* W H))
        surface (.fill (js/Int32Array. W) -1)
        mid (quot W 2)
        cy (quot H 2)
        chamber? (fn [x y] (or (gate-room? x y)
                               (and (<= (- mid 10) x (+ mid 10)) (<= (- cy 7) y (+ cy 2)))))]
    (doseq [x (range W) y (range H)]
      (aset tiles (idx x y) (if (and (< 2 y (- H 3)) (slime-cave? x y)) air slimestone)))
    (doseq [x (range (- mid 8) (+ mid 9))]
      (doseq [y (range (- cy 5) (inc cy))]
        (aset tiles (idx x y) air))
      (aset tiles (idx x (inc cy)) slimestone)
      (aset tiles (idx x (+ cy 2)) slimestone))
    (doseq [x (range 3 (- W 3) 2)
            y (range 4 (- H 3))
            :when (and (= air (aget tiles (idx x y)))
                       (solid? (aget tiles (idx x (inc y))))
                       (< (hash2 (quot x 24) (+ 50 (quot y 16))) 0.4))]
      (flood-pool! tiles x y chamber?))
    (let [gate (carve-gate! tiles nil)]
      {:tiles tiles :surface surface :chests [] :gate gate
       :levels (js/Float32Array.from tiles #(if (= % ooze) 1 0))
       :portal {:x (- (* T mid) 48)
                :y (- (* T (inc cy)) portal-h)
                :back-x (* T mid)}})))

(def arena-x0 200)
(def arena-x1 280)
(def arena-top 44)
(def arena-floor 62)

(defn generate-arena
  "Builds the boss arena: one wide hall of slimestone with three ledges to jump on and
  the closed gate on its west wall."
  []
  (let [tiles (.fill (js/Uint8Array. (* W H)) slimestone)
        surface (.fill (js/Int32Array. W) -1)]
    (doseq [x (range arena-x0 arena-x1) y (range arena-top arena-floor)]
      (aset tiles (idx x y) air))
    (doseq [[x0 x1 y] [[212 221 56] [259 268 56] [235 245 51]]
            x (range x0 x1)]
      (aset tiles (idx x y) slimestone))
    {:tiles tiles :surface surface :chests []
     :gate {:x (* T (inc arena-x0)) :y (* T (- arena-floor 3))}}))

(defn- box-hits? [tiles x y w h]
  (let [x0 (js/Math.floor (/ x T))
        x1 (js/Math.floor (/ (+ x w -0.01) T))
        y0 (js/Math.floor (/ y T))
        y1 (js/Math.floor (/ (+ y h -0.01) T))]
    (some (fn [[tx ty]] (solid? (tile-at tiles tx ty)))
          (for [tx (range x0 (inc x1)) ty (range y0 (inc y1))] [tx ty]))))

(defn- move-x [tiles {:keys [x y vx ground? w h] :or {w player-w h player-h} :as p} dx]
  (let [nx (+ x dx)]
    (cond
      (not (box-hits? tiles nx y w h)) (assoc p :x nx)
      ;; walk up single-tile steps
      (and ground? (not (box-hits? tiles nx (- y T) w h)))
      (assoc p :x nx :y (- y T))
      (pos? dx)
      (assoc p :x (- (* T (js/Math.floor (/ (+ nx w) T))) w 0.001) :vx 0)
      :else
      (assoc p :x (* T (inc (js/Math.floor (/ nx T)))) :vx (if (neg? vx) 0 vx)))))

(defn- move-y [tiles {:keys [x y w h] :or {w player-w h player-h} :as p} dy]
  (let [ny (+ y dy)]
    (cond
      (not (box-hits? tiles x ny w h)) (assoc p :y ny :ground? false)
      (pos? dy)
      (assoc p :y (- (* T (js/Math.floor (/ (+ ny h) T))) h 0.001)
             :vy 0 :ground? true)
      :else
      (assoc p :y (* T (inc (js/Math.floor (/ ny T)))) :vy 0))))

(defn- ooze-at? [tiles wx wy]
  (= ooze (tile-at tiles (js/Math.floor (/ wx T)) (js/Math.floor (/ wy T)))))

(defn- step-free
  "Moves a player who is not on a rope. In ooze the player moves slowly, sinks gently
  and swims up with Space, hopping out when the head is above the surface."
  [tiles p keys dt]
  (let [dir (- (if (keys "KeyD") 1 0) (if (keys "KeyA") 1 0))
        jump? (keys "Space")
        cx (+ (:x p) (/ player-w 2))
        wet? (ooze-at? tiles cx (+ (:y p) (* 0.6 player-h)))
        head-out? (not (ooze-at? tiles cx (+ (:y p) 6)))
        vx (+ (:vx p) (* (- (* dir run-v (if wet? 0.5 1)) (:vx p)) (min 1 (* 0.25 dt))))
        vy (cond
             (and wet? jump? head-out?) (* 0.8 jump-v)
             (and wet? jump?) (max -2.6 (- (:vy p) (* 0.5 dt)))
             (and jump? (:ground? p)) jump-v
             ;; letting go of Space early gives a shorter jump
             (and (not jump?) (< (:vy p) -3)) -3
             :else (:vy p))
        vy (if wet?
             (min 2 (+ vy (* 0.15 dt)))
             (min max-fall (+ vy (* gravity dt))))
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

(defn- on-rope?
  "Checks whether a rope tile overlaps the player's centre column."
  [tiles {:keys [x y]}]
  (let [tx (js/Math.floor (/ (+ x (/ player-w 2)) T))]
    (some #(= rope (tile-at tiles tx %))
          (range (js/Math.floor (/ y T)) (inc (js/Math.floor (/ (+ y player-h -1) T)))))))

(defn- climb
  "Moves a player holding a rope. Space or W climbs while the hands stay on the rope,
  S climbs down, and A or D lets go with a small hop to that side."
  [tiles p keys dt]
  (let [dir (- (if (keys "KeyD") 1 0) (if (keys "KeyA") 1 0))
        up? (or (keys "Space") (keys "KeyW"))
        down? (keys "KeyS")
        tx (js/Math.floor (/ (+ (:x p) (/ player-w 2)) T))
        hands-on? (fn [y] (= rope (tile-at tiles tx (js/Math.floor (/ (+ y 10) T)))))
        vy (cond up? (- climb-v) down? climb-v :else 0)
        snap-x (- (* T tx) (/ (- player-w T) 2))
        p (cond-> (assoc p :vx 0 :vy 0)
            (not (box-hits? tiles snap-x (:y p) player-w player-h)) (assoc :x snap-x))]
    (if-not (zero? dir)
      (assoc p :climbing? false :grab-cd 20 :vx (* dir run-v) :vy -5 :facing dir)
      (let [p (if (or (not up?) (hands-on? (+ (:y p) (* vy dt))))
                (move-y tiles p (* vy dt))
                p)]
        (if (or (not (on-rope? tiles p)) (and down? (:ground? p)))
          (assoc p :climbing? false)
          (assoc p :vy 0))))))

(defn step-player
  "Moves the player one frame. Jumping or falling into a rope grabs it."
  [tiles p keys dt]
  (if (:climbing? p)
    (climb tiles p keys dt)
    (let [p (step-free tiles (update p :grab-cd #(max 0 (- (or % 0) dt))) keys dt)]
      (if (and (not (:ground? p)) (zero? (:grab-cd p)) (on-rope? tiles p))
        (assoc p :climbing? true :vx 0 :vy 0)
        p))))

(def palette
  {grass ["#5cb83a" "#52aa33"]
   dirt ["#8b5a2b" "#7f5226"]
   stone ["#7d7d85" "#71717a"]
   trunk ["#7a4a24" "#6c4120"]
   leaves ["#2f9e44" "#288a3b"]
   rope ["#c8a165" "#8a6a3a"]
   slimestone ["#56705f" "#4e6757"]})

(defn- hand [{:keys [x y]}]
  [(+ x (/ player-w 2)) (+ y 22)])

(defn dig-targets
  "Returns the first solid or rope tile within `reach` on the line from the player's
  hand toward `:aim-at`, the mouse position in world coordinates."
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
              (if (let [t (tile-at tiles tx ty)] (or (solid? t) (= rope t)))
                [[tx ty]]
                (recur (+ step 2))))))))))

(defn- in-world? [tx ty] (and (< -1 tx W) (< -1 ty (- H 1))))

(defn- place-rope!
  "Hangs a rope from the air tile under the mouse, if it is within `reach` of the hand,
  down to `most` tiles (at most `rope-len`) or the first tile that is not air. Returns
  the number of rope tiles placed."
  [^js tiles {:keys [aim-at] :as p} most]
  (let [[hx hy] (hand p)
        [ax ay] (or aim-at [hx hy])
        tx (js/Math.floor (/ ax T))
        ty (js/Math.floor (/ ay T))
        d (js/Math.hypot (- (* T (+ tx 0.5)) hx) (- (* T (+ ty 0.5)) hy))]
    (if (and aim-at (<= d reach) (in-world? tx ty) (= air (tile-at tiles tx ty)))
      (loop [y ty n 0]
        (if (and (< n (min most rope-len)) (in-world? tx y) (= air (tile-at tiles tx y)))
          (do (aset tiles (idx tx y) rope) (recur (inc y) (inc n)))
          n))
      0)))

(defn- place-block!
  "Puts `tile` on the air tile under the mouse if it is within `reach` of the hand and
  does not overlap the player or a slime. Returns true when it was placed."
  [^js tiles {:keys [aim-at x y] :as p} enemies tile]
  (let [[hx hy] (hand p)
        [ax ay] (or aim-at [hx hy])
        tx (js/Math.floor (/ ax T))
        ty (js/Math.floor (/ ay T))
        bx (* tx T) by (* ty T)
        hits? (fn [ex ey ew eh] (and (< bx (+ ex ew)) (< ex (+ bx T)) (< by (+ ey eh)) (< ey (+ by T))))]
    (when (and aim-at
               (<= (js/Math.hypot (- (+ bx (/ T 2)) hx) (- (+ by (/ T 2)) hy)) reach)
               (in-world? tx ty)
               (= air (tile-at tiles tx ty))
               (not (hits? x y player-w player-h))
               (not-any? #(hits? (:x %) (:y %) (:w %) (:h %)) enemies))
      (aset tiles (idx tx ty) tile)
      true)))

(defn- take-rope!
  "Clears the whole rope that the tile at `tx`, `ty` belongs to and returns how many
  tiles it had."
  [^js tiles tx ty]
  (let [top (loop [y ty] (if (= rope (tile-at tiles tx (dec y))) (recur (dec y)) y))]
    (loop [y top n 0]
      (if (= rope (tile-at tiles tx y))
        (do (aset tiles (idx tx y) air) (recur (inc y) (inc n)))
        n))))

(declare strike-enemies stash portal-aimed? teleport gate-aimed? knock-gate strike-boss)

(defn- swing!
  "Advances the pickaxe swing. A new swing strikes the enemies in front of the pickaxe,
  or breaks the targeted tile when it reaches none. Hitting a rope takes the whole rope
  back into the bag. Returns true when the inventory changed."
  [state ^js tiles dt]
  (let [{:keys [player keys mouse-down?]} @state
        left (max 0 (- (:swing player 0) dt))]
    (cond
      (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight"))
           (portal-aimed? player (:portal @state)))
      (do (swap! state teleport) true)

      (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight"))
           (= :slime (:realm @state)) (not (:gate-open? @state))
           (gate-aimed? player (get-in @state [:worlds :slime :gate])))
      (do (swap! state knock-gate) true)

      (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight")))
      (let [struck (or (strike-boss state) (strike-enemies state))
            arena? (= :arena (:realm @state))
            hits (if struck
                   []
                   (filterv (fn [[tx ty]]
                              (and (in-world? tx ty)
                                   (not (and arena? (= slimestone (aget tiles (idx tx ty)))))))
                            (dig-targets tiles player)))
            debris (vec (for [[tx ty] hits
                              :let [color (get-in palette [(aget tiles (idx tx ty)) 0])]
                              i (range 7)]
                          {:x (+ (* tx T) (* T (hash2 (+ tx i) ty)))
                           :y (+ (* ty T) (* T (hash2 tx (+ ty i))))
                           :vx (- (* 4 (hash2 (* 3 i) tx)) 2)
                           :vy (- (* -3 (hash2 ty (* 5 i))) 1)
                           :life 40 :color color}))
            ropes (filterv (fn [[tx ty]] (= rope (aget tiles (idx tx ty)))) hits)
            pieces (reduce + (map (fn [[tx ty]] (take-rope! tiles tx ty)) ropes))
            dug (into [] (keep (fn [[tx ty]]
                                 (let [t (aget tiles (idx tx ty))]
                                   (cond (or (= t grass) (= t dirt)) :dirt
                                         (or (= t stone) (= t slimestone)) :stone))))
                      hits)]
        (doseq [[tx ty] hits]
          (aset tiles (idx tx ty) air))
        (swap! state #(cond-> (-> %
                                  (stash (map (fn [[id n]] (assoc (dissoc (blocks id) :tile) :count n))
                                              (frequencies dug)))
                                  (assoc-in [:player :swing] swing-frames)
                                  (assoc-in [:player :facing]
                                            (if (< (first (:aim-at player)) (first (hand player))) -1 1))
                                  (update :particles into debris))
                        (seq ropes)
                        (-> (stash [(assoc (select-keys (first (filter (fn [i] (= :rope (:id i))) loot))
                                                        [:id :name :icon])
                                           :count pieces)])
                            (update :popups conj
                                    (let [[tx ty] (first ropes)]
                                      {:text (str "+" pieces " Rope") :icon :rope :x (* T (+ tx 0.5)) :y (* T ty) :life 90})))))
        (or (= struck :loot) (boolean (seq ropes))))
      :else (do (swap! state assoc-in [:player :swing] left) false))))

(defn- step-particles [ps dt]
  (into [] (comp (map (fn [p] (-> p
                                  (update :x + (* (:vx p) dt))
                                  (update :y + (* (:vy p) dt))
                                  (update :vy + (* 0.3 dt))
                                  (update :life - dt))))
                 (filter #(pos? (:life %))))
        ps))

(def day-ms 180000)
(def night-ms 180000)
;; the first day starts in the morning rather than at sunrise
(def start-ms 30000)

(defn time-of-day
  "Returns `:day?`, `:frac` (how far through the current day or night) and `:light`
  (0 at night, 1 in full daylight) for page time `t` in milliseconds."
  [t]
  (let [p (mod (+ t start-ms) (+ day-ms night-ms))
        day? (< p day-ms)
        frac (if day? (/ p day-ms) (/ (- p day-ms) night-ms))
        s (* (js/Math.sin (* js/Math.PI frac)) (if day? 1 -1))]
    {:day? day? :frac frac :light (-> (+ 0.5 (* 2 s)) (max 0) (min 1))}))

(defn- mix-rgb [a b w] (mapv #(+ %1 (* w (- %2 %1))) a b))

(defn- rgb [[r g b]]
  (str "rgb(" (js/Math.round r) "," (js/Math.round g) "," (js/Math.round b) ")"))

(defn- disc [^js ctx color x y r]
  (set! (.-fillStyle ctx) color)
  (.beginPath ctx)
  (.arc ctx x y r 0 (* 2 js/Math.PI))
  (.fill ctx))

(defn- draw-sky
  "Draws the sky behind everything already on the canvas, so the call order runs
  from the nearest layer (clouds) to the farthest (the gradient)."
  [^js ctx vw vh cam-x t {:keys [day? frac light]}]
  (let [dawn (- 1 (js/Math.abs (- (* 2 light) 1)))
        arc-x (* vw (+ 0.05 (* 0.9 frac)))
        arc-y (- (* vh 0.45) (* vh 0.37 (js/Math.sin (* js/Math.PI frac))))]
    (set! (.-globalCompositeOperation ctx) "destination-over")
    (set! (.-fillStyle ctx) (str "rgba(255,255,255," (* 0.85 (+ 0.2 (* 0.8 light))) ")"))
    (doseq [i (range 9)]
      (let [span (+ vw 400)
            cx (- (mod (- (* i 260) (* cam-x 0.2) (* t 0.01)) span) 200)
            cy (+ 60 (* 90 (hash2 i 5)))
            s (+ 0.7 (* 0.6 (hash2 i 9)))]
        (doseq [[ox oy rx ry] [[0 0 60 18] [30 -10 36 18] [-26 -6 30 14]]]
          (.beginPath ctx)
          (.ellipse ctx (+ cx (* ox s)) (+ cy (* oy s)) (* rx s) (* ry s) 0 0 (* 2 js/Math.PI))
          (.fill ctx))))
    (if day?
      (do (disc ctx (rgb (mix-rgb [255 246 194] [255 170 80] dawn)) arc-x arc-y 42)
          (disc ctx (str "rgba(255,230,150," (* 0.25 (+ 0.4 dawn)) ")") arc-x arc-y 64))
      (do (doseq [[dx dy r] [[-12 -8 7] [10 6 9] [-4 14 5] [14 -14 4]]]
            (disc ctx "rgba(150,150,160,0.55)" (+ arc-x dx) (+ arc-y dy) r))
          (disc ctx "#eeeedd" arc-x arc-y 34)
          (disc ctx "rgba(200,210,255,0.12)" arc-x arc-y 60)))
    (when (< light 1)
      (doseq [i (range 140)]
        (let [sx (mod (- (* (hash2 i 71) vw 1.3) (* cam-x 0.03)) vw)
              sy (* (hash2 i 13) vh 0.7)
              tw (+ 0.6 (* 0.4 (js/Math.sin (+ (/ t 400) (* 7 i)))))]
          (set! (.-fillStyle ctx) (str "rgba(255,255,240," (* (- 1 light) tw) ")"))
          (.fillRect ctx (js/Math.round sx) (js/Math.round sy)
                     (if (< (hash2 i 5) 0.15) 2 1) (if (< (hash2 i 5) 0.15) 2 1)))))
    (let [g (.createLinearGradient ctx 0 0 0 vh)
          top (mix-rgb [10 14 40] [79 159 230] light)
          bot (mix-rgb (mix-rgb [28 38 78] [196 230 255] light) [255 150 90] (* 0.6 dawn))]
      (.addColorStop g 0 (rgb top))
      (.addColorStop g 1 (rgb bot))
      (set! (.-fillStyle ctx) g)
      (.fillRect ctx 0 0 vw vh))
    (set! (.-globalCompositeOperation ctx) "source-over")))

(defn- draw-night
  "Darkens what is already drawn by the darkness of the night, leaving a dim glow
  around the player."
  [^js ctx vw vh px py light]
  (let [dark (* 0.72 (- 1 light))]
    (when (pos? dark)
      (let [g (.createRadialGradient ctx px py 30 px py 240)]
        (.addColorStop g 0 (str "rgba(6,10,32," (* 0.3 dark) ")"))
        (.addColorStop g 1 (str "rgba(6,10,32," dark ")"))
        (set! (.-globalCompositeOperation ctx) "source-atop")
        (set! (.-fillStyle ctx) g)
        (.fillRect ctx 0 0 vw vh)
        (set! (.-globalCompositeOperation ctx) "source-over")))))

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

(defn- paint-tile!
  "Draws tile (`tx`, `ty`) with its top left corner at (`sx`, `sy`)."
  [^js ctx ^js tiles ^js surface tx ty sx sy]
      (let [t (aget tiles (idx tx ty))
            shade (if (< (hash2 tx ty) 0.5) 0 1)
            cave-bg (fn []
                      (set! (.-fillStyle ctx)
                            (if (neg? (aget surface tx))
                              (if (zero? shade) "#141c17" "#121914")
                              (if (zero? shade) "#3d2a1b" "#382618")))
                      (.fillRect ctx sx sy T T))]
        (cond
          (= t ooze) (cave-bg)

          (= t rope)
          (do (when (> ty (aget surface tx))
                (cave-bg))
              (set! (.-fillStyle ctx) "#c8a165")
              (.fillRect ctx (+ sx 7) sy 3 T)
              (set! (.-fillStyle ctx) "#8a6a3a")
              (.fillRect ctx (+ sx 7) (+ sy 4) 3 2)
              (.fillRect ctx (+ sx 7) (+ sy 11) 3 2))

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
                (.fillRect ctx (+ sx 3) (+ sy 4) 5 3))
              (= t slimestone)
              (when (< (hash2 tx (+ ty 500)) 0.35)
                (set! (.-fillStyle ctx) "#5f9a4a")
                (.fillRect ctx (+ sx 2) (+ sy 3) 6 3))))
          (> ty (aget surface tx))
          (cave-bg))))

(def chunk-n 32)

;; tile array -> {chunk index -> #js {:canvas :snap}}, so each world keeps its own and a
;; loaded save starts fresh
(def ^:private chunk-caches (js/WeakMap.))

(defn- chunk-canvas
  "Returns a canvas holding chunk (`cx`, `cy`) of `tiles`, repainting it only when its
  tiles differ from the copy taken at the last paint."
  [^js tiles ^js surface cx cy]
  (let [cache (or (.get chunk-caches tiles)
                  (let [c (js/Map.)] (.set chunk-caches tiles c) c))
        key (+ cx (* cy W))
        ^js entry (or (.get cache key)
                      (let [cv (js/document.createElement "canvas")
                            e #js {:canvas cv :snap nil}]
                        (set! (.-width cv) (* chunk-n T))
                        (set! (.-height cv) (* chunk-n T))
                        (.set cache key e)
                        e))
        x0 (* cx chunk-n) y0 (* cy chunk-n)
        x1 (min W (+ x0 chunk-n)) y1 (min H (+ y0 chunk-n))
        ^js snap (.-snap entry)
        same? (and snap
                   (loop [ty y0 k 0]
                     (if (= ty y1)
                       true
                       (let [row (idx 0 ty)
                             ok? (loop [tx x0 k k]
                                   (cond (= tx x1) true
                                         (= (aget snap k) (aget tiles (+ row tx))) (recur (inc tx) (inc k))
                                         :else false))]
                         (when ok? (recur (inc ty) (+ k (- x1 x0))))))))]
    (when-not same?
      (let [^js cv (.-canvas entry)
            ctx (.getContext cv "2d")
            snap (js/Uint8Array. (* chunk-n chunk-n))]
        (.clearRect ctx 0 0 (.-width cv) (.-height cv))
        (loop [ty y0 k 0]
          (when (< ty y1)
            (loop [tx x0 k k]
              (when (< tx x1)
                (aset snap k (aget tiles (idx tx ty)))
                (paint-tile! ctx tiles surface tx ty (* T (- tx x0)) (* T (- ty y0)))
                (recur (inc tx) (inc k))))
            (recur (inc ty) (+ k (- x1 x0)))))
        (set! (.-snap entry) snap)))
    (.-canvas entry)))

(defn- draw-tiles
  "Draws the visible tiles from cached chunk canvases."
  [^js ctx tiles ^js surface vw vh cam-x cam-y]
  (let [cx0 (max 0 (js/Math.floor (/ cam-x (* chunk-n T))))
        cx1 (min (quot (dec W) chunk-n) (js/Math.floor (/ (+ cam-x vw) (* chunk-n T))))
        cy0 (max 0 (js/Math.floor (/ cam-y (* chunk-n T))))
        cy1 (min (quot (dec H) chunk-n) (js/Math.floor (/ (+ cam-y vh) (* chunk-n T))))
        ox (js/Math.round (- cam-x))
        oy (js/Math.round (- cam-y))]
    (loop [cy cy0]
      (when (<= cy cy1)
        (loop [cx cx0]
          (when (<= cx cx1)
            (.drawImage ctx (chunk-canvas tiles surface cx cy)
                        (+ ox (* cx chunk-n T)) (+ oy (* cy chunk-n T)))
            (recur (inc cx))))
        (recur (inc cy))))))

;; ooze thinner than this dries up, so a spreading sheet stops as a shallow puddle
(def ooze-min 0.04)

(defn- set-ooze! [^js tiles ^js levels i l]
  (if (> l ooze-min)
    (do (aset levels i l) (aset tiles i ooze))
    (do (aset levels i 0) (aset tiles i air))))

(defn- open? [t] (or (= t air) (= t ooze)))

(defn flow-ooze!
  "Moves the ooze one step. Each tile pours into the open tile below it, then shares
  with lower open tiles beside it. `flip?` reverses the sideways order so the flow
  does not lean to one side."
  [^js tiles ^js levels flip?]
  (loop [y (- H 2)]
    (when (>= y 0)
      (loop [k 1]
        (when (< k (dec W))
          (let [x (if flip? (- W 1 k) k)
                i (idx x y)]
            (when (= ooze (aget tiles i))
              (let [b (+ i W)
                    l (aget levels i)
                    lb (aget levels b)
                    fall (if (open? (aget tiles b)) (min l (- 1 lb)) 0)
                    fall (if (> (+ lb fall) ooze-min) fall 0)
                    _ (when (pos? fall) (set-ooze! tiles levels b (+ lb fall)))
                    l (- l fall)
                    share (fn [l n]
                            (let [ln (aget levels n)
                                  f (* 0.25 (- l ln))]
                              (if (and (open? (aget tiles n)) (> f 0.002) (> (+ ln f) ooze-min))
                                (do (set-ooze! tiles levels n (+ ln f)) (- l f))
                                l)))
                    l (if (pos? l) (share (share l (dec i)) (inc i)) l)]
                (when (not= l (aget levels i))
                  (set-ooze! tiles levels i l)))))
          (recur (inc k))))
      (recur (dec y)))))

(defn- draw-ooze
  "Draws the ooze over everything in it, filled to each tile's level, with a rippling
  lighter surface where air lies above."
  [^js ctx tiles ^js levels vw vh cam-x cam-y t]
  (let [x0 (max 0 (js/Math.floor (/ cam-x T)))
        x1 (min (dec W) (js/Math.floor (/ (+ cam-x vw) T)))
        y0 (max 0 (js/Math.floor (/ cam-y T)))
        y1 (min (dec H) (js/Math.floor (/ (+ cam-y vh) T)))]
    (doseq [tx (range x0 (inc x1)) ty (range y0 (inc y1))
            :when (= ooze (aget tiles (idx tx ty)))]
      (let [sx (js/Math.round (- (* tx T) cam-x))
            sy (js/Math.round (- (* ty T) cam-y))]
        (set! (.-fillStyle ctx) "rgba(110,214,60,0.6)")
        (if (= ooze (tile-at tiles tx (dec ty)))
          (.fillRect ctx sx sy T T)
          (let [l (aget levels (idx tx ty))
                wave (if (> l 0.9) (js/Math.round (* 1.5 (js/Math.sin (+ (/ t 300) (* 0.8 tx))))) 0)
                top (+ sy (max 0 (min (- T 2) (+ wave (js/Math.round (* T (- 1 (min l 0.82))))))))]
            (.fillRect ctx sx top T (- (+ sy T) top))
            (set! (.-fillStyle ctx) "rgba(190,255,120,0.85)")
            (.fillRect ctx sx top T 2)))
        (when (< (hash2 tx (+ ty (js/Math.floor (/ t 700)))) 0.04)
          (set! (.-fillStyle ctx) "rgba(210,255,160,0.7)")
          (.fillRect ctx (+ sx 6) (+ sy 8) 3 3))))))

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

(def bag-size 10)

(defn- bag-slot
  "Returns the bag slot that holds `id`, or else the first empty slot, or nil."
  [bag id]
  (or (first (keep-indexed (fn [i s] (when (= id (:id s)) i)) bag))
      (first (keep-indexed (fn [i s] (when-not s i)) bag))))

(defn- add-coins [st n] (update st :coins (fnil + 0) n))

(defn- stash
  "Adds coins from `items` to the coin purse and stacks every other item into the bag,
  one kind per slot and blocks up to `stack-max`. Items that find no room are lost,
  and the player is told that the bag is full."
  [st items]
  (reduce (fn [st {:keys [id count] :as item}]
            (if (= :coin id)
              (add-coins st count)
              (let [i (bag-slot (:bag st) id)
                    n (if i (min count (- (stack-max id ##Inf) (get-in st [:bag i :count] 0))) 0)
                    st (cond-> st
                         (pos? n) (update-in [:bag i] (fn [s] (-> (or s (select-keys item [:id :name :icon]))
                                                                  (update :count (fnil + 0) n)))))
                    {:keys [x y]} (:player st)]
                (cond-> st
                  (and (< n count) (not (blocks id)))
                  (update :popups conj {:text (str "Bag full: " (:name item) " lost") :icon id
                                        :x (+ x (/ player-w 2)) :y (- y 30) :life 120})))))
          st items))

(defn- take-from-bag
  "Removes `n` of item `id` from the bag, emptying its slot when none are left."
  [st id n]
  (if-let [i (first (keep-indexed (fn [i s] (when (= id (:id s)) i)) (:bag st)))]
    (if (> (get-in st [:bag i :count]) n)
      (update-in st [:bag i :count] - n)
      (assoc-in st [:bag i] nil))
    st))

(defn- bag-count [bag id]
  (reduce + (keep #(when (= id (:id %)) (:count %)) bag)))

(defn- open-chests!
  "Opens every closed chest the player is next to and returns the new state, or nil."
  [state]
  (let [{:keys [player chests]} @state
        hits (keep-indexed (fn [i c] (when (and (not (:open? c)) (near-chest? player c)) i)) chests)]
    (when (seq hits)
      (swap! state
             (fn [st]
               (reduce (fn [st i]
                         (let [c (get-in st [:chests i])
                               [cx cy] (chest-box c)]
                           (-> st
                               (assoc-in [:chests i :open?] true)
                               (stash (:items c))
                               (update :popups into
                                       (map-indexed (fn [n {:keys [id name count]}]
                                                      {:text (str "+" count " " name) :icon id
                                                       :x (+ cx (/ chest-w 2))
                                                       :y (- cy 8 (* n 18))
                                                       :life 120})
                                                    (:items c))))))
                       st hits))))))

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
  (doseq [{:keys [text icon x y life]} ps
          :let [sx (- x cam-x)
                sy (- y cam-y)
                     ;; an icon sits left of the text and the pair stays centred on x
                tx (if icon (+ sx 8) sx)]]
    (set! (.-globalAlpha ctx) (min 1 (/ life 30)))
    (when icon
      (icons/draw! ctx icon (js/Math.round (- tx (/ (.-width (.measureText ctx text)) 2) 16))
                   (js/Math.round (- sy 11)) 1.4))
    (.strokeText ctx text tx sy)
    (set! (.-fillStyle ctx) "#ffe680")
    (.fillText ctx text tx sy))
  (set! (.-globalAlpha ctx) 1))

(defn- draw-shop [^js ctx {:keys [tx ty]} vw cam-x cam-y]
  (let [w (* shop-w T)
        h (* shop-h T)
        sx (js/Math.round (- (* tx T) cam-x))
        base (js/Math.round (- (* ty T) cam-y))
        top (- base h)
        rect (fn [color x y rw rh]
               (set! (.-fillStyle ctx) color)
               (.fillRect ctx (+ sx x) (+ top y) rw rh))]
    (when (< (- w) sx (+ vw w))
      (rect "#a0703c" 4 18 (- w 8) (- h 18))
      (doseq [i (range 3 (/ (- h 18) 6))]
        (rect "#8b5e30" 4 (+ 18 (* i 6)) (- w 8) 1))
      (set! (.-fillStyle ctx) "#b33a2e")
      (.beginPath ctx)
      (.moveTo ctx (- sx 6) (+ top 22))
      (.lineTo ctx (+ sx (/ w 2)) (- top 14))
      (.lineTo ctx (+ sx w 6) (+ top 22))
      (.closePath ctx)
      (.fill ctx)
      (rect "#4a2a12" 46 (- h 34) 20 34)
      (rect "#e0b84a" 61 (- h 18) 3 3)
      (rect "#4a2a12" 12 36 24 18)
      (rect "#ffe9a8" 14 38 20 14)
      (rect "#4a2a12" 23 38 2 14)
      (rect "#4a2a12" 76 36 24 18)
      (rect "#ffe9a8" 78 38 20 14)
      (rect "#4a2a12" 87 38 2 14)
      (rect "#3a2410" 30 20 52 14)
      (set! (.-font ctx) "bold 11px system-ui, sans-serif")
      (set! (.-textAlign ctx) "center")
      (set! (.-fillStyle ctx) "#ffd54a")
      (icons/draw! ctx :coin (+ sx 32) (+ top 21) 1)
      (.fillText ctx "SHOP" (+ sx 62) (+ top 31)))))

(defn- near-shop? [{:keys [x y]} {:keys [tx ty]}]
  (let [sx (* tx T) base (* ty T)]
    (and (< (- sx 8) (+ x player-w)) (< x (+ sx (* shop-w T) 8))
         (< (- base (* shop-h T)) (+ y player-h)) (< y (+ base 8)))))

(defn- sell
  "Sells up to `n` of item `id` from the bag for coins and shows what was earned."
  [st id n]
  (let [n (min n (bag-count (:bag st) id))
        earned (* n (prices id 0))
        {:keys [x y]} (:player st)]
    (if (pos? n)
      (-> st
          (take-from-bag id n)
          (add-coins earned)
          (update :popups conj {:text (str "+" earned) :icon :coin :x (+ x (/ player-w 2)) :y (- y 10) :life 90}))
      st)))

(def slime-w 22)
(def slime-h 16)
(def max-slimes 10)
(def slime-color "#4cc94c")

(defn- cave-spot?
  "Checks that a slime fits on a cave floor at tile `tx`, `ty`, well below the surface."
  [tiles ^js surface tx ty]
  (and (< 1 tx (- W 2))
       (> ty (+ (aget surface tx) 6))
       (every? #(= air (tile-at tiles % ty)) [tx (inc tx)])
       (every? #(= air (tile-at tiles % (dec ty))) [tx (inc tx)])
       (every? #(solid? (tile-at tiles % (inc ty))) [tx (inc tx)])))

(def tough-slime
  "Overrides for slimes in the Slime Dimension, which take twice the hits and deal
  twice the damage."
  {:hp 6 :dmg 2 :color "#2a8a35"})

(defn- spawn-slime
  "Returns a slime on a random cave floor 15 to 45 tiles to the side of the player, or nil."
  [tiles surface {:keys [x y]}]
  (let [ptx (js/Math.floor (/ x T))
        pty (js/Math.floor (/ y T))]
    (some (fn [_]
            (let [side (if (< (js/Math.random) 0.5) -1 1)
                  tx (+ ptx (* side (+ 15 (js/Math.floor (* 30 (js/Math.random))))))
                  spots (filterv #(cave-spot? tiles surface tx %) (range (- pty 20) (+ pty 35)))
                  ty (when (seq spots) (rand-nth spots))]
              (when ty
                {:x (+ (* tx T) 4) :y (- (* (inc ty) T) slime-h 0.01)
                 :w slime-w :h slime-h :vx 0 :vy 0 :ground? true
                 :hp 3 :flash 0 :hop (* 60 (js/Math.random))})))
          (range 20))))

(defn- step-slime [tiles {:keys [x hop ground?] :as s} player dt]
  (let [dx (- (:x player) x)
        dy (- (:y player) (:y s))
        chase? (and (< (js/Math.abs dx) (* 18 T)) (< (js/Math.abs dy) (* 10 T)))
        dir (if chase? (if (neg? dx) -1 1) (if (< (js/Math.random) 0.5) -1 1))
        jump? (and ground? (<= hop 0))
        s (cond-> (-> s
                      (update :hop - dt)
                      (update :flash #(max 0 (- % dt))))
            jump? (assoc :vy (- -5 (* 2.5 (js/Math.random))) :vx (* dir (if chase? 2.4 1.4))
                         :hop (+ 40 (* 50 (js/Math.random))))
            (and ground? (not jump?)) (update :vx * 0.7))
        vy (min max-fall (+ (:vy s) (* gravity dt)))
        s (assoc s :vy vy)]
    (as-> s s
      (move-x tiles s (* (:vx s) dt))
      (move-y tiles s (* vy dt)))))

(defn- overlaps? [{:keys [x y]} s]
  (and (< (:x s) (+ x player-w)) (< x (+ (:x s) slime-w))
       (< (:y s) (+ y player-h)) (< y (+ (:y s) slime-h))))

(defn- puff [{:keys [x y]} color n]
  (for [i (range n)]
    {:x (+ x (* slime-w (js/Math.random))) :y (+ y (* slime-h (js/Math.random)))
     :vx (- (* 4 (js/Math.random)) 2) :vy (- (* -3 (js/Math.random)) 1)
     :life (+ 25 (* 20 (js/Math.random))) :color color :i i}))

(defn- step-enemies!
  "Spawns slimes in caves at night, moves them, knocks the player back on contact and
  removes them in daylight."
  [state tiles surface light dt]
  (swap! state
         (fn [{:keys [player enemies spawn-in] :as st}]
           (let [night? (< light 0.3)
                 spawn-in (- (or spawn-in 0) dt)
                 new (when (and night? (<= spawn-in 0) (< (count enemies) max-slimes))
                       (cond-> (spawn-slime tiles surface player)
                         (= :slime (:realm st)) (some-> (merge tough-slime))))
                 enemies (cond-> (mapv #(step-slime tiles % player dt) (or enemies []))
                           new (conj new))
                 vanish (if (> light 0.6) enemies [])
                 enemies (if (> light 0.6) [] enemies)
                 touching (when (<= (:hurt player 0) 0) (first (filter #(overlaps? player %) enemies)))]
             (cond-> (assoc st
                            :enemies enemies
                            :spawn-in (if (<= spawn-in 0) 120 spawn-in)
                            :particles (into (:particles st)
                                             (mapcat #(puff % "rgba(200,255,200,0.8)" 8) vanish)))
               true (update-in [:player :hurt] #(max 0 (- (or % 0) dt)))
               touching (update :player assoc
                                :vx (if (< (+ (:x player) (/ player-w 2))
                                           (+ (:x touching) (/ slime-w 2))) -7 7)
                                :vy -5 :hurt 50 :climbing? false
                                :hp (max 0 (- (:hp player max-hp) (:dmg touching 1)))))))))

(defn- strike-enemies
  "Damages the slimes in front of the pickaxe. Returns nil when it reaches none,
  `:loot` when a slime died and dropped coins, and `:hit` otherwise."
  [state]
  (let [{:keys [player enemies]} @state
        [hx hy] (hand player)
        [ax ay] (or (:aim-at player) [(+ hx (:facing player)) hy])
        ad (max 0.001 (js/Math.hypot (- ax hx) (- ay hy)))
        in-reach? (fn [{:keys [x y]}]
                    (let [cx (- (+ x (/ slime-w 2)) hx)
                          cy (- (+ y (/ slime-h 2)) hy)
                          d (js/Math.hypot cx cy)]
                      (and (<= d (+ reach 10))
                           (or (< d 24)
                               (> (/ (+ (* cx (- ax hx)) (* cy (- ay hy))) (* d ad)) 0.55)))))
        hit-idx (set (keep-indexed (fn [i e] (when (in-reach? e) i)) enemies))]
    (when (seq hit-idx)
      (let [hit (mapv (fn [i e]
                        (if (hit-idx i)
                          (assoc e :hp (dec (:hp e)) :flash 10 :vy -4
                                 :vx (if (< (+ (:x e) (/ slime-w 2)) hx) -4 4))
                          e))
                      (range) enemies)
            dead (filter #(<= (:hp %) 0) hit)
            coins (reduce + (map (fn [_] (+ 2 (js/Math.floor (* 4 (js/Math.random))))) dead))
            amulets (filterv (fn [_] (< (js/Math.random) amulet-chance)) dead)
            arena-keys (if (= :slime (:realm @state))
                         (filterv (fn [_] (< (js/Math.random) key-chance)) dead)
                         [])]
        (swap! state
               (fn [st]
                 (cond-> (-> st
                             (assoc :enemies (filterv #(pos? (:hp %)) hit))
                             (update :particles into
                                     (mapcat #(puff % slime-color (if (pos? (:hp %)) 4 14)) hit)))
                   (pos? coins)
                   (-> (add-coins coins)
                       (update :popups conj
                               (let [{:keys [x y]} (first dead)]
                                 {:text (str "+" coins) :icon :coin :x (+ x (/ slime-w 2)) :y (- y 6)
                                  :life 90})))
                   (seq amulets)
                   (-> (stash [(assoc amulet :count (count amulets))])
                       (update :popups conj
                               (let [{:keys [x y]} (first amulets)]
                                 {:text "+1 Dimension Amulet!" :icon :amulet :x (+ x (/ slime-w 2)) :y (- y 22)
                                  :life 150})))
                   (seq arena-keys)
                   (-> (stash [(assoc arena-key :count (count arena-keys))])
                       (update :popups conj
                               (let [{:keys [x y]} (first arena-keys)]
                                 {:text "+1 Arena Key!" :icon :key :x (+ x (/ slime-w 2)) :y (- y 38)
                                  :life 150}))))))
        (if (pos? coins) :loot :hit)))))

(defn- portal-aimed?
  "Checks that the mouse points at `portal` and the portal is within reach of the hand."
  [{:keys [aim-at] :as p} {:keys [x y] :as portal}]
  (when (and portal aim-at)
    (let [[hx hy] (hand p)
          [ax ay] aim-at]
      (and (<= (- x 4) ax (+ x portal-w 4))
           (<= (- y 4) ay (+ y portal-h 4))
           (<= (js/Math.hypot (- (+ x (/ portal-w 2)) hx) (- (+ y (/ portal-h 2)) hy))
               (+ reach 16))))))

(defn- switch-realm
  "Stores the chests, slimes and portal of the current realm and brings back those of
  realm `to`."
  [{:keys [realm] :as st} to]
  (let [here (select-keys st [:chests :enemies :portal])
        there (get-in st [:away to] {:chests (get-in st [:worlds to :chests] [])
                                     :enemies []
                                     :portal (get-in st [:worlds to :portal])})]
    (-> st
        (assoc-in [:away realm] here)
        (merge there)
        (assoc :realm to :particles [] :popups [] :spawn-in 0))))

(declare leave-arena)

(defn- teleport
  "Moves the player through the portal of the current realm to the other realm, next
  to the portal there. The portal in the arena leads back to the arena gate."
  [{:keys [realm] :as st}]
  (if (= realm :arena)
    (leave-arena st)
    (let [to (if (= realm :slime) :overworld :slime)
          st (switch-realm st to)
          {:keys [x y back-x]} (:portal st)
          px (+ back-x (/ player-w 2))
          portal-glow (for [i (range 24)]
                        {:x (+ x (* portal-w (js/Math.random))) :y (+ y (* portal-h (js/Math.random)))
                         :vx (- (* 4 (js/Math.random)) 2) :vy (- (* -3 (js/Math.random)) 1)
                         :life (+ 25 (* 25 (js/Math.random))) :color "#c58cff" :i i})]
      (-> st
          (update :player assoc :x back-x :y (- (+ y portal-h) player-h 0.5)
                  :vx 0 :vy 0 :climbing? false :swing swing-frames)
          (update :particles into portal-glow)
          (update :popups conj {:text (if (= to :slime) "The Slime Dimension" "Back home")
                                :x px :y (- y 14) :life 150})))))

(defn- draw-portal [^js ctx {:keys [x y] :as portal} t cam-x cam-y]
  (when portal
    (let [cx (- (+ x (/ portal-w 2)) cam-x)
          cy (- (+ y (/ portal-h 2)) cam-y)
          rx (/ portal-w 2)
          ry (/ portal-h 2)
          pulse (* 0.5 (+ 1 (js/Math.sin (/ t 250))))]
      (set! (.-fillStyle ctx) (str "rgba(170,90,255," (+ 0.18 (* 0.12 pulse)) ")"))
      (.beginPath ctx)
      (.ellipse ctx cx cy (+ rx 8) (+ ry 8) 0 0 (* 2 js/Math.PI))
      (.fill ctx)
      (let [g (.createRadialGradient ctx cx cy 2 cx cy ry)]
        (.addColorStop g 0 "#f3e6ff")
        (.addColorStop g 0.35 "#b46cff")
        (.addColorStop g 1 "#3b0f6e")
        (set! (.-fillStyle ctx) g)
        (.beginPath ctx)
        (.ellipse ctx cx cy rx ry 0 0 (* 2 js/Math.PI))
        (.fill ctx))
      (set! (.-fillStyle ctx) "#efd9ff")
      (doseq [i (range 6)]
        (let [a (+ (/ t 400) (* i (/ js/Math.PI 3)))
              r (+ 0.45 (* 0.35 (mod (+ (/ t 1500) (/ i 6)) 1)))]
          (.fillRect ctx (js/Math.round (+ cx (* rx r (js/Math.cos a))))
                     (js/Math.round (+ cy (* ry r (js/Math.sin a)))) 2 2))))))

(defn- on-surface?
  "Checks that the player stands at ground level rather than down in the caves."
  [{:keys [x y]} ^js surface]
  (let [tx (js/Math.floor (/ (+ x (/ player-w 2)) T))
        feet (js/Math.floor (/ (+ y player-h) T))]
    (<= feet (inc (aget surface tx)))))

(defn- open-portal
  "Opens a portal beside the player on the side they face."
  [{:keys [player] :as st}]
  (let [{:keys [x y facing]} player
        cx (+ x (/ player-w 2) (* facing (+ (/ player-w 2) 22)))
        px (- cx (/ portal-w 2))
        py (- (+ y player-h) portal-h)]
    (-> st
        (assoc :portal {:x px :y py :back-x x})
        (update :popups conj {:text "A portal opened! Hit it with the pickaxe"
                              :x cx :y (- py 12) :life 150}))))

(defn- draw-enemies [^js ctx enemies t vw vh cam-x cam-y]
  (doseq [{:keys [x y vy flash ground? color]} enemies
          :let [sx (js/Math.round (- x cam-x))
                sy (js/Math.round (- y cam-y))]
          :when (and (< -40 sx (+ vw 40)) (< -40 sy (+ vh 40)))]
    (let [squash (cond (not ground?) (max -3 (min 3 (* -0.5 vy)))
                       :else (* 1.5 (js/Math.sin (/ t 120))))
          h (+ slime-h squash)
          w (- slime-w squash)
          bx (+ sx (/ (- slime-w w) 2))
          by (- (+ sy slime-h) h)]
      (set! (.-fillStyle ctx) (if (pos? flash) "#ffffff" (or color slime-color)))
      (.beginPath ctx)
      (.roundRect ctx bx by w h #js [9 9 3 3])
      (.fill ctx)
      (set! (.-fillStyle ctx) "rgba(255,255,255,0.45)")
      (.fillRect ctx (+ bx 4) (+ by 3) 4 3)
      (set! (.-fillStyle ctx) "#14321a")
      (.fillRect ctx (+ bx 6) (+ by 6) 3 4)
      (.fillRect ctx (+ bx w -9) (+ by 6) 3 4))))

(defn- draw-eyes
  "Draws glowing eyes over the darkness so slimes outside the player's light are visible."
  [^js ctx enemies light cam-x cam-y]
  (when (< light 1)
    (set! (.-fillStyle ctx) (str "rgba(210,255,120," (* 0.9 (- 1 light)) ")"))
    (doseq [{:keys [x y]} enemies
            :let [sx (js/Math.round (- x cam-x)) sy (js/Math.round (- y cam-y))]]
      (.fillRect ctx (+ sx 6) (+ sy 6) 3 3)
      (.fillRect ctx (+ sx slime-w -9) (+ sy 6) 3 3))))

(defn- spawn [{:keys [surface]}]
  (let [x (quot W 2)]
    {:x (+ (* x T) (/ (- T player-w) 2))
     :y (- (* T (aget surface x)) player-h 1)
     :vx 0 :vy 0 :facing 1 :walked 0 :ground? false :swing 0 :hp max-hp}))

(def buy-prices
  {:potion 10 :rope 5 :house 30})

(def buy-amounts
  {:potion 1 :rope 5 :house 1})

(def house-item {:id :house :name "Fluffy House"})

(defn- item-def
  "Returns the id and name of the item `id` that the shop sells."
  [id]
  (select-keys (or (first (filter #(= id (:id %)) loot))
                   (when (= id :house) house-item))
               [:id :name]))

(defn- buy
  "Buys `buy-amounts` of `id` into the bag. Leaves the state unchanged when the bag has
  no slot for it or the player has too few coins."
  [{:keys [bag] :as st} id]
  (let [cost (buy-prices id)]
    (if (and cost (bag-slot bag id) (>= (:coins st 0) cost))
      (-> st
          (add-coins (- cost))
          (stash [(assoc (item-def id) :count (buy-amounts id))]))
      st)))

(def map-price 80)

(defn- buy-map
  "Buys the map for `map-price` coins. Leaves the state unchanged when the player
  already owns it or has too few coins."
  [st]
  (if (and (not (:map? st)) (>= (:coins st 0) map-price))
    (-> st (add-coins (- map-price)) (assoc :map? true))
    st))

(defn add-arena
  "Gives a game started before the arena existed its gate and arena."
  [st]
  (cond-> st
    (not (get-in st [:worlds :arena]))
    (assoc-in [:worlds :arena] (generate-arena))
    (not (get-in st [:worlds :slime :gate]))
    (update-in [:worlds :slime]
               (fn [w] (assoc w :gate (carve-gate! (:tiles w) (:levels w)))))))

(defn- gate-aimed?
  "Checks that the mouse points at `gate` and the gate is within reach of the hand."
  [{:keys [aim-at] :as p} {:keys [x y] :as gate}]
  (when (and gate aim-at)
    (let [[hx hy] (hand p)
          [ax ay] aim-at]
      (and (<= (- x 4) ax (+ x gate-w 4))
           (<= (- y 4) ay (+ y gate-h 4))
           (<= (js/Math.hypot (- (+ x (/ gate-w 2)) hx) (- (+ y (/ gate-h 2)) hy))
               (+ reach 16))))))

(defn- knock-gate
  "Opens the arena gate with an Arena Key from the bag, or tells the player it is
  locked."
  [st]
  (let [{:keys [x y]} (get-in st [:worlds :slime :gate])
        popup {:x (+ x (/ gate-w 2)) :y (- y 10) :life 120}]
    (-> (if (pos? (bag-count (:bag st) :key))
          (-> st
              (take-from-bag :key 1)
              (assoc :gate-open? true)
              (update :popups conj (assoc popup :text "The gate opens!")))
          (update st :popups conj (assoc popup :text "Locked. It needs an Arena Key" :icon :key)))
        (assoc-in [:player :swing] swing-frames))))

(def boss-w 96)
(def boss-h 64)
(def boss-max-hp 40)
(def boss-reward 100)

(defn- new-boss []
  {:x (* T 258) :y (* T (+ arena-top 2)) :w boss-w :h boss-h :vx 0 :vy 0 :ground? false
   :hp boss-max-hp :flash 0 :hop 90 :dmg 3 :mode :ground :leap-in 420})

(defn- enter-arena
  "Moves the player through the open gate into the arena, closes the gate behind them
  and wakes the Lord of Goo."
  [st]
  (let [st (switch-realm (add-arena st) :arena)
        px (* T (+ arena-x0 4))]
    (-> st
        (assoc :gate-open? false :portal nil :enemies [] :boss (new-boss))
        (update :player assoc :x px :y (- (* T arena-floor) player-h 0.5)
                :vx 0 :vy 0 :climbing? false :facing 1)
        (update :popups conj {:text "The gate slams shut… the Lord of Goo awakens!"
                              :x (+ px 120) :y (- (* T arena-floor) player-h 30) :life 180}))))

(defn- leave-arena
  "Takes the player from the arena back to the Slime Dimension, in front of the gate."
  [st]
  (let [st (switch-realm st :slime)
        {:keys [x y]} (get-in st [:worlds :slime :gate])]
    (-> st
        (dissoc :boss)
        (update :player assoc :x (- x 34) :y (- (+ y gate-h) player-h 0.5)
                :vx 0 :vy 0 :climbing? false :swing swing-frames)
        (update :popups conj {:text "Back at the gate" :x x :y (- y 14) :life 120}))))

(defn- touching? [{:keys [x y]} b]
  (and (< (:x b) (+ x player-w)) (< x (+ (:x b) (:w b)))
       (< (:y b) (+ y player-h)) (< y (+ (:y b) (:h b)))))

(def perch
  "Position the boss takes on top of the highest ledge of the arena."
  {:x (- (* T 240) (/ boss-w 2)) :y (- (* T 51) boss-h 0.01)})

(def leap-frames 60)
(def minion-throws 3)
(def perch-max 900)

(defn- next-leap [] (+ 420 (* 240 (js/Math.random))))

(defn- minion
  "Returns a small slime thrown from the boss toward the side the player is on."
  [{:keys [x y]} dir]
  {:x (+ x (/ boss-w 2) -11) :y (+ y 16) :w slime-w :h slime-h
   :vx (* dir (+ 1.5 (* 2.5 (js/Math.random)))) :vy (- -4 (* 2 (js/Math.random)))
   :ground? false :hp 2 :flash 0 :hop 50 :minion? true :color "#8fe86b"})

(defn- move-boss
  "Moves the boss one frame and returns it with the minions it throws. On the ground it
  hops at the player until it is time to leap onto the highest ledge. There it throws
  `minion-throws` minions, waits until they are dead (at most `perch-max` frames) and
  jumps back down."
  [tiles boss dir minions-alive dt]
  (let [b (update boss :flash #(max 0 (- % dt)))]
    (case (:mode b)
      :leap
      (let [t (min 1 (+ (:t b) (/ dt leap-frames)))
            [fx fy] (:from b)
            arc (* 4 90 t (- 1 t))]
        [(if (< t 1)
           (assoc b :t t :x (+ fx (* t (- (:x perch) fx))) :y (- (+ fy (* t (- (:y perch) fy))) arc))
           (merge b perch {:mode :perch :vx 0 :vy 0 :ground? true :throws minion-throws
                           :throw-in 40 :perched 0 :landed? true}))
         []])

      :perch
      (let [b (-> b (update :throw-in - dt) (update :perched + dt) (dissoc :landed?))
            throw? (and (pos? (:throws b)) (<= (:throw-in b) 0))
            b (cond-> b throw? (-> (update :throws dec) (assoc :throw-in 70)))
            done? (and (zero? (:throws b)) (or (zero? minions-alive) (> (:perched b) perch-max)))]
        [(if done?
           (assoc b :mode :ground :vx (* dir 3) :vy -5 :ground? false :leap-in (next-leap) :hop 60)
           b)
         (if throw? [(minion b dir)] [])])

      (let [jump? (and (:ground? b) (<= (:hop b) 0))
            leap? (and (:ground? b) (<= (:leap-in b) 0))
            b (cond-> (-> b (update :hop - dt) (update :leap-in (fnil - 420) dt))
                jump? (assoc :vy (- -8 (* 3 (js/Math.random)))
                             :vx (* dir (+ 2.5 (* 1.5 (js/Math.random))))
                             :hop (+ 50 (* 60 (js/Math.random))))
                (and (:ground? b) (not jump?)) (update :vx * 0.8))]
        (if leap?
          [(assoc b :mode :leap :t 0 :from [(:x b) (:y b)] :ground? false) []]
          (let [was-up? (not (:ground? b))
                b (assoc b :vy (min max-fall (+ (:vy b) (* gravity dt))))
                b (as-> b b
                    (move-x tiles b (* (:vx b) dt))
                    (move-y tiles b (* (:vy b) dt)))]
            [(assoc b :landed? (and was-up? (:ground? b))) []]))))))

(defn- step-boss!
  "Moves the Lord of Goo, adds the minions it throws to the slimes and hurts the player
  on contact."
  [state tiles dt]
  (when (:boss @state)
    (swap! state
           (fn [{:keys [boss player enemies] :as st}]
             (let [dir (if (< (+ (:x player) (/ player-w 2)) (+ (:x boss) (/ boss-w 2))) -1 1)
                   [b thrown] (move-boss tiles boss dir (count (filter :minion? enemies)) dt)
                   hit? (and (<= (:hurt player 0) 0) (touching? player b))]
               (cond-> (-> st
                           (assoc :boss (dissoc b :landed?))
                           (update :enemies (fnil into []) thrown))
                 (:landed? b)
                 (update :particles into (map #(assoc % :x (+ (:x b) (* boss-w (js/Math.random)))
                                                      :y (+ (:y b) boss-h -4))
                                              (puff b "rgba(120,220,120,0.8)" 10)))
                 (seq thrown)
                 (update :particles into (puff (first thrown) "#8fe86b" 6))
                 hit? (update :player assoc
                              :vx (* 8 (- dir)) :vy -6 :hurt 60 :climbing? false
                              :hp (max 0 (- (:hp player max-hp) (:dmg b))))))))))

(defn- strike-boss
  "Hits the Lord of Goo when it is in front of the pickaxe and within reach. Returns
  nil when the pickaxe does not reach it, `:loot` when it dies and `:hit` otherwise."
  [state]
  (let [{:keys [player boss]} @state]
    (when boss
      (let [[hx hy] (hand player)
            [ax ay] (or (:aim-at player) [(+ hx (:facing player)) hy])
            nx (max (:x boss) (min hx (+ (:x boss) boss-w)))
            ny (max (:y boss) (min hy (+ (:y boss) boss-h)))
            d (js/Math.hypot (- nx hx) (- ny hy))
            cx (- (+ (:x boss) (/ boss-w 2)) hx)
            cy (- (+ (:y boss) (/ boss-h 2)) hy)
            facing? (> (+ (* cx (- ax hx)) (* cy (- ay hy))) 0)]
        (when (and (<= d (+ reach 8)) (or (< d 20) facing?))
          (let [hp (dec (:hp boss))
                {:keys [x y]} boss]
            (if (pos? hp)
              (do (swap! state #(-> %
                                    (update :boss assoc :hp hp :flash 8
                                            :vx (if (neg? cx) 2 -2))
                                    (update :particles into (puff boss slime-color 5))))
                  :hit)
              (do (swap! state
                         (fn [st]
                           (-> st
                               (dissoc :boss)
                               (update :particles into
                                       (mapcat #(puff % "#8fe86b" 8) (filter :minion? (:enemies st))))
                               (update :enemies #(filterv (complement :minion?) %))
                               (assoc :portal {:x (- (* T 240) (/ portal-w 2))
                                               :y (- (* T arena-floor) portal-h)})
                               (add-coins boss-reward)
                               (update :particles into
                                       (mapcat (fn [i]
                                                 (puff {:x (+ x (* i 18)) :y (+ y (* (mod i 3) 18))}
                                                       (if (even? i) slime-color "#ffd54a") 10))
                                               (range 6)))
                               (update :popups conj
                                       {:text (str "Lord of Goo defeated! +" boss-reward) :icon :coin
                                        :x (+ x (/ boss-w 2)) :y (- y 10) :life 200}
                                       {:text "A portal back opened in the middle of the arena"
                                        :x (+ x (/ boss-w 2)) :y (- y 32) :life 200}))))
                  :loot))))))))

(defn- draw-gate
  "Draws a stone arch with an iron gate. An open gate shows a glowing passage."
  [^js ctx {:keys [x y] :as gate} open? t cam-x cam-y]
  (when gate
    (let [sx (js/Math.round (- x cam-x))
          sy (js/Math.round (- y cam-y))
          rect (fn [color rx ry rw rh]
                 (set! (.-fillStyle ctx) color)
                 (.fillRect ctx (+ sx rx) (+ sy ry) rw rh))]
      (rect "#3a4a40" -6 -8 (+ gate-w 12) 8)
      (rect "#3a4a40" -6 0 6 gate-h)
      (rect "#3a4a40" gate-w 0 6 gate-h)
      (rect "#6b7d70" -6 -8 (+ gate-w 12) 2)
      (if open?
        (let [pulse (* 0.5 (+ 1 (js/Math.sin (/ t 300))))]
          (rect "#0a0f0c" 0 0 gate-w gate-h)
          (rect (str "rgba(140,255,120," (+ 0.15 (* 0.2 pulse)) ")") 2 2 (- gate-w 4) (- gate-h 2)))
        (do (rect "#1b241e" 0 0 gate-w gate-h)
            (doseq [i (range 5)]
              (rect "#7a7f86" (+ 2 (* i 7)) 0 3 gate-h))
            (rect "#7a7f86" 0 14 gate-w 3)
            (rect "#7a7f86" 0 32 gate-w 3)
            (rect "#c9a227" 13 21 6 7)
            (rect "#1b241e" 15 23 2 3))))))

(defn- draw-boss [^js ctx {:keys [x y vy ground? flash]} t cam-x cam-y]
  (let [sx (js/Math.round (- x cam-x))
        sy (js/Math.round (- y cam-y))
        squash (if ground? (* 3 (js/Math.sin (/ t 160))) (max -8 (min 8 (* -1.2 vy))))
        h (+ boss-h squash)
        w (- boss-w squash)
        bx (+ sx (/ (- boss-w w) 2))
        by (- (+ sy boss-h) h)]
    (set! (.-fillStyle ctx) (if (pos? flash) "#ffffff" "#3fae3f"))
    (.beginPath ctx)
    (.roundRect ctx bx by w h #js [40 40 10 10])
    (.fill ctx)
    (set! (.-fillStyle ctx) "rgba(20,70,25,0.35)")
    (.fillRect ctx (+ bx 8) (+ by h -10) (- w 16) 6)
    (set! (.-fillStyle ctx) "rgba(255,255,255,0.45)")
    (.fillRect ctx (+ bx 16) (+ by 12) 12 8)
    (set! (.-fillStyle ctx) "#0f2a14")
    (.fillRect ctx (+ bx 26) (+ by 24) 9 12)
    (.fillRect ctx (+ bx w -35) (+ by 24) 9 12)
    (.fillRect ctx (+ bx 22) (+ by 20) 14 3)
    (.fillRect ctx (+ bx w -36) (+ by 20) 14 3)
    (.fillRect ctx (+ bx 36) (+ by 44) (- w 72) 4)
    (set! (.-fillStyle ctx) "#ffd54a")
    (let [cx (+ bx (/ w 2))
          top (- by 4)]
      (.beginPath ctx)
      (.moveTo ctx (- cx 20) (+ top 6))
      (.lineTo ctx (- cx 20) (- top 10))
      (.lineTo ctx (- cx 10) (- top 2))
      (.lineTo ctx cx (- top 14))
      (.lineTo ctx (+ cx 10) (- top 2))
      (.lineTo ctx (+ cx 20) (- top 10))
      (.lineTo ctx (+ cx 20) (+ top 6))
      (.closePath ctx)
      (.fill ctx)
      (set! (.-fillStyle ctx) "#e0453a")
      (.fillRect ctx (- cx 3) (- top 3) 6 6))))

(defui boss-bar [{:keys [hp]}]
  ($ :div {:class "absolute left-1/2 top-4 w-[28rem] max-w-[60vw] -translate-x-1/2 rounded-md bg-black/60 px-4 py-2 text-white shadow-lg select-none"}
     ($ :div {:class "mb-1 flex items-center justify-between text-sm"}
        ($ :span {:class "flex items-center gap-1.5 font-bold tracking-wide"} ($ icons/icon {:id :crown :size 18}) "Lord of Goo")
        ($ :span {:class "font-mono tabular-nums"} hp " / " boss-max-hp))
     ($ :div {:class "h-4 w-full overflow-hidden rounded-sm border border-white/20 bg-green-950/80"}
        ($ :div {:class "h-full bg-gradient-to-r from-lime-400 to-green-600 transition-[width] duration-150"
                 :style {:width (str (* 100 (/ hp boss-max-hp)) "%")}}))))

(def fluffy-r 9)
(def fluffy-reach (* 6 T))

(defn- pocket-at
  "Returns the row of the highest open 3 by 3 cave pocket in column `x`, at least 12
  tiles below the surface, or nil when the column has none."
  [^js tiles ^js surface x]
  (some (fn [y]
          (when (every? (fn [[dx dy]] (= air (tile-at tiles (+ x dx) (+ y dy))))
                        (for [dx [-1 0 1] dy [-1 0 1]] [dx dy]))
            y))
        (range (+ (aget surface x) 12) (- H 4))))

(defn place-fluffies
  "Hides rare Fluffies in roomy cave pockets of the overworld, well below the surface
  and at least 60 tiles apart."
  [^js tiles ^js surface]
  (loop [x 12 last -100 acc []]
    (if (>= x (- W 12))
      acc
      (let [spot (when (and (> (- x last) 60) (< (hash2 x 913) 0.03))
                   (pocket-at tiles surface x))]
        (if spot
          (recur (inc x) x (conj acc {:realm :overworld
                                      :hx (* T (+ x 0.5)) :hy (* T (+ spot 0.5))
                                      :x (* T (+ x 0.5)) :y (* T (+ spot 0.5))
                                      :phase (* 6 (hash2 x 17))
                                      :following? false}))
          (recur (inc x) last acc))))))

(defn add-fluffies
  "Gives a game started before Fluffies existed its Fluffies."
  [st]
  (if (:fluffies st)
    st
    (let [{:keys [tiles surface]} (get-in st [:worlds :overworld])]
      (assoc st :fluffies (place-fluffies tiles surface)))))

(defn- toggle-fluffy
  "Makes the nearest Fluffy within `fluffy-reach` of the player follow them, or makes a
  following one stay where it is."
  [{:keys [player realm fluffies] :as st}]
  (let [px (+ (:x player) (/ player-w 2))
        py (+ (:y player) (/ player-h 2))
        dist #(js/Math.hypot (- (:x %) px) (- (:y %) py))
        near (->> (map-indexed vector fluffies)
                  (filter (fn [[_ f]] (and (= realm (:realm f)) (not (:home f)) (<= (dist f) fluffy-reach))))
                  (sort-by (fn [[_ f]] (dist f)))
                  first)]
    (if-let [[i f] near]
      (let [follow? (not (:following? f))]
        (-> st
            (cond-> (and follow? (:red? f)) (assoc-in [:quest :found?] true))
            (update-in [:fluffies i] assoc :following? follow? :hx (:x f) :hy (:y f))
            (update :popups conj {:text (if follow? "A Fluffy is following you!" "The Fluffy waits here")
                                  :x (:x f) :y (- (:y f) 18) :life 120})))
      st)))

(declare house-hole)

(def roam-reach (* 5 T))
(def roam-cycle 24000)
(def home-ms 10000)

(defn- step-fluffies
  "Makes waiting Fluffies hover where they are, and following ones fly after the player,
  through walls and into whichever realm the player is in. A resident buzzes inside its
  house for `home-ms` of every `roam-cycle` and spends the rest flying within
  `roam-reach` of it."
  [{:keys [player realm fluffies houses] :as st} clock dt]
  (let [px (+ (:x player) (/ player-w 2))
        py (:y player)
        facing (or (:facing player) 1)
        n (atom -1)]
    (assoc st :fluffies
           (mapv (fn [f]
                   (if (:following? f)
                     (let [i (swap! n inc)
                           tx (- px (* facing (+ 26 (* 20 i))))
                           ty (+ py -14 (* 4 (js/Math.sin (+ (/ clock 300) (:phase f)))))
                           far? (or (not= realm (:realm f))
                                    (> (js/Math.hypot (- tx (:x f)) (- ty (:y f))) 700))
                           k (min 1 (* 0.08 dt))]
                       (if far?
                         (assoc f :realm realm :x tx :y (- ty 20))
                         (assoc f :x (+ (:x f) (* k (- tx (:x f))))
                                :y (+ (:y f) (* k (- ty (:y f)))))))
                     (if-let [h (some->> (:home f) (get houses))]
                       (let [[hx hy] (house-hole h)
                             ph (:phase f)
                             roaming? (> (mod (+ clock (* ph 4000)) roam-cycle) home-ms)
                             k (min 1 (* (if roaming? 0.03 0.12) dt))
                             tx (if roaming?
                                  (+ hx (* roam-reach (js/Math.sin (+ (/ clock 2600) ph))))
                                  (+ hx (* 1.5 (js/Math.sin (+ (/ clock 60) ph)))))
                             ty (if roaming?
                                  (- hy (* T (+ 1.5 (* 1.2 (js/Math.sin (+ (/ clock 1900) ph))))))
                                  (+ hy (* 1.2 (js/Math.sin (+ (/ clock 45) ph)))))
                             x (+ (:x f) (* k (- tx (:x f))))
                             y (+ (:y f) (* k (- ty (:y f))))]
                         (assoc f :x x :y y
                                :out? (or roaming? (> (js/Math.hypot (- x hx) (- y hy)) 6))))
                       (let [k (min 1 (* 0.06 dt))
                             ty (+ (:hy f) (* 3 (js/Math.sin (+ (/ clock 400) (:phase f)))))]
                         (assoc f :x (+ (:x f) (* k (- (:hx f) (:x f))))
                                :y (+ (:y f) (* k (- ty (:y f)))))))))
                 (or fluffies [])))))

(def house-w 48)
(def house-h 48)
(def house-reward 70)
(def settle-reach (* 4 T))
(def hole-r 14)

(defn- house-bottom
  "Returns the ground line under house `h`. Houses placed before they grew to
  `house-h` have no :h and were 32 pixels tall."
  [h]
  (+ (:y h) (:h h 32)))

(defn- house-hole
  "Returns the centre of the round opening of house `h`, where its resident lives."
  [h]
  [(+ (:x h) (/ house-w 2)) (- (house-bottom h) 20)])

(def quest-reward 100)
(def quest-reach (* 4 T))
(def neighbour-gap (* 40 T))

(defn- next-door?
  "Checks that house `h` stands within `neighbour-gap` of the house of the Fluffy who
  gave the quest."
  [{:keys [quest houses]} h]
  (when-let [g (get houses (:giver quest))]
    (and (not= g h) (= (:realm g) (:realm h))
         (let [[gx gy] (house-hole g)
               [hx hy] (house-hole h)]
           (<= (js/Math.hypot (- gx hx) (- gy hy)) neighbour-gap)))))

(defn- settle-fluffies
  "Moves each following Fluffy that comes near an empty house into it, one per house,
  and pays the player `house-reward` coins for every Fluffy that moves in. The red
  Fluffy of the quest moves only into a house near its friend, and finishes
  the quest for `quest-reward` coins."
  [{:keys [realm houses fluffies] :as st}]
  (reduce
   (fn [st [i f]]
     (let [free (->> (map-indexed vector (:houses st))
                     (filter (fn [[_ h]]
                               (let [[hx hy] (house-hole h)]
                                 (and (= realm (:realm h)) (nil? (:resident h))
                                      (or (not (:red? f)) (next-door? st h))
                                      (<= (js/Math.hypot (- hx (:x f)) (- hy (:y f))) settle-reach)))))
                     first)]
       (if-let [[hi h] free]
         (let [[hx hy] (house-hole h)
               top (- (house-bottom h) house-h 14)]
           (cond-> (-> st
                       (assoc-in [:houses hi :resident] i)
                       (update-in [:fluffies i] assoc :following? false :home hi :hx hx :hy hy))
             (not (:red? f))
             (-> (add-coins house-reward)
                 (update :popups conj {:text (str "A Fluffy moved in! +" house-reward) :icon :coin
                                       :x hx :y top :life 150}))
             (:red? f)
             (-> (add-coins quest-reward)
                 (assoc-in [:quest :stage] :done)
                 (update :popups conj {:text (str "Quest complete! The friends are neighbours. +" quest-reward)
                                       :icon :coin :x hx :y top :life 240}))))
         st)))
   st
   (when (seq houses)
     (filter (fn [[_ f]] (and (:following? f) (= realm (:realm f))))
             (map-indexed vector fluffies)))))

(defn- offer-quest
  "Lets the Fluffy in the first lived-in house ask for help once no quest has been
  offered yet."
  [{:keys [quest houses] :as st}]
  (if-let [hi (and (nil? quest)
                   (first (keep-indexed (fn [i h] (when (:resident h) i)) houses)))]
    (assoc st :quest {:stage :offered :giver hi})
    st))

(defn- quest-giver-near?
  "Checks that the player stands close enough to the house that offers the quest to
  accept it."
  [{:keys [quest houses player realm]}]
  (when-let [h (and (= :offered (:stage quest)) (get houses (:giver quest)))]
    (let [[hx hy] (house-hole h)]
      (and (= realm (:realm h))
           (<= (js/Math.hypot (- hx (+ (:x player) (/ player-w 2)))
                              (- hy (+ (:y player) (/ player-h 2))))
               quest-reach)))))

(defn- lost-friend
  "Returns a red Fluffy in a cave pocket of the overworld at least 60 tiles from
  column `from`, or floating above the ground when the caves have no room."
  [^js tiles ^js surface from]
  (let [xs (shuffle (filter #(> (js/Math.abs (- % from)) 60) (range 12 (- W 12))))
        [x y] (or (some (fn [x] (some->> (pocket-at tiles surface x) (vector x))) xs)
                  (let [x (first xs)] [x (- (aget surface x) 4)]))]
    {:realm :overworld :red? true
     :hx (* T (+ x 0.5)) :hy (* T (+ y 0.5))
     :x (* T (+ x 0.5)) :y (* T (+ y 0.5))
     :phase (* 6 (js/Math.random))
     :following? false}))

(defn- accept-quest
  "Accepts the quest when the player stands by the house that offers it and puts the
  lost red Fluffy into the world. Returns nil when there is no quest to accept here."
  [st]
  (when (quest-giver-near? st)
    (let [{:keys [tiles surface]} (get-in st [:worlds :overworld])
          h (get (:houses st) (get-in st [:quest :giver]))
          [hx _] (house-hole h)
          friend (lost-friend tiles surface (js/Math.floor (/ hx T)))]
      (-> st
          (update :fluffies (fnil conj []) friend)
          (update :quest assoc :stage :active :friend (count (:fluffies st)))
          (update :popups conj {:text "My red friend is lost! Please find it and build it a home near mine."
                                :x hx :y (- (house-bottom h) house-h 14) :life 300})))))

(defn- place-house
  "Sets a Fluffy House on open ground at the tile under the mouse, within `reach` of the
  hand. Returns the new state, or nil when there is no room there."
  [{:keys [player realm houses] :as st} tiles]
  (let [[hx hy] (hand player)
        [ax ay] (or (:aim-at player) [hx hy])
        tx (js/Math.floor (/ ax T))
        ;; a house aimed into the ground rises onto it, and one aimed in the air drops onto it
        ty (loop [ty (js/Math.floor (/ ay T)) n 0]
             (if (and (< n 3) (solid? (tile-at tiles tx ty)))
               (recur (dec ty) (inc n))
               ty))
        cols (range (dec tx) (+ tx 2))
        ;; the first solid row under each column; the house rests on the highest of them
        ground (fn [c] (first (filter #(solid? (tile-at tiles c %)) (range ty (+ ty 6)))))
        grounds (map ground cols)
        ty (when (every? some? grounds) (dec (apply min grounds)))
        x (* T (dec tx))
        bottom (* T (inc (or ty 0)))
        room? (and ty
                   (every? #(<= % (+ ty 3)) grounds)
                   (every? (fn [[c dy]] (= air (tile-at tiles c (- ty dy))))
                           (for [c cols dy (range 3)] [c dy]))
                   (<= (js/Math.hypot (- (+ x (/ house-w 2)) hx) (- (- bottom (/ house-h 2)) hy)) (+ reach 24))
                   (not-any? (fn [h] (and (= realm (:realm h))
                                          (< (js/Math.abs (- (:x h) x)) house-w)
                                          (< (js/Math.abs (- (house-bottom h) bottom)) house-h)))
                             houses))]
    (when room?
      (update st :houses (fnil conj []) {:realm realm :x x :y (- bottom house-h) :h house-h :resident nil}))))

(defn- draw-houses
  "Draws the Fluffy Houses of `realm` as wooden birdhouses with a round opening, lit
  warm inside when a Fluffy lives there. The rim of the opening is drawn later by
  `draw-house-rims` so the resident shows inside it."
  [^js ctx houses realm cam-x cam-y]
  (doseq [{:keys [x resident] :as h} houses
          :when (= realm (:realm h))
          :let [sx (js/Math.round (- x cam-x))
                top (js/Math.round (- (house-bottom h) house-h cam-y))
                [hx hy] (house-hole h)
                rect (fn [color rx ry rw rh]
                       (set! (.-fillStyle ctx) color)
                       (.fillRect ctx (+ sx rx) (+ top ry) rw rh))]]
    (rect "#c98b4f" 4 16 40 32)
    (doseq [i (range 4)]
      (rect "#b87a42" 4 (+ 22 (* i 7)) 40 1))
    (rect "#8a5a2b" 4 44 40 4)
    (set! (.-fillStyle ctx) "#3f7be0")
    (.beginPath ctx)
    (.moveTo ctx (- sx 1) (+ top 18))
    (.lineTo ctx (+ sx 24) top)
    (.lineTo ctx (+ sx 49) (+ top 18))
    (.closePath ctx)
    (.fill ctx)
    (rect "#2a5bb0" 0 16 48 3)
    (set! (.-fillStyle ctx) (if resident "#ffe9a8" "#2a1608"))
    (.beginPath ctx)
    (.arc ctx (- hx cam-x) (- hy cam-y) hole-r 0 (* 2 js/Math.PI))
    (.fill ctx)))

(defn- draw-house-rims
  "Draws the wooden rim and perch of each house opening over its resident."
  [^js ctx houses realm cam-x cam-y]
  (doseq [h houses
          :when (= realm (:realm h))
          :let [[hx hy] (house-hole h)
                sx (- hx cam-x)
                sy (- hy cam-y)]]
    (set! (.-strokeStyle ctx) "#6b4423")
    (set! (.-lineWidth ctx) 3)
    (.beginPath ctx)
    (.arc ctx sx sy (+ hole-r 1) 0 (* 2 js/Math.PI))
    (.stroke ctx)
    (set! (.-fillStyle ctx) "#6b4423")
    (.fillRect ctx (- sx 9) (+ sy hole-r 1) 18 3)))

(defn- draw-fluffies
  "Draws the Fluffies of `realm` as fuzzy blue balls with flapping wings. A Fluffy
  inside its house is drawn a little smaller with its wings folded so it fits."
  [^js ctx fluffies realm clock cam-x cam-y]
  (doseq [{:keys [x y phase out? red?] :as f} fluffies
          :when (= realm (:realm f))
          :let [home (and (:home f) (not out?))
                [wing body shine] (if red?
                                    ["#ffd6d2" "#e0453d" "#ff8a80"]
                                    ["#cfe6ff" "#3d7be8" "#6fa3ff"])
                flap (js/Math.sin (+ (/ clock 70) phase))
                disc (fn [cx cy r]
                       (.beginPath ctx)
                       (.arc ctx cx cy r 0 (* 2 js/Math.PI))
                       (.fill ctx))]]
    (.save ctx)
    (.translate ctx (js/Math.round (- x cam-x)) (js/Math.round (- y cam-y)))
    (when home (.scale ctx 0.85 0.85))
    (when-not home
      (set! (.-fillStyle ctx) wing)
      (doseq [side [-1 1]]
        (.beginPath ctx)
        (.ellipse ctx (* side 11) -3 7 (+ 2 (* 3 (js/Math.abs flap))) (* side -0.5) 0 (* 2 js/Math.PI))
        (.fill ctx)))
    (set! (.-fillStyle ctx) body)
    (doseq [a (range 0 (* 2 js/Math.PI) 0.5)]
      (disc (* fluffy-r (js/Math.cos a)) (* fluffy-r (js/Math.sin a)) 3.5))
    (disc 0 0 fluffy-r)
    (set! (.-fillStyle ctx) shine)
    (disc -2 -3 5)
    (set! (.-fillStyle ctx) "rgba(255,140,180,0.75)")
    (disc -6.5 3 2)
    (disc 6.5 3 2)
    (doseq [side [-1 1]]
      (set! (.-fillStyle ctx) "#ffffff")
      (disc (* side 3.8) -1 3.6)
      (set! (.-fillStyle ctx) "#10204a")
      (disc (* side 3.6) -0.4 2.6)
      (set! (.-fillStyle ctx) "#ffffff")
      (disc (+ (* side 3.6) 1) -1.6 1)
      (disc (- (* side 3.6) 0.8) 0.6 0.5))
    (set! (.-strokeStyle ctx) "#10204a")
    (set! (.-lineWidth ctx) 1)
    (.beginPath ctx)
    (.arc ctx 0 3.5 1.6 0.2 (- js/Math.PI 0.2))
    (.stroke ctx)
    (.restore ctx)))

(defn- draw-fluffy-glow
  "Draws a faint glow over the darkness so Fluffies in dark caves can be found, and an
  E key hint above a waiting Fluffy the player is close enough to call."
  [^js ctx fluffies realm {:keys [x y]} cam-x cam-y]
  (let [px (+ x (/ player-w 2))
        py (+ y (/ player-h 2))]
    (doseq [f fluffies
            :when (= realm (:realm f))
            :let [sx (- (:x f) cam-x) sy (- (:y f) cam-y)
                  g (.createRadialGradient ctx sx sy 2 sx sy 26)]]
      (.addColorStop g 0 (if (:red? f) "rgba(255,140,130,0.55)" "rgba(140,190,255,0.45)"))
      (.addColorStop g 1 (if (:red? f) "rgba(255,140,130,0)" "rgba(140,190,255,0)"))
      (set! (.-fillStyle ctx) g)
      (.fillRect ctx (- sx 26) (- sy 26) 52 52)
      (when (and (not (:following? f)) (not (:home f))
                 (<= (js/Math.hypot (- (:x f) px) (- (:y f) py)) fluffy-reach))
        (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
        (.fillRect ctx (- sx 8) (- sy 34) 16 16)
        (set! (.-fillStyle ctx) "#ffffff")
        (set! (.-font ctx) "bold 12px system-ui, sans-serif")
        (set! (.-textAlign ctx) "center")
        (.fillText ctx "E" sx (- sy 22))))))

(defn- draw-quest-mark
  "Draws a bobbing yellow exclamation mark over the house that offers a quest, with an
  E key hint beside it while the player is close enough to accept."
  [^js ctx {:keys [quest houses realm] :as st} clock cam-x cam-y]
  (when-let [h (and (= :offered (:stage quest)) (get houses (:giver quest)))]
    (when (= realm (:realm h))
      (let [sx (- (+ (:x h) (/ house-w 2)) cam-x)
            sy (- (house-bottom h) house-h cam-y 16 (* 3 (js/Math.sin (/ clock 250))))]
        (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
        (.fillRect ctx (- sx 9) (- sy 20) 18 22)
        (set! (.-fillStyle ctx) "#ffd54a")
        (set! (.-font ctx) "bold 20px system-ui, sans-serif")
        (set! (.-textAlign ctx) "center")
        (.fillText ctx "!" sx (- sy 2))
        (when (quest-giver-near? st)
          (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
          (.fillRect ctx (+ sx 12) (- sy 18) 16 16)
          (set! (.-fillStyle ctx) "#ffffff")
          (set! (.-font ctx) "bold 12px system-ui, sans-serif")
          (.fillText ctx "E" (+ sx 20) (- sy 6)))))))

(defui quest-panel
  "Shows the steps of the quest in progress, ticking off each one the player has done."
  [{:keys [quest]}]
  (when (= :active (:stage quest))
    (let [{:keys [found?]} quest
          step (fn [done? text]
                 ($ :li {:class (if done? "text-emerald-300 line-through" "text-amber-50")}
                    (if done? "✓ " "• ") text))]
      ($ :div {:class "w-64 rounded-md border border-amber-600/60 bg-black/60 px-3 py-2 text-sm select-none"}
         ($ :div {:class "mb-1 font-bold text-amber-300"} "! Quest: My red friend is lost")
         ($ :ul {:class "space-y-0.5"}
            (step found? "Find the red Fluffy (it shows on your map) and press E")
            (step false "Place a Fluffy House within 40 blocks of its friend's house"))))))

(defn- use-slot
  "Uses the item in bag slot `i`. A Healing Potion restores `potion-heal` hit points
  when health is not full, Rope hangs down from the tile under the mouse, a block
  is placed on the tile under the mouse, and the Dimension Amulet opens a portal when
  the player is on the surface."
  [{:keys [player bag enemies] :as st} tiles i]
  (let [{:keys [id name count]} (get bag i)
        spend (fn [st n] (if (< n count) (update-in st [:bag i :count] - n) (assoc-in st [:bag i] nil)))
        popup (fn [st text] (update st :popups conj {:text text :x (+ (:x player) (/ player-w 2))
                                                     :y (- (:y player) 10) :life 90}))]
    (cond
      (and (= :potion id) (< (:hp player) max-hp))
      (-> st
          (update-in [:player :hp] #(min max-hp (+ % potion-heal)))
          (spend 1)
          (update :popups conj {:text (str "+" potion-heal) :icon :heart :x (+ (:x player) (/ player-w 2))
                                :y (- (:y player) 10) :life 90}))

      (= :potion id) (popup st "Health is full")

      (= :rope id)
      (let [n (place-rope! tiles player count)]
        (if (pos? n)
          (spend st n)
          (popup st "Point at an open spot within reach")))

      (blocks id)
      (if (place-block! tiles player enemies (:tile (blocks id)))
        (spend st 1)
        (popup st "Point at an open spot within reach"))

      (= :house id)
      (if-let [placed (place-house st tiles)]
        (spend placed 1)
        (popup st "Point at open ground within reach, two blocks wide"))

      (and (= :amulet id) (not= :overworld (:realm st)))
      (popup st "The amulet is silent here; find the portal home")

      (= :amulet id)
      (if (on-surface? player (get-in st [:worlds :overworld :surface]))
        (open-portal st)
        (popup st "The amulet works only on the surface"))

      id (popup st (str name " can't be used yet"))
      :else st)))

(defui bag-bar [{:keys [bag]}]
  ($ :div {:class "flex gap-1.5 select-none"}
     (for [i (range bag-size)
           :let [{:keys [id name count]} (get bag i)]]
       ($ :div {:key i :title name
                :class "relative flex h-11 w-11 items-center justify-center rounded-md border border-white/20 bg-black/40"}
          ($ :span {:class "absolute left-1 top-0.5 text-[10px] font-semibold text-white/70"} (mod (inc i) 10))
          (when id ($ icons/icon {:id id :size 28}))
          (when (> count 1)
            ($ :span {:class "absolute bottom-0 right-1 font-mono text-[11px] font-bold text-white"} count))))))

(defui health-bar [{:keys [hp]}]
  ($ :div {:class "w-56 rounded-md bg-black/40 px-3 py-2 text-sm text-white select-none"}
     ($ :div {:class "mb-1 flex items-center justify-between"}
        ($ :span {:class "flex items-center gap-1 font-semibold"} ($ icons/icon {:id :heart :size 16}) "Health")
        ($ :span {:class "font-mono tabular-nums"} (js/Math.floor hp) " / " max-hp))
     ($ :div {:class "h-3 w-full overflow-hidden rounded-sm bg-red-950/80"}
        ($ :div {:class "h-full bg-red-500 transition-[width] duration-150"
                 :style {:width (str (* 100 (/ hp max-hp)) "%")}}))
     ($ :div {:class "mt-1 text-xs text-white/60"} "M menu")))

(defui how-to-play []
  ($ :ul {:class "space-y-1 text-white/90"}
     ($ :li "A and D move, Space jumps")
     ($ :li "The mouse aims the pickaxe")
     ($ :li "Shift or click digs or hits slimes")
     ($ :li "Keys 1 to 9 and 0 use the items in your bag; a Healing Potion gives +" potion-heal " health")
     ($ :li "The bag has " bag-size " slots, each holding a stack of one kind of item; when it is full, new finds are lost")
     ($ :li "Rope hangs down from where the mouse points, one rope per block; jump into it to grab it")
     ($ :li "Hit a rope with the pickaxe to take all of it back")
     ($ :li "Digging gives Dirt and Stone blocks (up to 100 each); use them from the bag to build")
     ($ :li "On a rope, Space or W climbs, S climbs down, A or D jumps off")
     ($ :li "Slimes come out in the caves at night; one in twenty drops a Dimension Amulet")
     ($ :li "Use the amulet on the surface to open a portal, then hit the portal with the pickaxe to enter the Slime Dimension, where darker slimes take twice the hits and hit twice as hard")
     ($ :li "In green ooze you move slowly; hold Space to swim up")
     ($ :li "Slimes in the Slime Dimension sometimes drop an Arena Key; far to the east a locked gate leads to the Lord of Goo's arena")
     ($ :li "Rare blue Fluffies hide in caves around the world; press E next to one to make it follow you, and E again to let it wait")
     ($ :li "Buy a Fluffy House at the shop and place it on the ground from the bag; a Fluffy following you moves into an empty house and pays you " house-reward " coins")
     ($ :li "Find the shop on the surface to sell your finds and buy potions and rope")))

(defn- ->b64 [^js arr]
  (let [b (js/Uint8Array. (.-buffer arr) (.-byteOffset arr) (.-byteLength arr))
        chunk 0x8000]
    (js/btoa (apply str (for [i (range 0 (.-length b) chunk)]
                          (.apply js/String.fromCharCode nil (.subarray b i (+ i chunk))))))))

(defn- <-b64 [kind s]
  (let [raw (js/atob s)
        b (js/Uint8Array. (.-length raw))]
    (dotimes [i (.-length raw)] (aset b i (.charCodeAt raw i)))
    (case kind
      :u8 b
      :i32 (js/Int32Array. (.-buffer b))
      :f32 (js/Float32Array. (.-buffer b)))))

(def ^:private array-kinds {:tiles :u8 :surface :i32 :levels :f32})

(defn- pack-world [world]
  (reduce (fn [w k] (cond-> w (get w k) (update k ->b64))) world (keys array-kinds)))

(defn- unpack-world [world]
  (reduce-kv (fn [w k kind] (cond-> w (get w k) (update k #(<-b64 kind %)))) world array-kinds))

(defn save-data
  "Writes everything needed to resume the game (both worlds, the player, items and
  the time of day) as an EDN string."
  [st]
  (pr-str (-> st
              (dissoc :keys :mouse :mouse-down? :particles :popups :clock-offset :paused?)
              (update :worlds #(update-vals % pack-world)))))

(defn- merge-inventory
  "Moves the items list of a game saved before the bag held everything into the
  ten-slot bag and the coin purse."
  [st]
  (let [st (update st :bag #(vec (take bag-size (concat % (repeat nil)))))]
    (if-let [items (:inventory st)]
      (-> st (dissoc :inventory) (stash items))
      st)))

(defn load-data
  "Replaces the game in `st` with the one in `data`, a string from `save-data`, keeping
  the keys and mouse buttons held right now and whether the game is paused."
  [st data]
  (let [saved (-> (reader/read-string data)
                  (update :worlds #(update-vals % unpack-world))
                  add-arena
                  merge-inventory
                  add-fluffies)
        raw-t (- (:clock st 0) (:clock-offset st 0))
        {:keys [x y]} (:player saved)]
    (merge (select-keys st [:keys :mouse :mouse-down? :paused?])
           saved
           {:clock-offset (- (:clock saved 0) raw-t)
            :particles []
            :popups [{:text "Game loaded" :x (+ x (/ player-w 2)) :y (- y 10) :life 120}]})))

(defn- save-label [{:keys [created-at]}]
  (.toLocaleString (js/Date. created-at) js/undefined
                   #js {:dateStyle "medium" :timeStyle "short"}))

(def ^:private index-key "sandbox-saves")

(defn- data-key [id] (str "sandbox-save:" id))

(defn- pipe-text
  "Runs `text` (a string or bytes) through a compression or decompression stream and
  resolves to the result as a Response."
  [text ^js stream]
  (js/Response. (.pipeThrough (.stream (js/Blob. #js [text])) stream)))

(defn- gzip-b64 [s]
  (-> (.arrayBuffer (pipe-text s (js/CompressionStream. "gzip")))
      (.then #(->b64 (js/Uint8Array. %)))))

(defn- gunzip-b64 [s]
  (.text (pipe-text (<-b64 :u8 s) (js/DecompressionStream. "gzip"))))

(defn- read-index []
  (try
    (->> (some-> (js/localStorage.getItem index-key) js/JSON.parse (js->clj :keywordize-keys true))
         (sort-by :created-at #(compare %2 %1))
         vec)
    (catch :default _ [])))

(defn- store-packed!
  "Stores `packed` save data in the browser under a new id and returns the new save's
  summary. Throws when the browser has no room for it."
  [name created-at packed]
  (let [id (str (js/Date.now))
        entry {:id id :name (some-> name .trim not-empty) :created-at created-at}]
    (try
      (js/localStorage.setItem (data-key id) packed)
      (js/localStorage.setItem index-key (js/JSON.stringify (clj->js (into [entry] (read-index)))))
      entry
      (catch :default e
        (js/localStorage.removeItem (data-key id))
        (throw e)))))

(defn- put-save
  "Compresses `data` and stores it in the browser with `name` and the current time."
  [name data]
  (-> (gzip-b64 data)
      (.then #(store-packed! name (.toISOString (js/Date.)) %))))

(defn- get-save [id]
  (if-let [packed (js/localStorage.getItem (data-key id))]
    (gunzip-b64 packed)
    (js/Promise.reject (js/Error. "missing"))))

(def ^:private file-format "sandbox-save")

(defn- download-save!
  "Gives the browser a JSON file of the save `entry` to keep."
  [{:keys [id name created-at]}]
  (let [json (js/JSON.stringify #js {:format file-format :version 1 :name name :createdAt created-at
                                     :data (js/localStorage.getItem (data-key id))})
        url (js/URL.createObjectURL (js/Blob. #js [json] #js {:type "application/json"}))
        a (js/document.createElement "a")
        stamp (subs (.replace created-at (js/RegExp. "[:T]" "g") "-") 0 16)
        base (-> (or name "save") (.replace (js/RegExp. "[^A-Za-z0-9äöåÄÖÅ_-]+" "g") "-"))]
    (set! (.-href a) url)
    (set! (.-download a) (str base "-" stamp ".json"))
    (.click a)
    (js/setTimeout #(js/URL.revokeObjectURL url) 1000)))

(defn- import-save
  "Reads a file made by `download-save!` and stores it as a new save. Resolves to the
  new save's summary, or rejects when the file is not a save or cannot be read."
  [^js file]
  (-> (.text file)
      (.then (fn [text]
               (let [^js o (js/JSON.parse text)]
                 (when-not (and (= file-format (.-format o)) (string? (.-data o)))
                   (throw (js/Error. "not a save")))
                           ;; decompressing proves the data is intact before it is stored
                 (-> (gunzip-b64 (.-data o))
                     (.then (fn [_] (store-packed! (.-name o)
                                                   (or (.-createdAt o) (.toISOString (js/Date.)))
                                                   (.-data o))))))))))

(defn- delete-save! [id]
  (js/localStorage.removeItem (data-key id))
  (js/localStorage.setItem index-key (js/JSON.stringify (clj->js (filterv #(not= id (:id %)) (read-index))))))

(def ^:private btn
  "rounded px-3 py-1 font-semibold disabled:cursor-not-allowed disabled:bg-white/10 disabled:text-white/40")

(defui saves-tab [{:keys [snapshot restore]}]
  (let [[saves set-saves!] (use-state read-index)
        [name set-name!] (use-state "")
        [selected set-selected!] (use-state nil)
        [confirm set-confirm!] (use-state nil)
        [busy? set-busy!] (use-state false)
        [message set-message!] (use-state nil)
        blur #(.blur (.-currentTarget %))
        save! (fn []
                (set-busy! true)
                (-> (put-save name (snapshot))
                    (.then (fn [s]
                             (set-name! "")
                             (set-selected! (:id s))
                             (set-message! "Game saved")
                             (set-saves! (read-index))))
                    (.catch #(set-message! "Saving failed: the browser is out of room. Delete an old save and try again."))
                    (.finally #(set-busy! false))))
        load! (fn []
                (set-busy! true)
                (-> (get-save selected)
                    (.then (fn [data]
                             (restore data)
                             (set-confirm! nil)
                             (set-message! "Game loaded")))
                    (.catch #(set-message! "Loading failed"))
                    (.finally #(set-busy! false))))
        file-ref (use-ref nil)
        import! (fn [^js e]
                  (when-let [file (aget (.. e -target -files) 0)]
                    (set-busy! true)
                    (-> (import-save file)
                        (.then (fn [s]
                                 (set-saves! (read-index))
                                 (set-selected! (:id s))
                                 (set-message! (str "Imported " (or (:name s) (save-label s))))))
                        (.catch #(set-message! "That file is not a game save, or the browser is out of room."))
                        (.finally (fn []
                                    (set! (.. e -target -value) "")
                                    (set-busy! false))))))
        delete! (fn []
                  (try (delete-save! selected) (catch :default _ nil))
                  (set-saves! (read-index))
                  (set-selected! nil)
                  (set-confirm! nil)
                  (set-message! "Save deleted"))
        chosen (first (filter #(= selected (:id %)) saves))]
    ($ :div
       ($ :div {:class "mb-3 flex gap-2"}
          ($ :input {:class "min-w-0 flex-1 rounded bg-white/10 px-2 py-1 text-white placeholder:text-white/40 outline-none focus:ring-2 focus:ring-emerald-500"
                     :placeholder "Name (optional)"
                     :value name
                     :max-length 60
                     :on-change #(set-name! (.. % -target -value))
                     :on-key-down #(when (and (= "Enter" (.-key %)) (not busy?)) (save!))})
          ($ :button {:class (str btn " bg-emerald-600 hover:bg-emerald-500")
                      :disabled busy?
                      :on-click #(do (blur %) (save!))}
             "Save game"))
       (when message
         ($ :div {:class "mb-2 text-xs text-emerald-300"} message))
       ($ :div {:class "mb-2 font-semibold text-white/80"} "Saved games")
       (if (empty? saves)
         ($ :div {:class "text-white/60"} "No saves yet.")
         ($ :ul {:class "max-h-64 space-y-1.5 overflow-y-auto p-1"}
            (for [{:keys [id] :as s} saves]
              ($ :li {:key id}
                 ($ :button {:class (str "flex w-full items-center gap-2 rounded px-2 py-1 text-left "
                                         (if (= id selected)
                                           "bg-emerald-400 text-slate-950 ring-2 ring-white"
                                           "bg-white/10 hover:bg-white/20"))
                             :on-click #(do (blur %) (set-selected! id) (set-confirm! nil))}
                    ($ :div {:class "flex-1"}
                       (if (:name s)
                         ($ :<>
                            ($ :div {:class "font-semibold"} (:name s))
                            ($ :div {:class (if (= id selected) "text-xs text-slate-800" "text-xs text-white/60")}
                               (save-label s)))
                         ($ :div (save-label s))))
                    (when (= id selected)
                      ($ :span {:class "text-base font-bold"} "✓")))))))
       (if (and confirm chosen)
         ($ :div {:class (str "mt-3 rounded p-3 " (if (= confirm :delete) "bg-red-900/60" "bg-amber-900/60"))}
            ($ :div {:class "mb-2"}
               (if (= confirm :delete) "Delete " "Load ")
               ($ :span {:class "font-semibold"} (or (:name chosen) (save-label chosen)))
               (if (= confirm :delete)
                 "? It cannot be brought back."
                 "? Progress since your last save will be lost."))
            ($ :div {:class "flex gap-2"}
               ($ :button {:class (str btn (if (= confirm :delete)
                                             " bg-red-600 hover:bg-red-500"
                                             " bg-emerald-600 hover:bg-emerald-500"))
                           :disabled busy?
                           :on-click #(do (blur %) (if (= confirm :delete) (delete!) (load!)))}
                  "Yes")
               ($ :button {:class (str btn " bg-white/15 hover:bg-white/25")
                           :on-click #(do (blur %) (set-confirm! nil))}
                  "No")))
         ($ :div {:class "mt-3 flex gap-2"}
            ($ :button {:class (str btn " bg-sky-600 hover:bg-sky-500")
                        :disabled (not chosen)
                        :on-click #(do (blur %) (set-message! nil) (set-confirm! :load))}
               "Load")
            ($ :button {:class (str btn " bg-white/15 hover:bg-white/25")
                        :disabled (not chosen)
                        :title "Save the selected game as a file on this computer"
                        :on-click #(do (blur %) (download-save! chosen))}
               "Download")
            ($ :button {:class (str btn " bg-red-700 hover:bg-red-600")
                        :disabled (not chosen)
                        :on-click #(do (blur %) (set-message! nil) (set-confirm! :delete))}
               "Delete")))
       ($ :div {:class "mt-3 border-t border-white/15 pt-3"}
          ($ :input {:ref file-ref :type "file" :accept ".json,application/json" :class "hidden"
                     :on-change import!})
          ($ :button {:class (str btn " bg-white/15 hover:bg-white/25")
                      :disabled busy?
                      :on-click #(do (blur %) (some-> @file-ref .click))}
             "Import file")
          ($ :span {:class "ml-2 text-xs text-white/50"} "Add a save from a downloaded file")))))

(defui menu-panel [{:keys [on-close snapshot restore]}]
  (let [[tab set-tab!] (use-state :help)
        tab-btn (fn [id label]
                  ($ :button {:class (str "rounded-t px-3 py-1.5 font-semibold "
                                          (if (= tab id)
                                            "bg-white/15 text-white"
                                            "text-white/60 hover:text-white"))
                              :on-click #(do (.blur (.-currentTarget %)) (set-tab! id))}
                     label))]
    ($ :div {:class "absolute inset-0 flex items-center justify-center bg-black/50 select-none"
             :on-click on-close}
       ($ :div {:class "max-h-[90vh] w-96 max-w-[92vw] overflow-y-auto rounded-lg bg-slate-900/95 p-5 text-sm text-white shadow-xl"
                :on-click #(.stopPropagation %)}
          ($ :div {:class "mb-3 flex items-baseline justify-between"}
             ($ :span {:class "text-lg font-bold"} "Mine Valley")
             ($ :span {:class "rounded bg-white/15 px-2 py-0.5 text-xs font-semibold uppercase tracking-wider text-white/80"}
                "Paused"))
          ($ :div {:class "mb-3 flex gap-1 border-b border-white/15"}
             (tab-btn :help "How to play")
             (tab-btn :saves "Saves"))
          (case tab
            :help ($ how-to-play)
            :saves ($ saves-tab {:snapshot snapshot :restore restore}))
          ($ :div {:class "mt-4 text-xs text-white/60"} "Press M again to close")))))

(defn- render-scale
  "Returns the whole number of canvas pixels drawn per CSS pixel. A fractional device
  pixel ratio such as 1.25 would put tile edges between screen pixels and blend them
  into light seams, so the browser stretches a whole-number canvas instead."
  []
  (max 1 (js/Math.round (or js/window.devicePixelRatio 1))))

(defn- typing? [^js e]
  (let [tag (some-> e .-target .-tagName)]
    (or (= tag "INPUT") (= tag "TEXTAREA") (some-> e .-target .-isContentEditable))))

(def game-keys #{"KeyA" "KeyD" "KeyW" "KeyS" "Space" "ShiftLeft" "ShiftRight"})

(def map-colors
  {grass [86 170 60] dirt [140 92 52] stone [118 118 128] trunk [110 72 40]
   leaves [52 140 60] rope [200 170 110]})

(defui mini-map
  "Draws the overworld one pixel per tile, refreshed twice a second, with every
  Fluffy in the overworld as a blue dot and the player as a white dot."
  [{:keys [state-ref]}]
  (let [canvas-ref (use-ref nil)]
    (use-effect
     (fn []
       (let [draw (fn []
                    (when-let [^js canvas @canvas-ref]
                      (when-let [state @state-ref]
                        (let [st @state
                              {:keys [tiles surface]} (get-in st [:worlds :overworld])
                              ctx (.getContext canvas "2d")
                              img (.createImageData ctx W H)
                              ^js px (.-data img)
                              dot (fn [x y r color]
                                    (set! (.-fillStyle ctx) color)
                                    (.beginPath ctx)
                                    (.arc ctx (/ x T) (/ y T) r 0 (* 2 js/Math.PI))
                                    (.fill ctx))]
                          (dotimes [y H]
                            (dotimes [x W]
                              (let [t (aget tiles (idx x y))
                                    [r g b] (or (map-colors t)
                                                (if (< y (aget surface x)) [120 190 240] [34 24 18]))
                                    i (* 4 (idx x y))]
                                (aset px i r)
                                (aset px (+ i 1) g)
                                (aset px (+ i 2) b)
                                (aset px (+ i 3) 255))))
                          (.putImageData ctx img 0 0)
                          (doseq [f (sort-by :red? (:fluffies st)) :when (= :overworld (:realm f))]
                            (dot (:x f) (:y f) (if (:red? f) 4.5 3.5) "#ffffff")
                            (dot (:x f) (:y f) (if (:red? f) 3.5 2.5) (if (:red? f) "#e0453d" "#3d7be8")))
                          (when (= :overworld (:realm st))
                            (let [{:keys [x y]} (:player st)]
                              (dot (+ x (/ player-w 2)) (+ y (/ player-h 2)) 3 "#111111")
                              (dot (+ x (/ player-w 2)) (+ y (/ player-h 2)) 2 "#ffffff")))))))
             id (js/setInterval draw 500)]
         (draw)
         #(js/clearInterval id)))
     [state-ref])
    ($ :canvas {:ref canvas-ref :width W :height H
                :class "block w-[480px] max-w-[45vw] rounded-md border-2 border-amber-800 [image-rendering:pixelated]"})))

(defui coin-panel [{:keys [coins on-menu map? map-shown? on-map]}]
  (let [[muted? set-muted!] (use-state (music/muted?))]
    ($ :div {:class "absolute top-4 right-4 flex items-stretch gap-2 select-none"}
       (when map?
         ($ :button {:class (str "flex items-center gap-1.5 rounded-md bg-black/50 px-3 py-2 text-lg font-bold hover:bg-black/70 "
                                 (if map-shown? "text-white" "text-white/40"))
                     :title (if map-shown? "Hide the map" "Show the map")
                     :on-click #(do (.blur (.-currentTarget %)) (on-map))}
            ($ icons/icon {:id :map :size 20})
            "Map"))
       ($ :button {:class (str "rounded-md bg-black/50 px-3 py-2 text-lg font-bold hover:bg-black/70 "
                               (if muted? "text-white/40 line-through" "text-white"))
                   :title (if muted? "Turn music on" "Turn music off")
                   :on-click #(do (.blur (.-currentTarget %)) (set-muted! (music/toggle-muted!)))}
          "♪ Music")
       ($ :button {:class "rounded-md bg-black/50 px-4 py-2 text-lg font-bold text-white hover:bg-black/70"
                   :on-click #(do (.blur (.-currentTarget %)) (on-menu))}
          "☰ Menu")
       ($ :div {:class "flex items-center gap-2 rounded-md bg-black/50 px-4 py-2 text-white"
                :title "Gold Coins"}
          ($ icons/icon {:id :coin :size 24})
          ($ :span {:class "font-mono text-xl font-bold tabular-nums text-amber-300"} coins)))))

(defui price [{:keys [amount]}]
  ($ :span {:class "inline-flex items-center gap-1 font-mono text-amber-300"}
     amount ($ icons/icon {:id :coin :size 14})))

(defui shop-panel [{:keys [coins bag on-sell on-buy map? on-buy-map]}]
  (let [sellable (filter #(and % (prices (:id %))) bag)
        money coins]
    ($ :div {:class "absolute bottom-6 left-1/2 w-80 -translate-x-1/2 rounded-lg border-2 border-amber-700 bg-amber-950/90 p-4 text-sm text-amber-50 shadow-xl select-none"}
       ($ :div {:class "mb-1 flex items-center gap-1.5 text-base font-bold"} ($ icons/icon {:id :coin :size 18}) "Shop")
       ($ :div {:class "mb-2 font-semibold text-amber-200"} "Buy")
       ($ :ul {:class "mb-1 space-y-1"}
          (for [[id cost] buy-prices
                :let [{:keys [name]} (item-def id)
                      room? (bag-slot bag id)
                      ok? (and room? (>= money cost))]]
            ($ :li {:key (str id) :class "flex items-center gap-2 rounded bg-white/10 px-2 py-1"}
               ($ icons/icon {:id id :size 20})
               ($ :span {:class "flex-1"} name (when (> (buy-amounts id) 1) (str " ×" (buy-amounts id))))
               ($ price {:amount cost})
               ($ :button {:class (if ok?
                                    "rounded bg-emerald-600 px-2 py-0.5 font-semibold hover:bg-emerald-500"
                                    "cursor-not-allowed rounded bg-white/10 px-2 py-0.5 font-semibold text-white/40")
                           :disabled (not ok?)
                           :on-click #(do (.blur (.-currentTarget %)) (on-buy id))}
                  "Buy")))
          (let [ok? (and (not map?) (>= money map-price))]
            ($ :li {:key "map" :class "flex items-center gap-2 rounded bg-white/10 px-2 py-1"}
               ($ icons/icon {:id :map :size 20})
               ($ :span {:class "flex-1"} "Map of Fluffies")
               (if map?
                 ($ :span {:class "px-2 py-0.5 font-semibold text-emerald-300"} "Owned")
                 ($ :<>
                    ($ price {:amount map-price})
                    ($ :button {:class (if ok?
                                         "rounded bg-emerald-600 px-2 py-0.5 font-semibold hover:bg-emerald-500"
                                         "cursor-not-allowed rounded bg-white/10 px-2 py-0.5 font-semibold text-white/40")
                                :disabled (not ok?)
                                :on-click #(do (.blur (.-currentTarget %)) (on-buy-map))}
                       "Buy"))))))
       ($ :div {:class "mb-3 flex items-center gap-1 text-xs text-amber-200/70"}
          "You have " ($ price {:amount money}))
       ($ :div {:class "mb-2 font-semibold text-amber-200"} "Sell")
       (if (empty? sellable)
         ($ :div {:class "text-amber-200/70"} "You have nothing to sell yet.")
         ($ :ul {:class "space-y-1"}
            (for [{:keys [id name count]} sellable]
              ($ :li {:key (str id) :class "flex items-center gap-2 rounded bg-white/10 px-2 py-1"}
                 ($ icons/icon {:id id :size 20})
                 ($ :span {:class "flex-1"} name " ×" count)
                 ($ price {:amount (prices id)})
                 ($ :button {:class "rounded bg-amber-600 px-2 py-0.5 font-semibold hover:bg-amber-500"
                             :on-click #(do (.blur (.-currentTarget %)) (on-sell id 1))}
                    "Sell")
                 (when (> count 1)
                   ($ :button {:class "rounded bg-amber-700 px-2 py-0.5 font-semibold hover:bg-amber-600"
                               :on-click #(do (.blur (.-currentTarget %)) (on-sell id count))}
                      "All")))))))))

(defui game []
  (let [canvas-ref (use-ref nil)
        state-ref (use-ref nil)
        [coins set-coins!] (use-state 0)
        [at-shop? set-at-shop!] (use-state false)
        [hp set-hp!] (use-state max-hp)
        [boss-hp set-boss-hp!] (use-state nil)
        [menu? set-menu!] (use-state false)
        [bag set-bag!] (use-state [])
        [map? set-map!] (use-state false)
        [map-shown? set-map-shown!] (use-state true)
        [quest set-quest!] (use-state nil)
        sync! (fn [st]
                (set-coins! (:coins st 0))
                (set-bag! (:bag st)))
        on-buy-map (fn []
                     (when-let [state @state-ref]
                       (sync! (swap! state buy-map))
                       (set-map-shown! true)))
        on-buy (fn [id]
                 (when-let [state @state-ref]
                   (sync! (swap! state buy id))))
        on-sell (fn [id n]
                  (when-let [state @state-ref]
                    (sync! (swap! state sell id n))))]
    (use-effect
     (fn []
       (some-> @state-ref (swap! assoc :paused? menu?))
       js/undefined)
     [menu?])
    (use-effect
     (fn []
       (let [^js canvas @canvas-ref
             ctx (.getContext canvas "2d")
             home (generate)
             state (atom {:player (spawn home) :keys #{} :particles []
                          :chests (:chests home) :coins 0 :popups []
                          :bag (vec (repeat bag-size nil))
                          :realm :overworld
                          :worlds {:overworld home :slime (generate-slime) :arena (generate-arena)}
                          :fluffies (place-fluffies (:tiles home) (:surface home))})
             _ (reset! state-ref state)
             here (fn [] (get-in @state [:worlds (:realm @state)]))
             home? (fn [] (= :overworld (:realm @state)))
             raf (atom nil)
             last-t (atom nil)
             flips (atom false)
             resize (fn []
                      (let [dpr (render-scale)]
                        (set! (.-width canvas) (* dpr js/window.innerWidth))
                        (set! (.-height canvas) (* dpr js/window.innerHeight))))
             on-down (fn [^js e]
                       (when-not (typing? e)
                         (case (.-code e)
                           "KeyM" (when-not (.-repeat e) (set-menu! not))
                           "KeyE" (when-not (or (.-repeat e) (:paused? @state))
                                    (swap! state #(or (accept-quest %) (toggle-fluffy %))))
                           ("Digit1" "Digit2" "Digit3" "Digit4" "Digit5" "Digit6" "Digit7" "Digit8" "Digit9" "Digit0")
                           (when-not (or (.-repeat e) (:paused? @state))
                                                             ;; Digit0 is the tenth slot
                             (swap! state use-slot (:tiles (here))
                                    (mod (dec (js/parseInt (subs (.-code e) 5))) bag-size)))
                           (when (game-keys (.-code e))
                             (.preventDefault e)
                             (swap! state update :keys conj (.-code e))))))
             on-up (fn [^js e] (swap! state update :keys disj (.-code e)))
             on-blur (fn [] (swap! state assoc :keys #{} :mouse-down? false))
             on-move (fn [^js e] (swap! state assoc :mouse [(.-clientX e) (.-clientY e)]))
             on-press (fn [^js e]
                        (when (zero? (.-button e))
                          (swap! state assoc :mouse-down? true
                                 :mouse [(.-clientX e) (.-clientY e)])))
             on-release (fn [] (swap! state assoc :mouse-down? false))
             frame (fn frame [t]
                     (if (:paused? @state)
                                   ;; the canvas keeps the last picture and the clock stands still
                       (do (when @last-t
                             (swap! state update :clock-offset (fnil - 0) (- t @last-t)))
                           (reset! last-t t)
                           (reset! raf (js/requestAnimationFrame frame)))
                       (let [home (get-in @state [:worlds :overworld])
                             shop (:shop home)
                             dt (if @last-t (min 2 (/ (- t @last-t) 16.667)) 1)
                             _ (reset! last-t t)
                             player (:player (swap! state update :player
                                                    #(step-player (:tiles (here)) % (:keys @state) dt)))
                             dpr (render-scale)
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
                             clock (+ t (:clock-offset @state 0))
                             _ (swap! state assoc :clock clock)
                             tod (time-of-day clock)
                             _ (music/want! (case (:realm @state)
                                              :overworld (if (:day? tod) :overworld-day :overworld-night)
                                              :slime :slime-hollow
                                              ;; the throwing part plays from the leap onto the ledge until the boss jumps down
                                              :arena (case (get-in @state [:boss :mode])
                                                       nil :slime-hollow
                                                       (:leap :perch) :boss-throw
                                                       :boss-ground)
                                              nil))
                             _ (swing! state (:tiles (here)) dt)
                                       ;; the Slime Dimension stays as dark as night; the arena is dimly lit
                             light (case (:realm @state) :overworld (:light tod) :arena 0.45 0.15)
                             world (here)
                             _ (when-let [levels (:levels world)]
                                 (swap! flips not)
                                 (flow-ooze! (:tiles world) levels @flips))
                             _ (step-enemies! state (:tiles world) (:surface world) light dt)
                             _ (step-boss! state (:tiles world) dt)
                             _ (swap! state #(-> % (step-fluffies clock dt) settle-fluffies offer-quest))
                             _ (set-quest! (:quest @state))
                             _ (when (and (= :slime (:realm @state)) (:gate-open? @state)
                                          (touching? player (assoc (:gate world) :w gate-w :h gate-h)))
                                 (swap! state enter-arena))
                             _ (set-boss-hp! (get-in @state [:boss :hp]))
                             _ (open-chests! state)
                             _ (set-at-shop! (and (home?) (near-shop? player shop)))
                             _ (swap! state
                                      (fn [st]
                                        (if (<= (get-in st [:player :hp] max-hp) 0)
                                          (-> st
                                              (cond-> (not= :overworld (:realm st)) (switch-realm :overworld))
                                              (dissoc :boss)
                                              (assoc :player (spawn home))
                                              (update :popups conj
                                                      (let [p (spawn home)]
                                                        {:text "You fainted!" :x (+ (:x p) (/ player-w 2))
                                                         :y (- (:y p) 10) :life 150})))
                                          (update-in st [:player :hp] #(min max-hp (+ (or % max-hp) (* regen dt)))))))
                             _ (set-coins! (:coins @state 0))
                             _ (set-bag! (:bag @state))
                             _ (set-map! (boolean (:map? @state)))
                             _ (set-hp! (/ (js/Math.floor (* 10 (get-in @state [:player :hp]))) 10))
                             {:keys [player particles chests popups enemies portal]}
                             (swap! state #(-> %
                                               (update :particles step-particles dt)
                                               (update :popups step-popups dt)))]
                         (.setTransform ctx dpr 0 0 dpr 0 0)
                         (set! (.-imageSmoothingEnabled ctx) false)
                         (.clearRect ctx 0 0 vw vh)
                         (when (home?) (draw-hills ctx vw vh cam-x cam-y))
                         (draw-tiles ctx (:tiles world) (:surface world) vw vh cam-x cam-y)
                         (when (home?) (draw-shop ctx shop vw cam-x cam-y))
                         (draw-chests ctx chests vw vh cam-x cam-y)
                         (draw-gate ctx (:gate world) (and (= :slime (:realm @state)) (:gate-open? @state))
                                    t cam-x cam-y)
                         (draw-portal ctx portal t cam-x cam-y)
                         (draw-target ctx (:tiles world) player cam-x cam-y)
                         (draw-enemies ctx enemies t vw vh cam-x cam-y)
                         (when-let [boss (:boss @state)] (draw-boss ctx boss t cam-x cam-y))
                         (draw-houses ctx (:houses @state) (:realm @state) cam-x cam-y)
                         (draw-fluffies ctx (:fluffies @state) (:realm @state) clock cam-x cam-y)
                         (draw-house-rims ctx (:houses @state) (:realm @state) cam-x cam-y)
                         (when (even? (js/Math.floor (/ (:hurt player 0) 4)))
                           (draw-player ctx player cam-x cam-y)
                           (draw-pickaxe ctx player cam-x cam-y))
                         (when-let [levels (:levels world)]
                           (draw-ooze ctx (:tiles world) levels vw vh cam-x cam-y t))
                         (draw-particles ctx particles cam-x cam-y)
                         (when (home?) (draw-depth ctx (:surface world) vw vh cam-y))
                         (draw-night ctx vw vh
                                     (- (+ (:x player) (/ player-w 2)) cam-x)
                                     (- (+ (:y player) (/ player-h 2)) cam-y)
                                     light)
                         (if (home?)
                           (draw-sky ctx vw vh cam-x t tod)
                           (do (set! (.-globalCompositeOperation ctx) "destination-over")
                               (set! (.-fillStyle ctx) "#0b120e")
                               (.fillRect ctx 0 0 vw vh)
                               (set! (.-globalCompositeOperation ctx) "source-over")))
                         (draw-eyes ctx enemies light cam-x cam-y)
                         (draw-fluffy-glow ctx (:fluffies @state) (:realm @state) player cam-x cam-y)
                         (draw-quest-mark ctx @state clock cam-x cam-y)
                         (draw-popups ctx popups cam-x cam-y)
                         (reset! raf (js/requestAnimationFrame frame)))))]
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
       ($ :canvas {:ref canvas-ref :class "block w-full h-full [image-rendering:pixelated]"})
       ($ :div {:class "absolute top-4 left-4 flex flex-col gap-2"}
          ($ bag-bar {:bag bag})
          ($ health-bar {:hp hp})
          ($ quest-panel {:quest quest}))
       ($ coin-panel {:coins coins :on-menu #(set-menu! not)
                      :map? map? :map-shown? map-shown? :on-map #(set-map-shown! not)})
       (when (and map? map-shown?)
         ($ :div {:class "absolute top-20 right-4 select-none"}
            ($ mini-map {:state-ref state-ref})))
       (when boss-hp
         ($ boss-bar {:hp boss-hp}))
       (when at-shop?
         ($ shop-panel {:coins coins :bag bag :on-sell on-sell :on-buy on-buy
                        :map? map? :on-buy-map on-buy-map}))
       (when menu?
         ($ menu-panel {:on-close #(set-menu! false)
                        :snapshot #(save-data @@state-ref)
                        :restore (fn [data]
                                   (sync! (swap! @state-ref load-data data)))})))))