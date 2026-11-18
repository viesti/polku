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

(defn generate-slime
      "Builds the slime dimension: a world of caves with no sky, an arrival chamber with a
  portal back in the middle, and pools of ooze at the bottom of some caves."
      []
      (let [tiles (js/Uint8Array. (* W H))
            surface (.fill (js/Int32Array. W) -1)
            mid (quot W 2)
            cy (quot H 2)
            chamber? (fn [x y] (and (<= (- mid 10) x (+ mid 10)) (<= (- cy 7) y (+ cy 2))))]
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
           {:tiles tiles :surface surface :chests []
            :levels (js/Float32Array.from tiles #(if (= % ooze) 1 0))
            :portal {:x (- (* T mid) 48)
                     :y (- (* T (inc cy)) portal-h)
                     :back-x (* T mid)}}))

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

(declare strike-enemies stash portal-aimed? teleport)

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

             (and (<= left 0) (or mouse-down? (keys "ShiftLeft") (keys "ShiftRight")))
             (let [struck (strike-enemies state)
                   hits (if struck [] (filterv (fn [[tx ty]] (in-world? tx ty)) (dig-targets tiles player)))
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
                                                         {:text (str "+" pieces " 🪢 Rope") :x (* T (+ tx 0.5)) :y (* T ty) :life 90})))))
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

(defn- draw-tiles [^js ctx tiles ^js surface vw vh cam-x cam-y]
       (let [x0 (max 0 (js/Math.floor (/ cam-x T)))
             x1 (min (dec W) (js/Math.floor (/ (+ cam-x vw) T)))
             y0 (max 0 (js/Math.floor (/ cam-y T)))
             y1 (min (dec H) (js/Math.floor (/ (+ cam-y vh) T)))]
            (doseq [tx (range x0 (inc x1)) ty (range y0 (inc y1))]
                   (let [t (aget tiles (idx tx ty))
                         sx (js/Math.round (- (* tx T) cam-x))
                         sy (js/Math.round (- (* ty T) cam-y))
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
                         (cave-bg))))))

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

