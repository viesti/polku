(ns demo.icons
  (:require ["lucide-react" :as lucide]
            [clojure.string :as str]
            [uix.core :refer [$ defui]]))

(defui butter-icon [props]
  ($ :svg (merge {:xmlns "http://www.w3.org/2000/svg" :view-box "0 0 24 24"
                  :fill "none" :stroke "currentColor" :stroke-width 2
                  :stroke-linecap "round" :stroke-linejoin "round"}
                 props)
     ($ :path {:d "M3 11 7 7h14l-4 4Z"})
     ($ :path {:d "M17 11v7l4-4V7"})
     ($ :rect {:x 3 :y 11 :width 14 :height 7 :rx 1})
     ($ :path {:d "M8 11v7"})))

(def ^:private keywords
  "Lucide icons and the grocery words that pick them. The first icon with a matching word wins."
  [[butter-icon           ["butter" "margarine"]]
   [lucide/Milk           ["milk" "cream" "yogurt" "yoghurt" "cheese"]]
   [lucide/Egg            ["egg"]]
   [lucide/Croissant      ["croissant" "pastry" "bun"]]
   [lucide/Wheat          ["bread" "flour" "rye" "oat" "cereal" "pasta" "rice"]]
   [lucide/Apple          ["apple" "pear" "peach" "fruit"]]
   [lucide/Banana         ["banana"]]
   [lucide/Cherry         ["cherry" "cherries" "berry" "berries" "strawberr" "blueberr"]]
   [lucide/Citrus         ["lemon" "lime" "orange" "grapefruit"]]
   [lucide/Grape          ["grape"]]
   [lucide/Carrot         ["carrot" "potato" "onion" "vegetable" "veggie"]]
   [lucide/Salad          ["salad" "lettuce" "cucumber" "tomato" "spinach" "cabbage"]]
   [lucide/Drumstick      ["chicken" "turkey"]]
   [lucide/Ham            ["ham" "pork" "bacon" "sausage"]]
   [lucide/Beef           ["beef" "steak" "meat" "mince"]]
   [lucide/Fish           ["fish" "salmon" "tuna" "shrimp" "prawn"]]
   [lucide/Nut            ["nut" "almond" "peanut"]]
   [lucide/Bean           ["bean" "lentil" "pea"]]
   [lucide/Leaf           ["herb" "basil" "parsley" "dill" "tea"]]
   [lucide/Coffee         ["coffee"]]
   [lucide/Wine           ["wine"]]
   [lucide/Beer           ["beer" "cider"]]
   [lucide/CupSoda        ["soda" "juice" "cola" "lemonade"]]
   [lucide/Droplet        ["water" "oil" "vinegar"]]
   [lucide/IceCreamCone   ["ice cream"]]
   [lucide/Cake           ["cake"]]
   [lucide/Donut          ["donut" "doughnut"]]
   [lucide/Cookie         ["cookie" "biscuit"]]
   [lucide/Candy          ["candy" "sweet" "chocolate"]]
   [lucide/Popcorn        ["popcorn" "chips" "crisps"]]
   [lucide/Pizza          ["pizza"]]
   [lucide/Sandwich       ["sandwich"]]
   [lucide/Soup           ["soup"]]])

(defn- icon-for [item-name]
  (let [name (str/lower-case item-name)]
    (or (some (fn [[icon words]]
                (when (some #(str/includes? name %) words) icon))
              keywords)
        lucide/ShoppingBasket)))

(defui item-icon [{:keys [name class]}]
  ($ (icon-for name) {:class class :aria-hidden true}))
