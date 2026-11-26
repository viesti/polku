(ns demo.app
  (:require [demo.game :as game]
            [polku.client.core :as polku]
            [uix.core :refer [$ defui]]
            [uix.dom]))

(defui app []
  ($ :main
     ($ game/game)))

;; Polku server URL, set from POLKU_ENDPOINT in shadow-cljs.edn. Empty means port 4777 on the page's host
(goog-define polku-endpoint "")

(defonce root
  (uix.dom/create-root (js/document.getElementById "app")))

(defn ^:dev/after-load render []
  (uix.dom/render-root ($ app) root))

(defn init []
  (when ^boolean goog.DEBUG
    (polku/init (cond-> {:lang      "en-US"
                         :languages ["en-US" "fi-FI"]}
                  (seq polku-endpoint) (assoc :endpoint polku-endpoint))))
  (render))
