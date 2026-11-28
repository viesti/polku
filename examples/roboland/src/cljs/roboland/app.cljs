(ns roboland.app
  (:require [polku.client.core :as polku]
            [uix.core :refer [$ defui]]
            [uix.dom]))

(defui app []
  ($ :main {:class "min-h-screen flex items-center justify-center bg-slate-900 text-slate-100"}
     ($ :h1 {:class "text-4xl font-bold"} "Roboland")))

;; Polku server URL, set from POLKU_ENDPOINT in shadow-cljs.edn
(goog-define polku-endpoint "")

(defn- polku-url
  "POLKU_ENDPOINT when set, otherwise port 4877 on the host that served the page. Polku's own
  default is port 4777, which Mine Valley uses."
  []
  (if (seq polku-endpoint)
    polku-endpoint
    (str js/location.protocol "//" (or (not-empty js/location.hostname) "localhost") ":4877")))

(defonce root
  (uix.dom/create-root (js/document.getElementById "app")))

(defn ^:dev/after-load render []
  (uix.dom/render-root ($ app) root))

(defn init []
  (when ^boolean goog.DEBUG
    (polku/init {:lang      "en-US"
                 :languages ["en-US" "fi-FI"]
                 :endpoint  (polku-url)}))
  (render))
