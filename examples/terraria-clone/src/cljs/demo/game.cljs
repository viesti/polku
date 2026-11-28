(ns demo.game
  "Side-scrolling tile world with a player moved by A, D and Space who digs with a
  mouse-aimed pickaxe and collects items from chests in caves."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
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
(def torch 9)
(def asteroid 10)
(def snow 11)
(def ore 12)
(def hay 13)
(def rope-len 10)

(def blocks
  {:dirt {:id :dirt :name "Dirt" :icon "🟫" :tile dirt}
   :stone {:id :stone :name "Stone" :icon "🪨" :tile stone}})

(def stack-max {:dirt 100 :stone 100})

(def ore-item {:id :ore :name "Iron Ore" :icon "🪨"})
(def hay-item {:id :hay :name "Hay" :icon "🌾"})
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

(defn- solid? [t] (or (<= grass t stone) (= t slimestone) (= t asteroid) (= t snow) (= t ore)))

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
  {:torch 5 :rope 5 :star 15 :bar 10 :potion 10 :ruby 50 :mirror 40 :ore 8})

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

(defn- hay-spot?
  "Checks that hay can grow at column `x`: on grass with open air above it, and in the
  mountains only down in the valleys, not up on the slopes and peaks."
  [^js tiles x mountains?]
  (let [s (loop [y 0] (if (or (>= y (dec H)) (not= air (aget tiles (idx x y)))) y (recur (inc y))))]
    (when (and (pos? s) (= grass (aget tiles (idx x s)))
               (or (not mountains?) (> s 38)))
      (dec s))))

(defn- sow-hay!
  "Puts a tuft of hay on roughly one grass column in `every`."
  [^js tiles mountains? every]
  (doseq [x (range 2 (- W 2))
          :when (< (hash2 x (if mountains? 913 911)) (/ 1 every))]
    (when-let [y (hay-spot? tiles x mountains?)]
      (aset tiles (idx x y) hay))))

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
      (sow-hay! tiles false 14)
      {:tiles tiles :surface surface :shop shop :chests (place-chests tiles surface)})))

;; the mountains: peaks rise to `peak-top`, valleys sink to `valley-floor`, and the
;; ground climbs at most `max-climb` tiles a column so a jump gets over every slope
(def peak-top 10)
(def valley-floor 66)
(def max-climb 2)
(def edge-ground 40)
(def snow-line 30)

(defn- mountain-height
  "Returns the surface row of column `x` in the mountains before slopes are limited:
  sharp ridged peaks, flattening out to `edge-ground` at both ends of the world."
  [x]
  (let [ridge (fn [n] (- 1 (js/Math.abs (- (* 2 n) 1))))
        r (+ (* 0.75 (js/Math.pow (ridge (noise2 (/ x 38) 61.5)) 1.6))
             (* 0.25 (ridge (noise2 (/ x 11) 67.5))))
        raw (- valley-floor (* r (- valley-floor peak-top)))
        edge (min 1 (/ (min x (- W 1 x)) 30))]
    (js/Math.floor (+ (* edge raw) (* (- 1 edge) edge-ground)))))

(defn- iron-vein? [x y]
  (and (> (noise2 (/ x 70) 91.5) 0.45)
       (> (+ (noise2 (/ x 4) (+ 800 (/ y 3))) (* 0.0015 y)) 0.84)))

(defn generate-mountains
  "Builds the mountains: high rocky peaks with snowy tops, deep valleys with grass and
  trees, caves and chests, and veins of iron ore in parts of the rock."
  []
  (let [tiles (js/Uint8Array. (* W H))
        surface (js/Int32Array. W)]
    (doseq [x (range W)]
      (aset surface x (mountain-height x)))
    ;; limit the climb from either side
    (doseq [x (range 1 W)]
      (aset surface x (max (aget surface x) (- (aget surface (dec x)) max-climb))))
    (doseq [x (range (- W 2) -1 -1)]
      (aset surface x (max (aget surface x) (- (aget surface (inc x)) max-climb))))
    (doseq [x (range W)]
      (let [s (aget surface x)
            snowy? (< s snow-line)
            soil (if snowy? 1 (+ 2 (js/Math.floor (* 3 (noise2 (/ x 8) 23.5)))))]
        (doseq [y (range s H)]
          (aset tiles (idx x y)
                (cond
                  (and (> y (+ s 7)) (< y (- H 2)) (cave? (+ x 1000) y)) air
                  (and snowy? (< y (+ s 2))) snow
                  (= y s) grass
                  (< y (+ s soil)) dirt
                  (and (> y (+ s 5)) (iron-vein? x y)) ore
                  :else stone)))))
    (loop [x 8 last -10]
      (when (< x (- W 8))
        (let [s (aget surface x)
              plant? (and (> (- x last) 6)
                          (> s 44)
                          (< (hash2 x 142) 0.2)
                          (= grass (aget tiles (idx x s))))]
          (when plant? (plant-tree! tiles x s))
          (recur (inc x) (if plant? x last)))))
    (sow-hay! tiles true 5)
    {:tiles tiles :surface surface :chests (place-chests tiles surface)}))

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

;; outer space is a block of tiles in the middle of the world array that wraps around
;; at its edges, with empty margins so nothing collides at the array's own edges
(def space-x0 40)
(def space-y0 10)
(def space-w 400)
(def space-h 100)
(def space-asteroids 110)
(def ship-w 64)
(def ship-h 60)

(defn generate-space
  "Builds outer space: asteroids floating in empty space, with the spaceship parked on
  a flat-topped asteroid in the middle. Asteroids keep clear of the edges, so a player
  wrapping round never lands inside one."
  []
  (let [tiles (js/Uint8Array. (* W H))
        surface (.fill (js/Int32Array. W) H)
        blob!(fn [cx cy r]
                (doseq [x (range (- cx r 1) (+ cx r 2))
                        y (range (- cy r 1) (+ cy r 2))
                        :when (<= (js/Math.hypot (- x cx) (* 1.3 (- y cy)))
                                  (+ r -0.3 (* 0.8 (hash2 x y))))]
                  (aset tiles (idx x y) asteroid)))
        hx (+ space-x0 (quot space-w 2))
        hy (+ space-y0 (quot space-h 2) 8)]
    (doseq [x (range (- hx 6) (+ hx 7))]
      (aset tiles (idx x hy) asteroid))
    (blob! hx (+ hy 2) 4)
    (loop [placed [[hx hy]] tries 0]
      (when (and (< (count placed) space-asteroids) (< tries 5000))
        (let [r (+ 2 (rand-int 3))
              cx (+ space-x0 r 4 (rand-int (- space-w (* 2 r) 8)))
              cy (+ space-y0 r 4 (rand-int (- space-h (* 2 r) 8)))
              clear? (every? (fn [[px py]] (> (js/Math.hypot (- px cx) (- py cy)) 11)) placed)]
          (when clear? (blob! cx cy r))
          (recur (cond-> placed clear? (conj [cx cy])) (inc tries)))))
    {:tiles tiles :surface surface :chests [] :size [space-w space-h]
     :ship {:x (- (* T (+ hx 0.5)) (/ ship-w 2)) :y (- (* T hy) ship-h)}}))

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

;; share of normal gravity, and the fastest fall, in outer space
(def space-g 0.3)
(def space-fall 5)

(defn- step-free
  "Moves a player who is not on a rope. In ooze the player moves slowly, sinks gently
  and swims up with Space, hopping out when the head is above the surface. A player
  marked `:low-g?` falls under `space-g` of normal gravity. A player marked `:flung?`
  flies on without steering until landing."
  [tiles p keys dt]
  (let [flung? (:flung? p)
        dir (if flung? 0 (- (if (keys "KeyD") 1 0) (if (keys "KeyA") 1 0)))
        jump? (and (not flung?) (keys "Space"))
        cx (+ (:x p) (/ player-w 2))
        wet? (ooze-at? tiles cx (+ (:y p) (* 0.6 player-h)))
        head-out? (not (ooze-at? tiles cx (+ (:y p) 6)))
        vx (if flung?
             (:vx p)
             (+ (:vx p) (* (- (* dir run-v (:run p 1) (if wet? 0.5 1)) (:vx p)) (min 1 (* 0.25 dt)))))
        vy (cond
             (and wet? jump? head-out?) (* 0.8 jump-v)
             (and wet? jump?) (max -2.6 (- (:vy p) (* 0.5 dt)))
             (and jump? (:ground? p)) (* jump-v (:leap p 1))
             ;; letting go of Space early gives a shorter jump
             (and (not jump?) (not flung?) (< (:vy p) -3)) -3
             :else (:vy p))
        vy (cond
             wet? (min 2 (+ vy (* 0.15 dt)))
             (:low-g? p) (min space-fall (+ vy (* gravity space-g dt)))
             :else (min max-fall (+ vy (* gravity dt))))
        dx (* vx dt) dy (* vy dt)
        n (js/Math.ceil (/ (max (js/Math.abs dx) (js/Math.abs dy) 1) 6))
        p (assoc p :vx vx :vy vy
                 :facing (cond (pos? dir) 1 (neg? dir) -1 :else (:facing p))
                 :walked (+ (:walked p) (js/Math.abs dx)))]
    (let [p (reduce (fn [p _]
                      (as-> p p
                        (move-x tiles p (/ dx n))
                        (move-y tiles p (/ (* (:vy p) dt) n))))
                    p
                    (range n))]
      ;; standing on something, or in ooze, ends the flight
      (if (and flung? (or wet? (box-hits? tiles (:x p) (+ (:y p) 1) player-w player-h)))
        (assoc p :flung? false :vx 0)
        p))))

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
   slimestone ["#56705f" "#4e6757"]
   torch ["#ffb347" "#8a5a2b"]
   asteroid ["#6e6478" "#635a6c"]
   snow ["#eef3f8" "#e3eaf1"]
   ore ["#75706f" "#6b6665"]
   hay ["#e0c060" "#b89a40"]})

(defn- hand [{:keys [x y]}]
  [(+ x (/ player-w 2)) (+ y 22)])