(defn- add-items [inventory items]
       (reduce (fn [inv {:keys [id count] :as item}]
                   (if-let [i (first (keep-indexed #(when (= id (:id %2)) %1) inv))]
                           (update-in inv [i :count] + count)
                           (conj inv item)))
               inventory
               items))

(def bag-size 5)
(def bag-ids #{:potion :rope :dirt :stone :amulet})

(defn- bag-slot
       "Returns the bag slot that holds `id`, or else the first empty slot, or nil."
       [bag id]
       (or (first (keep-indexed (fn [i s] (when (= id (:id s)) i)) bag))
           (first (keep-indexed (fn [i s] (when-not s i)) bag))))

(defn- stash
       "Stacks potions, rope and blocks from `items` into the bag, one kind per slot and up
  to `stack-max`. Other items, and potions or rope that find no room, go into the
  inventory. Blocks that find no room are dropped."
       [st items]
       (reduce (fn [st {:keys [id count] :as item}]
                   (if-let [i (and (bag-ids id) (bag-slot (:bag st) id))]
                           (let [n (min count (- (stack-max id ##Inf) (get-in st [:bag i :count] 0)))
                                 st (cond-> st
                                            (pos? n) (update-in [:bag i] (fn [s] (-> (or s (select-keys item [:id :name :icon]))
                                                                                     (update :count (fnil + 0) n)))))]
                                (cond-> st
                                        (and (< n count) (not (blocks id)))
                                        (update :inventory add-items [(assoc item :count (- count n))])))
                           (cond-> st
                                   (not (blocks id)) (update :inventory add-items [item]))))
               st items))

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
                                                          (map-indexed (fn [n {:keys [icon name count]}]
                                                                           {:text (str "+" count " " icon " " name)
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
       (doseq [{:keys [text x y life]} ps]
              (set! (.-globalAlpha ctx) (min 1 (/ life 30)))
              (.strokeText ctx text (- x cam-x) (- y cam-y))
              (set! (.-fillStyle ctx) "#ffe680")
              (.fillText ctx text (- x cam-x) (- y cam-y)))
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
                  (.fillText ctx "🪙 SHOP" (+ sx 56) (+ top 31)))))

(defn- near-shop? [{:keys [x y]} {:keys [tx ty]}]
       (let [sx (* tx T) base (* ty T)]
            (and (< (- sx 8) (+ x player-w)) (< x (+ sx (* shop-w T) 8))
                 (< (- base (* shop-h T)) (+ y player-h)) (< y (+ base 8)))))

(defn- sell [inventory id n]
       (let [{:keys [count]} (first (filter #(= id (:id %)) inventory))
             n (min n (or count 0))
             earned (* n (prices id 0))]
            (if (pos? n)
                (-> (into [] (keep (fn [item]
                                       (cond
                                        (not= id (:id item)) item
                                        (> (:count item) n) (update item :count - n))))
                          inventory)
                    (add-items [(assoc (select-keys coin [:id :name :icon]) :count earned)]))
                inventory)))

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
                        amulets (filterv (fn [_] (< (js/Math.random) amulet-chance)) dead)]
                       (swap! state
                              (fn [st]
                                  (cond-> (-> st
                                              (assoc :enemies (filterv #(pos? (:hp %)) hit))
                                              (update :particles into
                                                      (mapcat #(puff % slime-color (if (pos? (:hp %)) 4 14)) hit)))
                                          (pos? coins)
                                          (-> (update :inventory add-items
                                                      [(assoc (select-keys coin [:id :name :icon]) :count coins)])
                                              (update :popups conj
                                                      (let [{:keys [x y]} (first dead)]
                                                           {:text (str "+" coins " 🪙") :x (+ x (/ slime-w 2)) :y (- y 6)
                                                            :life 90})))
                                          (seq amulets)
                                          (-> (stash [(assoc amulet :count (count amulets))])
                                              (update :popups conj
                                                      (let [{:keys [x y]} (first amulets)]
                                                           {:text "+1 🧿 Dimension Amulet!" :x (+ x (/ slime-w 2)) :y (- y 22)
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

(defn- teleport
       "Moves the player through the portal of the current realm to the other realm, next
  to the portal there."
       [{:keys [realm] :as st}]
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
                                      :x px :y (- y 14) :life 150}))))

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
     {:potion 10 :rope 5})

(defn- coins [inventory]
       (reduce + (map :count (filter #(= :coin (:id %)) inventory))))

(defn- remove-items [inventory id n]
       (into [] (keep (fn [item]
                          (cond
                           (not= id (:id item)) item
                           (> (:count item) n) (update item :count - n))))
             inventory))

(def buy-amounts
     {:potion 1 :rope 5})

(defn- buy
       "Buys `buy-amounts` of `id` into the bag. Leaves the state unchanged when the bag has
  no slot for it or the player has too few coins."
       [{:keys [bag inventory] :as st} id]
       (let [cost (buy-prices id)]
            (if (and cost (bag-slot bag id) (>= (coins inventory) cost))
                (-> st
                    (update :inventory remove-items :coin cost)
                    (stash [(assoc (select-keys (first (filter #(= id (:id %)) loot)) [:id :name :icon])
                                   :count (buy-amounts id))]))
                st)))

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
                 (popup (str "+" potion-heal " ❤")))

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
                :let [{:keys [icon name count]} (get bag i)]]
               ($ :div {:key i :title name
                        :class "relative flex h-11 w-11 items-center justify-center rounded-md border border-white/20 bg-black/40 text-2xl"}
                  ($ :span {:class "absolute left-1 top-0.5 text-[10px] font-semibold text-white/70"} (inc i))
                  icon
                  (when (> count 1)
                        ($ :span {:class "absolute bottom-0 right-1 font-mono text-[11px] font-bold text-white"} count))))))

(defui health-bar [{:keys [hp]}]
       ($ :div {:class "w-56 rounded-md bg-black/40 px-3 py-2 text-sm text-white select-none"}
          ($ :div {:class "mb-1 flex items-center justify-between"}
             ($ :span {:class "font-semibold"} "❤ Health")
             ($ :span {:class "font-mono tabular-nums"} (js/Math.floor hp) " / " max-hp))
          ($ :div {:class "h-3 w-full overflow-hidden rounded-sm bg-red-950/80"}
             ($ :div {:class "h-full bg-red-500 transition-[width] duration-150"
                      :style {:width (str (* 100 (/ hp max-hp)) "%")}}))
          ($ :div {:class "mt-1 text-xs text-white/60"} "M menu")))

(defui menu-panel [{:keys [on-close]}]
       ($ :div {:class "absolute inset-0 flex items-center justify-center bg-black/50 select-none"
                :on-click on-close}
          ($ :div {:class "w-96 rounded-lg bg-slate-900/95 p-5 text-sm text-white shadow-xl"
                   :on-click #(.stopPropagation %)}
             ($ :div {:class "mb-3 text-lg font-bold"} "Menu")
             ($ :div {:class "mb-2 font-semibold text-white/80"} "How to play")
             ($ :ul {:class "space-y-1 text-white/90"}
                ($ :li "A and D move, Space jumps")
                ($ :li "The mouse aims the pickaxe")
                ($ :li "Shift or click digs or hits slimes")
                ($ :li "Keys 1 to 5 use the items in your bag; a Healing Potion gives +" potion-heal " health")
                ($ :li "The bag has " bag-size " slots, each holding a stack of one kind of item")
                ($ :li "Rope hangs down from where the mouse points, one rope per block; jump into it to grab it")
                ($ :li "Hit a rope with the pickaxe to take all of it back")
                ($ :li "Digging gives Dirt and Stone blocks (up to 100 each); use them from the bag to build")
                ($ :li "On a rope, Space or W climbs, S climbs down, A or D jumps off")
                ($ :li "Slimes come out in the caves at night; one in twenty drops a Dimension Amulet")
                ($ :li "Use the amulet on the surface to open a portal, then hit the portal with the pickaxe to enter the Slime Dimension, where darker slimes take twice the hits and hit twice as hard")
                ($ :li "In green ooze you move slowly; hold Space to swim up")
                ($ :li "Find the shop on the surface to sell your finds and buy potions and rope"))
             ($ :div {:class "mt-4 text-xs text-white/60"} "Press M again to close"))))

(defn- typing? [^js e]
       (let [tag (some-> e .-target .-tagName)]
            (or (= tag "INPUT") (= tag "TEXTAREA") (some-> e .-target .-isContentEditable))))

(def game-keys #{"KeyA" "KeyD" "KeyW" "KeyS" "Space" "ShiftLeft" "ShiftRight"})

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

(defui shop-panel [{:keys [items bag on-sell on-buy]}]
       (let [sellable (filter #(prices (:id %)) items)
             money (coins items)]
            ($ :div {:class "absolute bottom-6 left-1/2 w-80 -translate-x-1/2 rounded-lg border-2 border-amber-700 bg-amber-950/90 p-4 text-sm text-amber-50 shadow-xl select-none"}
               ($ :div {:class "mb-1 text-base font-bold"} "🪙 Shop")
               ($ :div {:class "mb-2 font-semibold text-amber-200"} "Buy")
               ($ :ul {:class "mb-1 space-y-1"}
                  (for [[id cost] buy-prices
                        :let [{:keys [icon name]} (first (filter #(= id (:id %)) loot))
                              room? (bag-slot bag id)
                              ok? (and room? (>= money cost))]]
                       ($ :li {:key (str id) :class "flex items-center gap-2 rounded bg-white/10 px-2 py-1"}
                          ($ :span {:class "text-base"} icon)
                          ($ :span {:class "flex-1"} name (when (> (buy-amounts id) 1) (str " ×" (buy-amounts id))))
                          ($ :span {:class "font-mono text-amber-300"} cost " 🪙")
                          ($ :button {:class (if ok?
                                                 "rounded bg-emerald-600 px-2 py-0.5 font-semibold hover:bg-emerald-500"
                                                 "cursor-not-allowed rounded bg-white/10 px-2 py-0.5 font-semibold text-white/40")
                                      :disabled (not ok?)
                                      :on-click #(do (.blur (.-currentTarget %)) (on-buy id))}
                             "Buy"))))
               ($ :div {:class "mb-3 text-xs text-amber-200/70"}
                  (str "You have " money " 🪙."))
               ($ :div {:class "mb-2 font-semibold text-amber-200"} "Sell")
               (if (empty? sellable)
                   ($ :div {:class "text-amber-200/70"} "You have nothing to sell yet.")
                   ($ :ul {:class "space-y-1"}
                      (for [{:keys [id icon name count]} sellable]
                           ($ :li {:key (str id) :class "flex items-center gap-2 rounded bg-white/10 px-2 py-1"}
                              ($ :span {:class "text-base"} icon)
                              ($ :span {:class "flex-1"} name " ×" count)
                              ($ :span {:class "font-mono text-amber-300"} (prices id) " 🪙")
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
             [items set-items!] (use-state [])
             [at-shop? set-at-shop!] (use-state false)
             [hp set-hp!] (use-state max-hp)
             [menu? set-menu!] (use-state false)
             [bag set-bag!] (use-state [])
             on-buy (fn [id]
                        (when-let [state @state-ref]
                                  (let [st (swap! state buy id)]
                                       (set-items! (:inventory st))
                                       (set-bag! (:bag st)))))
             on-sell (fn [id n]
                         (when-let [state @state-ref]
                                   (let [before (:inventory @state)
                                         after (:inventory (swap! state update :inventory sell id n))]
                                        (when (not= before after)
                                              (let [{:keys [x y]} (:player @state)
                                                    earned (- (reduce + (map :count (filter #(= :coin (:id %)) after)))
                                                              (reduce + (map :count (filter #(= :coin (:id %)) before))))]
                                                   (swap! state update :popups conj
                                                          {:text (str "+" earned " 🪙") :x (+ x (/ player-w 2)) :y (- y 10) :life 90}))
                                              (set-items! after)))))]
            (use-effect
             (fn []
                 (let [^js canvas @canvas-ref
                       ctx (.getContext canvas "2d")
                       home (generate)
                       state (atom {:player (spawn home) :keys #{} :particles []
                                    :chests (:chests home) :inventory [] :popups []
                                    :bag (vec (repeat bag-size nil))
                                    :realm :overworld
                                    :worlds {:overworld home :slime (generate-slime)}})
                       _ (reset! state-ref state)
                       here (fn [] (get-in @state [:worlds (:realm @state)]))
                       home? (fn [] (= :overworld (:realm @state)))
                       shop (:shop home)
                       raf (atom nil)
                       last-t (atom nil)
                       flips (atom false)
                       resize (fn []
                                  (let [dpr (or js/window.devicePixelRatio 1)]
                                       (set! (.-width canvas) (* dpr js/window.innerWidth))
                                       (set! (.-height canvas) (* dpr js/window.innerHeight))))
                       on-down (fn [^js e]
                                   (when-not (typing? e)
                                             (case (.-code e)
                                                   "KeyM" (when-not (.-repeat e) (set-menu! not))
                                                   ("Digit1" "Digit2" "Digit3" "Digit4" "Digit5")
                                                   (when-not (.-repeat e)
                                                             (swap! state use-slot (:tiles (here)) (dec (js/parseInt (subs (.-code e) 5)))))
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
                                 (let [dt (if @last-t (min 2 (/ (- t @last-t) 16.667)) 1)
                                       _ (reset! last-t t)
                                       player (:player (swap! state update :player
                                                              #(step-player (:tiles (here)) % (:keys @state) dt)))
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
                                       tod (time-of-day t)
                                       _ (when (swing! state (:tiles (here)) dt)
                                               (set-items! (:inventory @state)))
                                       ;; the Slime Dimension stays as dark as night
                                       light (if (home?) (:light tod) 0.15)
                                       world (here)
                                       _ (when-let [levels (:levels world)]
                                                   (swap! flips not)
                                                   (flow-ooze! (:tiles world) levels @flips))
                                       _ (step-enemies! state (:tiles world) (:surface world) light dt)
                                       _ (some-> (open-chests! state) :inventory set-items!)
                                       _ (set-at-shop! (and (home?) (near-shop? player shop)))
                                       _ (swap! state
                                                (fn [st]
                                                    (if (<= (get-in st [:player :hp] max-hp) 0)
                                                        (-> st
                                                            (cond-> (not= :overworld (:realm st)) (switch-realm :overworld))
                                                            (assoc :player (spawn home))
                                                            (update :popups conj
                                                                    (let [p (spawn home)]
                                                                         {:text "You fainted!" :x (+ (:x p) (/ player-w 2))
                                                                          :y (- (:y p) 10) :life 150})))
                                                        (update-in st [:player :hp] #(min max-hp (+ (or % max-hp) (* regen dt)))))))
                                       _ (set-bag! (:bag @state))
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
                                      (draw-portal ctx portal t cam-x cam-y)
                                      (draw-target ctx (:tiles world) player cam-x cam-y)
                                      (draw-enemies ctx enemies t vw vh cam-x cam-y)
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
               ($ :div {:class "absolute top-4 left-4 flex flex-col gap-2"}
                  ($ bag-bar {:bag bag})
                  ($ health-bar {:hp hp}))
               ($ inventory-panel {:items items})
               (when at-shop?
                     ($ shop-panel {:items items :bag bag :on-sell on-sell :on-buy on-buy}))
               (when menu?
                     ($ menu-panel {:on-close #(set-menu! false)})))))
