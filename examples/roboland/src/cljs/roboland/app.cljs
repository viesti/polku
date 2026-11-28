(ns roboland.app
  (:require [polku.client.core :as polku]
            [roboland.game :as game]
            [uix.core :refer [$ defui use-effect use-ref]]
            [uix.dom]))

(defui world []
  (let [container (use-ref nil)]
    (use-effect
     (fn [] (game/start! @container))
     [])
    ($ :div {:ref container :class "absolute inset-0"})))

;; Bumped on every reload so the 3D world restarts with the new code
(defonce reloads (atom 0))

(defui app [{:keys [reload]}]
  ($ :main {:class "fixed inset-0 overflow-hidden bg-sky-200 select-none"}
     ($ world {:key reload})
     ($ :div {:class "pointer-events-none absolute left-4 top-4 rounded-lg bg-slate-900/60 px-4 py-2 text-slate-100"}
        ($ :h1 {:class "text-2xl font-bold"} "Roboland")
        ($ :p {:class "text-sm opacity-80"} "Pidä hiiren nappia pohjassa ja osoita, minne robotti kävelee. Rullalla zoomaat."))))

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
  (uix.dom/render-root ($ app {:reload (swap! reloads inc)}) root))

(defn init []
  (when ^boolean goog.DEBUG
    (polku/init {:lang      "en-US"
                 :languages ["en-US" "fi-FI"]
                 :endpoint  (polku-url)}))
  (render))