(defn dig-targets
  "Returns the first solid, rope or torch tile within `reach` on the line from the player's
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
              (if (let [t (tile-at tiles tx ty)] (or (solid? t) (= rope t) (= torch t) (= hay t)))
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

(declare strike-enemies stash portal-aimed? teleport gate-aimed? knock-gate strike-boss
         strike-drone strike-alien strike-robber strike-goat calm-goat here-ship tally box-aimed? fly-ship aimed-mirror take-mirror)

(defn- swing!
  "Advances the pickaxe swing. A new swing strikes the enemies in front of the pickaxe,
  or breaks the targeted tile when it reaches none. Hitting a rope takes the whole rope
  back into the bag, and hitting a torch takes the torch back. Returns true when the inventory changed."
  [state ^js tiles dt]
  (let [{:keys [player keys mouse-down?]} @state
        left (max 0 (- (:swing player 0) dt))]
    (cond
      (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight"))
           (portal-aimed? player (:portal @state)))
      (do (swap! state teleport) true)

      (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight"))
           (when-let [ship (here-ship @state)] (box-aimed? player ship ship-w ship-h)))
      (do (swap! state fly-ship) true)

      (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight"))
           (aimed-mirror @state))
      (do (swap! state #(-> % (take-mirror (aimed-mirror %)) (assoc-in [:player :swing] swing-frames)))
          true)

      (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight"))
           (= :slime (:realm @state)) (not (:gate-open? @state))
           (gate-aimed? player (get-in @state [:worlds :slime :gate])))
      (do (swap! state knock-gate) true)

      (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight")))
      (let [struck (or (strike-boss state) (strike-drone state) (strike-alien state) (strike-robber state) (strike-goat state)
                         (strike-enemies state))
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
            torches (count (filter (fn [[tx ty]] (= torch (aget tiles (idx tx ty)))) hits))
            dug (into [] (keep (fn [[tx ty]]
                                 (let [t (aget tiles (idx tx ty))]
                                   (cond (or (= t grass) (= t dirt) (= t snow)) :dirt
                                         (or (= t stone) (= t slimestone) (= t asteroid)) :stone
                                         (= t ore) :ore
                                         (= t hay) :hay))))
                      hits)]
        (doseq [[tx ty] hits]
          (aset tiles (idx tx ty) air))
        (swap! state #(cond-> (-> %
                                  (stash (map (fn [[id n]] (assoc (dissoc (or (blocks id) ({:ore ore-item :hay hay-item} id)) :tile) :count n))
                                              (frequencies dug)))
                                  (tally :dug (count (remove #{:hay} dug)))
                                  (tally :ore (count (filter #{:ore} dug)))
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
                                      {:text (str "+" pieces " Rope") :icon :rope :x (* T (+ tx 0.5)) :y (* T ty) :life 90})))
                        (pos? torches)
                        (stash [(assoc (select-keys (first (filter (fn [i] (= :torch (:id i))) loot))
                                                    [:id :name :icon])
                                       :count torches)])))
        (or (= struck :loot) (boolean (seq ropes)) (pos? torches)))
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

(defn- draw-peaks
  "Draws two ranges of far-off mountains with snowy tops behind the mountains realm,
  moving slower than the ground so they look distant."
  [^js ctx vw vh cam-x cam-y]
  (doseq [[par rock snow amp base k] [[0.12 "#a7b4c6" "#eef2f7" 260 0.36 0] [0.3 "#8494a9" "#e3e9f0" 200 0.44 1]]]
    (let [step 12
          height (fn [sx]
                   (let [wx (+ sx (* cam-x par))
                         n (noise2 (/ wx 260) (+ 40 (* 10 k)))
                         ridge (- 1 (js/Math.abs (- (* 2 n) 1)))]
                     (- (* H T base) (* amp ridge ridge) cam-y)))
          xs (range 0 (+ vw step) step)
          ys (mapv height xs)]
      (set! (.-fillStyle ctx) rock)
      (.beginPath ctx)
      (.moveTo ctx 0 vh)
      (doseq [[sx hy] (map vector xs ys)] (.lineTo ctx sx hy))
      (.lineTo ctx vw vh)
      (.closePath ctx)
      (.fill ctx)
      ;; snow on whatever rises above the snow line of this range
      (let [line (- (* H T base) (* amp 0.55) cam-y)]
        (.save ctx)
        (.beginPath ctx)
        (.rect ctx 0 -10000 vw (+ 10000 line))
        (.clip ctx)
        (set! (.-fillStyle ctx) snow)
        (.beginPath ctx)
        (.moveTo ctx 0 vh)
        (doseq [[sx hy] (map vector xs ys)] (.lineTo ctx sx hy))
        (.lineTo ctx vw vh)
        (.closePath ctx)
        (.fill ctx)
        (.restore ctx)))))

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

          ;; the flame is drawn each frame by `draw-flames`
          (= t torch)
          (do (when (> ty (aget surface tx))
                (cave-bg))
              (set! (.-fillStyle ctx) "#8a5a2b")
              (.fillRect ctx (+ sx 7) (+ sy 7) 3 9)
              (set! (.-fillStyle ctx) "#5a3418")
              (.fillRect ctx (+ sx 7) (+ sy 7) 3 2))

          (= t hay)
          (doseq [[bx bh lean] [[2 9 -1] [5 12 0] [8 10 1] [11 13 0] [13 8 1]]]
            (set! (.-fillStyle ctx) (get-in palette [hay (mod bx 2)]))
            (.fillRect ctx (+ sx bx) (+ sy (- T bh)) 2 bh)
            (set! (.-fillStyle ctx) "#f2dc8a")
            (.fillRect ctx (+ sx bx lean) (+ sy (- T bh) -1) 2 3))

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
                (.fillRect ctx (+ sx 2) (+ sy 3) 6 3))
              (= t snow)
              (when (< (hash2 tx (+ ty 500)) 0.3)
                (set! (.-fillStyle ctx) "#ffffff")
                (.fillRect ctx (+ sx 3) (+ sy 3) 5 2))
              (= t ore)
              (do (set! (.-fillStyle ctx) "#b5652e")
                  (.fillRect ctx (+ sx 2) (+ sy 3) 4 3)
                  (.fillRect ctx (+ sx 9) (+ sy 8) 4 4)
                  (set! (.-fillStyle ctx) "#d98a4a")
                  (.fillRect ctx (+ sx 2) (+ sy 3) 2 1)
                  (.fillRect ctx (+ sx 9) (+ sy 8) 2 1)
                  (set! (.-fillStyle ctx) "#8a4520")
                  (.fillRect ctx (+ sx 4) (+ sy 12) 3 2))
              (= t asteroid)
              (when (< (hash2 tx (+ ty 500)) 0.4)
                (set! (.-fillStyle ctx) "#4a4252")
                (.fillRect ctx (+ sx 4) (+ sy 5) 4 4)
                (set! (.-fillStyle ctx) "#8c8296")
                (.fillRect ctx (+ sx 4) (+ sy 4) 4 1))))
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

(def torch-reach (* 6 T))

(defn- near-torch?
  "Checks that a torch is within `torch-reach` of the centre of `player`."
  [^js tiles {:keys [x y]}]
  (let [cx (+ x (/ player-w 2))
        cy (+ y (/ player-h 2))
        r (js/Math.ceil (/ torch-reach T))
        ptx (js/Math.floor (/ cx T))
        pty (js/Math.floor (/ cy T))]
    (loop [ty (- pty r)]
      (when (<= ty (+ pty r))
        (or (loop [tx (- ptx r)]
              (when (<= tx (+ ptx r))
                (if (and (= torch (tile-at tiles tx ty))
                         (<= (js/Math.hypot (- (* T (+ tx 0.5)) cx) (- (* T (+ ty 0.5)) cy)) torch-reach))
                  true
                  (recur (inc tx)))))
            (recur (inc ty)))))))

;; drawing helpers that keep busy scenes cheap: things off screen are skipped, and
;; Fluffies and glows are drawn once into small canvases that are then stamped

(defn- off-screen?
  "Checks that the point `sx`, `sy` lies more than `m` pixels outside the canvas. The
  canvas is measured in its own pixels, which are never fewer than CSS pixels, so
  nothing on screen is ever skipped."
  [^js ctx sx sy m]
  (let [^js c (.-canvas ctx)]
    (or (< sx (- m)) (< sy (- m)) (> sx (+ (.-width c) m)) (> sy (+ (.-height c) m)))))

(defn- device-scale
  "Returns the whole number of canvas pixels per drawing unit on `ctx`."
  [^js ctx]
  (max 1 (js/Math.ceil (.-a (.getTransform ctx)))))

(def ^:private sprite-cache (js/Map.))

(defn- cached-sprite
  "Returns a canvas of `w` by `h` drawing units at scale `scale` holding what `paint!`
  draws with its origin at `ox`, `oy`, painting it only the first time `key` is asked for."
  [key scale w h ox oy paint!]
  (let [k (str key "|" scale)]
    (or (.get sprite-cache k)
        (let [cv (js/document.createElement "canvas")
              _ (set! (.-width cv) (* w scale))
              _ (set! (.-height cv) (* h scale))
              c (.getContext cv "2d")]
          (when (> (.-size sprite-cache) 800) (.clear sprite-cache))
          (.setTransform c scale 0 0 scale (* ox scale) (* oy scale))
          (paint! c)
          (.set sprite-cache k cv)
          cv))))

(defn- glow-sprite
  "Returns a cached canvas of a round glow of radius `r` fading from `inner` to `outer`."
  [scale r inner outer]
  (cached-sprite (str "glow|" r "|" inner "|" outer) scale (* 2 r) (* 2 r) r r
                 (fn [^js c]
                   (let [g (.createRadialGradient c 0 0 2 0 0 r)]
                     (.addColorStop g 0 inner)
                     (.addColorStop g 1 outer)
                     (set! (.-fillStyle c) g)
                     (.fillRect c (- r) (- r) (* 2 r) (* 2 r))))))

(defn- draw-flames
  "Draws a flickering flame on each visible torch and a warm glow around it that
  brightens what is already drawn, more strongly in the dark."
  [^js ctx ^js tiles vw vh cam-x cam-y t light]
  (let [x0 (max 0 (js/Math.floor (/ (- cam-x torch-reach) T)))
        x1 (min (dec W) (js/Math.floor (/ (+ cam-x vw torch-reach) T)))
        y0 (max 0 (js/Math.floor (/ (- cam-y torch-reach) T)))
        y1 (min (dec H) (js/Math.floor (/ (+ cam-y vh torch-reach) T)))
        glow (+ 0.18 (* 0.32 (- 1 light)))
        scale (device-scale ctx)]
    (loop [ty y0]
      (when (<= ty y1)
        (loop [tx x0]
          (when (<= tx x1)
            (when (= torch (aget tiles (idx tx ty)))
              (let [sx (js/Math.round (- (* tx T) cam-x))
                    sy (js/Math.round (- (* ty T) cam-y))
                    fx (+ sx 8.5)
                    fy (+ sy 4)
                    flick (* 0.5 (+ 1 (js/Math.sin (+ (/ t 90) (* 3 tx) ty))))
                    r (+ torch-reach (* 6 flick))]
                ;; one glow image, faded by the darkness and scaled by the flicker
                (set! (.-globalCompositeOperation ctx) "lighter")
                (set! (.-globalAlpha ctx) glow)
                (.drawImage ctx (glow-sprite scale (+ torch-reach 6) "rgba(255,170,70,1)" "rgba(255,170,70,0)")
                            (- fx r) (- fy r) (* 2 r) (* 2 r))
                (set! (.-globalAlpha ctx) 1)
                (set! (.-globalCompositeOperation ctx) "source-over")
                (set! (.-fillStyle ctx) "#ff7a1a")
                (.fillRect ctx (+ sx 6) (- sy (js/Math.round (* 2 flick)) -2) 5 (+ 5 (js/Math.round (* 2 flick))))
                (set! (.-fillStyle ctx) "#ffd54a")
                (.fillRect ctx (+ sx 7) (+ sy 3) 3 4)
                (set! (.-fillStyle ctx) "#fff6c0")
                (.fillRect ctx (+ sx 8) (+ sy 5) 1 2)))
            (recur (inc tx))))
        (recur (inc ty))))))

(defn- draw-depth [^js ctx row vw vh cam-y]
  (let [top (- (* T (+ 14 row)) cam-y)
        bot (- (* T H) cam-y)
        g (.createLinearGradient ctx 0 top 0 bot)]
    (.addColorStop g 0 "rgba(0,0,0,0)")
    (.addColorStop g 1 "rgba(0,0,0,0.65)")
    (set! (.-fillStyle ctx) g)
    (.fillRect ctx 0 (max 0 top) vw vh)))

(defn- draw-spacesuit
  "Draws the player in a white spacesuit with an oxygen tank on the back and a round
  glass helmet that shows the face."
  [^js ctx sx sy facing swing rect]
  (rect "#8f99a3" -3 14 4 13)
  (rect "#e67e22" -3 13 4 2)
  (rect "#e8ecef" 3 28 5 10)
  (rect "#e8ecef" 10 28 5 10)
  (rect "#7d8791" (+ 3 swing) 34 5 4)
  (rect "#7d8791" (- 10 swing) 34 5 4)
  (rect "#e8ecef" 2 15 14 14)
  (rect "#e67e22" 2 23 14 2)
  (rect "#9aa3ad" 9 17 4 3)
  (rect "#d5dbe0" (+ 7 (- swing)) 17 4 9)
  (rect "#7d8791" (+ 7 (- swing)) 26 4 3)
  (rect "#f1c27d" 4 4 11 10)
  (rect "#6b3e1f" 4 3 11 3)
  (rect "#1d1d1d" 12 8 2 3)
  (rect "#9aa3ad" 2 14 15 2)
  (let [cx (if (pos? facing) (+ sx 9.5) (- (+ sx player-w) 9.5))
        cy (+ sy 8)]
    (.beginPath ctx)
    (.arc ctx cx cy 8.5 0 (* 2 js/Math.PI))
    (set! (.-fillStyle ctx) "rgba(160,215,255,0.28)")
    (.fill ctx)
    (set! (.-strokeStyle ctx) "#dfe6ea")
    (set! (.-lineWidth ctx) 2)
    (.stroke ctx)
    (.beginPath ctx)
    (.arc ctx cx cy 5.5 (* 1.1 js/Math.PI) (* 1.45 js/Math.PI))
    (set! (.-strokeStyle ctx) "rgba(255,255,255,0.8)")
    (set! (.-lineWidth ctx) 1.5)
    (.stroke ctx)))

(defn- draw-player
  "Draws the player, in the spacesuit when `suit?` is true."
  [^js ctx {:keys [x y facing walked ground? vx]} cam-x cam-y suit?]
  (let [sx (js/Math.round (- x cam-x))
        sy (js/Math.round (- y cam-y))
        moving? (and ground? (> (js/Math.abs vx) 0.4))
        swing (if moving? (js/Math.round (* 3 (js/Math.sin (/ walked 6)))) 0)
        rect (fn [color rx ry rw rh]
               (set! (.-fillStyle ctx) color)
               (.fillRect ctx (if (pos? facing) (+ sx rx) (- (+ sx player-w) rx rw)) (+ sy ry) rw rh))]
    (if suit?
      (draw-spacesuit ctx sx sy facing swing rect)
      (do (rect "#3b2f8f" 3 28 5 10)
          (rect "#3b2f8f" 10 28 5 10)
          (rect "#2a2266" (+ 3 swing) 34 5 4)
          (rect "#2a2266" (- 10 swing) 34 5 4)
          (rect "#c0392b" 2 15 14 14)
          (rect "#f1c27d" (+ 7 (- swing)) 17 4 11)
          (rect "#f1c27d" 3 3 13 13)
          (rect "#6b3e1f" 2 0 15 5)
          (rect "#6b3e1f" 2 0 4 10)
          (rect "#1d1d1d" 12 8 2 3)))))

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

(defn- tally
  "Counts `n` more of `k` in today's log of events, which the newspaper reports the
  next day."
  [st k n]
  (if (pos? n) (update-in st [:today k] (fnil + 0) n) st))

(defn- tally-found
  "Records the items in `items` as found today."
  [st items]
  (reduce (fn [st {:keys [id count]}] (update-in st [:today :found id] (fnil + 0) (or count 1)))
          st items))

(defn- add-coins
  "Adds `n` coins, or takes them away when `n` is negative, and logs them as earned or
  spent today."
  [st n]
  (-> (update st :coins (fnil + 0) n)
      (tally (if (pos? n) :earned :spent) (js/Math.abs n))))

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
                               (tally :chests 1)
                               (tally-found (:items c))
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

(defn- draw-shop
  "Draws the shop, with an E key hint over its roof while the player is close enough to
  open it."
  [^js ctx {:keys [tx ty]} vw cam-x cam-y near?]
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
      (.fillText ctx "SHOP" (+ sx 62) (+ top 31))
      (when near?
        (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
        (.fillRect ctx (- (+ sx (/ w 2)) 8) (- top 38) 16 16)
        (set! (.-fillStyle ctx) "#ffffff")
        (set! (.-font ctx) "bold 12px system-ui, sans-serif")
        (.fillText ctx "E" (+ sx (/ w 2)) (- top 26))))))

(defn- near-shop? [{:keys [x y]} {:keys [tx ty]}]
  (let [sx (* tx T) base (* ty T)]
    (and (< (- sx 8) (+ x player-w)) (< x (+ sx (* shop-w T) 8))
         (< (- base (* shop-h T)) (+ y player-h)) (< y (+ base 8)))))

(defn- by-shop?
  "Returns true when the player stands at the shop in the overworld."
  [st]
  (and (= :overworld (:realm st))
       (near-shop? (:player st) (get-in st [:worlds :overworld :shop]))))

(defn- toggle-shop
  "Opens or closes the shop panel when the player stands at the shop, or returns nil."
  [st]
  (when (by-shop? st)
    (update st :shop-open? not)))

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
                             (tally :slimes (count dead))
                             (tally :amulets (count amulets))
                             (tally :keys (count arena-keys))
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
        (update-in [:today :visited] (fnil conj #{}) to)
        (assoc :realm to :particles [] :popups [] :rockets [] :spawn-in 0))))

(defn- ground-y
  "Returns the y at which a player standing over tile columns `x0` to `x1` lands on
  the highest solid tile."
  [tiles x0 x1]
  (let [ty (or (some (fn [ty] (when (some #(solid? (tile-at tiles % ty)) (range x0 (inc x1))) ty))
                     (range 0 H))
               H)]
    (- (* T ty) player-h 0.5)))

(defn cross-edge
  "Walking on past the left or right end of the overworld leads into the mountains,
  and past either end of the mountains back to the overworld, as if the two were one
  ring: going left comes in at the right end of the other realm and going right at
  its left end."
  [{:keys [realm player keys] :as st}]
  (let [{:keys [x]} player
        left? (and (keys "KeyA") (<= x 0.5))
        right? (and (keys "KeyD") (>= (+ x player-w) (- (* W T) 0.5)))]
    (if (and (#{:overworld :mountains} realm) (or left? right?))
      (let [to (if (= realm :overworld) :mountains :overworld)
            st (cond-> st
                 (not (get-in st [:worlds :mountains])) (assoc-in [:worlds :mountains] (generate-mountains)))
            st (switch-realm st to)
            tiles (get-in st [:worlds to :tiles])
            nx (if left? (- (* W T) T player-w) T)
            tx0 (js/Math.floor (/ nx T))
            tx1 (js/Math.floor (/ (+ nx player-w -0.01) T))]
        (update st :player assoc :x nx :y (ground-y tiles tx0 tx1) :vy 0 :climbing? false))
      st)))

(defn- sow-old-hay
  "Sows hay once in worlds made before hay grew in them."
  [st]
  (if (:hay-sown? st)
    st
    (do (some-> (get-in st [:worlds :overworld :tiles]) (sow-hay! false 14))
        (some-> (get-in st [:worlds :mountains :tiles]) (sow-hay! true 5))
        (assoc st :hay-sown? true))))

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
  {:potion 10 :rope 5 :torch 5 :house 50 :paint-red 10 :paint-yellow 10 :paint-blue 10})

(def buy-amounts
  {:potion 1 :rope 5 :torch 1 :house 1 :paint-red 1 :paint-yellow 1 :paint-blue 1})

(def house-item {:id :house :name "Fluffy House"})

;; things the shop sells for stars once the player has collected one
(def star-items
  {:mirror {:id :mirror :name "Magic Mirror"}
   :rocket {:id :rocket :name "Firework Rocket"}
   :tamer {:id :tamer :name "Alien Tamer"}})

(def star-prices {:mirror 15 :rocket 5 :tamer 5})

(def space-map-price 30)

;; hues are degrees on the painter's red-yellow-blue wheel, where blue and yellow mix to green
(def paints
  {:paint-red {:id :paint-red :name "Red Paint" :hue 0}
   :paint-yellow {:id :paint-yellow :name "Yellow Paint" :hue 120}
   :paint-blue {:id :paint-blue :name "Blue Paint" :hue 240}})

(defn- item-def
  "Returns the id and name of the item `id` that the shop sells."
  [id]
  (select-keys (or (first (filter #(= id (:id %)) loot))
                   (when (= id :house) house-item)
                   (paints id)
                   (star-items id))
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

(defn star-count-in
  "Returns how many stars the bag holds."
  [bag]
  (reduce + 0 (keep #(when (= :star (:id %)) (:count %)) bag)))

(defn- pay-stars
  "Takes `n` stars out of the bag, from as many slots as it takes."
  [st n]
  (loop [st st n n]
    (let [i (first (keep-indexed (fn [i s] (when (= :star (:id s)) i)) (:bag st)))]
      (if (or (<= n 0) (nil? i))
        st
        (let [have (get-in st [:bag i :count])]
          (recur (if (<= have n)
                   (assoc-in st [:bag i] nil)
                   (update-in st [:bag i :count] - n))
                 (- n have)))))))

(defn- buy-for-stars
  "Buys one star item `id` into the bag. Leaves the state unchanged when the player has
  too few stars or the bag has no slot for it."
  [st id]
  (let [cost (star-prices id)]
    (if (and cost (>= (star-count-in (:bag st)) cost))
      (let [paid (pay-stars st cost)]
        (if (bag-slot (:bag paid) id)
          (stash paid [(assoc (item-def id) :count 1)])
          st))
      st)))

(defn- buy-space-map
  "Buys the map of outer space for `space-map-price` stars, once."
  [st]
  (if (and (not (:space-map? st)) (>= (star-count-in (:bag st)) space-map-price))
    (-> st (pay-stars space-map-price) (assoc :space-map? true))
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
                               (assoc :goo-beaten? true)
                               (tally :goo 1)
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

(def drone-w 28)
(def drone-h 14)
(def drone-count 3)
(def drone-hp 3)
(def drone-reward 20)
;; how far above the ground drones cruise, in tiles
(def drone-alt 14)

(defn- ground-row
  "Returns the row of the highest solid tile in column `tx`."
  [^js tiles tx]
  (let [tx (max 0 (min (dec W) tx))]
    (loop [y 0]
      (if (or (>= y (dec H)) (solid? (aget tiles (idx tx y)))) y (recur (inc y))))))

(defn- cruise-y [^js tiles x]
  (* T (- (ground-row tiles (js/Math.floor (/ x T))) drone-alt)))

(defn- new-drone [tiles px]
  (let [x (max (* 2 T) (min (* T (- W 3)) (+ px (* (if (< (js/Math.random) 0.5) -1 1) (+ 500 (* 300 (js/Math.random)))))))]
    {:x x :y (cruise-y tiles x) :vx (if (< (js/Math.random) 0.5) -1.2 1.2)
     :mode :high :timer (+ 600 (* 1200 (js/Math.random))) :hp drone-hp :flash 0}))

(defn- step-drone
  "Moves drone `d` one frame. A cruising drone patrols the sky near the player at
  `px` and, when its timer runs out and `busy?` is false, dives to the ground near the
  player, sits there for a while and climbs back up."
  [tiles {:keys [x y vx mode timer] :as d} px dt busy?]
  (let [d (assoc d :flash (max 0 (- (:flash d 0) dt)) :timer (- timer dt))
        toward (fn [d tx ty speed]
                 (let [dx (- tx (:x d)) dy (- ty (:y d)) dist (js/Math.hypot dx dy)
                       step (* speed dt)]
                   (if (<= dist step)
                     [(assoc d :x tx :y ty) true]
                     [(assoc d :x (+ (:x d) (* step (/ dx dist))) :y (+ (:y d) (* step (/ dy dist)))) false])))]
    (case mode
      :high
      (let [vx (cond (or (> (- x px) 700) (> x (* T (- W 3)))) (- (js/Math.abs vx))
                     (or (< (- x px) -700) (< x (* 2 T))) (js/Math.abs vx)
                     :else vx)
            x (if (> (js/Math.abs (- x px)) 1400) (- px (* 600 (js/Math.sign vx))) (+ x (* vx dt)))
            ty (cruise-y tiles x)
            d (assoc d :vx vx :x x :y (+ y (* (min 1 (* 0.05 dt)) (- ty y))))]
        (cond
          (pos? (:timer d)) d
          busy? (assoc d :timer 300)
          :else (let [tx (max (* 2 T) (min (* T (- W 3)) (+ px (- (* 640 (js/Math.random)) 320))))
                      col (js/Math.floor (/ (+ tx (/ drone-w 2)) T))]
                  (assoc d :mode :dive :tx tx :ty (- (* T (ground-row tiles col)) drone-h)))))

      :dive
      (let [[d there?] (toward d (:tx d) (:ty d) 3)]
        (if there? (assoc d :mode :ground :timer 600) d))

      :ground
      (if (pos? (:timer d)) d (assoc d :mode :rise))

      :rise
      (let [ty (cruise-y tiles x)
            [d there?] (toward d x ty 2)]
        (if there? (assoc d :mode :high :timer (+ 900 (* 1200 (js/Math.random)))) d)))))

(defn- step-sky
  "Keeps `drone-count` drones flying over the overworld once the Lord of Goo is beaten,
  and lowers a landing spaceship to the ground."
  [{:keys [goo-beaten? realm drones player ship] :as st} dt]
  (if (= realm :overworld)
    (let [tiles (get-in st [:worlds :overworld :tiles])
          px (+ (:x player) (/ player-w 2))
          drones (cond-> (or drones [])
                   (and goo-beaten? (< (count drones) drone-count)) (conj (new-drone tiles px)))
          busy? (some #(#{:dive :ground} (:mode %)) drones)
          st (assoc st :drones (mapv #(step-drone tiles % px dt busy?) drones))]
      (if (:landing? ship)
        (let [y (+ (:y ship) (* 2.5 dt))]
          (if (>= y (:ty ship))
            (-> st
                (assoc :ship (-> ship (assoc :y (:ty ship)) (dissoc :landing? :ty)))
                (update :particles into
                        (for [i (range 20)]
                          {:x (+ (:x ship) (* ship-w (js/Math.random))) :y (+ (:ty ship) ship-h -2)
                           :vx (- (* 6 (js/Math.random)) 3) :vy (- (* -2 (js/Math.random)) 0.5)
                           :life (+ 25 (* 20 (js/Math.random))) :color "rgba(220,210,190,0.9)" :i i})))
            (assoc-in st [:ship :y] y)))
        st))
    st))

(defn- box-aimed?
  "Checks that the mouse points at the box at `x`, `y` of size `w` by `h` and that the
  box is within reach of the hand."
  [{:keys [aim-at] :as p} {:keys [x y]} w h]
  (when aim-at
    (let [[hx hy] (hand p)
          [ax ay] aim-at]
      (and (<= (- x 4) ax (+ x w 4))
           (<= (- y 4) ay (+ y h 4))
           (<= (js/Math.hypot (- (max x (min hx (+ x w))) hx) (- (max y (min hy (+ y h))) hy))
               (+ reach 8))))))

(defn- land-ship
  "Sends a spaceship down to the surface 30 to 60 tiles to one side of the player."
  [{:keys [player] :as st}]
  (let [tiles (get-in st [:worlds :overworld :tiles])
        ptx (js/Math.floor (/ (:x player) T))
        side (if (< (js/Math.random) 0.5) -1 1)
        off (+ 30 (rand-int 31))
        c (+ ptx (* side off))
        [side c] (if (< 8 c (- W 9)) [side c] [(- side) (- ptx (* side off))])
        c (max 8 (min (- W 9) c))
        row (apply min (map #(ground-row tiles %) (range (- c 2) (+ c 2))))
        ty (- (* T row) ship-h)]
    (-> st
        (assoc :ship {:x (* T (- c 2)) :y (- ty 700) :ty ty :landing? true})
        (update :popups conj {:text (str "A spaceship is landing to the " (if (pos? side) "east" "west") "!")
                              :x (+ (:x player) (/ player-w 2)) :y (- (:y player) 30) :life 240}))))

(defn- strike-drone
  "Hits the drone sitting on the ground that the pickaxe points at. Breaking one pays
  `drone-reward` coins and brings down the spaceship when none has landed yet. Returns
  nil when no grounded drone is aimed at, `:loot` when it breaks and `:hit` otherwise."
  [state]
  (let [{:keys [player drones realm]} @state
        i (when (= realm :overworld)
            (first (keep-indexed (fn [i d] (when (and (= :ground (:mode d))
                                                      (box-aimed? player d drone-w drone-h))
                                             i))
                                 drones)))]
    (when i
      (let [{:keys [x y hp]} (get drones i)
            sparks (fn [n] (for [k (range n)]
                             {:x (+ x (* drone-w (js/Math.random))) :y (+ y (* drone-h (js/Math.random)))
                              :vx (- (* 4 (js/Math.random)) 2) :vy (- (* -3 (js/Math.random)) 1)
                              :life (+ 15 (* 20 (js/Math.random))) :color (if (even? k) "#ffd54a" "#9aa3ad") :i k}))]
        (if (> hp 1)
          (do (swap! state #(-> %
                                (update-in [:drones i] assoc :hp (dec hp) :flash 8)
                                (update :particles into (sparks 6))))
              :hit)
          (do (swap! state
                     (fn [st]
                       (cond-> (-> st
                                   (update :drones (fn [ds] (into (subvec ds 0 i) (subvec ds (inc i)))))
                                   (tally :drones 1)
                                   (add-coins drone-reward)
                                   (update :particles into (sparks 18))
                                   (update :popups conj {:text (str "Drone destroyed! +" drone-reward) :icon :coin
                                                         :x (+ x (/ drone-w 2)) :y (- y 10) :life 150}))
                         (nil? (:ship st)) land-ship)))
              :loot))))))

(defn- here-ship
  "Returns the spaceship standing in the realm the player is in, or nil."
  [{:keys [realm ship] :as st}]
  (case realm
    :overworld (when (and ship (not (:landing? ship))) ship)
    :space (get-in st [:worlds :space :ship])
    nil))

(defn- add-space
  "Gives the game an outer space, replacing one built at an older size."
  [st]
  (if (= [space-w space-h] (get-in st [:worlds :space :size]))
    st
    (-> st
        (assoc-in [:worlds :space] (generate-space))
        (dissoc :aliens :stars))))

(defn- fly-ship
  "Takes the player aboard the spaceship to the other end of its trip: from the
  overworld to outer space, or back home."
  [{:keys [realm] :as st}]
  (let [to (if (= realm :space) :overworld :space)
        st (switch-realm (add-space st) to)
        {:keys [x y]} (here-ship st)
        px (- (+ x (/ ship-w 2)) (/ player-w 2))]
    (-> st
        (update :player assoc :x px :y (- (+ y ship-h) player-h 0.5)
                :vx 0 :vy 0 :climbing? false :swing swing-frames :low-g? (= to :space))
        (update :popups conj {:text (if (= to :space) "Outer space! Gravity is weak here" "Back home")
                              :x (+ x (/ ship-w 2)) :y (- y 14) :life 180}))))

(defn- wrap-space
  "Brings a player who leaves outer space over one edge back in over the opposite edge."
  [{:keys [x y] :as p}]
  (let [x0 (* T space-x0) wpx (* T space-w)
        y0 (* T space-y0) hpx (* T space-h)
        cx (+ x (/ player-w 2))
        cy (+ y (/ player-h 2))]
    (cond-> p
      (< cx x0) (update :x + wpx)
      (>= cx (+ x0 wpx)) (update :x - wpx)
      (< cy y0) (update :y + hpx)
      (>= cy (+ y0 hpx)) (update :y - hpx))))

(defn- wrap-delta
  "Returns the shortest signed distance `d` along an axis that wraps every `n` pixels."
  [d n]
  (- (mod (+ d (/ n 2)) n) (/ n 2)))

(defn- space-offsets
  "Returns the shifts at which outer space is drawn so that the view shows its wrapped
  copies across the seams."
  []
  (let [wpx (* T space-w) hpx (* T space-h)]
    (for [ox [(- wpx) 0 wpx] oy [(- hpx) 0 hpx]] [ox oy])))

(def alien-w 24)
(def alien-h 20)
(def alien-count 10)
;; each new alien takes a random number of pickaxe hits in this range
(def alien-hp-min 10)
(def alien-hp-max 15)
(def alien-reward 8)
(def alien-sight 450)
(def star-count 30)
(def star-item {:id :star :name "Star"})

(defn- open-spot
  "Returns a random point in outer space where a `w` by `h` box touches no asteroid
  and lies at least `away` pixels from (`px`, `py`) the wrapped way round."
  [tiles w h px py away]
  (loop [n 0]
    (let [x (+ (* T (+ space-x0 2)) (* (js/Math.random) (* T (- space-w 4))))
          y (+ (* T (+ space-y0 2)) (* (js/Math.random) (* T (- space-h 4))))
          far? (> (js/Math.hypot (wrap-delta (- x px) (* T space-w))
                                 (wrap-delta (- y py) (* T space-h)))
                  away)]
      (cond
        (and far? (not (box-hits? tiles (- x 4) (- y 4) (+ w 8) (+ h 8)))) [x y]
        (< n 60) (recur (inc n))
        :else nil))))

(defn- step-alien
  "Moves alien `a` one frame. It floats through the asteroids, drifting idly until the
  player comes within `alien-sight`, then floats after the player."
  [{:keys [x y vx vy] :as a} px py dt]
  (let [dx (wrap-delta (- px (+ x (/ alien-w 2))) (* T space-w))
        dy (wrap-delta (- py (+ y (/ alien-h 2))) (* T space-h))
        d (max 1 (js/Math.hypot dx dy))
        chase? (< d alien-sight)
        [ax ay] (if chase?
                  [(* 0.05 (/ dx d)) (* 0.05 (/ dy d))]
                  [(* 0.04 (- (js/Math.random) 0.5)) (* 0.04 (- (js/Math.random) 0.5))])
        top (if chase? 1.6 0.7)
        vx (+ vx (* ax dt)) vy (+ vy (* ay dt))
        s (js/Math.hypot vx vy)
        [vx vy] (if (> s top) [(* vx (/ top s)) (* vy (/ top s))] [vx vy])
        x0 (* T space-x0) y0 (* T space-y0)]
    (assoc a :vx vx :vy vy
           :x (+ x0 (mod (- (+ x (* vx dt)) x0) (* T space-w)))
           :y (+ y0 (mod (- (+ y (* vy dt)) y0) (* T space-h)))
           :flash (max 0 (- (:flash a 0) dt)))))

(defn- step-space
  "Keeps `alien-count` aliens and `star-count` stars in outer space, moves the aliens,
  hurts the player on an alien's touch and collects the stars the player touches."
  [{:keys [realm player aliens stars] :as st} dt]
  (if (not= realm :space)
    st
    (let [tiles (get-in st [:worlds :space :tiles])
          px (+ (:x player) (/ player-w 2))
          py (+ (:y player) (/ player-h 2))
          aliens (or aliens [])
          aliens (if-let [[x y] (and (< (count aliens) alien-count) (open-spot tiles alien-w alien-h px py 600))]
                   (let [hp (+ alien-hp-min (rand-int (inc (- alien-hp-max alien-hp-min))))]
                     (conj aliens {:x x :y y :vx 0 :vy 0 :hp hp :max-hp hp :flash 0 :phase (* 6 (js/Math.random))}))
                   aliens)
          aliens (mapv #(step-alien % px py dt) aliens)
          new-star #(when-let [[x y] (open-spot tiles 14 14 px py 300)]
                      {:x (+ x 7) :y (+ y 7) :phase (* 6 (js/Math.random))})
          ;; space starts full of stars, and a collected one comes back every 5 seconds
          stars (or stars (into [] (keep (fn [_] (new-star))) (range star-count)))
          star-in (- (:star-in st 0) dt)
          [stars star-in] (if-let [s (and (< (count stars) star-count) (<= star-in 0) (new-star))]
                            [(conj stars s) 300]
                            [stars star-in])
          near? (fn [x y r] (< (js/Math.hypot (wrap-delta (- x px) (* T space-w))
                                              (wrap-delta (- y py) (* T space-h)))
                               r))
          got (count (filter #(near? (:x %) (:y %) 22) stars))
          stars (filterv #(not (near? (:x %) (:y %) 22)) stars)
          touching (when (<= (:hurt player 0) 0)
                     (first (filter #(near? (+ (:x %) (/ alien-w 2)) (+ (:y %) (/ alien-h 2)) 22) aliens)))]
      (cond-> (assoc st :aliens aliens :stars stars :star-in star-in)
        (pos? got)
        (-> (stash [(assoc star-item :count got)])
            (tally :stars got)
            (assoc :star-shop? true)
            (update :popups conj {:text (str "+" got " Star") :icon :star :x px :y (- (:y player) 10) :life 90}))
        touching
        (update :player assoc
                :vx (if (neg? (wrap-delta (- (+ (:x touching) (/ alien-w 2)) px) (* T space-w))) 6 -6)
                :vy -3 :hurt 50 :climbing? false
                :hp (max 0 (- (:hp player max-hp) 1)))))))

(defn- strike-alien
  "Hits the nearest alien in front of the pickaxe and within reach. Returns nil when the
  pickaxe reaches none, `:loot` when the alien dies and pays `alien-reward` coins, and
  `:hit` otherwise."
  [state]
  (let [{:keys [player aliens realm]} @state
        px (+ (:x player) (/ player-w 2))
        py (+ (:y player) (/ player-h 2))
        near (fn [a] (assoc a
                            :x (+ px (wrap-delta (- (:x a) px) (* T space-w)))
                            :y (+ py (wrap-delta (- (:y a) py) (* T space-h)))))
        [hx hy] (hand player)
        [ax ay] (or (:aim-at player) [(+ hx (:facing player)) hy])
        ad (max 0.001 (js/Math.hypot (- ax hx) (- ay hy)))
        ;; the same reach and swing direction as `strike-enemies` uses for slimes
        dist (fn [a]
               (let [{:keys [x y]} (near a)
                     cx (- (+ x (/ alien-w 2)) hx)
                     cy (- (+ y (/ alien-h 2)) hy)
                     d (js/Math.hypot cx cy)]
                 (when (and (<= d (+ reach 10))
                            (or (< d 24)
                                (> (/ (+ (* cx (- ax hx)) (* cy (- ay hy))) (* d ad)) 0.55)))
                   d)))
        i (when (= realm :space)
            (some->> (keep-indexed (fn [i a] (when-let [d (dist a)] [d i])) aliens)
                     seq (apply min-key first) second))]
    (when i
      (let [{:keys [hp] :as a} (get aliens i)
            {:keys [x y]} (near a)
            goo (fn [n] (for [k (range n)]
                          {:x (+ x (* alien-w (js/Math.random))) :y (+ y (* alien-h (js/Math.random)))
                           :vx (- (* 4 (js/Math.random)) 2) :vy (- (* 4 (js/Math.random)) 2)
                           :life (+ 15 (* 20 (js/Math.random))) :color (if (even? k) "#b06cff" "#7cff9a") :i k}))]
        (if (> hp 1)
          (do (swap! state #(-> %
                                (update-in [:aliens i] assoc :hp (dec hp) :flash 8
                                           :vx (if (< x px) -3 3) :vy -1)
                                (update :particles into (goo 6))))
              :hit)
          (do (swap! state #(-> %
                                (update :aliens (fn [as] (into (subvec as 0 i) (subvec as (inc i)))))
                                (tally :aliens 1)
                                (add-coins alien-reward)
                                (update :particles into (goo 16))
                                (update :popups conj {:text (str "+" alien-reward) :icon :coin
                                                      :x (+ x (/ alien-w 2)) :y (- y 8) :life 90})))
              :loot))))))

(defn- draw-alien
  "Draws one alien with its top-left corner at screen position `sx`, `sy`."
  [^js ctx sx sy flash phase t]
  (let [cx (+ sx (/ alien-w 2))]
    (set! (.-strokeStyle ctx) "#7a3fc4")
    (set! (.-lineWidth ctx) 2)
    (doseq [side [-1 1]]
      (.beginPath ctx)
      (.moveTo ctx (+ cx (* side 4)) (+ sy 5))
      (.lineTo ctx (+ cx (* side 8)) (- sy 3))
      (.stroke ctx))
    (disc ctx "#7cff9a" (- cx 8) (- sy 3) 2)
    (disc ctx "#7cff9a" (+ cx 8) (- sy 3) 2)
    (set! (.-fillStyle ctx) (if (pos? flash) "#ffffff" "#b06cff"))
    (.beginPath ctx)
    (.ellipse ctx cx (+ sy 10) (/ alien-w 2) (/ alien-h 2) 0 0 (* 2 js/Math.PI))
    (.fill ctx)
    (set! (.-fillStyle ctx) "#8a4be0")
    (doseq [k (range 4)]
      (.fillRect ctx (+ sx 3 (* k 5)) (+ sy alien-h -3) 3 (+ 3 (js/Math.round (* 2 (js/Math.sin (+ (/ t 120) k phase)))))))
    (disc ctx "#ffffff" cx (+ sy 9) 5)
    (disc ctx "#1a0a33" (+ cx 1) (+ sy 9) 2.5)))

(defn- draw-aliens
  "Draws the aliens, with a health bar over each one that has been hit. Aliens from
  before they had more health have no :max-hp and started with 3."
  [^js ctx aliens t cam-x cam-y]
  (doseq [{:keys [x y flash phase hp max-hp]} aliens
          :let [bob (* 2 (js/Math.sin (+ (/ t 300) phase)))
                sx (js/Math.round (- x cam-x))
                sy (js/Math.round (+ (- y cam-y) bob))
                max-hp (or max-hp 3)]]
    (draw-alien ctx sx sy flash phase t)
    (when (< hp max-hp)
      (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
      (.fillRect ctx (- sx 1) (- sy 12) (+ alien-w 2) 5)
      (set! (.-fillStyle ctx) "#7cff9a")
      (.fillRect ctx sx (- sy 11) (js/Math.round (* alien-w (/ (max 0 hp) max-hp))) 3))))

;; robbers walk the surface of the overworld and steal coins on touch
(def robber-w 18)
(def robber-h 38)
(def robber-hp 10)
(def max-robbers 1)
(defn- robber-pause
  "Returns how many frames pass before the next robber comes: one to two minutes."
  []
  (+ 3600 (rand-int 3601)))
(def robber-sight (* 50 T))
(def robber-flee-frames 360)
(def robber-steal-min 10)
(def robber-steal-max 20)

(defn- spawn-robber
  "Returns a robber standing on the surface 25 to 45 tiles to one side of `px`, or nil
  when that spot is outside the world."
  [^js tiles px]
  (let [side (if (< (js/Math.random) 0.5) -1 1)
        tx (+ (js/Math.floor (/ px T)) (* side (+ 25 (rand-int 21))))]
    (when (< 2 tx (- W 3))
      ;; the robber spans two columns, so it stands on the higher of the two
      {:x (+ (* tx T) 1) :y (- (* T (min (ground-row tiles tx) (ground-row tiles (inc tx)))) robber-h 0.01)
       :w robber-w :h robber-h :vx 0 :vy 0 :ground? false
       :hp robber-hp :flash 0 :stolen 0 :flee 0 :knock 0
       :dir side :turn-in (+ 60 (rand-int 120)) :walked 0})))

(defn- step-robber
  "Moves robber `r` one frame. It wanders until the player comes within `robber-sight`,
  then walks after the player; while `:flee` counts down it runs away instead. It hops
  over steps and walls in its way."
  [tiles r player dt]
  (let [;; a robber caught inside the ground rises a tile at a time until it is free
        r (loop [r r n 0]
            (if (and (< n H) (box-hits? tiles (:x r) (:y r) robber-w robber-h))
              (recur (assoc r :y (- (:y r) T) :vy 0) (inc n))
              r))
        {:keys [x y dir flee knock turn-in ground?]} r
        dx (- (+ (:x player) (/ player-w 2)) (+ x (/ robber-w 2)))
        dy (- (:y player) y)
        chase? (and (< (js/Math.abs dx) robber-sight) (< (js/Math.abs dy) (* 8 T)))
        turn? (<= turn-in 0)
        dir (cond (pos? flee) (if (neg? dx) 1 -1)
                  chase? (if (neg? dx) -1 1)
                  turn? (if (< (js/Math.random) 0.5) -1 1)
                  :else dir)
        speed (cond (pos? flee) 2.6 chase? 1.6 :else 0.7)
        vx (if (pos? knock) (* (:vx r) 0.9) (* dir speed))
        vy (min max-fall (+ (:vy r) (* gravity dt)))
        moved (-> r
                  (assoc :dir dir :vx vx :vy vy
                         :flee (max 0 (- flee dt)) :knock (max 0 (- knock dt))
                         :flash (max 0 (- (:flash r) dt))
                         :turn-in (if turn? (+ 90 (rand-int 180)) (- turn-in dt)))
                  (as-> s (move-x tiles s (* vx dt)))
                  (as-> s (move-y tiles s (* vy dt)))
                  (update :x #(max T (min (* T (- W 2)) %))))
        blocked? (and ground? (zero? (:vx moved)) (not (zero? vx)))]
    (cond-> (update moved :walked + (js/Math.abs (* (:vx moved) dt)))
      blocked? (assoc :vy -7.5))))

(defn- step-robbers
  "Keeps up to `max-robbers` robbers on the overworld's surface, moves them and lets a
  robber that touches the player steal 10 to 20 coins and run off with them. Robbers
  far from the player wander off for good. The wait for the next robber runs only
  at night while there is room for one, and starts again when one comes. Robbers
  come out only at night, and any still about at sunrise vanish in a puff of smoke."
  [{:keys [realm player robbers coins] :as st} clock dt]
  (cond
    (not= realm :overworld)
    st

    ;; robbers come out only at night; at sunrise they run off in a puff of smoke
    (:day? (time-of-day clock))
    (cond-> st
      (seq robbers)
      (-> (assoc :robbers [])
          (update :particles into
                  (for [{:keys [x y]} robbers k (range 16)]
                    {:x (+ x (* robber-w (js/Math.random))) :y (+ y (* robber-h (js/Math.random)))
                     :vx (- (* 3 (js/Math.random)) 1.5) :vy (- (* -2 (js/Math.random)) 0.5)
                     :life (+ 25 (* 25 (js/Math.random))) :color "rgba(200,200,200,0.8)" :i k}))))

    :else
    (let [tiles (get-in st [:worlds :overworld :tiles])
          px (+ (:x player) (/ player-w 2))
          room? (< (count robbers) max-robbers)
          robber-in (cond-> (:robber-in st 0) room? (- dt))
          new (when (and room? (<= robber-in 0))
                (spawn-robber tiles px))
          robber-in (if new (robber-pause) robber-in)
          robbers (cond-> (into [] (comp (map #(step-robber tiles % player dt))
                                         (filter #(< (js/Math.abs (- (:x %) px)) (* 80 T))))
                                (or robbers []))
                    new (conj new))
          coins (or coins 0)
          thief (some (fn [[i r]] (when (and (zero? (:flee r)) (touching? player r)) i))
                      (map-indexed vector robbers))
          n (min coins (+ robber-steal-min (rand-int (inc (- robber-steal-max robber-steal-min)))))]
      (cond-> (assoc st :robbers robbers :robber-in robber-in)
        thief
        (assoc-in [:robbers thief :flee] robber-flee-frames)
        (and thief (pos? n))
        (-> (add-coins (- n))
            (tally :stolen n)
            (update-in [:robbers thief :stolen] + n)
            (update :popups conj {:text (str "A robber stole " n " coins!") :icon :coin
                                  :x px :y (- (:y player) 10) :life 120}))
        ;; with nothing to steal the robber grumbles and runs off all the same
        (and thief (zero? n))
        (update :popups conj (let [r (get robbers thief)]
                               {:text "No coins!" :x (+ (:x r) (/ robber-w 2)) :y (- (:y r) 10) :life 120}))))))

(defn- strike-robber
  "Hits the nearest robber in front of the pickaxe and within reach. Returns nil when
  the pickaxe reaches none, `:loot` when the robber is knocked out and drops half the
  coins it stole, and `:hit` otherwise."
  [state]
  (let [{:keys [player robbers realm]} @state
        px (+ (:x player) (/ player-w 2))
        [hx hy] (hand player)
        [ax ay] (or (:aim-at player) [(+ hx (:facing player)) hy])
        ad (max 0.001 (js/Math.hypot (- ax hx) (- ay hy)))
        ;; the same reach and swing direction as `strike-enemies` uses for slimes
        dist (fn [{:keys [x y]}]
               (let [cx (- (+ x (/ robber-w 2)) hx)
                     cy (- (+ y (/ robber-h 2)) hy)
                     d (js/Math.hypot cx cy)]
                 (when (and (<= d (+ reach 14))
                            (or (< d 26)
                                (> (/ (+ (* cx (- ax hx)) (* cy (- ay hy))) (* d ad)) 0.55)))
                   d)))
        i (when (= realm :overworld)
            (some->> (keep-indexed (fn [i r] (when-let [d (dist r)] [d i])) robbers)
                     seq (apply min-key first) second))]
    (when i
      (let [{:keys [x y hp stolen]} (get robbers i)
            dust (fn [n] (for [k (range n)]
                           {:x (+ x (* robber-w (js/Math.random))) :y (+ y (* robber-h (js/Math.random)))
                            :vx (- (* 4 (js/Math.random)) 2) :vy (- (* -3 (js/Math.random)) 1)
                            :life (+ 15 (* 20 (js/Math.random))) :color (if (even? k) "#222222" "#eeeeee") :i k}))]
        (if (> hp 1)
          (do (swap! state #(-> %
                                (update-in [:robbers i] assoc :hp (dec hp) :flash 8 :knock 12
                                           :vx (if (< (+ x (/ robber-w 2)) px) -4 4) :vy -2.5)
                                (update :particles into (dust 5))))
              :hit)
          (let [back (quot stolen 2)]
            (swap! state #(cond-> (-> %
                                      (update :robbers (fn [rs] (into (subvec rs 0 i) (subvec rs (inc i)))))
                                      (tally :robbers 1)
                                      (tally :recovered back)
                                      (update :particles into (dust 18)))
                            (pos? back)
                            (-> (add-coins back)
                                (update :popups conj {:text (str "+" back) :icon :coin
                                                      :x (+ x (/ robber-w 2)) :y (- y 8) :life 90}))))
            :loot))))))

(defn- draw-robber
  "Draws robber `r` in a black mask, a striped shirt and a beanie, carrying a coin sack
  once it has stolen something."
  [^js ctx {:keys [x y dir vx ground? walked flash stolen]} cam-x cam-y]
  (let [sx (js/Math.round (- x cam-x))
        sy (js/Math.round (- y cam-y))
        moving? (and ground? (> (js/Math.abs vx) 0.4))
        swing (if moving? (js/Math.round (* 3 (js/Math.sin (/ walked 6)))) 0)
        rect (fn [color rx ry rw rh]
               (set! (.-fillStyle ctx) (if (pos? flash) "#ffffff" color))
               (.fillRect ctx (if (pos? dir) (+ sx rx) (- (+ sx robber-w) rx rw)) (+ sy ry) rw rh))]
    (when (pos? stolen)
      (rect "#a0783c" -5 14 9 11)
      (rect "#7a5a2a" -4 12 7 3)
      (rect "#ffd54a" -2 18 3 3))
    (rect "#2b2b2b" 3 28 5 10)
    (rect "#2b2b2b" 10 28 5 10)
    (rect "#111111" (+ 3 swing) 34 5 4)
    (rect "#111111" (- 10 swing) 34 5 4)
    (rect "#f5f5f5" 2 15 14 14)
    (doseq [k (range 4)] (rect "#1d1d1d" 2 (+ 16 (* 3 k)) 14 2))
    (rect "#f1c27d" (+ 7 (- swing)) 17 4 11)
    (rect "#f1c27d" 3 3 13 13)
    (rect "#111111" 3 7 13 4)
    (rect "#ffffff" 11 8 2 2)
    (rect "#ffffff" 6 8 2 2)
    (rect "#1d1d1d" 2 0 15 5)
    (rect "#3a3a3a" 2 4 15 1)))

;; mountain goats wander the mountains, butt the player once hit, and calm down for hay
(def goat-w 28)
(def goat-h 24)
(def max-goats 4)
(def goat-calm-reach (* 5 T))
(def goat-mount-reach (* 3 T))
(def ride-frames 600)

(defn- spawn-goat
  "Returns a calm goat standing on the ground at a random column at least 30 tiles
  from `px`, or nil when that column is too close."
  [^js tiles px]
  (let [tx (+ 4 (rand-int (- W 10)))]
    (when (> (js/Math.abs (- (* tx T) px)) (* 30 T))
      {:x (+ (* tx T) 2) :y (- (* T (min (ground-row tiles tx) (ground-row tiles (inc tx)))) goat-h 0.01)
       :w goat-w :h goat-h :vx 0 :vy 0 :ground? false :dir (if (< (js/Math.random) 0.5) -1 1)
       :turn-in (+ 60 (rand-int 200)) :graze 0 :angry? false :flash 0 :knock 0 :walked 0})))

(defn- step-goat
  "Moves goat `g` one frame. A calm goat ambles about, stops now and then to graze and
  leaps up any step in its way; an angry one charges at the player."
  [tiles g player dt]
  (let [g (loop [g g n 0]
            (if (and (< n H) (box-hits? tiles (:x g) (:y g) goat-w goat-h))
              (recur (assoc g :y (- (:y g) T) :vy 0) (inc n))
              g))
        {:keys [x dir knock turn-in graze ground? angry?]} g
        dx (- (+ (:x player) (/ player-w 2)) (+ x (/ goat-w 2)))
        turn? (<= turn-in 0)
        grazing? (and (not angry?) (pos? graze))
        dir (cond angry? (if (neg? dx) -1 1)
                  turn? (if (< (js/Math.random) 0.5) -1 1)
                  :else dir)
        speed (cond angry? 2.5 grazing? 0 :else 0.8)
        vx (if (pos? knock) (* (:vx g) 0.92) (* dir speed))
        vy (min max-fall (+ (:vy g) (* gravity dt)))
        moved (-> g
                  (assoc :dir dir :vx vx :vy vy
                         :knock (max 0 (- knock dt))
                         :flash (max 0 (- (:flash g) dt))
                         :graze (if (and turn? (< (js/Math.random) 0.4)) (+ 60 (rand-int 120)) (max 0 (- graze dt)))
                         :turn-in (if turn? (+ 90 (rand-int 240)) (- turn-in dt)))
                  (as-> s (move-x tiles s (* vx dt)))
                  (as-> s (move-y tiles s (* vy dt)))
                  (update :x #(max T (min (- (* T (dec W)) goat-w) %))))
        blocked? (and ground? (zero? (:vx moved)) (not (zero? vx)))]
    (cond-> (update moved :walked + (js/Math.abs (* (:vx moved) dt)))
      ;; goats are good climbers and leap five tiles up
      blocked? (assoc :vy -8.6)
      ;; and now and then hop for the fun of it
      (and ground? (not blocked?) (not grazing?) (< (js/Math.random) (* 0.004 dt))) (assoc :vy -6))))

(defn- end-ride
  "Ends the ride: the goat bucks the player off with a hop and is an ordinary calm
  goat again."
  [{:keys [riding player] :as st}]
  (let [i (:i riding)]
    (-> st
        (dissoc :riding)
        (cond-> (get-in st [:goats i]) (update-in [:goats i] assoc :friendly? false :ridden? false :knock 30
                                                  :vx (* -2 (:facing player 1)) :vy -3))
        (update :player #(cond-> (dissoc % :run :leap)
                           (not (:flung? %)) (assoc :vy -6 :ground? false)))
        (cond-> (= :mountains (:realm st))
          (update :popups conj {:text "The goat bucks you off!" :x (+ (:x player) (/ player-w 2))
                                :y (- (:y player) 20) :life 90})))))

(defn mount-goat
  "Climbs onto the nearest friendly goat within `goat-mount-reach`, or returns nil."
  [{:keys [player goats realm riding] :as st}]
  (let [px (+ (:x player) (/ player-w 2))
        py (+ (:y player) player-h)
        dist (fn [{:keys [x y]}] (js/Math.hypot (- (+ x (/ goat-w 2)) px) (- (+ y goat-h) py)))
        i (when (and (= realm :mountains) (not riding))
            (some->> (keep-indexed (fn [i g] (when (and (:friendly? g) (<= (dist g) goat-mount-reach)) [(dist g) i])) goats)
                     seq (apply min-key first) second))]
    (when i
      (let [{:keys [x y]} (get goats i)
            nx (- (+ x (/ goat-w 2)) (/ player-w 2))
            ny (- (+ y goat-h) player-h)
            tiles (get-in st [:worlds :mountains :tiles])]
        (-> st
            (assoc :riding {:i i :left ride-frames})
            (update-in [:goats i] assoc :ridden? true :graze 0)
            (update :player assoc :run 1.6 :leap 1.25 :climbing? false :vx 0
                    :x (if (box-hits? tiles nx ny player-w player-h) (:x player) nx))
            (tally :rides 1))))))

(defn- step-goats
  "Keeps `max-goats` goats in the mountains while the player is there and moves them.
  An angry goat that touches the player butts them back through the air."
  [{:keys [realm player goats riding] :as st} dt]
  (cond
    (and riding (or (not= realm :mountains) (<= (:left riding) 0) (:flung? player)))
    (end-ride st)

    (not= realm :mountains)
    st

    :else
    (let [tiles (get-in st [:worlds :mountains :tiles])
          px (+ (:x player) (/ player-w 2))
          new (when (< (count goats) max-goats) (spawn-goat tiles px))
          ridden (:i riding)
          goats (cond-> (into [] (map-indexed (fn [i g]
                                                (if (= i ridden)
                                                  ;; the ridden goat carries the player
                                                  (assoc g :x (- px (/ goat-w 2)) :y (- (+ (:y player) player-h) goat-h)
                                                         :vx (:vx player) :dir (:facing player) :ground? (:ground? player)
                                                         :graze 0 :angry? false
                                                         :walked (+ (:walked g 0) (js/Math.abs (* (:vx player) dt))))
                                                  (step-goat tiles g player dt))))
                              (or goats []))
                  new (conj new))
          st (cond-> st riding (update-in [:riding :left] - dt))
          butt (when (<= (:hurt player 0) 0)
                 (some (fn [[i g]] (when (and (:angry? g) (zero? (:knock g)) (touching? player g)) i))
                       (map-indexed vector goats)))]
      (if butt
        (let [g (get goats butt)
              side (if (< px (+ (:x g) (/ goat-w 2))) -1 1)]
          (-> st
              (assoc :goats goats)
              (update-in [:goats butt] assoc :knock 40 :vx (* side -3) :vy -3)
              (tally :butted 1)
              ;; a butt does no harm but sends the player flying a long way
              (update :player assoc :vx (* side 7) :vy -10 :hurt 50 :climbing? false :flung? true :ground? false)
              (update :popups conj {:text "Bonk!" :x px :y (- (:y player) 10) :life 70})))
        (assoc st :goats goats)))))

(defn- strike-goat
  "Hits the nearest goat in front of the pickaxe and within reach, which makes it
  angry. Returns nil when the pickaxe reaches none and `:hit` otherwise."
  [state]
  (let [{:keys [player goats realm]} @state
        px (+ (:x player) (/ player-w 2))
        [hx hy] (hand player)
        [ax ay] (or (:aim-at player) [(+ hx (:facing player)) hy])
        ad (max 0.001 (js/Math.hypot (- ax hx) (- ay hy)))
        dist (fn [{:keys [x y]}]
               (let [cx (- (+ x (/ goat-w 2)) hx)
                     cy (- (+ y (/ goat-h 2)) hy)
                     d (js/Math.hypot cx cy)]
                 (when (and (<= d (+ reach 14))
                            (or (< d 26)
                                (> (/ (+ (* cx (- ax hx)) (* cy (- ay hy))) (* d ad)) 0.55)))
                   d)))
        i (when (= realm :mountains)
            (some->> (keep-indexed (fn [i g] (when-not (:ridden? g) (when-let [d (dist g)] [d i]))) goats)
                     seq (apply min-key first) second))]
    (when i
      (let [{:keys [x y angry?]} (get goats i)]
        (swap! state #(cond-> (update-in % [:goats i] assoc :angry? true :friendly? false :flash 8 :knock 12 :graze 0
                                         :vx (if (< (+ x (/ goat-w 2)) px) -3 3) :vy -2.5)
                        (not angry?)
                        (update :popups conj {:text "Baa!" :x (+ x (/ goat-w 2)) :y (- y 8) :life 70})))
        :hit))))

(defn- calm-goat
  "Feeds hay to the nearest angry goat within `goat-calm-reach` of the player, which
  calms it, or else to the nearest calm one, which becomes friendly and lets the
  player ride it. Returns nil when no goat that wants hay is that close."
  [{:keys [player goats realm riding] :as st}]
  (let [px (+ (:x player) (/ player-w 2))
        py (+ (:y player) (/ player-h 2))
        dist (fn [{:keys [x y]}] (js/Math.hypot (- (+ x (/ goat-w 2)) px) (- (+ y (/ goat-h 2)) py)))
        i (when (= realm :mountains)
            (some->> (keep-indexed (fn [i g] (when (and (:angry? g) (<= (dist g) goat-calm-reach)) [(dist g) i])) goats)
                     seq (apply min-key first) second))]
    (if i
      (let [{:keys [x y]} (get goats i)]
        (-> st
            (update-in [:goats i] assoc :angry? false :graze 150 :knock 0)
            (tally :calmed 1)
            (update :particles into (for [k (range 8)]
                                      {:x (+ x (* goat-w (js/Math.random))) :y (+ y (* 6 (js/Math.random)))
                                       :vx (- (* 2 (js/Math.random)) 1) :vy (- (* -2 (js/Math.random)) 1)
                                       :life (+ 25 (* 20 (js/Math.random))) :color "#ff7aa8" :i k}))
            (update :popups conj {:text "Munch! The goat calms down" :x (+ x (/ goat-w 2)) :y (- y 8) :life 100})))
      (when-let [i (when (= realm :mountains)
                     (some->> (keep-indexed (fn [i g] (when (and (not (:angry? g)) (not (:friendly? g)) (not= i (:i riding))
                                                                 (<= (dist g) goat-calm-reach))
                                                        [(dist g) i]))
                                            goats)
                              seq (apply min-key first) second))]
        (let [{:keys [x y]} (get goats i)]
          (-> st
              (update-in [:goats i] assoc :friendly? true :graze 200)
              (update :particles into (for [k (range 8)]
                                        {:x (+ x (* goat-w (js/Math.random))) :y (+ y (* 6 (js/Math.random)))
                                         :vx (- (* 2 (js/Math.random)) 1) :vy (- (* -2 (js/Math.random)) 1)
                                         :life (+ 25 (* 20 (js/Math.random))) :color "#ff7aa8" :i k}))
              (update :popups conj {:text "The goat likes you! Press E to ride" :x (+ x (/ goat-w 2)) :y (- y 8) :life 120})))))))

(defn- draw-goat
  "Draws goat `g` as a white goat with curved horns and a beard, with a red anger
  mark over its head while it is angry."
  [^js ctx {:keys [x y dir vx ground? walked flash angry? graze]} clock cam-x cam-y]
  (let [sx (js/Math.round (- x cam-x))
        sy (js/Math.round (- y cam-y))
        moving? (and ground? (> (js/Math.abs vx) 0.3))
        swing (if moving? (js/Math.round (* 2 (js/Math.sin (/ walked 4)))) 0)
        ;; a grazing goat lowers its head to the grass
        dip (if (and (pos? graze) (not angry?)) 5 0)
        rect (fn [color rx ry rw rh]
               (set! (.-fillStyle ctx) (if (pos? flash) "#ffd0d0" color))
               (.fillRect ctx (if (pos? dir) (+ sx rx) (- (+ sx goat-w) rx rw)) (+ sy ry) rw rh))]
    ;; legs
    (rect "#d8d8d0" (+ 3 swing) 16 3 8)
    (rect "#d8d8d0" (- 8 swing) 16 3 8)
    (rect "#d8d8d0" (+ 15 swing) 16 3 8)
    (rect "#d8d8d0" (- 19 swing) 16 3 8)
    (rect "#3a3530" 3 22 19 2)
    ;; body and tail
    (rect "#f4f4ee" 1 7 22 11)
    (rect "#e2e2da" 1 15 22 3)
    (rect "#f4f4ee" -1 6 3 3)
    ;; neck and head
    (rect "#f4f4ee" 19 (+ 3 dip) 5 8)
    (rect "#f4f4ee" 21 (+ 1 dip) 7 7)
    (rect "#e8c0b0" 26 (+ 5 dip) 2 2)
    (rect "#222222" 24 (+ 3 dip) 2 2)
    ;; beard
    (rect "#e2e2da" 23 (+ 8 dip) 3 4)
    ;; curved horns sweeping back
    (rect "#8a7a64" 21 (- dip 1) 3 2)
    (rect "#8a7a64" 19 (- dip 2) 3 2)
    (rect "#8a7a64" 18 dip 2 2)
    (rect "#eedcc0" 26 (+ 1 dip) 2 2)
    (when angry?
      (let [bob (js/Math.round (* 2 (js/Math.sin (/ clock 120))))
            mx (+ sx (/ goat-w 2) -5)
            my (+ sy -14 bob)]
        (set! (.-fillStyle ctx) "#e53935")
        (doseq [[ax ay aw ah] [[0 0 3 4] [7 0 3 4] [0 6 3 4] [7 6 3 4] [3 3 4 1] [3 6 4 1]]]
          (.fillRect ctx (+ mx ax) (+ my ay) aw ah))))))

(defn- draw-goats [^js ctx goats clock cam-x cam-y]
  (doseq [g goats] (draw-goat ctx g clock cam-x cam-y)))

(defn- draw-heart [^js ctx cx top]
  (set! (.-fillStyle ctx) "#ffffff")
  (doseq [[x y w h] [[-6 1 12 5] [-5 0 4 1] [1 0 4 1] [-4 6 8 2] [-2 8 4 2]]]
    (.fillRect ctx (+ cx x) (+ top y -1) w (+ h 2)))
  (set! (.-fillStyle ctx) "#ff5c8a")
  (doseq [[x y w h] [[-5 1 10 4] [-4 0 3 1] [1 0 3 1] [-3 5 6 2] [-1 7 2 2]]]
    (.fillRect ctx (+ cx x) (+ top y) w h))
  (set! (.-fillStyle ctx) "#ffc2d4")
  (.fillRect ctx (- cx 4) (+ top 1) 2 2))

(defn- draw-goat-marks
  "Draws a pink heart over each friendly goat, with \"E: Ride\" beside it when the player
  is close enough to climb on, and over a ridden goat's rider a heart with a bar of
  the ride time left."
  [^js ctx {:keys [goats player riding]} clock cam-x cam-y]
  (let [bob (js/Math.round (* 1.5 (js/Math.sin (/ clock 200))))
        px (+ (:x player) (/ player-w 2))
        py (+ (:y player) player-h)]
    (doseq [[i {:keys [x y friendly?]}] (map-indexed vector goats)
            :when friendly?]
      (let [cx (js/Math.round (- (+ x (/ goat-w 2)) cam-x))]
        (if (= i (:i riding))
          (let [top (js/Math.round (- (:y player) cam-y 34))
                frac (max 0 (/ (:left riding) ride-frames))]
            (draw-heart ctx cx (+ top bob))
            (set! (.-fillStyle ctx) "rgba(0,0,0,0.55)")
            (.fillRect ctx (- cx 14) (+ top 13) 28 5)
            (set! (.-fillStyle ctx) "#ff5c8a")
            (.fillRect ctx (- cx 13) (+ top 14) (js/Math.round (* 26 frac)) 3))
          (let [top (js/Math.round (- y cam-y 14))]
            (draw-heart ctx cx (+ top bob))
            (when (and (not riding)
                       (<= (js/Math.hypot (- (+ x (/ goat-w 2)) px) (- (+ y goat-h) py)) goat-mount-reach))
              (.save ctx)
              (set! (.-font ctx) "bold 12px system-ui, sans-serif")
              (set! (.-textAlign ctx) "left")
              (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
              (.fillRect ctx (+ cx 9) (+ top -2) 48 15)
              (set! (.-fillStyle ctx) "#ffffff")
              (.fillText ctx "E: Ride" (+ cx 13) (+ top 10))
              (.restore ctx))))))))

(defn- near-player?
  "Checks that the point `x`, `y` lies within `r` pixels of the player's middle."
  [{:keys [player]} x y r]
  (< (js/Math.hypot (- x (+ (:x player) (/ player-w 2))) (- y (+ (:y player) (/ player-h 2)))) r))

(defn discover
  "Adds to `:seen` the parts of the game that the player has met, which unlock their
  sections of the How to play help. Games saved before `:seen` existed unlock what
  their other state shows the player has reached."
  [{:keys [bag realm enemies fluffies robbers goo-beaten? star-shop? gate-open?] :as st}]
  (let [has?  (fn [id] (some #(= id (:id %)) bag))
        found (cond-> #{}
                (or (has? :rope) (has? :torch)) (conj :rope)
                (some #(near-player? st (:x %) (:y %) (* 30 T)) enemies) (conj :slimes)
                (or (has? :amulet) (= :slime realm)) (conj :dimension)
                (or (has? :key) (= :arena realm) gate-open?) (conj :goo)
                (some #(and (= (:realm % :overworld) realm) (near-player? st (:x %) (:y %) (* 12 T))) fluffies)
                (conj :fluffies)
                (and (= :overworld realm) (some #(near-player? st (:x %) (:y %) (* 40 T)) robbers)) (conj :robbers)
                goo-beaten? (conj :drones)
                (or (:paper st)
                    (some->> (:paperboy st) ((fn [b] (near-player? st (:x b) (:y b) (* 30 T))))))
                (conj :paper)
                (or (= :space realm) star-shop? (has? :star)) (conj :space)
                (or (= :mountains realm) (has? :ore) (has? :hay)) (conj :mountains))
        seen  (or (:seen st) #{})]
    (if (every? seen found)
      st
      (assoc st :seen (into seen found)))))

(defn- draw-robbers
  "Draws the robbers, with a health bar over each one that has been hit."
  [^js ctx robbers cam-x cam-y]
  (doseq [{:keys [x y hp] :as r} robbers
          :let [sx (js/Math.round (- x cam-x))
                sy (js/Math.round (- y cam-y))]]
    (draw-robber ctx r cam-x cam-y)
    (when (< hp robber-hp)
      (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
      (.fillRect ctx (- sx 1) (- sy 10) (+ robber-w 2) 5)
      (set! (.-fillStyle ctx) "#e53935")
      (.fillRect ctx sx (- sy 9) (js/Math.round (* robber-w (/ (max 0 hp) robber-hp))) 3))))

;; the newspaper: a paperboy sells a paper about the day before once the player has
;; made friends with enough Fluffies

(def paper-price 5)
(def paperboy-friends 6)
(def paperboy-w 18)
(def paperboy-h 38)

(defn- day-number
  "Returns the number of the day, counted from 1, that game time `clock` falls in. A
  day starts at sunrise and runs through the night after it."
  [clock]
  (inc (js/Math.floor (/ (+ clock start-ms) (+ day-ms night-ms)))))

(defn friend-count
  "Counts the Fluffies that have become the player's friends."
  [fluffies]
  (count (filter #(or (:friend? %) (:following? %) (:home %)) fluffies)))

(defn- roll-day
  "At sunrise keeps the finished day's log as `:yesterday` and starts an empty one."
  [st clock]
  (let [n (day-number clock)]
    (cond
      (nil? (:day st)) (assoc st :day n)
      (= n (:day st))  st
      :else (assoc st :day n :yesterday {:day (:day st) :log (or (:today st) {})} :today {}))))

(defn- spawn-paperboy
  "Returns a paperboy on the surface 18 to 27 tiles to one side of `px`, facing it."
  [^js tiles px]
  (let [side (if (< (js/Math.random) 0.5) -1 1)
        tx (-> (+ (js/Math.floor (/ px T)) (* side (+ 18 (rand-int 10)))) (max 3) (min (- W 5)))]
    {:x (+ (* tx T) 1) :y (- (* T (min (ground-row tiles tx) (ground-row tiles (inc tx)))) paperboy-h 0.01)
     :w paperboy-w :h paperboy-h :vx 0 :vy 0 :ground? false
     :dir (- side) :home-x (* tx T) :turn-in (+ 240 (rand-int 240)) :walked 0}))

(defn- by-paperboy?
  "Checks that the player stands next to the paperboy."
  [{:keys [player paperboy realm]}]
  (and paperboy (= realm :overworld)
       (< (js/Math.abs (- (+ (:x paperboy) (/ paperboy-w 2)) (+ (:x player) (/ player-w 2)))) (* 3 T))
       (< (js/Math.abs (- (:y paperboy) (:y player))) (* 3 T))))

(defn- step-paperboy
  "Moves paperboy `b` one frame. He strolls back and forth within 40 tiles of where he
  came in, hops over steps, and stops to sell when the player is next to him."
  [tiles b player dt]
  (let [b (loop [b b n 0]
            (if (and (< n H) (box-hits? tiles (:x b) (:y b) paperboy-w paperboy-h))
              (recur (assoc b :y (- (:y b) T) :vy 0) (inc n))
              b))
        {:keys [x dir turn-in home-x ground?]} b
        near? (and (< (js/Math.abs (- (+ x (/ paperboy-w 2)) (+ (:x player) (/ player-w 2)))) (* 2.5 T))
                   (< (js/Math.abs (- (:y b) (:y player))) (* 3 T)))
        turn? (<= turn-in 0)
        dir (cond (> (js/Math.abs (- x home-x)) (* 40 T)) (if (> x home-x) -1 1)
                  turn? (if (< (js/Math.random) 0.5) -1 1)
                  :else dir)
        vx (if near? 0 (* dir 0.8))
        vy (min max-fall (+ (:vy b) (* gravity dt)))
        moved (-> b
                  (assoc :dir (if near? (if (< (:x player) x) -1 1) dir) :vx vx :vy vy
                         :turn-in (if turn? (+ 240 (rand-int 360)) (- turn-in dt)))
                  (as-> s (move-x tiles s (* vx dt)))
                  (as-> s (move-y tiles s (* vy dt)))
                  (update :x #(max T (min (* T (- W 2)) %))))
        blocked? (and ground? (zero? (:vx moved)) (not (zero? vx)))]
    (cond-> (update moved :walked + (js/Math.abs (* (:vx moved) dt)))
      blocked? (assoc :vy -7.5))))

(defn- step-paper
  "Starts a new day's log at sunrise, and on each day after the first sends a paperboy
  to the overworld's surface once `paperboy-friends` Fluffies are the player's friends.
  He leaves at nightfall."
  [st clock dt]
  (let [{:keys [realm player fluffies paperboy yesterday] :as st} (roll-day st clock)]
    (if (not= realm :overworld)
      st
      (let [tiles (get-in st [:worlds :overworld :tiles])
            day? (:day? (time-of-day clock))]
        (cond
          (not day?) (dissoc st :paperboy)
          paperboy (update st :paperboy #(step-paperboy tiles % player dt))
          (and yesterday (not= (:day st) (:paperboy-day st))
               (>= (friend-count fluffies) paperboy-friends))
          (assoc st :paperboy (spawn-paperboy tiles (+ (:x player) (/ player-w 2))) :paperboy-day (:day st))
          :else st)))))

(defn paper-stand
  "Answers the E key for the newspaper: closes an open paper, or next to the paperboy
  buys the paper about yesterday for `paper-price` coins, opening it again for free
  once bought. Returns nil when neither applies."
  [{:keys [paper-open? paper yesterday coins player] :as st}]
  (let [popup (fn [st text] (update st :popups conj {:text text :x (+ (:x player) (/ player-w 2))
                                                      :y (- (:y player) 10) :life 120}))]
    (cond
      paper-open? (assoc st :paper-open? false)
      (not (by-paperboy? st)) nil
      (= (:day paper) (:day yesterday)) (assoc st :paper-open? true)
      (< (or coins 0) paper-price) (popup st (str "The paper costs " paper-price " coins"))
      :else (-> st (add-coins (- paper-price)) (assoc :paper yesterday :paper-open? true)))))

(defn- paperboy-label
  "Returns the hint over the paperboy when the player stands next to him, or nil."
  [{:keys [paper yesterday] :as st}]
  (when (by-paperboy? st)
    (if (= (:day paper) (:day yesterday))
      "E: read the paper"
      (str "E: newspaper, " paper-price " coins"))))

(defn draw-paperboy
  "Draws paperboy `b` in a red cap and a blue shirt with a satchel of papers, with the
  hint `label` over him when it is given."
  [^js ctx {:keys [x y dir vx ground? walked]} cam-x cam-y label]
  (let [sx (js/Math.round (- x cam-x))
        sy (js/Math.round (- y cam-y))
        moving? (and ground? (> (js/Math.abs vx) 0.3))
        swing (if moving? (js/Math.round (* 3 (js/Math.sin (/ walked 6)))) 0)
        rect (fn [color rx ry rw rh]
               (set! (.-fillStyle ctx) color)
               (.fillRect ctx (if (pos? dir) (+ sx rx) (- (+ sx paperboy-w) rx rw)) (+ sy ry) rw rh))]
    (rect "#a0783c" -4 19 8 10)
    (rect "#f5f5f5" -3 17 6 3)
    (rect "#4a4a52" 3 28 5 10)
    (rect "#4a4a52" 10 28 5 10)
    (rect "#222222" (+ 3 swing) 35 5 3)
    (rect "#222222" (- 10 swing) 35 5 3)
    (rect "#3f7fd9" 2 15 14 14)
    (rect "#7a4a24" 4 15 3 14)
    (rect "#f1c27d" (+ 8 (- swing)) 17 4 11)
    (rect "#f1c27d" 3 3 13 13)
    (rect "#1d1d1d" 12 8 2 3)
    (rect "#d84315" 2 0 15 5)
    (rect "#bf360c" 12 4 8 2)
    (when label
      (.save ctx)
      (set! (.-font ctx) "bold 11px system-ui, sans-serif")
      (set! (.-textAlign ctx) "center")
      (let [w (+ 12 (.-width (.measureText ctx label)))
            cx (+ sx (/ paperboy-w 2))]
        (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
        (.fillRect ctx (- cx (/ w 2)) (- sy 24) w 17)
        (set! (.-fillStyle ctx) "#ffffff")
        (.fillText ctx label cx (- sy 12)))
      (.restore ctx))))

(def tame-reach (* 8 T))

(defn- tame-alien
  "Turns the nearest alien within `tame-reach` of the player into a friendly Fluffy that
  keeps the alien's looks and follows the player. Returns nil when no alien is near."
  [{:keys [player realm aliens] :as st}]
  (let [px (+ (:x player) (/ player-w 2))
        py (+ (:y player) (/ player-h 2))
        dist (fn [{:keys [x y]}]
               (js/Math.hypot (wrap-delta (- (+ x (/ alien-w 2)) px) (* T space-w))
                              (wrap-delta (- (+ y (/ alien-h 2)) py) (* T space-h))))
        near (when (= :space realm)
               (->> (map-indexed vector aliens)
                    (filter (fn [[_ a]] (<= (dist a) tame-reach)))
                    (sort-by (fn [[_ a]] (dist a)))
                    first))]
    (when-let [[i {:keys [x y phase]}] near]
      (let [cx (+ x (/ alien-w 2))
            cy (+ y (/ alien-h 2))]
        (-> st
            (update :aliens (fn [as] (into (subvec as 0 i) (subvec as (inc i)))))
            (update :fluffies (fnil conj []) {:realm :space :alien? true :phase phase
                                              :x cx :y cy :hx cx :hy cy :following? true})
            (update :particles into (for [k (range 14)
                                          :let [a (* k 0.45)]]
                                      {:x cx :y cy :vx (* 2.5 (js/Math.cos a)) :vy (- (* 2.5 (js/Math.sin a)) 1)
                                       :life 35 :color (if (even? k) "#7cff9a" "#ffffff")}))
            (update :popups conj {:text "The alien is tame and follows you!" :x cx :y (- y 10) :life 150}))))))

(defn- draw-stars [^js ctx stars t cam-x cam-y]
  (doseq [{:keys [x y phase]} stars
          :let [sx (- x cam-x)
                sy (+ (- y cam-y) (* 2 (js/Math.sin (+ (/ t 400) phase))))
                spin (+ (/ t 900) phase)
                glow (+ 0.25 (* 0.15 (js/Math.sin (+ (/ t 200) phase))))]]
    (disc ctx (str "rgba(255,230,120," glow ")") sx sy 12)
    (set! (.-fillStyle ctx) "#ffd54a")
    (.beginPath ctx)
    (doseq [k (range 10)
            :let [a (+ spin (* k (/ js/Math.PI 5)))
                  r (if (even? k) 8 3.5)]]
      (if (zero? k)
        (.moveTo ctx (+ sx (* r (js/Math.cos a))) (+ sy (* r (js/Math.sin a))))
        (.lineTo ctx (+ sx (* r (js/Math.cos a))) (+ sy (* r (js/Math.sin a))))))
    (.closePath ctx)
    (.fill ctx)
    (disc ctx "#fff6c0" sx sy 2)))

(defn- draw-drones [^js ctx drones t cam-x cam-y]
  (doseq [{:keys [x y flash mode]} drones
          :let [sx (js/Math.round (- x cam-x))
                sy (js/Math.round (- y cam-y))]]
    (set! (.-fillStyle ctx) "#3b4048")
    (.fillRect ctx (+ sx 2) (+ sy 2) 4 3)
    (.fillRect ctx (+ sx drone-w -6) (+ sy 2) 4 3)
    (set! (.-fillStyle ctx) "rgba(200,210,220,0.8)")
    (let [spin (js/Math.abs (js/Math.sin (/ t 30)))
          blade (+ 2 (* 7 spin))]
      (doseq [cx [(+ sx 4) (+ sx drone-w -4)]]
        (.fillRect ctx (- cx blade) sy (* 2 blade) 2)))
    (set! (.-fillStyle ctx) (if (pos? flash) "#ffffff" "#7d8794"))
    (.beginPath ctx)
    (.roundRect ctx (+ sx 3) (+ sy 5) (- drone-w 6) 7 3)
    (.fill ctx)
    (set! (.-fillStyle ctx) "#1d2228")
    (.fillRect ctx (+ sx 8) (+ sy 12) 2 2)
    (.fillRect ctx (+ sx drone-w -10) (+ sy 12) 2 2)
    (set! (.-fillStyle ctx) "#46e0ff")
    (.fillRect ctx (+ sx (/ drone-w 2) -2) (+ sy 7) 4 3)
    (when (or (= mode :ground) (< (mod t 900) 450))
      (set! (.-fillStyle ctx) "#ff3b3b")
      (.fillRect ctx (+ sx (/ drone-w 2) -1) (+ sy 3) 2 2))))

(defn- draw-ship
  "Draws the spaceship as a silver rocket on three legs, with a flame under it while it
  is landing."
  [^js ctx {:keys [x y landing?]} t cam-x cam-y]
  (let [sx (js/Math.round (- x cam-x))
        sy (js/Math.round (- y cam-y))
        cx (+ sx (/ ship-w 2))]
    (when landing?
      (let [f (* 6 (js/Math.abs (js/Math.sin (/ t 40))))]
        (set! (.-fillStyle ctx) "#ff7a1a")
        (.beginPath ctx)
        (.moveTo ctx (- cx 9) (+ sy 52))
        (.lineTo ctx (+ cx 9) (+ sy 52))
        (.lineTo ctx cx (+ sy 74 f))
        (.fill ctx)
        (set! (.-fillStyle ctx) "#ffd54a")
        (.beginPath ctx)
        (.moveTo ctx (- cx 5) (+ sy 52))
        (.lineTo ctx (+ cx 5) (+ sy 52))
        (.lineTo ctx cx (+ sy 64 f))
        (.fill ctx)))
    (set! (.-fillStyle ctx) "#5a6270")
    (doseq [[lx fx] [[(- cx 14) (- cx 26)] [(+ cx 14) (+ cx 26)]]]
      (.beginPath ctx)
      (.moveTo ctx lx (+ sy 36))
      (.lineTo ctx fx (+ sy ship-h))
      (.lineTo ctx (+ fx (if (< fx cx) 5 -5)) (+ sy ship-h))
      (.lineTo ctx lx (+ sy 44))
      (.fill ctx))
    (.fillRect ctx (- cx 2) (+ sy 44) 4 16)
    (set! (.-fillStyle ctx) "#d9dee6")
    (.beginPath ctx)
    (.moveTo ctx cx sy)
    (.quadraticCurveTo ctx (+ cx 16) (+ sy 14) (+ cx 14) (+ sy 50))
    (.lineTo ctx (- cx 14) (+ sy 50))
    (.quadraticCurveTo ctx (- cx 16) (+ sy 14) cx sy)
    (.fill ctx)
    (set! (.-fillStyle ctx) "#e53935")
    (.beginPath ctx)
    (.moveTo ctx cx sy)
    (.quadraticCurveTo ctx (+ cx 8) (+ sy 5) (+ cx 10) (+ sy 12))
    (.lineTo ctx (- cx 10) (+ sy 12))
    (.quadraticCurveTo ctx (- cx 8) (+ sy 5) cx sy)
    (.fill ctx)
    (.fillRect ctx (- cx 14) (+ sy 46) 28 4)
    (disc ctx "#2b3a55" cx (+ sy 25) 6)
    (disc ctx "#7fd4ff" cx (+ sy 25) 4)
    (set! (.-fillStyle ctx) "#ffffff")
    (.fillRect ctx (- cx 2) (+ sy 22) 2 2)))

(defn- draw-space-sky
  "Draws black space with steady stars behind everything already on the canvas."
  [^js ctx vw vh cam-x cam-y]
  (set! (.-globalCompositeOperation ctx) "destination-over")
  (doseq [i (range 160)]
    (let [sx (mod (- (* (hash2 i 71) 2400) (* cam-x 0.1)) vw)
          sy (mod (- (* (hash2 i 13) 1600) (* cam-y 0.1)) vh)
          big? (< (hash2 i 5) 0.12)]
      (set! (.-fillStyle ctx) (if (< (hash2 i 3) 0.2) "#ffe9b0" "#e8eeff"))
      (.fillRect ctx (js/Math.round sx) (js/Math.round sy) (if big? 2 1) (if big? 2 1))))
  (set! (.-fillStyle ctx) "#05060d")
  (.fillRect ctx 0 0 vw vh)
  (set! (.-globalCompositeOperation ctx) "source-over"))

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

(defn- fluffy-hue
  "Returns the red-yellow-blue wheel hue of Fluffy `f`, which starts blue, or red for
  the lost friend of a quest."
  [f]
  (or (:hue f) (if (:red? f) 0 240)))

(defn- mix-hue
  "Returns the hue halfway between `a` and `b` along the shorter way round the wheel."
  [a b]
  (let [d (- (mod (+ (- b a) 540) 360) 180)]
    (mod (+ a (/ d 2)) 360)))

(def ^:private wheel
  [[0 0] [60 30] [120 60] [180 120] [240 240] [300 280] [360 360]])

(defn- screen-hue
  "Converts a red-yellow-blue wheel hue to the hue of an HSL colour."
  [h]
  (some (fn [[[a ra] [b rb]]]
          (when (<= a h b) (+ ra (* (- rb ra) (/ (- h a) (- b a))))))
        (partition 2 1 wheel)))

(def ^:private hue-colors
  (memoize (fn [hue]
             (let [h (js/Math.round (screen-hue hue))]
               [(str "hsl(" h ",100%,90%)") (str "hsl(" h ",72%,52%)") (str "hsl(" h ",100%,72%)")]))))

(defn- fluffy-colors
  "Returns the wing, body and shine colours of Fluffy `f`."
  [f]
  (cond
    (:hue f) (hue-colors (:hue f))
    (:red? f) ["#ffd6d2" "#e0453d" "#ff8a80"]
    :else ["#cfe6ff" "#3d7be8" "#6fa3ff"]))

(defn- paint-fluffy
  "Paints the Fluffy under the mouse within `fluffy-reach` of the player with the paint
  of `hue`, mixing it halfway with the Fluffy's colour. Returns nil when the mouse
  points at no Fluffy in reach."
  [{:keys [player realm fluffies] :as st} hue]
  (let [px (+ (:x player) (/ player-w 2))
        py (+ (:y player) (/ player-h 2))
        [ax ay] (:aim-at player)
        hit (when ax
              (->> (map-indexed vector fluffies)
                   (filter (fn [[_ f]]
                             (and (= realm (:realm f))
                                  (not (:alien? f))
                                  (<= (js/Math.hypot (- (:x f) ax) (- (:y f) ay)) (+ fluffy-r 8))
                                  (<= (js/Math.hypot (- (:x f) px) (- (:y f) py)) fluffy-reach))))
                   (sort-by (fn [[_ f]] (js/Math.hypot (- (:x f) ax) (- (:y f) ay))))
                   first))]
    (when-let [[i f] hit]
      (let [painted (assoc f :hue (mix-hue (fluffy-hue f) hue))]
        (-> st
            (assoc-in [:fluffies i] painted)
            (update :particles into
                    (for [k (range 10)
                          :let [a (* k 0.628)]]
                      {:x (:x f) :y (:y f) :vx (* 2 (js/Math.cos a)) :vy (- (* 2 (js/Math.sin a)) 1)
                       :life 30 :color (second (fluffy-colors painted))})))))))

(defn- quest-friend?
  "Checks that Fluffy `i` is the lost friend of the quest in progress."
  [{:keys [quest]} i]
  (and (= :active (:stage quest)) (= i (:friend quest))))

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
            (cond-> (and follow? (quest-friend? st i)) (assoc-in [:quest :found?] true))
            ;; the first time a Fluffy follows, it becomes a friend for good
            (cond-> (and follow? (not (:friend? f))) (-> (assoc-in [:fluffies i :friend?] true)
                                                         (tally :friends 1)))
            (update-in [:fluffies i] assoc :following? follow? :hx (:x f) :hy (:y f))
            (update :popups conj {:text (cond
                                          (:alien? f) (if follow? "The tame alien follows you!" "The tame alien waits here")
                                          follow? "A Fluffy is following you!"
                                          :else "The Fluffy waits here")
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
     (let [lost? (quest-friend? st i)
           free (->> (map-indexed vector (:houses st))
                     (filter (fn [[_ h]]
                               (let [[hx hy] (house-hole h)]
                                 (and (= realm (:realm h)) (nil? (:resident h))
                                      (or (not lost?) (next-door? st h))
                                      (<= (js/Math.hypot (- hx (:x f)) (- hy (:y f))) settle-reach)))))
                     first)]
       (if-let [[hi h] free]
         (let [[hx hy] (house-hole h)
               top (- (house-bottom h) house-h 14)]
           (cond-> (-> st
                       (tally :moved-in 1)
                       (assoc-in [:houses hi :resident] i)
                       (update-in [:fluffies i] assoc :following? false :home hi :hx hx :hy hy))
             (not lost?)
             (-> (add-coins house-reward)
                 (update :popups conj {:text (str "A Fluffy moved in! +" house-reward) :icon :coin
                                       :x hx :y top :life 150}))
             lost?
             (-> (add-coins quest-reward)
                 (update-in [:today :quests] (fnil conj []) {:kind :friend})
                 (assoc-in [:quest :stage] :done)
                 (update :popups conj {:text (str "Quest complete! The friends are neighbours. +" quest-reward)
                                       :icon :coin :x hx :y top :life 240}))))
         st)))
   st
   (when (seq houses)
     (filter (fn [[_ f]] (and (:following? f) (= realm (:realm f))))
             (map-indexed vector fluffies)))))

(def quest-wait (* 5 60000))

;; besides asking for a lost friend, a Fluffy can feel sick, wish for an item or wish
;; for a new colour
(def wish-items
  [{:id :ruby :count 1} {:id :torch :count 5} {:id :bar :count 2} {:id :hay :count 3}])

(def wish-colors
  [{:name "green" :hue 180} {:name "orange" :hue 60} {:name "purple" :hue 300}
   {:name "red" :hue 0} {:name "yellow" :hue 120} {:name "blue" :hue 240}])

(def wish-reward 50)

(def wish-gifts
  [{:id :potion :count 2} {:id :ruby :count 1} {:id :rope :count 8} {:id :torch :count 5}])

(defn- hue-gap [a b]
  (js/Math.abs (- (mod (+ (- b a) 540) 360) 180)))

(defn- item-name [id]
  (or (:name (first (filter #(= id (:id %)) loot)))
      ({:hay "Hay" :ore "Iron Ore"} id)
      (name id)))

(defn- item-plural [id]
  (case id :hay "Hay" :torch "Torches" (str (item-name id) "s")))

(defn- new-wish
  "Returns a quest offered by the Fluffy living in house `hi`: a lost friend, a sick
  Fluffy wanting a Healing Potion, a wish for an item, or a wish for a colour other
  than the one it has."
  [{:keys [houses fluffies]} hi]
  (let [f (get fluffies (get-in houses [hi :resident]))
        kind (rand-nth [:friend :sick :item :color])]
    (case kind
      :friend {:stage :offered :giver hi :kind :friend}
      :sick {:stage :offered :giver hi :kind :sick :item :potion :count 1}
      :item (let [{:keys [id count]} (rand-nth wish-items)]
              {:stage :offered :giver hi :kind :item :item id :count count})
      :color (let [c (rand-nth (filterv #(> (hue-gap (:hue %) (fluffy-hue (or f {}))) 30) wish-colors))]
               {:stage :offered :giver hi :kind :color :color (:name c) :hue (:hue c)}))))

(defn wish-title
  "Returns what the Fluffy of quest `q` says it wants."
  [{:keys [kind item count color]}]
  (case kind
    :sick "I feel sick!"
    :item (str "I'd love " count " " (if (> count 1) (item-plural item) (item-name item)) "!")
    :color (str "I want to be " color "!")
    "My red friend is lost"))

(defn- offer-quest
  "Lets the Fluffy of a random lived-in house ask for help at a random moment within
  `quest-wait` of the game clock, counted from the start or from the last finished
  quest. Only one quest exists at a time."
  [{:keys [quest houses next-quest-at clock] :as st}]
  (let [clock (or clock 0)
        homes (keep-indexed (fn [i h] (when (:resident h) i)) houses)]
    (cond
      (= :done (:stage quest))
      (assoc st :quest nil :next-quest-at (+ clock (* quest-wait (js/Math.random))))

      (or quest (empty? homes)) st

      (nil? next-quest-at)
      (assoc st :next-quest-at (+ clock (* quest-wait (js/Math.random))))

      (>= clock next-quest-at)
      (-> st
          (assoc :quest (new-wish st (rand-nth homes)))
          (dissoc :next-quest-at))

      :else st)))

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
    (if (#{:sick :item :color} (get-in st [:quest :kind]))
      (let [{:keys [kind] :as q} (:quest st)
            h (get (:houses st) (:giver q))
            [hx _] (house-hole h)]
        (-> st
            (assoc-in [:quest :stage] :active)
            (update :popups conj {:text (case kind
                                          :sick "I feel sick! Could you bring me a Healing Potion?"
                                          :item (str (wish-title q) " Could you bring them to my house?")
                                          :color (str (wish-title q) " Could you paint me with the shop's paints?"))
                                  :x hx :y (- (house-bottom h) house-h 14) :life 300})))
    (let [{:keys [tiles surface]} (get-in st [:worlds :overworld])
          h (get (:houses st) (get-in st [:quest :giver]))
          [hx _] (house-hole h)
          friend (lost-friend tiles surface (js/Math.floor (/ hx T)))]
      (-> st
          (update :fluffies (fnil conj []) friend)
          (update :quest assoc :stage :active :friend (count (:fluffies st)))
          (update :popups conj {:text "My red friend is lost! Please find it and build it a home near mine."
                                :x hx :y (- (house-bottom h) house-h 14) :life 300}))))))

(defn- wish-house-near?
  "Checks that the player stands by the house whose Fluffy made the wish in progress."
  [{:keys [quest houses player realm]}]
  (when-let [h (and (= :active (:stage quest)) (get houses (:giver quest)))]
    (let [[hx hy] (house-hole h)]
      (and (= realm (:realm h))
           (<= (js/Math.hypot (- hx (+ (:x player) (/ player-w 2)))
                              (- hy (+ (:y player) (/ player-h 2))))
               quest-reach)))))

(defn- grant-wish
  "Finishes the wish in progress: pays `wish-reward` coins and a random gift."
  [{:keys [quest houses] :as st}]
  (let [h (get houses (:giver quest))
        [hx _] (house-hole h)
        {:keys [id count]} (rand-nth wish-gifts)]
    (-> st
        (add-coins wish-reward)
        (stash [(assoc (select-keys (first (filter #(= id (:id %)) loot)) [:id :name :icon]) :count count)])
        (tally :wishes 1)
        (update-in [:today :quests] (fnil conj []) (select-keys quest [:kind :item :count :color]))
        (assoc-in [:quest :stage] :done)
        (update :popups conj {:text (str "Wish granted! Thank you! +" wish-reward " and " count " " (item-name id))
                              :icon :coin :x hx :y (- (house-bottom h) house-h 14) :life 240}))))

(defn- give-wish
  "Hands the item a Fluffy wished for to it when the player stands by its house and
  has enough in the bag, or says what is still missing. Returns nil away from the
  house or when the wish is not for an item."
  [{:keys [quest bag] :as st}]
  (when (and (#{:sick :item} (:kind quest)) (wish-house-near? st))
    (let [{:keys [item count]} quest
          h (get (:houses st) (:giver quest))
          [hx _] (house-hole h)]
      (if (>= (bag-count bag item) count)
        (-> st (take-from-bag item count) grant-wish)
        (update st :popups conj {:text (str "Bring " count " " (if (> count 1) (item-plural item) (item-name item)) " from your bag")
                                 :x hx :y (- (house-bottom h) house-h 14) :life 120})))))

(defn- check-colour-wish
  "Grants a colour wish as soon as the Fluffy has been painted the colour it wanted."
  [{:keys [quest houses fluffies] :as st}]
  (if-let [f (and (= :active (:stage quest)) (= :color (:kind quest))
                  (get fluffies (get-in houses [(:giver quest) :resident])))]
    (if (<= (hue-gap (fluffy-hue f) (:hue quest)) 25)
      (grant-wish st)
      st)
    st))

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
          :when (and (= realm (:realm h)) (not (off-screen? ctx (- x cam-x) (- (house-bottom h) cam-y) 80)))
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

(def mirror-w 32)
(def mirror-h 48)
;; how far from a mirror the player still shows in it
(def mirror-sight (* 6 T))

(defn- place-mirror
  "Stands a Magic Mirror two tiles wide on open ground at the tile under the mouse,
  within `reach` of the hand. Returns the new state, or nil when there is no room there."
  [{:keys [player realm mirrors] :as st} tiles]
  (let [[hx hy] (hand player)
        [ax ay] (or (:aim-at player) [hx hy])
        tx (js/Math.floor (/ ax T))
        ;; a mirror aimed into the ground rises onto it, and one aimed in the air drops onto it
        ty (loop [ty (js/Math.floor (/ ay T)) n 0]
             (if (and (< n 3) (solid? (tile-at tiles tx ty)))
               (recur (dec ty) (inc n))
               ty))
        cols [tx (inc tx)]
        ground (fn [c] (first (filter #(solid? (tile-at tiles c %)) (range ty (+ ty 6)))))
        grounds (map ground cols)
        ty (when (every? some? grounds) (dec (apply min grounds)))
        x (* T tx)
        bottom (* T (inc (or ty 0)))
        room? (and ty
                   (every? #(<= % (+ ty 2)) grounds)
                   (every? (fn [[c dy]] (= air (tile-at tiles c (- ty dy))))
                           (for [c cols dy (range 3)] [c dy]))
                   (<= (js/Math.hypot (- (+ x (/ mirror-w 2)) hx) (- (- bottom (/ mirror-h 2)) hy)) (+ reach 24))
                   (not-any? (fn [m] (and (= realm (:realm m))
                                          (< (js/Math.abs (- (:x m) x)) mirror-w)
                                          (< (js/Math.abs (- (:y m) (- bottom mirror-h))) mirror-h)))
                             mirrors))]
    (when room?
      (update st :mirrors (fnil conj []) {:realm realm :x x :y (- bottom mirror-h)}))))

(defn- aimed-mirror
  "Returns the index of the mirror in the player's realm that the pickaxe points at
  within reach, or nil."
  [{:keys [player realm mirrors]}]
  (first (keep-indexed (fn [i m] (when (and (= realm (:realm m)) (box-aimed? player m mirror-w mirror-h)) i))
                       mirrors)))

(defn- take-mirror
  "Takes mirror `i` back into the bag, unless the bag has no room for it."
  [{:keys [player] :as st} i]
  (if (bag-slot (:bag st) :mirror)
    (-> st
        (update :mirrors (fn [ms] (into (subvec ms 0 i) (subvec ms (inc i)))))
        (stash [(assoc (item-def :mirror) :count 1)]))
    (update st :popups conj {:text "The bag is full" :x (+ (:x player) (/ player-w 2))
                             :y (- (:y player) 10) :life 90})))

(defn- draw-mirrors
  "Draws the Magic Mirrors of `realm` as gold-framed standing mirrors. A player within
  `mirror-sight` shows in the glass, turned the other way and fading with distance."
  [^js ctx mirrors realm player suit? cam-x cam-y]
  (doseq [{:keys [x y] :as m} mirrors
          :when (and (= realm (:realm m)) (not (off-screen? ctx (- x cam-x) (- y cam-y) 60)))
          :let [sx (js/Math.round (- x cam-x))
                sy (js/Math.round (- y cam-y))
                gx (+ sx 7) gy (+ sy 5) gw 18 gh 35
                dx (- (+ (:x player) (/ player-w 2)) (+ x (/ mirror-w 2)))
                dy (- (+ (:y player) player-h) (+ y mirror-h))
                near? (and (< (js/Math.abs dx) mirror-sight) (< (js/Math.abs dy) (* 2 T)))
                rect (fn [color rx ry rw rh]
                       (set! (.-fillStyle ctx) color)
                       (.fillRect ctx (+ sx rx) (+ sy ry) rw rh))
                g (.createLinearGradient ctx gx gy (+ gx gw) (+ gy gh))]]
    (rect "#8a6a10" 6 44 6 4)
    (rect "#8a6a10" 20 44 6 4)
    (rect "#c9a227" 6 0 20 2)
    (rect "#c9a227" 4 2 24 42)
    (rect "#8a6a10" 4 42 24 2)
    (.addColorStop g 0 "#cfeaff")
    (.addColorStop g 0.5 "#eef8ff")
    (.addColorStop g 1 "#9fcff0")
    (set! (.-fillStyle ctx) g)
    (.fillRect ctx gx gy gw gh)
    (when near?
      (.save ctx)
      (.beginPath ctx)
      (.rect ctx gx gy gw gh)
      (.clip ctx)
      (set! (.-globalAlpha ctx) (- 0.9 (* 0.5 (/ (js/Math.abs dx) mirror-sight))))
      ;; the reflection shifts a little towards the player's side but stays in the glass
      (.translate ctx (+ gx (/ gw 2) (max -5 (min 5 (* 0.08 dx)))) (+ gy gh))
      (.scale ctx 0.8 0.8)
      (draw-player ctx (assoc player :x (- (/ player-w 2)) :y (- player-h) :facing (- (:facing player 1)))
                   0 0 suit?)
      (.restore ctx))
    (set! (.-fillStyle ctx) "rgba(255,255,255,0.6)")
    (.fillRect ctx (+ gx 3) (+ gy 4) 2 10)
    (.fillRect ctx (+ gx 6) (+ gy 3) 1 5)))

;; frames before a rocket bursts, about 200 pixels above the player
(def rocket-fuse 34)

(defn- launch-rocket
  "Sends a firework rocket up from the player."
  [{:keys [player] :as st}]
  (update st :rockets (fnil conj [])
          {:x (+ (:x player) (/ player-w 2)) :y (:y player) :vy -6 :fuse rocket-fuse :hue (rand-int 360)}))

(defn- step-rockets
  "Flies each rocket up with a trail of sparks and bursts it into a ring of coloured
  sparks when its fuse runs out."
  [st dt]
  (if (empty? (:rockets st))
    st
    (let [moved (mapv #(-> % (update :y + (* (:vy %) dt)) (update :fuse - dt)) (:rockets st))
          trail (for [{:keys [x y]} moved]
                  {:x (+ x (- (js/Math.random) 0.5)) :y (+ y 8) :vx (- (* 0.6 (js/Math.random)) 0.3) :vy 1
                   :life 12 :color "#ffb347"})
          burst (for [{:keys [x y hue fuse]} moved
                      :when (<= fuse 0)
                      k (range 48)
                      :let [a (* 2 js/Math.PI (/ k 48))
                            s (+ 2 (* 3 (js/Math.random)))
                            h (mod (+ hue (* 120 (mod k 3))) 360)]]
                  {:x x :y y :vx (* s (js/Math.cos a)) :vy (- (* s (js/Math.sin a)) 1)
                   :life (+ 40 (rand-int 30)) :color (str "hsl(" h ",100%,65%)")})]
      (-> st
          (assoc :rockets (filterv #(pos? (:fuse %)) moved))
          (update :particles into (concat trail burst))))))

(defn- draw-rockets [^js ctx rockets cam-x cam-y]
  (doseq [{:keys [x y]} rockets
          :let [sx (js/Math.round (- x cam-x))
                sy (js/Math.round (- y cam-y))]]
    (set! (.-fillStyle ctx) "#e53935")
    (.fillRect ctx (- sx 2) (- sy 5) 5 10)
    (set! (.-fillStyle ctx) "#ffffff")
    (.fillRect ctx (- sx 2) (- sy 1) 5 2)
    (set! (.-fillStyle ctx) "#ffd54a")
    (.fillRect ctx (- sx 1) (+ sy 5) 3 3)))

(defn- draw-house-rims
  "Draws the wooden rim and perch of each house opening over its resident."
  [^js ctx houses realm cam-x cam-y]
  (doseq [h houses
          :let [[hx hy] (house-hole h)
                sx (- hx cam-x)
                sy (- hy cam-y)]
          :when (and (= realm (:realm h)) (not (off-screen? ctx sx sy 40)))]
    (set! (.-strokeStyle ctx) "#6b4423")
    (set! (.-lineWidth ctx) 3)
    (.beginPath ctx)
    (.arc ctx sx sy (+ hole-r 1) 0 (* 2 js/Math.PI))
    (.stroke ctx)
    (set! (.-fillStyle ctx) "#6b4423")
    (.fillRect ctx (- sx 9) (+ sy hole-r 1) 18 3)))

(declare paint-fluffy!)

(defn- draw-fluffies
  "Draws the Fluffies of `realm` as fuzzy blue balls with flapping wings. A Fluffy
  inside its house is drawn a little smaller with its wings folded so it fits."
  [^js ctx fluffies realm clock cam-x cam-y]
  ;; a tamed alien behaves as a Fluffy but keeps its alien looks
  (doseq [{:keys [x y phase] :as f} fluffies
          :when (and (= realm (:realm f)) (:alien? f))
          :let [bob (* 2 (js/Math.sin (+ (/ clock 300) phase)))]]
    (draw-alien ctx (js/Math.round (- x cam-x (/ alien-w 2)))
                (js/Math.round (+ (- y cam-y (/ alien-h 2)) bob)) 0 phase clock))
  (let [scale (device-scale ctx)]
    (doseq [{:keys [x y phase out?] :as f} fluffies
            :let [sx (js/Math.round (- x cam-x))
                  sy (js/Math.round (- y cam-y))]
            :when (and (= realm (:realm f)) (not (:alien? f)) (not (off-screen? ctx sx sy 30)))
            :let [home (and (:home f) (not out?))
                  colors (fluffy-colors f)
                  ;; the wings take one of nine shapes, so each look is drawn only once
                  flap (/ (js/Math.round (* 8 (js/Math.abs (js/Math.sin (+ (/ clock 70) phase))))) 8)
                  sprite (cached-sprite (str "fluffy|" (first colors) (second colors) "|" home "|" (if home 0 flap))
                                        scale 48 40 24 20
                                        #(paint-fluffy! % colors home flap))]]
      (.drawImage ctx sprite (- sx 24) (- sy 20) 48 40))))

(defn- paint-fluffy!
  "Draws a Fluffy with colours `[wing body shine]` centred on the origin, wings spread
  by `flap` from 0 to 1, or a little smaller with its wings folded when `home`."
  [^js ctx [wing body shine] home flap]
  (let [disc (fn [cx cy r]
               (.beginPath ctx)
               (.arc ctx cx cy r 0 (* 2 js/Math.PI))
               (.fill ctx))]
    (.save ctx)
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
            :let [sx (- (:x f) cam-x) sy (- (:y f) cam-y)]
            :when (and (= realm (:realm f)) (not (off-screen? ctx sx sy 30)))
            :let [[inner outer] (if-let [h (:hue f)]
                                  (let [h (js/Math.round (screen-hue h))]
                                    [(str "hsla(" h ",100%,75%,0.5)") (str "hsla(" h ",100%,75%,0)")])
                                  (if (:red? f)
                                    ["rgba(255,140,130,0.55)" "rgba(255,140,130,0)"]
                                    ["rgba(140,190,255,0.45)" "rgba(140,190,255,0)"]))]]
      (.drawImage ctx (glow-sprite (device-scale ctx) 26 inner outer) (- sx 26) (- sy 26) 52 52)
      (when (and (not (:following? f)) (not (:home f))
                 (<= (js/Math.hypot (- (:x f) px) (- (:y f) py)) fluffy-reach))
        (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
        (.fillRect ctx (- sx 8) (- sy 34) 16 16)
        (set! (.-fillStyle ctx) "#ffffff")
        (set! (.-font ctx) "bold 12px system-ui, sans-serif")
        (set! (.-textAlign ctx) "center")
        (.fillText ctx "E" sx (- sy 22))))))

(defn- draw-wish-mark
  "Draws a speech bubble over the house of a Fluffy with a wish in progress, showing
  the item it wants or a blob of the colour it wants to be, with an E key hint while
  the player is close enough to hand an item over."
  [^js ctx {:keys [quest houses realm bag] :as st} clock cam-x cam-y]
  (when-let [h (and (= :active (:stage quest)) (#{:sick :item :color} (:kind quest))
                    (get houses (:giver quest)))]
    (when (= realm (:realm h))
      (let [sx (js/Math.round (- (+ (:x h) (/ house-w 2)) cam-x))
            sy (js/Math.round (- (house-bottom h) house-h cam-y 16 (* 3 (js/Math.sin (/ clock 250)))))]
        (set! (.-fillStyle ctx) "rgba(255,255,255,0.92)")
        (.fillRect ctx (- sx 13) (- sy 26) 26 24)
        (.fillRect ctx (- sx 3) (- sy 2) 6 4)
        (if (= :color (:kind quest))
          (do (set! (.-fillStyle ctx) (str "hsl(" (js/Math.round (screen-hue (:hue quest))) ",72%,52%)"))
              (.beginPath ctx)
              (.arc ctx sx (- sy 14) 8 0 (* 2 js/Math.PI))
              (.fill ctx))
          (icons/draw! ctx (:item quest) (- sx 10) (- sy 24) 2))
        (when (and (#{:sick :item} (:kind quest)) (wish-house-near? st))
          (.save ctx)
          (set! (.-fillStyle ctx) "rgba(0,0,0,0.6)")
          (.fillRect ctx (+ sx 16) (- sy 22) 16 16)
          (set! (.-fillStyle ctx) "#ffffff")
          (set! (.-font ctx) "bold 12px system-ui, sans-serif")
          (set! (.-textAlign ctx) "center")
          (.fillText ctx "E" (+ sx 24) (- sy 10))
          (.restore ctx))))))

(defn- draw-quest-mark
  "Draws a bobbing yellow exclamation mark over the house that offers a quest, with an
  E key hint beside it while the player is close enough to accept."
  [^js ctx {:keys [quest houses realm] :as st} clock cam-x cam-y]
  (draw-wish-mark ctx st clock cam-x cam-y)
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
  [{:keys [quest bag]}]
  (when (= :active (:stage quest))
    (let [{:keys [found? kind item count color]} quest
          step (fn [done? text]
                 ($ :li {:class (if done? "text-emerald-300 line-through" "text-amber-50")}
                    (if done? "✓ " "• ") text))
          have? (and item (>= (bag-count bag item) count))]
      ($ :div {:class "w-64 rounded-md border border-amber-600/60 bg-black/60 px-3 py-2 text-sm select-none"}
         ($ :div {:class "mb-1 font-bold text-amber-300"}
            (if (#{:sick :item :color} kind) (str "! Wish: " (wish-title quest)) "! Quest: My red friend is lost"))
         (case kind
           (:sick :item)
           ($ :ul {:class "space-y-0.5"}
              (step have? (str "Get " count " " (if (> count 1) (item-plural item) (item-name item)) (when (= :sick kind) " (the shop sells them)")))
              (step false "Press E by the Fluffy's house to hand it over"))
           :color
           ($ :ul {:class "space-y-0.5"}
              (step false (str "Paint the Fluffy " color " with the shop's paints; each coat mixes halfway with its colour")))
           ($ :ul {:class "space-y-0.5"}
              (step found? "Find the red Fluffy (it shows on your map) and press E")
              (step false "Place a Fluffy House within 40 blocks of its friend's house")))))))

(defn- use-slot
  "Uses the item in bag slot `i`. A Healing Potion restores `potion-heal` hit points
  when health is not full, Rope hangs down from the tile under the mouse, a block
  or a torch is placed on the tile under the mouse, paint colours the Fluffy under the
  mouse, and the Dimension Amulet opens a portal when
  the player is on the surface."
  [{:keys [player bag enemies robbers realm] :as st} tiles i]
  (let [{:keys [id name count]} (get bag i)
        ;; robbers walk only in the overworld, and no block may bury one there
        enemies (cond-> (vec enemies) (= :overworld realm) (into robbers))
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

      (paints id)
      (if-let [painted (paint-fluffy st (:hue (paints id)))]
        (spend painted 1)
        (popup st "Point at a Fluffy near you"))

      (= :hay id)
      (if-let [calmed (calm-goat st)]
        (spend calmed 1)
        (popup st (if (= :mountains (:realm st)) "No goat nearby wants hay" "Goats live in the mountains")))

      (= :torch id)
      (if (place-block! tiles player enemies torch)
        (spend st 1)
        (popup st "Point at an open spot within reach"))

      (blocks id)
      (if (place-block! tiles player enemies (:tile (blocks id)))
        (spend st 1)
        (popup st "Point at an open spot within reach"))

      (= :house id)
      (if-let [placed (place-house st tiles)]
        (spend placed 1)
        (popup st "Point at open ground within reach, two blocks wide"))

      (= :mirror id)
      (if-let [placed (place-mirror st tiles)]
        (spend placed 1)
        (popup st "Point at open ground within reach, two blocks wide"))

      (= :rocket id)
      (spend (launch-rocket st) 1)

      (= :tamer id)
      (if-let [tamed (tame-alien st)]
        (spend tamed 1)
        (popup st (if (= :space (:realm st)) "No alien close enough" "Aliens live in outer space")))

      (and (= :amulet id) (not= :overworld (:realm st)))
      (popup st "The amulet is silent here; find the portal home")

      (= :amulet id)
      (if (on-surface? player (get-in st [:worlds :overworld :surface]))
        (open-portal st)
        (popup st "The amulet works only on the surface"))

      id (popup st (str name " can't be used yet"))
      :else st)))

(defui bag-bar
  "Shows the bag slots. With `on-use`, which the touch controls pass, tapping a slot
  uses its item."
  [{:keys [bag on-use]}]
  ($ :div {:class "flex gap-1.5 select-none"}
     (for [i (range bag-size)
           :let [{:keys [id name count]} (get bag i)]]
       ($ :div {:key i :title name
                :on-pointer-down (when on-use
                                   (fn [^js e] (.preventDefault e) (on-use i)))
                :class (str "relative flex h-11 w-11 items-center justify-center rounded-md border border-white/20 bg-black/40"
                            (when on-use " touch-none active:bg-white/30"))}
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

(defui help-section [{:keys [title children]}]
  ($ :section {:class "mt-3 first:mt-0"}
     ($ :h3 {:class "mb-1 text-sm font-bold uppercase tracking-wide text-amber-200"} title)
     ($ :ul {:class "list-disc space-y-1 pl-5 text-white/90"} children)))

(defui how-to-play
  "Shows the help in sections. Controls, the bag and the shop are always there; each
  other section shows once the player has met that part of the game, as `discover`
  records in `seen`, so the help does not give away what is still to come."
  [{:keys [seen]}]
  (let [seen (or seen #{})
        hidden (count (remove seen [:rope :slimes :dimension :goo :fluffies :robbers :drones :space :paper]))]
    ($ :div {:class "max-h-[55vh] overflow-y-auto pr-1"}
       ($ help-section {:title "Controls"}
          ($ :li "A and D or the left and right arrow keys move, Space or the up arrow jumps")
          ($ :li "The mouse aims the pickaxe; Shift or click digs, or hits whatever is in the way")
          ($ :li "E acts on what you stand next to, such as the shop")
          ($ :li "On a tablet, the pad in the bottom left corner moves, jumps and climbs, a finger anywhere else aims and digs, the E button in the bottom right does what the E key does, and tapping a bag slot uses its item where you last touched")
          ($ :li "M opens and closes this menu"))
       ($ help-section {:title "Bag and building"}
          ($ :li "Keys 1 to 9 and 0 use the items in your bag; a Healing Potion gives +" potion-heal " health")
          ($ :li "The bag has " bag-size " slots, each holding a stack of one kind of item; when it is full, new finds are lost")
          ($ :li "Digging gives Dirt and Stone blocks (up to 100 each); use them from the bag to build"))
       ($ help-section {:title "Shop"}
          ($ :li "Find the shop on the surface and press E there to sell your finds and buy potions, rope and torches"))
       (when (seen :rope)
         ($ help-section {:title "Rope and torches"}
            ($ :li "Rope hangs down from where the mouse points, one rope per block; jump into it to grab it")
            ($ :li "On a rope, Space, W or the up arrow climbs, S or the down arrow climbs down, and A, D or the left and right arrows jump off")
            ($ :li "Hit a rope with the pickaxe to take all of it back")
            ($ :li "Torches are placed from the bag like blocks and light up their surroundings; near a torch health comes back twice as fast. Hit a torch with the pickaxe to take it back")))
       (when (seen :slimes)
         ($ help-section {:title "Slimes and night"}
            ($ :li "Slimes come out in the caves at night; one in twenty drops a Dimension Amulet")))
       (when (seen :dimension)
         ($ help-section {:title "The Slime Dimension"}
            ($ :li "Use the amulet on the surface to open a portal, then hit the portal with the pickaxe to enter the Slime Dimension, where darker slimes take twice the hits and hit twice as hard")
            ($ :li "In green ooze you move slowly; hold Space to swim up")))
       (when (seen :goo)
         ($ help-section {:title "The Lord of Goo"}
            ($ :li "Slimes in the Slime Dimension sometimes drop an Arena Key; far to the east a locked gate leads to the Lord of Goo's arena")))
       (when (seen :fluffies)
         ($ help-section {:title "Fluffies"}
            ($ :li "Rare blue Fluffies hide in caves around the world; press E next to one to make it follow you, and E again to let it wait")
            ($ :li "Buy a Fluffy House at the shop and place it on the ground from the bag; a Fluffy following you moves into an empty house and pays you " house-reward " coins")
            ($ :li "Buy red, yellow or blue paint at the shop, point at a Fluffy near you and use the paint from the bag to colour it; each new colour mixes halfway with the old one, so a blue Fluffy painted yellow turns green")
            ($ :li "Now and then a Fluffy in a house has a wish, shown by a ! over its house: press E there to hear it. It may have lost a friend, feel sick and want a Healing Potion, want an item, or want a new colour; a granted wish pays " wish-reward " coins and a gift")))
       (when (seen :paper)
         ($ help-section {:title "The newspaper"}
            ($ :li "Every morning a paperboy in a red cap walks the surface; press E next to him to buy The Mine Valley Times for " paper-price " coins")
            ($ :li "The paper tells what you got up to the day before; once bought, the newspaper button in the bottom right corner opens it again")))
       (when (seen :mountains)
         ($ help-section {:title "The Mountains"}
            ($ :li "Walk on past the left or right end of the valley to reach the Mountains, and past either end of the Mountains to come back; the two lands join up in a ring")
            ($ :li "Jump up the steep slopes to the snowy peaks; the mini-map shows the Mountains while you are there")
            ($ :li "Rust-spotted rock deep inside the mountains is iron ore; dig it for Iron Ore and sell it at the shop for " (prices :ore) " coins each")
            ($ :li "White mountain goats wander the slopes and leave you be, but hit one with the pickaxe and it gets angry and butts you, sending you flying a long way")
            ($ :li "Golden tufts of hay grow on the grass in the valley and down in the mountain valleys; hit one with the pickaxe to pick it, then use Hay from the bag near an angry goat to calm it")
            ($ :li "Hay given to a calm goat makes it friendly, with a pink heart over it; press E next to it to ride it for about ten seconds, steering with A and D and leaping high with Space, until it bucks you off")))
       (when (seen :robbers)
         ($ help-section {:title "Robbers"}
            ($ :li "Masked robbers come out only at night and run off at sunrise; one that touches you steals " robber-steal-min " to " robber-steal-max " coins and runs off")
            ($ :li "Knock a robber out with " robber-hp " pickaxe hits to get back half of what it stole")))
       (when (seen :drones)
         ($ help-section {:title "Drones and the spaceship"}
            ($ :li "Drones fly over the overworld and now and then land; break a landed drone with the pickaxe and a spaceship comes down somewhere nearby")
            ($ :li "Hit the spaceship with the pickaxe to fly to outer space, and hit it again there to fly home")))
       (when (seen :space)
         ($ help-section {:title "Outer space"}
            ($ :li "Gravity is weak in space, so you can hop between asteroids; space wraps round, so flying on past one edge brings you in over the opposite one")
            ($ :li "Purple aliens float after you and hurt on touch; " alien-hp-min " to " alien-hp-max " pickaxe hits pop one for " alien-reward " coins")
            ($ :li "Touch the gold stars to collect them and sell them at the shop")
            ($ :li "Once you have a star, the shop also sells things for stars: a Magic Mirror to stand on the ground (hit it with the pickaxe to take it back), Firework Rockets, a Map of Space, and an Alien Tamer that turns the nearest alien into a tame friend who follows you like a Fluffy")))
       (when (pos? hidden)
         ($ :p {:class "mt-3 text-xs italic text-white/60"} "More tips appear here as you discover new things.")))))

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
              (dissoc :keys :mouse :mouse-down? :particles :popups :rockets :clock-offset :paused? :paper-open?)
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

;; the Graphics setting in the menu: Sharp always draws at the screen's full
;; resolution, Fast at one canvas pixel per CSS pixel, and Auto switches from sharp to
;; fast when frames stay slow
(def ^:private graphics-key "mine-valley-graphics")

(defonce ^:private graphics
  (atom (let [v (try (js/localStorage.getItem graphics-key) (catch :default _ nil))]
          (if (#{"sharp" "fast" "auto"} v) (keyword v) :auto))))

(defonce ^:private low-res? (atom false))

(defn- low-res-now? []
  (case @graphics :fast true :sharp false @low-res?))

(defn- set-graphics! [mode]
  (reset! graphics mode)
  (try (js/localStorage.setItem graphics-key (name mode)) (catch :default _ nil)))

(defui graphics-setting []
  (let [[mode set-mode!] (use-state @graphics)
        btn (fn [id label]
              ($ :button {:class (str "rounded px-2.5 py-1 font-semibold "
                                      (if (= mode id) "bg-white/20 text-white" "text-white/60 hover:text-white"))
                          :on-click #(do (.blur (.-currentTarget %)) (set-graphics! id) (set-mode! id))}
                 label))]
    ($ :div {:class "mt-4 flex items-center gap-2 border-t border-white/15 pt-3"}
       ($ :span {:class "mr-auto text-white/80"} "Graphics")
       (btn :auto "Auto") (btn :sharp "Sharp") (btn :fast "Fast"))))

(defui menu-panel [{:keys [on-close snapshot restore seen]}]
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
            :help ($ how-to-play {:seen seen})
            :saves ($ saves-tab {:snapshot snapshot :restore restore}))
          ($ graphics-setting)
          ($ :div {:class "mt-2 text-xs text-white/60"} "Auto draws sharp and switches to Fast if the game runs slowly. Press M again to close")))))

(defn- render-scale
  "Returns the whole number of canvas pixels drawn per CSS pixel. A fractional device
  pixel ratio such as 1.25 would put tile edges between screen pixels and blend them
  into light seams, so the browser stretches a whole-number canvas instead. With the
  Fast graphics setting, or Auto once `low-res?` is set, the canvas has one pixel per
  CSS pixel, a quarter of the work on a high-density screen, and the browser scales
  it up smoothly."
  []
  (if (low-res-now?) 1 (max 1 (js/Math.round (or js/window.devicePixelRatio 1)))))

;; a machine that averages slower than about 40 frames a second for a few seconds
;; switches to `low-res?` for the rest of the session
(def ^:private slow-frame-ms 25)
(def ^:private slow-warmup 180)

(defn- watch-speed!
  "Feeds the time since the last frame, `ms`, into the running average in `speed`, an
  atom of `{:avg :n}`, and turns on `low-res?` when frames stay slow. Returns true when
  it just did."
  [speed ms]
  (when (and (= :auto @graphics) (not @low-res?) (< ms 250) (> (or js/window.devicePixelRatio 1) 1.25))
    (let [{:keys [avg n]} (swap! speed (fn [{:keys [avg n]}]
                                         {:avg (if avg (+ (* 0.97 avg) (* 0.03 ms)) ms) :n (inc n)}))]
      (when (and (> n slow-warmup) (> avg slow-frame-ms))
        (reset! low-res? true)
        true))))

(defn- typing? [^js e]
  (let [tag (some-> e .-target .-tagName)]
    (or (= tag "INPUT") (= tag "TEXTAREA") (some-> e .-target .-isContentEditable))))

(def game-keys #{"KeyA" "KeyD" "KeyW" "KeyS" "Space" "ShiftLeft" "ShiftRight"})

;; the arrow keys stand in for A, D, Space and S
(def key-aliases {"ArrowLeft" "KeyA" "ArrowRight" "KeyD" "ArrowUp" "Space" "ArrowDown" "KeyS"})

(defn- game-key [code] (get key-aliases code code))

(def map-colors
  {grass [86 170 60] dirt [140 92 52] stone [118 118 128] trunk [110 72 40]
   leaves [52 140 60] rope [200 170 110] torch [255 170 60]
   snow [238 243 248] ore [190 104 50] hay [224 192 96]})

(defui mini-map
  "Draws the overworld, or the mountains while the player is there, one pixel per
  tile, refreshed twice a second, with every Fluffy there as a dot of its colour, the
  spaceship as a silver dot and the player as a white dot."
  [{:keys [state-ref]}]
  (let [canvas-ref (use-ref nil)
        [shown set-shown!] (use-state :overworld)]
    (use-effect
     (fn []
       (let [draw (fn []
                    (when-let [^js canvas @canvas-ref]
                      (when-let [state @state-ref]
                        (let [st @state
                              ;; in the mountains the map shows the mountains
                              shown (if (and (= :mountains (:realm st)) (get-in st [:worlds :mountains]))
                                      :mountains :overworld)
                              _ (set-shown! shown)
                              {:keys [tiles surface]} (get-in st [:worlds shown])
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
                          (doseq [f (sort-by :red? (:fluffies st)) :when (= shown (:realm f :overworld))]
                            (dot (:x f) (:y f) (if (:red? f) 4.5 3.5) "#ffffff")
                            (dot (:x f) (:y f) (if (:red? f) 3.5 2.5) (second (fluffy-colors f))))
                          (when-let [{:keys [x y]} (when (= shown :overworld) (:ship st))]
                            (dot (+ x (/ ship-w 2)) (+ y (/ ship-h 2)) 4 "#111111")
                            (dot (+ x (/ ship-w 2)) (+ y (/ ship-h 2)) 3 "#d9dee6"))
                          (when (= shown (:realm st))
                            (let [{:keys [x y]} (:player st)]
                              (dot (+ x (/ player-w 2)) (+ y (/ player-h 2)) 3 "#111111")
                              (dot (+ x (/ player-w 2)) (+ y (/ player-h 2)) 2 "#ffffff")))))))
             id (js/setInterval draw 500)]
         (draw)
         #(js/clearInterval id)))
     [state-ref])
    ($ :canvas {:ref canvas-ref :width W :height H
                :class (str "block w-[480px] max-w-[45vw] rounded-md border-2 [image-rendering:pixelated] "
                            (if (= shown :mountains) "border-slate-300" "border-amber-800"))})))

(defui space-map
  "Draws outer space one pixel per tile, refreshed twice a second, with the stars as
  gold dots, wild aliens as purple dots, tame ones as green dots, the spaceship as a
  silver dot and the player as a white dot."
  [{:keys [state-ref]}]
  (let [canvas-ref (use-ref nil)]
    (use-effect
     (fn []
       (let [draw (fn []
                    (when-let [^js canvas @canvas-ref]
                      (when-let [state @state-ref]
                        (let [st @state
                              tiles (get-in st [:worlds :space :tiles])
                              ctx (.getContext canvas "2d")
                              img (.createImageData ctx space-w space-h)
                              ^js px (.-data img)
                              dot (fn [x y r color]
                                    (set! (.-fillStyle ctx) color)
                                    (.beginPath ctx)
                                    (.arc ctx (- (/ x T) space-x0) (- (/ y T) space-y0) r 0 (* 2 js/Math.PI))
                                    (.fill ctx))]
                          (when tiles
                            (dotimes [y space-h]
                              (dotimes [x space-w]
                                (let [[r g b] (if (= asteroid (aget tiles (idx (+ space-x0 x) (+ space-y0 y))))
                                                [128 118 136] [10 10 26])
                                      i (* 4 (+ x (* y space-w)))]
                                  (aset px i r)
                                  (aset px (+ i 1) g)
                                  (aset px (+ i 2) b)
                                  (aset px (+ i 3) 255))))
                            (.putImageData ctx img 0 0)
                            (doseq [{:keys [x y]} (:stars st)]
                              (dot x y 1.5 "#ffd54a"))
                            (doseq [{:keys [x y]} (:aliens st)]
                              (dot (+ x (/ alien-w 2)) (+ y (/ alien-h 2)) 2.5 "#b06cff"))
                            (doseq [f (:fluffies st) :when (= :space (:realm f))]
                              (dot (:x f) (:y f) 2.5 (if (:alien? f) "#7cff9a" (second (fluffy-colors f)))))
                            (when-let [{:keys [x y]} (get-in st [:worlds :space :ship])]
                              (dot (+ x (/ ship-w 2)) (+ y (/ ship-h 2)) 4 "#111111")
                              (dot (+ x (/ ship-w 2)) (+ y (/ ship-h 2)) 3 "#d9dee6"))
                            (when (= :space (:realm st))
                              (let [{:keys [x y]} (:player st)]
                                (dot (+ x (/ player-w 2)) (+ y (/ player-h 2)) 3 "#111111")
                                (dot (+ x (/ player-w 2)) (+ y (/ player-h 2)) 2 "#ffffff"))))))))
             id (js/setInterval draw 500)]
         (draw)
         #(js/clearInterval id)))
     [state-ref])
    ($ :canvas {:ref canvas-ref :width space-w :height space-h
                :class "block w-[480px] max-w-[45vw] rounded-md border-2 border-indigo-400 [image-rendering:pixelated]"})))

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

(defui star-price [{:keys [amount]}]
  ($ :span {:class "inline-flex items-center gap-1 font-mono text-yellow-200"}
     amount ($ icons/icon {:id :star :size 14})))

(defn- buy-button [ok? on-click]
  ($ :button {:class (if ok?
                       "rounded bg-emerald-600 px-2 py-0.5 font-semibold hover:bg-emerald-500"
                       "cursor-not-allowed rounded bg-white/10 px-2 py-0.5 font-semibold text-white/40")
              :disabled (not ok?)
              :on-click #(do (.blur (.-currentTarget %)) (on-click))}
     "Buy"))

(defui star-shop
  "Lists what the shop sells for stars, paid from the stars in the bag."
  [{:keys [bag on-buy-star space-map? on-buy-space-map]}]
  (let [stars (star-count-in bag)]
    ($ :<>
       ($ :div {:class "mb-2 flex items-center gap-1.5 font-semibold text-yellow-200"}
          ($ icons/icon {:id :star :size 16}) "Star Shop")
       ($ :ul {:class "mb-1 space-y-1"}
          (for [[id cost] star-prices
                :let [{:keys [name]} (item-def id)
                      ok? (and (>= stars cost) (bag-slot bag id))]]
            ($ :li {:key (str id) :class "flex items-center gap-2 rounded bg-indigo-400/20 px-2 py-1"}
               ($ icons/icon {:id id :size 20})
               ($ :span {:class "flex-1"} name (when (= id :tamer) " (one use)"))
               ($ star-price {:amount cost})
               (buy-button ok? #(on-buy-star id))))
          ($ :li {:key "space-map" :class "flex items-center gap-2 rounded bg-indigo-400/20 px-2 py-1"}
             ($ icons/icon {:id :map :size 20})
             ($ :span {:class "flex-1"} "Map of Space")
             (if space-map?
               ($ :span {:class "px-2 py-0.5 font-semibold text-emerald-300"} "Owned")
               ($ :<>
                  ($ star-price {:amount space-map-price})
                  (buy-button (>= stars space-map-price) on-buy-space-map)))))
       ($ :div {:class "mb-3 flex items-center gap-1 text-xs text-amber-200/70"}
          "You have " ($ star-price {:amount stars})))))

(defui shop-panel [{:keys [coins bag on-sell on-buy map? on-buy-map star-shop? on-buy-star
                           space-map? on-buy-space-map]}]
  (let [sellable (filter #(and % (prices (:id %))) bag)
        money coins]
    ($ :div {:class "absolute bottom-6 left-1/2 max-h-[85vh] w-80 -translate-x-1/2 overflow-y-auto rounded-lg border-2 border-amber-700 bg-amber-950/90 p-4 text-sm text-amber-50 shadow-xl select-none"}
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
       (when star-shop?
         ($ star-shop {:bag bag :on-buy-star on-buy-star
                       :space-map? space-map? :on-buy-space-map on-buy-space-map}))
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

(def ^:private pad-dead-zone
  "How far in CSS pixels the thumb must move from the centre of the touch pad before a
  direction counts."
  22)

(defn- pad-keys
  "Returns the movement keys that a thumb at `dx`, `dy` from the centre of the touch
  pad holds down: left and right move, up jumps, climbs and swims, down climbs down."
  [dx dy]
  (cond-> #{}
    (< dx (- pad-dead-zone)) (conj "KeyA")
    (> dx pad-dead-zone) (conj "KeyD")
    (< dy (- pad-dead-zone)) (conj "Space")
    (> dy pad-dead-zone) (conj "KeyS")))

(defui touch-pad
  "A pad for the left thumb that holds down the movement keys in the game state held
  by `state-ref`. Sliding the thumb across the pad changes direction without lifting it."
  [{:keys [state-ref]}]
  (let [held (use-ref #{})
        [shown set-shown!] (use-state #{})
        hold! (fn [ks]
                (when-let [state @state-ref]
                  (swap! state update :keys #(into (reduce disj % @held) ks)))
                (reset! held ks)
                (set-shown! ks))
        aim! (fn [^js e]
               (let [r (.getBoundingClientRect (.-currentTarget e))]
                 (hold! (pad-keys (- (.-clientX e) (+ (.-left r) (/ (.-width r) 2)))
                                  (- (.-clientY e) (+ (.-top r) (/ (.-height r) 2)))))))
        arrow (fn [k label pos]
                ($ :span {:class (str "absolute flex h-14 w-14 items-center justify-center rounded-full text-2xl "
                                      pos (if (shown k) " bg-white/50 text-black" " bg-white/15 text-white/80"))}
                   label))]
    ($ :div {:class "absolute bottom-6 left-6 h-44 w-44 touch-none select-none rounded-full border-2 border-white/25 bg-black/30"
             :on-pointer-down (fn [^js e]
                                (.preventDefault e)
                                (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                                (aim! e))
             :on-pointer-move (fn [^js e]
                                (when (.hasPointerCapture (.-currentTarget e) (.-pointerId e))
                                  (aim! e)))
             :on-pointer-up #(hold! #{})
             :on-pointer-cancel #(hold! #{})}
       (arrow "Space" "▲" "left-1/2 top-1 -translate-x-1/2")
       (arrow "KeyS" "▼" "left-1/2 bottom-1 -translate-x-1/2")
       ;; U+FE0E keeps the side arrows as plain text instead of emoji
       (arrow "KeyA" "◀︎" "left-1 top-1/2 -translate-y-1/2")
       (arrow "KeyD" "▶︎" "right-1 top-1/2 -translate-y-1/2"))))

(defui touch-e-button
  "A big E button for the right thumb that does what the E key does."
  [{:keys [on-press]}]
  ($ :button {:class "absolute bottom-10 right-8 h-24 w-24 touch-none select-none rounded-full border-2 border-white/30 bg-black/40 text-4xl font-bold text-white active:bg-white/40"
              :on-pointer-down (fn [^js e] (.preventDefault e) (on-press))}
     "E"))

;; The Mine Valley Times. The stories are put together from the day's log with
;; sentence patterns, picked by the day number so each paper reads differently.

(defn- pick
  "Picks one of `options` for story `k` in the paper of day `seed`."
  [seed k options]
  (nth options (mod (js/Math.floor (* 997 (hash2 seed (hash k)))) (count options))))

(defn- plural [n one many] (str n " " (if (= 1 n) one many)))

(def ^:private item-names
  (into {} (map (juxt :id :name)) (concat loot [coin amulet arena-key])))

(defn- news-stories
  "Returns the stories for the paper about the day logged in `log`, most newsworthy
  first. Each has a `:title`, a `:text`, a `:pic` for its picture and a `:score`."
  [log seed]
  (let [{:keys [slimes goo robbers stolen recovered aliens stars friends moved-in chests
                found dug earned fainted amulets drones visited]
         :or {slimes 0 robbers 0 stolen 0 recovered 0 aliens 0 stars 0 friends 0 moved-in 0
              chests 0 dug 0 earned 0 fainted 0 amulets 0 drones 0}} log
        arena-keys (:keys log 0)
        p (fn [k options] (pick seed k options))]
    (->> [(when goo
            {:score 1000 :pic {:icon :crown}
             :title (p :goo ["LORD OF GOO DEFEATED!" "THE GOO IS GONE!" "VALLEY HERO TOPPLES SLIME KING"])
             :text (p :goo-t ["In a battle that shook the Slime Dimension, a brave miner brought down the Lord of Goo. Witnesses say the arena is \"much less sticky now\"."
                              "The Lord of Goo is no more. Slimes across the valley are said to be considering early retirement."])})
          (when (pos? robbers)
            {:score (* 40 robbers) :pic :robber
             :title (p :rob ["MASKED ROBBER CAUGHT" "CRIME DOES NOT PAY" "PICKAXE JUSTICE ON MAIN STREET"])
             :text (str (p :rob-t ["A pickaxe-swinging local knocked out " "Our reporter watched as a miner sent "])
                        (plural robbers "robber" "robbers")
                        (p :rob-t2 [" on the surface" " sprawling into the grass"])
                        (if (pos? recovered) (str " and won back " (plural recovered "coin" "coins") ".") "."))})
          (when (and (pos? stolen) (zero? (or robbers 0)))
            {:score (* 3 stolen) :pic :robber
             :title (p :stolen ["ROBBER STRIKES AGAIN" "COINS VANISH IN BROAD DAYLIGHT" "WATCH YOUR PURSE!"])
             :text (str "A masked figure in a striped shirt made off with " (plural stolen "coin" "coins")
                        (p :stolen-t [". Police advise carrying a pickaxe." ". The thief is still at large." ". Residents are urged to keep their coins close."]))})
          (when (pos? friends)
            {:score (* 25 friends) :pic :fluffy
             :title (p :friends ["NEW FRIENDSHIP BLOOMS" "FLUFFY FOLLOWS LOCAL MINER" "FEATHERS AND FRIENDSHIP"])
             :text (str (plural friends "Fluffy has" "Fluffies have")
                        (p :friends-t [" been seen following a miner everywhere they go." " joined the miner's little flock. Experts call it \"adorable\"."]))})
          (when (pos? moved-in)
            {:score (* 20 moved-in) :pic {:icon :house}
             :title (p :home ["HOUSEWARMING IN THE VALLEY" "FLUFFY MOVES INTO NEW HOME" "PROPERTY MARKET HEATS UP"])
             :text (str (plural moved-in "Fluffy" "Fluffies")
                        (p :home-t [" moved into a brand new house yesterday." " found a home of their own. The neighbours brought cake."]))})
          (when (pos? slimes)
            {:score (* 3 slimes) :pic :slime
             :title (p :slimes [(str (plural slimes "SLIME" "SLIMES") " BOUNCED OUT OF THE CAVES")
                                (str "NIGHT OF THE PICKAXE: " slimes " SLIMES FALL")
                                "SLIME NUMBERS DROP"])
             :text (str (p :slimes-t ["Cave dwellers report " "Miners counted " "The slime census shows "])
                        (plural slimes "slime" "slimes")
                        (p :slimes-t2 [" fewer than the day before." " squashed by a single pickaxe." " gone. The rest are keeping their heads down."]))})
          (when (pos? aliens)
            {:score (* 6 aliens) :pic :alien
             :title (p :aliens ["ALIENS POPPED OVER THE ASTEROIDS" "CLOSE ENCOUNTERS OF THE PICKAXE KIND" "SPACE GETS A LITTLE QUIETER"])
             :text (str (plural aliens "purple alien was" "purple aliens were")
                        (p :aliens-t [" popped among the asteroids." " sent back to wherever aliens come from."]))})
          (when (pos? fainted)
            {:score 30 :pic :player
             :title (p :faint ["MINER FAINTS, RECOVERS" "A BAD DAY AT THE OFFICE" "LOCAL MINER TAKES A LONG NAP"])
             :text (str "Our favourite miner fainted " (if (= 1 fainted) "once" (str fainted " times"))
                        (p :faint-t [" but was back on their feet by morning." ". Doctors prescribe Healing Potions and fewer slimes."]))})
          (when (or (pos? amulets) (pos? arena-keys))
            {:score 28 :pic {:icon (if (pos? arena-keys) :key :amulet)}
             :title (if (pos? arena-keys) "MYSTERIOUS KEY TURNS UP" "STRANGE AMULET FOUND")
             :text (if (pos? arena-keys)
                     "A slime from the other dimension dropped a heavy key. Where does it lead? Somewhere to the east, rumour has it."
                     "A slime coughed up a glowing amulet. Scholars say it opens a door to somewhere much greener.")})
          (when (pos? drones)
            {:score (* 12 drones) :pic {:icon :rocket}
             :title "DRONE DOWN!"
             :text (str (plural drones "drone was" "drones were") " knocked out of the sky. Some say a spaceship was seen landing nearby.")})
          (when (contains? visited :space)
            {:score 22 :pic :space
             :title (p :space ["MINER SIGHTED AMONG THE STARS" "ONE SMALL HOP FOR A MINER"])
             :text (str "Yesterday our miner travelled to outer space"
                        (if (pos? stars) (str " and brought back " (plural stars "star" "stars") ".") ". The view is said to be excellent."))})
          (when (and (pos? stars) (not (contains? visited :space)))
            {:score (* 2 stars) :pic {:icon :star}
             :title "STARS COLLECTED" :text (str (plural stars "star was" "stars were") " collected from the sky.")})
          (when (contains? visited :mountains)
            {:score (+ 14 (* 2 (:ore log 0))) :pic {:icon :ore}
             :title (p :mountains ["MINER CLIMBS THE MOUNTAINS" "TRACKS FOUND IN THE SNOW" "IRON IN THE HILLS"])
             :text (if (pos? (:ore log 0))
                     (str "Our miner walked past the end of the valley into the Mountains and dug out "
                          (plural (:ore log 0) "lump" "lumps") " of iron ore. The smiths are delighted.")
                     "Our miner walked past the end of the valley into the Mountains. Locals say there is iron in that rock, if you dig deep enough.")})
          (when-let [quests (seq (:quests log))]
            {:score (+ 30 (* 10 (count quests))) :pic {:icon :house}
             :title (if (next quests)
                      (p :quests ["FLUFFY WISHES COME TRUE" "HAPPY FLUFFIES ALL ROUND" "A GOOD DAY FOR FLUFFIES"])
                      (case (:kind (first quests))
                        :friend "LOST FLUFFY FINDS ITS WAY HOME"
                        :sick "SICK FLUFFY MAKES FULL RECOVERY"
                        :color (str "FLUFFY GOES " (str/upper-case (:color (first quests) "")))
                        "FLUFFY GETS ITS WISH"))
             :text (str/join " "
                             (map-indexed
                              (fn [i {:keys [kind item count color]}]
                                (let [q (fn [options] (pick seed [:quest i] options))
                                      [what quotes]
                                      (case kind
                                        :friend ["A lost red Fluffy was found and moved in next door to its friend."
                                                 ["We're neighbours now. Best day ever!" "I knew someone would find my friend!"
                                                  "Now I have someone to chat with over the fence."]]
                                        :sick ["A Fluffy who felt sick was brought a Healing Potion."
                                               ["I feel as good as new!" "My fluff is fluffy again!"
                                                "That potion tasted of strawberries. Thank you!"]]
                                        :color [(str "A Fluffy was painted " color ", just as it wished.")
                                                [(str (str/capitalize (or color "")) " suits me perfectly!")
                                                 "I feel like a whole new Fluffy!" "Everyone in the valley will notice me now!"]]
                                        [(str "A Fluffy who wished for " (plural count (item-name item) (item-plural item)) " got its wish.")
                                         (if (= :hay item)
                                           ["I'm not a goat, but this hay makes a lovely bed!" "So soft! I'll sleep like a log."]
                                           ["Exactly what I wanted!" "I'll treasure it forever." "Best present ever!"])])]
                                  (str what " \"" (q quotes) "\" said the Fluffy.")))
                              quests))})
          (when (pos? (:calmed log 0))
            {:score (+ 18 (* 3 (:butted log 0))) :pic {:icon :hay}
             :title (p :goats ["GOAT CALMED WITH HAY" "PEACE RETURNS TO THE PEAKS" "MINER MAKES FRIENDS WITH GOAT"])
             :text (str "An angry mountain goat"
                        (when (pos? (:butted log 0)) (str ", which butted a miner " (plural (:butted log 0) "time" "times") ","))
                        " was calmed down with a mouthful of hay. Goat experts advise keeping pickaxes to yourself.")})
          (when (and (pos? (:butted log 0)) (zero? (:calmed log 0)))
            {:score (* 6 (:butted log 0)) :pic {:icon :hay}
             :title "GOAT BUTTS MINER" :text (str "A miner who poked a mountain goat with a pickaxe was butted " (plural (:butted log 0) "time" "times") ". The goat could not be reached for comment.")})
          (when (contains? visited :slime)
            {:score 15 :pic :slime
             :title "TRIP TO THE SLIME DIMENSION" :text "A portal opened and a miner stepped through into the green. They came back smelling faintly of ooze."})
          (when (pos? chests)
            {:score (* 8 chests) :pic {:icon (or (first (remove #{:coin} (keys found))) :ruby)}
             :title (p :chests ["TREASURE FOUND IN THE CAVES" "CHEST OPENED, CONTENTS REVEALED" "LUCKY DAY UNDERGROUND"])
             :text (str (plural chests "chest was" "chests were") " opened deep in the caves"
                        (when (seq found)
                          (str ", holding " (str/join ", " (for [[id n] (take 3 found)] (str n " " (item-names id (name id))))))) ".")})
          (when (> (or dug 0) 40)
            {:score (quot dug 10) :pic {:icon :dirt}
             :title (p :dug ["MINER MOVES MOUNTAINS" "DIGGING RECORD BROKEN?" "THE VALLEY HAS NEW HOLES"])
             :text (str (plural dug "block" "blocks") " of earth and stone were dug up yesterday.")})
          (when (> (or earned 0) 30)
            {:score (quot earned 5) :pic {:icon :coin}
             :title "BUSINESS IS BOOMING" :text (str "The miner earned " (plural earned "coin" "coins") " yesterday. The shopkeeper is smiling.")})]
         (remove nil?)
         (sort-by :score >)
         vec)))

(defn newspaper
  "Returns the paper about day `day` from its `log`: a headline story, the other
  stories and the day's numbers."
  [{:keys [day log]}]
  (let [stories (news-stories log day)
        quiet {:score 0 :pic :fluffy
               :title (pick day :quiet ["A QUIET DAY IN MINE VALLEY" "NOTHING TO REPORT" "ALL CALM IN THE VALLEY"])
               :text (pick day :quiet-t ["Nothing much happened yesterday. The Fluffies napped, the slimes sulked, and the editor went fishing."
                                         "The valley had a calm day. Our reporters spent it watching the clouds."])}]
    {:day day
     :lead (or (first stories) quiet)
     :more (take 4 (rest stories))
     :numbers (remove nil? [(when (pos? (:earned log 0)) ["Coins earned" (:earned log)])
                            (when (pos? (:spent log 0)) ["Coins spent or lost" (:spent log)])
                            (when (pos? (:dug log 0)) ["Blocks dug" (:dug log)])
                            (when (pos? (:slimes log 0)) ["Slimes defeated" (:slimes log)])
                            (when (pos? (:chests log 0)) ["Chests opened" (:chests log)])
                            (when (pos? (:stars log 0)) ["Stars collected" (:stars log)])])
     :found (:found log)
     :weather (pick day :weather ["Tomorrow: sunny, with a chance of slime."
                                  "Weather: clear skies by day, glowing eyes by night."
                                  "Forecast: dry above ground, damp below."])}))

(defn- draw-news-picture
  "Draws the picture `pic` for a newspaper story on `canvas`, using the game's own
  drawing of the subject on a strip of ground."
  [^js canvas pic]
  (let [ctx (.getContext canvas "2d")
        w 80 h 56 ground 48]
    (.setTransform ctx 2 0 0 2 0 0)
    (set! (.-imageSmoothingEnabled ctx) false)
    (set! (.-fillStyle ctx) (if (= pic :space) "#14122b" "#cfe3f0"))
    (.fillRect ctx 0 0 w h)
    (if (= pic :space)
      (doseq [i (range 14)]
        (set! (.-fillStyle ctx) "#ffffff")
        (.fillRect ctx (js/Math.floor (* w (hash2 i 7))) (js/Math.floor (* h (hash2 7 i))) 1 1))
      (do (set! (.-fillStyle ctx) "#5cb83a")
          (.fillRect ctx 0 ground w 3)
          (set! (.-fillStyle ctx) "#8b5a2b")
          (.fillRect ctx 0 (+ ground 3) w (- h ground 3))))
    (case pic
      :slime    (draw-enemies ctx [{:x 29 :y (- ground slime-h) :vy 0 :flash 0 :ground? true}] 0 w h 0 0)
      :robber   (draw-robber ctx {:x 31 :y (- ground robber-h) :dir 1 :vx 0 :ground? true :walked 0 :flash 0 :stolen 1} 0 0)
      :alien    (draw-alien ctx 28 18 0 0 0)
      :fluffy   (draw-fluffies ctx [{:x 40 :y 34 :realm :overworld :phase 0}] :overworld 0 0 0)
      :player   (draw-player ctx {:x 31 :y (- ground player-h) :facing 1 :walked 0 :ground? true :vx 0} 0 0 false)
      :space    (draw-player ctx {:x 31 :y 9 :facing 1 :walked 0 :ground? false :vx 0} 0 0 true)
      :paperboy (draw-paperboy ctx {:x 31 :y (- ground paperboy-h) :dir 1 :vx 0 :ground? true :walked 0} 0 0 nil)
      nil)))

(defui news-picture [{:keys [pic big?]}]
  (let [ref (use-ref nil)]
    (use-effect (fn [] (when-let [c @ref] (draw-news-picture c pic)) js/undefined) [pic])
    (if (map? pic)
      ($ :div {:class (str "flex shrink-0 items-center justify-center border border-stone-400 bg-[#cfe3f0] "
                           (if big? "h-28 w-40" "h-14 w-20"))}
         ($ icons/icon {:id (:icon pic) :size (if big? 56 28)}))
      ($ :canvas {:ref ref :width 160 :height 112
                  :class (str "shrink-0 border border-stone-400 [image-rendering:pixelated] sepia-[.35] "
                              (if big? "h-28 w-40" "h-14 w-20"))}))))

(defui newspaper-panel
  "Shows the bought paper as a newspaper page over the game."
  [{:keys [paper on-close]}]
  (let [{:keys [day lead more numbers found weather]} (newspaper paper)]
    ($ :div {:class "absolute inset-0 z-20 flex items-center justify-center bg-black/50 p-4"
             :on-click on-close}
       ($ :div {:class "max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-sm bg-[#f4ecd8] p-5 font-serif text-stone-900 shadow-2xl"
                :on-click #(.stopPropagation %)}
          ($ :div {:class "border-b-4 border-double border-stone-800 pb-1 text-center"}
             ($ :div {:class "text-3xl font-black tracking-tight sm:text-4xl"} "The Mine Valley Times")
             ($ :div {:class "mt-1 flex justify-between text-xs uppercase tracking-wider text-stone-600"}
                ($ :span (str "Day " day))
                ($ :span "The valley's only newspaper")
                ($ :span (str paper-price " coins"))))
          ($ :div {:class "mt-3 flex flex-col gap-3 sm:flex-row"}
             ($ news-picture {:pic (:pic lead) :big? true})
             ($ :div
                ($ :h2 {:class "text-2xl font-black leading-tight"} (:title lead))
                ($ :p {:class "mt-1 text-sm leading-snug"} (:text lead))))
          (when (seq more)
            ($ :div {:class "mt-3 grid gap-3 border-t border-stone-400 pt-3 sm:grid-cols-2"}
               (for [{:keys [title text pic]} more]
                 ($ :div {:key title :class "flex gap-2"}
                    ($ news-picture {:pic pic})
                    ($ :div
                       ($ :h3 {:class "text-sm font-bold leading-tight"} title)
                       ($ :p {:class "mt-0.5 text-xs leading-snug"} text))))))
          ($ :div {:class "mt-3 grid gap-3 border-t border-stone-400 pt-3 text-xs sm:grid-cols-2"}
             ($ :div
                ($ :div {:class "mb-1 font-bold uppercase tracking-wider"} "Yesterday in numbers")
                (if (seq numbers)
                  (for [[label n] numbers]
                    ($ :div {:key label :class "flex justify-between border-b border-dotted border-stone-400"}
                       ($ :span label) ($ :span {:class "font-mono"} n)))
                  ($ :div {:class "italic"} "Nothing worth counting.")))
             ($ :div
                (when (seq found)
                  ($ :<>
                     ($ :div {:class "mb-1 font-bold uppercase tracking-wider"} "Found in the caves")
                     ($ :div {:class "flex flex-wrap gap-2"}
                        (for [[id n] found]
                          ($ :span {:key (name id) :class "flex items-center gap-1"}
                             ($ icons/icon {:id id :size 18}) n)))))
                ($ :div {:class "mt-2 italic"} weather)))
          ($ :div {:class "mt-4 text-center"}
             ($ :button {:class "rounded bg-stone-800 px-4 py-1.5 font-sans text-sm font-semibold text-[#f4ecd8] hover:bg-stone-700"
                         :on-click on-close}
                "Close (E)"))))))

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
        [star-shop? set-star-shop!] (use-state false)
        [space-map? set-space-map!] (use-state false)
        [in-space? set-in-space!] (use-state false)
        ;; the newspaper the player is reading, if any
        [paper set-paper!] (use-state nil)
        ;; the day of the last paper bought, which a button opens again
        [paper-day set-paper-day!] (use-state nil)
        ;; the touch controls show from the first touch on
        [touch? set-touch!] (use-state false)
        on-press-e (fn []
                     (when-let [state @state-ref]
                       (when-not (:paused? @state)
                         (swap! state #(or (paper-stand %) (toggle-shop %) (accept-quest %) (give-wish %) (mount-goat %) (toggle-fluffy %))))))
        on-use-slot (fn [i]
                      (when-let [state @state-ref]
                        (when-not (:paused? @state)
                          (swap! state use-slot (get-in @state [:worlds (:realm @state) :tiles]) i))))
        sync! (fn [st]
                (set-coins! (:coins st 0))
                (set-bag! (:bag st)))
        on-buy-star (fn [id]
                      (when-let [state @state-ref]
                        (sync! (swap! state buy-for-stars id))))
        on-buy-space-map (fn []
                           (when-let [state @state-ref]
                             (sync! (swap! state buy-space-map))
                             (set-map-shown! true)))
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
             outdoors? (fn [] (#{:overworld :mountains} (:realm @state)))
             raf (atom nil)
             last-t (atom nil)
             speed (atom {:avg nil :n 0})
             flips (atom false)
             resize (fn []
                      (let [dpr (render-scale)]
                        (set! (.-width canvas) (* dpr js/window.innerWidth))
                        (set! (.-height canvas) (* dpr js/window.innerHeight))
                        ;; a canvas with fewer pixels than the screen is stretched smoothly,
                        ;; since blocky stretching by 1.5 makes round shapes jagged
                        (set! (.. canvas -style -imageRendering)
                              (if (< dpr (or js/window.devicePixelRatio 1)) "auto" "pixelated"))))
             on-down (fn [^js e]
                       (when-not (typing? e)
                         (case (.-code e)
                           "KeyM" (when-not (.-repeat e) (set-menu! not))
                           "KeyE" (when-not (or (.-repeat e) (:paused? @state))
                                    (swap! state #(or (paper-stand %) (toggle-shop %) (accept-quest %) (give-wish %) (mount-goat %) (toggle-fluffy %))))
                           ("Digit1" "Digit2" "Digit3" "Digit4" "Digit5" "Digit6" "Digit7" "Digit8" "Digit9" "Digit0")
                           (when-not (or (.-repeat e) (:paused? @state))
                                                             ;; Digit0 is the tenth slot
                             (swap! state use-slot (:tiles (here))
                                    (mod (dec (js/parseInt (subs (.-code e) 5))) bag-size)))
                           (when (game-keys (game-key (.-code e)))
                             (.preventDefault e)
                             (swap! state update :keys conj (game-key (.-code e)))))))
             on-up (fn [^js e] (swap! state update :keys disj (game-key (.-code e))))
             on-blur (fn [] (swap! state assoc :keys #{} :mouse-down? false))
             ;; browsers follow a tap with made-up mouse events at the tapped spot, which
             ;; would move the aim onto the touch buttons, so mouse events soon after a
             ;; touch are ignored
             last-touch (atom -1e9)
             touched-lately? #(< (- (js/performance.now) @last-touch) 1000)
             on-any-touch (fn [] (reset! last-touch (js/performance.now)) (set-touch! true))
             on-move (fn [^js e]
                       (when-not (touched-lately?)
                         (swap! state assoc :mouse [(.-clientX e) (.-clientY e)])))
             on-press (fn [^js e]
                        (when (and (zero? (.-button e)) (not (touched-lately?)))
                          (swap! state assoc :mouse-down? true
                                 :mouse [(.-clientX e) (.-clientY e)])))
             on-release (fn [] (when-not (touched-lately?) (swap! state assoc :mouse-down? false)))
             ;; one finger on the canvas aims and digs like the mouse
             aim-touch (atom nil)
             find-touch (fn [^js e]
                          (some #(when (= @aim-touch (.-identifier %)) %)
                                (array-seq (.-changedTouches e))))
             on-touch-start (fn [^js e]
                              (.preventDefault e)
                              (on-any-touch)
                              (when (nil? @aim-touch)
                                (let [^js t (aget (.-changedTouches e) 0)]
                                  (reset! aim-touch (.-identifier t))
                                  (swap! state assoc :mouse-down? true
                                         :mouse [(.-clientX t) (.-clientY t)]))))
             on-touch-move (fn [^js e]
                             (.preventDefault e)
                             (when-let [^js t (find-touch e)]
                               (swap! state assoc :mouse [(.-clientX t) (.-clientY t)])))
             on-touch-end (fn [^js e]
                            (on-any-touch)
                            (when (find-touch e)
                              (reset! aim-touch nil)
                              (swap! state assoc :mouse-down? false)))
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
                             _ (when @last-t (watch-speed! speed (- t @last-t)))
                             ;; follows a change of the Graphics setting or a switch to fast
                             _ (when (not= (.-width canvas) (* (render-scale) js/window.innerWidth)) (resize))
                             _ (reset! last-t t)
                             space? (= :space (:realm @state))
                             player (:player (swap! state #(-> %
                                                               (update :player
                                                                       (fn [p] (cond-> (step-player (:tiles (here)) p (:keys %) dt)
                                                                                 space? wrap-space)))
                                                               cross-edge)))
                             dpr (render-scale)
                             vw js/window.innerWidth
                             vh js/window.innerHeight
                             ;; space wraps round, so its camera follows the player without stopping at edges
                             cam-x (if space?
                                     (- (+ (:x player) (/ player-w 2)) (/ vw 2))
                                     (-> (- (+ (:x player) (/ player-w 2)) (/ vw 2))
                                         (max 0) (min (- (* W T) vw))))
                             cam-y (if space?
                                     (- (+ (:y player) (/ player-h 2)) (/ vh 2))
                                     (-> (- (:y player) (* vh 0.42))
                                         (max 0) (min (- (* H T) vh))))
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
                                              (:overworld :mountains) (if (:day? tod) :overworld-day :overworld-night)
                                              :slime :slime-hollow
                                              ;; the throwing part plays from the leap onto the ledge until the boss jumps down
                                              :arena (case (get-in @state [:boss :mode])
                                                       nil :slime-hollow
                                                       (:leap :perch) :boss-throw
                                                       :boss-ground)
                                              :space :asteroid-drift
                                              nil))
                             _ (swing! state (:tiles (here)) dt)
                             ;; the Slime Dimension stays as dark as night; the arena is dimly lit, and
                             ;; space is bright enough that no slimes come out
                             light (case (:realm @state) (:overworld :mountains) (:light tod) :arena 0.45 :space 0.8 0.15)
                             world (here)
                             _ (when-let [levels (:levels world)]
                                 (swap! flips not)
                                 (flow-ooze! (:tiles world) levels @flips))
                             _ (step-enemies! state (:tiles world) (:surface world) light dt)
                             _ (step-boss! state (:tiles world) dt)
                             _ (swap! state #(-> % (step-fluffies clock dt) settle-fluffies check-colour-wish offer-quest (step-sky dt) (step-space dt) (step-robbers clock dt) (step-goats dt) (step-paper clock dt) sow-old-hay discover))
                             _ (set-quest! (:quest @state))
                             _ (when (and (= :slime (:realm @state)) (:gate-open? @state)
                                          (touching? player (assoc (:gate world) :w gate-w :h gate-h)))
                                 (swap! state enter-arena))
                             _ (set-boss-hp! (get-in @state [:boss :hp]))
                             _ (open-chests! state)
                             near-shop (by-shop? @state)
                             ;; walking away from the shop closes its panel
                             _ (when (and (not near-shop) (:shop-open? @state))
                                 (swap! state assoc :shop-open? false))
                             _ (set-at-shop! (and near-shop (:shop-open? @state)))
                             _ (swap! state
                                      (fn [st]
                                        (if (<= (get-in st [:player :hp] max-hp) 0)
                                          (-> st
                                              (tally :fainted 1)
                                              (cond-> (not= :overworld (:realm st)) (switch-realm :overworld))
                                              (dissoc :boss)
                                              (assoc :player (spawn home))
                                              (update :popups conj
                                                      (let [p (spawn home)]
                                                        {:text "You fainted!" :x (+ (:x p) (/ player-w 2))
                                                         :y (- (:y p) 10) :life 150})))
                                          (let [rate (if (near-torch? (:tiles world) (:player st)) (* 2 regen) regen)]
                                            (update-in st [:player :hp] #(min max-hp (+ (or % max-hp) (* rate dt))))))))
                             _ (set-coins! (:coins @state 0))
                             _ (set-bag! (:bag @state))
                             _ (set-map! (boolean (:map? @state)))
                             _ (set-space-map! (boolean (:space-map? @state)))
                             _ (set-in-space! space?)
                             _ (set-paper! (when (:paper-open? @state) (:paper @state)))
                             _ (set-paper-day! (get-in @state [:paper :day]))
                             _ (set-star-shop! (boolean (or (:star-shop? @state)
                                                            (pos? (star-count-in (:bag @state))))))
                             _ (set-hp! (/ (js/Math.floor (* 10 (get-in @state [:player :hp]))) 10))
                             {:keys [player particles chests popups enemies portal]}
                             (swap! state #(-> %
                                               (step-rockets dt)
                                               (update :particles step-particles dt)
                                               (update :popups step-popups dt)))]
                         (.setTransform ctx dpr 0 0 dpr 0 0)
                         (set! (.-imageSmoothingEnabled ctx) false)
                         (.clearRect ctx 0 0 vw vh)
                         (case (:realm @state)
                           :overworld (draw-hills ctx vw vh cam-x cam-y)
                           :mountains (draw-peaks ctx vw vh cam-x cam-y)
                           nil)
                         (doseq [[ox oy] (if space? (space-offsets) [[0 0]])
                                 :let [cam-x (- cam-x ox) cam-y (- cam-y oy)]]
                           (draw-tiles ctx (:tiles world) (:surface world) vw vh cam-x cam-y)
                           (when space?
                             (draw-stars ctx (:stars @state) t cam-x cam-y)
                             (draw-aliens ctx (:aliens @state) t cam-x cam-y))
                           (when (home?) (draw-shop ctx shop vw cam-x cam-y near-shop))
                           (draw-chests ctx chests vw vh cam-x cam-y)
                           (draw-gate ctx (:gate world) (and (= :slime (:realm @state)) (:gate-open? @state))
                                      t cam-x cam-y)
                           (draw-portal ctx portal t cam-x cam-y)
                           (draw-target ctx (:tiles world) player cam-x cam-y)
                           (draw-enemies ctx enemies t vw vh cam-x cam-y)
                           (when-let [boss (:boss @state)] (draw-boss ctx boss t cam-x cam-y))
                           (when (home?)
                             (when-let [ship (:ship @state)] (draw-ship ctx ship t cam-x cam-y))
                             (draw-drones ctx (:drones @state) t cam-x cam-y)
                             (draw-robbers ctx (:robbers @state) cam-x cam-y)
                             (when-let [b (:paperboy @state)]
                               (draw-paperboy ctx b cam-x cam-y (paperboy-label @state))))
                           (when space?
                             (draw-ship ctx (get-in @state [:worlds :space :ship]) t cam-x cam-y))
                           (when (= :mountains (:realm @state))
                             (draw-goats ctx (:goats @state) clock cam-x cam-y))
                           (draw-houses ctx (:houses @state) (:realm @state) cam-x cam-y)
                           (draw-mirrors ctx (:mirrors @state) (:realm @state) player space? cam-x cam-y)
                           (draw-fluffies ctx (:fluffies @state) (:realm @state) clock cam-x cam-y)
                           (draw-house-rims ctx (:houses @state) (:realm @state) cam-x cam-y)
                           (when (even? (js/Math.floor (/ (:hurt player 0) 4)))
                             (let [cam-y (if (:riding @state) (+ cam-y 12) cam-y)]
                               (draw-player ctx player cam-x cam-y space?)
                               (draw-pickaxe ctx player cam-x cam-y)))
                           (when (= :mountains (:realm @state))
                             (draw-goat-marks ctx @state clock cam-x cam-y))
                           (when-let [levels (:levels world)]
                             (draw-ooze ctx (:tiles world) levels vw vh cam-x cam-y t))
                           (draw-rockets ctx (:rockets @state) cam-x cam-y)
                           (draw-particles ctx particles cam-x cam-y))
                         (when (outdoors?)
                           (draw-depth ctx (if (home?) (aget (:surface world) (quot W 2)) valley-floor) vw vh cam-y))
                         (draw-night ctx vw vh
                                     (- (+ (:x player) (/ player-w 2)) cam-x)
                                     (- (+ (:y player) (/ player-h 2)) cam-y)
                                     light)
                         (doseq [[ox oy] (if space? (space-offsets) [[0 0]])]
                           (draw-flames ctx (:tiles world) vw vh (- cam-x ox) (- cam-y oy) t light))
                         (cond
                           (outdoors?) (draw-sky ctx vw vh cam-x t tod)
                           space? (draw-space-sky ctx vw vh cam-x cam-y)
                           :else
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
         (.addEventListener js/window "touchstart" on-any-touch #js {:passive true})
         (.addEventListener js/window "touchend" on-any-touch #js {:passive true})
         (.addEventListener canvas "touchstart" on-touch-start #js {:passive false})
         (.addEventListener canvas "touchmove" on-touch-move #js {:passive false})
         (.addEventListener canvas "touchend" on-touch-end)
         (.addEventListener canvas "touchcancel" on-touch-end)
         (reset! raf (js/requestAnimationFrame frame))
         (fn []
           (js/cancelAnimationFrame @raf)
           (.removeEventListener js/window "resize" resize)
           (.removeEventListener js/window "keydown" on-down)
           (.removeEventListener js/window "keyup" on-up)
           (.removeEventListener js/window "blur" on-blur)
           (.removeEventListener js/window "mousemove" on-move)
           (.removeEventListener canvas "mousedown" on-press)
           (.removeEventListener js/window "mouseup" on-release)
           (.removeEventListener js/window "touchstart" on-any-touch)
           (.removeEventListener js/window "touchend" on-any-touch)
           (.removeEventListener canvas "touchstart" on-touch-start)
           (.removeEventListener canvas "touchmove" on-touch-move)
           (.removeEventListener canvas "touchend" on-touch-end)
           (.removeEventListener canvas "touchcancel" on-touch-end))))
     [])
    ($ :div {:class "fixed inset-0 overflow-hidden bg-sky-300"}
       ($ :canvas {:ref canvas-ref :class "block w-full h-full touch-none [image-rendering:pixelated]"})
       ($ :div {:class "absolute top-4 left-4 flex flex-col gap-2"}
          ($ bag-bar {:bag bag :on-use (when touch? on-use-slot)})
          ($ health-bar {:hp hp})
          ($ quest-panel {:quest quest :bag bag}))
       ($ coin-panel {:coins coins :on-menu #(set-menu! not)
                      :map? (or map? space-map?) :map-shown? map-shown? :on-map #(set-map-shown! not)})
       (when map-shown?
         (cond
           (and in-space? space-map?)
           ($ :div {:class "absolute top-20 right-4 select-none"}
              ($ space-map {:state-ref state-ref}))

           map?
           ($ :div {:class "absolute top-20 right-4 select-none"}
              ($ mini-map {:state-ref state-ref}))))
       (when boss-hp
         ($ boss-bar {:hp boss-hp}))
       (when touch?
         ($ :<>
            ($ touch-pad {:state-ref state-ref})
            ($ touch-e-button {:on-press on-press-e})))
       (when at-shop?
         ($ shop-panel {:coins coins :bag bag :on-sell on-sell :on-buy on-buy
                        :map? map? :on-buy-map on-buy-map
                        :star-shop? star-shop? :on-buy-star on-buy-star
                        :space-map? space-map? :on-buy-space-map on-buy-space-map}))
       ;; the last paper bought opens again from here, anywhere and for free; the button
       ;; stays above the Polku widget and the tablet E button
       (when (and paper-day (not paper))
         ($ :button {:class (str "absolute right-4 flex items-center gap-2 rounded-md border border-stone-400 bg-[#f4ecd8] px-3 py-1.5 font-serif text-sm font-bold text-stone-900 shadow-lg select-none hover:bg-white "
                                 (if touch? "bottom-40" "bottom-20"))
                     :title "Read the last newspaper you bought"
                     :on-click #(do (.blur (.-currentTarget %))
                                    (some-> @state-ref (swap! assoc :paper-open? true)))}
            ($ :span {:class "text-base leading-none"} "📰")
            (str "The Times · Day " paper-day)))
       (when paper
         ($ newspaper-panel {:paper paper
                             :on-close #(some-> @state-ref (swap! assoc :paper-open? false))}))
       (when menu?
         ($ menu-panel {:on-close #(set-menu! false)
                        ;; the game is paused while the menu is open, so this stays current
                        :seen (some-> @state-ref deref :seen)
                        :snapshot #(save-data @@state-ref)
                        :restore (fn [data]
                                   (sync! (swap! @state-ref load-data data)))})))))
