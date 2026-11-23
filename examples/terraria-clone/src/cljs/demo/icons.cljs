(ns demo.icons
  "Pixel-art icons for items and the HUD, drawn the same way on every system. Each
  icon is a 10 by 10 grid where every character names a colour in its palette and a
  dot is transparent."
  (:require [uix.core :refer [$ defui]]))

(defn- paint-can
  "Returns the art of a paint pot holding paint of `color` with a `light` drip."
  [color light]
  {:palette {\o "#3a3a44" \m "#b8b8c4" \p color \h light}
   :rows [".........."
          "...oooo..."
          "..o....o.."
          ".oooooooo."
          ".ommmmmmmo"
          ".ohppppppo"
          ".ohpppppho"
          ".opppppppo"
          ".ommmmmmmo"
          "..oooooo.."]})

(def ^:private art
  {:coin {:palette {\o "#7a4e00" \g "#ffc61a" \h "#fff1a0" \d "#d99400"}
          :rows ["..oooooo.."
                 ".ohhggggo."
                 "ohhggggddo"
                 "ohggdddgdo"
                 "oggdggggdo"
                 "oggdggggdo"
                 "ogggdddgdo"
                 "oggggggddo"
                 ".oddddddo."
                 "..oooooo.."]}
   :heart {:palette {\o "#5a0f12" \r "#e53935" \h "#ff9a9a"}
           :rows [".........."
                  ".oo....oo."
                  "ohro..orro"
                  "ohrroorrro"
                  "orrrrrrrro"
                  ".orrrrrro."
                  "..orrrro.."
                  "...orro..."
                  "....oo...."
                  ".........."]}
   :crown {:palette {\y "#ffd54a" \d "#b07d00" \r "#e53935" \b "#2f7bff"}
           :rows [".........."
                  "y...yy...y"
                  "yy..yy..yy"
                  "yyy.yy.yyy"
                  "yyyyyyyyyy"
                  "yyryybyryy"
                  "yyyyyyyyyy"
                  "dddddddddd"
                  ".........."
                  ".........."]}
   :dirt {:palette {\o "#3e2410" \b "#8b5a2b" \l "#a8743f" \d "#6a411d"}
          :rows ["oooooooooo"
                 "ollllllldo"
                 "olbbbbbbdo"
                 "obbdbbbbdo"
                 "obbbbbdbdo"
                 "obdbbbbbdo"
                 "obbbbdbbdo"
                 "obbbbbbbdo"
                 "oddddddddo"
                 "oooooooooo"]}
   :stone {:palette {\o "#34343a" \s "#8a8a92" \h "#b8b8c0" \d "#62626a"}
           :rows [".........."
                  "...oooo..."
                  "..ohhsso.."
                  ".ohssssdo."
                  ".ohssssddo"
                  "ohsssssddo"
                  "osssssdddo"
                  "osssdddddo"
                  ".oddddddo."
                  "..oooooo.."]}
   :torch {:palette {\y "#ffd54a" \r "#ff7a1a" \c "#fff6c0" \w "#8a5a2b" \d "#5a3418"}
           :rows ["....r....."
                  "...ryr...."
                  "...rcyr..."
                  "..rycyr..."
                  "..ryyyr..."
                  "...www...."
                  "....w....."
                  "....w....."
                  "....d....."
                  "....d....."]}
   :rope {:palette {\t "#d4ad6f" \d "#8a6a3a"}
          :rows [".........."
                 "...tttt..."
                 "..tddddt.."
                 ".td.tt.dt."
                 ".tdt..tdt."
                 ".tdt..tdt."
                 ".td.tt.dt."
                 "..tddddt.."
                 "...tttt.t."
                 ".......tt."]}
   :bar {:palette {\o "#2e3238" \s "#9aa4ae" \h "#dde4ea" \d "#6b7480"}
         :rows [".........."
                ".........."
                ".........."
                "...oooooo."
                "..ohhhhso."
                ".ohssssdo."
                "osssssddo."
                "oddddddo.."
                "oooooooo.."
                ".........."]}
   :potion {:palette {\o "#2a2a40" \g "#d6ecff" \r "#e53950" \h "#ff9aa8" \c "#a8743f"}
            :rows ["...cccc..."
                   "...oggo..."
                   "...oggo..."
                   "..oggggo.."
                   ".oggggggo."
                   "orhrrrrrro"
                   "orhrrrrrro"
                   "orrrrrrrro"
                   ".orrrrrro."
                   "..oooooo.."]}
   :ruby {:palette {\o "#5a0a1a" \r "#e0284a" \h "#ff8fa3" \d "#a3122e"}
          :rows [".........."
                 "..oooooo.."
                 ".ohhrrrdo."
                 "ohhrrrrddo"
                 "oooooooooo"
                 ".orrrrrdo."
                 "..orrrdo.."
                 "...ordo..."
                 "....oo...."
                 ".........."]}
   :mirror {:palette {\f "#c9a227" \d "#8a6a10" \m "#bfe3ff" \h "#ffffff"}
            :rows ["...ffff..."
                   "..fmmmhf.."
                   ".fmmmmhmf."
                   ".fmmmmmmf."
                   ".fmmmmmmf."
                   "..fmmmmf.."
                   "...ffff..."
                   "....dd...."
                   "....dd...."
                   "....ff...."]}
   :amulet {:palette {\s "#8a6a3a" \b "#1e5bd8" \w "#ffffff" \l "#7fb3ff" \k "#0a0a30"}
            :rows ["..s....s.."
                   "...s..s..."
                   "....ss...."
                   "..bbbbbb.."
                   ".bwwwwwwb."
                   ".bwllllwb."
                   ".bwlkklwb."
                   ".bwwllwwb."
                   "..bbbbbb.."
                   ".........."]}
   :house {:palette {\r "#3f7be0" \d "#2a5bb0" \w "#c98b4f" \s "#a8703c" \h "#2a1608" \p "#6b4423"}
           :rows ["....rr...."
                  "...rrrr..."
                  "..rrrrrr.."
                  ".rrrrrrrr."
                  "dddddddddd"
                  ".wwwwwwww."
                  ".wwwhhwww."
                  ".wwwhhwww."
                  ".ssssssss."
                  "....pp...."]}
   :paint-red (paint-can "#e53935" "#ff9a9a")
   :paint-yellow (paint-can "#ffd21a" "#fff1a0")
   :paint-blue (paint-can "#2f6fe0" "#9cc0ff")
   :star {:palette {\o "#9a6a00" \y "#ffd54a" \c "#fff6c0"}
          :rows ["....oo...."
                 "....yy...."
                 "...oyyo..."
                 "oooyccyooo"
                 "oyyyccyyyo"
                 ".oyyyyyyo."
                 "..oyyyyo.."
                 ".oyyooyyo."
                 ".oyo..oyo."
                 ".oo....oo."]}
   :key {:palette {\o "#6b4a00" \y "#ffcc33"}
         :rows [".........."
                ".ooo......"
                "oyyyo....."
                "oy.yoooooo"
                "oy.yyyyyyo"
                "oyyyoooyyo"
                ".ooo..oyo."
                ".......o.."
                ".........."
                ".........."]}
   :map {:palette {\o "#5a3a12" \p "#f3e2b3" \s "#d9c48c" \r "#e53935" \b "#3d7be8"}
         :rows [".........."
                "oooooooooo"
                "oppsppsppo"
                "opbspprppo"
                "oppsprrrpo"
                "opbsppspbo"
                "oppsppsppo"
                "opbsppspbo"
                "oooooooooo"
                ".........."]}})

(def ^:private pixels
  (into {}
        (for [[id {:keys [palette rows]}] art]
          [id (vec (for [[y row] (map-indexed vector rows)
                         [x ch] (map-indexed vector row)
                         :let [color (palette ch)]
                         :when color]
                     [x y color]))])))

(defui icon
  "Renders the icon for `id` as a crisp SVG of `size` CSS pixels."
  [{:keys [id size class]}]
  (when-let [px (pixels id)]
    ($ :svg {:view-box "0 0 10 10" :width size :height size :class class
             :shape-rendering "crispEdges" :aria-hidden true}
       (for [[x y color] px]
         ($ :rect {:key (str x "," y) :x x :y y :width 1.02 :height 1.02 :fill color})))))

(defn draw!
  "Draws the icon for `id` on a canvas with its top-left corner at `x`, `y`, each icon
  pixel `scale` canvas pixels wide."
  [^js ctx id x y scale]
  (doseq [[px py color] (pixels id)]
    (set! (.-fillStyle ctx) color)
    (.fillRect ctx (+ x (* px scale)) (+ y (* py scale)) scale scale)))
