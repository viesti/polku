(ns demo.app
  (:require [polku.client.core :as polku]
            [uix.core :refer [$ defui]]
            [uix.dom]))

(defui app []
  ($ :main))

(defonce root
  (uix.dom/create-root (js/document.getElementById "app")))

(defn ^:dev/after-load render []
  (uix.dom/render-root ($ app) root))

(defn init []
  (when ^boolean goog.DEBUG
    (polku/init {:endpoint "http://localhost:4777" :lang "en-US"}))
  (render))
