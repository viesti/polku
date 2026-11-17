(ns demo.app
  (:require [demo.game :as game]
            [polku.client.core :as polku]
            [uix.core :refer [$ defui]]
            [uix.dom]))

(defui app []
  ($ :main
     ($ game/game)))

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
