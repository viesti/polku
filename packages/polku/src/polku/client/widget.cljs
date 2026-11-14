(ns polku.client.widget
  "Draws the floating recording control inside a shadow root.")

(def host-tag "polku-widget")
(def ^:private max-steps 5)

(def ^:private css "
:host { all: initial; position: fixed; right: 16px; bottom: 16px; z-index: 2147483647;
  font: 13px/1.4 system-ui, -apple-system, 'Segoe UI', sans-serif; color: #111827; }
* { box-sizing: border-box; }
button { font: inherit; cursor: pointer; border: 0; border-radius: 999px; padding: 7px 14px; }
.pill { display: flex; align-items: center; gap: 8px; background: #111827; color: #fff;
  border-radius: 999px; padding: 7px 14px; box-shadow: 0 4px 14px rgba(0,0,0,.25); }
.dot { width: 10px; height: 10px; border-radius: 50%; background: #ef4444; flex: none; }
.rec .dot { animation: pulse 1.2s infinite; }
@keyframes pulse { 50% { opacity: .25; } }
.panel { width: 300px; background: #fff; border: 1px solid #e5e7eb; border-radius: 14px;
  padding: 12px; box-shadow: 0 8px 28px rgba(0,0,0,.22); }
.head { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.head .time { margin-left: auto; font-variant-numeric: tabular-nums; font-weight: 400; color: #6b7280; }
.caps { margin: 6px 0; color: #6b7280; font-size: 12px; }
.caps .off { color: #b45309; }
.heard { min-height: 18px; margin: 6px 0; color: #374151; font-style: italic; overflow-wrap: anywhere; }
textarea { width: 100%; min-height: 54px; resize: vertical; font: inherit; color: inherit; background: #fff;
  border: 1px solid #d1d5db; border-radius: 8px; padding: 6px 8px; }
.row { display: flex; gap: 8px; margin-top: 10px; }
.primary { background: #111827; color: #fff; flex: 1; }
.ghost { background: #f3f4f6; color: #111827; }
.msg { margin: 8px 0 0; overflow-wrap: anywhere; }
.steps { list-style: none; margin: 8px 0 0; padding: 0; overflow-wrap: anywhere; }
.steps li { margin-top: 4px; padding-left: 18px; text-indent: -18px; }
.steps .past { color: #6b7280; }
.steps .now::before { content: ''; display: inline-block; width: 8px; height: 8px; margin: 0 10px 1px 0;
  border-radius: 50%; background: #111827; animation: pulse 1.2s infinite; }
.error { color: #b91c1c; }
.spin { width: 12px; height: 12px; border: 2px solid #d1d5db; border-top-color: #111827;
  border-radius: 50%; animation: spin .8s linear infinite; flex: none; }
.pill .spin { border-color: #4b5563; border-top-color: #fff; }
@keyframes spin { to { transform: rotate(360deg); } }
")

(defn- h
  "Creates an element. Keys of `props` that start with `on` become event listeners."
  [tag props & children]
  (let [^js el (js/document.createElement tag)]
    (doseq [[k v] props
            :let [k (name k)]
            :when (some? v)]
      (if (= "on" (subs k 0 2))
        (.addEventListener el (subs k 2) v)
        (.setAttribute el k v)))
    (doseq [c children :when c]
      (.append el c))
    el))

(defn- clock [ms]
  (let [s (js/Math.floor (/ ms 1000))]
    (str (js/Math.floor (/ s 60)) ":" (.padStart (str (mod s 60)) 2 "0"))))

(defn- cap [on? label]
  (h "span" {:class (if on? "" "off")} (str (if on? "✓ " "✕ ") label)))

(defn- spinner-pill [label]
  (h "div" {:class "pill"} (h "span" {:class "spin"}) label))

(defn- recording-view [{:keys [capture heard started-at]} {:keys [stop cancel]} !ui]
  (let [time-el  (h "span" {:class "time"} (clock (- (js/Date.now) started-at)))
        heard-el (h "div" {:class "heard"} (or heard ""))
        ^js input (h "textarea"
                     {:placeholder (if (:speech capture)
                                     "Say what you want changed, or type it here"
                                     "Type what you want changed")
                      :oninput     (fn [^js e] (swap! !ui assoc :note (.-value (.-target e))))})
        take-note (fn [] (let [note (:note @!ui)] (swap! !ui assoc :note "") note))]
    (set! (.-value input) (:note @!ui))
    (swap! !ui assoc :time-el time-el :heard-el heard-el)
    (h "div" {:class "panel rec"}
       (h "div" {:class "head"} (h "span" {:class "dot"}) "Recording" time-el)
       (h "div" {:class "caps"}
          (cap (:speech capture) "voice") " · " (cap (:screen capture) "screen") " · ✓ pointer")
       heard-el
       input
       (h "div" {:class "row"}
          (h "button" {:class "primary" :onclick (fn [_] (stop (take-note)))} "Stop & send")
          (h "button" {:class "ghost" :onclick (fn [_] (take-note) (cancel))} "Cancel")))))

(defn- steps
  "Lists the last few steps the agent reported, marking the latest as the one in progress."
  [progress]
  (when (seq progress)
    (let [shown (take-last max-steps progress)]
      (apply h "ul" {:class "steps"}
             (concat (for [text (butlast shown)]
                       (h "li" {:class "past"} (str "✓ " text)))
                     [(h "li" {:class "now"} (last shown))])))))

(defn- view [state {:keys [start dismiss] :as actions} !ui]
  (case (:view state)
    :idle      (h "button" {:class "pill" :title "Record a change request" :onclick (fn [_] (start))}
                  (h "span" {:class "dot"}) "Record")
    :starting  (spinner-pill "Starting…")
    :recording (recording-view state actions !ui)
    :sending   (spinner-pill "Sending…")
    :waiting   (h "div" {:class "panel"}
                  (h "div" {:class "head"}
                     (h "span" {:class "spin"})
                     (if (= "claimed" (:status state))
                       "The agent is working on it…"
                       "Sent. Waiting for the agent…"))
                  (steps (:progress state))
                  (h "div" {:class "row"}
                     (h "button" {:class "ghost" :onclick (fn [_] (dismiss))} "Hide")))
    :done      (h "div" {:class "panel"}
                  (h "div" {:class "head"} "✓ Done")
                  (h "p" {:class "msg"} (or (:message state) "The change is in."))
                  (h "div" {:class "row"}
                     (h "button" {:class "primary" :onclick (fn [_] (start))} "Record another")
                     (h "button" {:class "ghost" :onclick (fn [_] (dismiss))} "Close")))
    :error     (h "div" {:class "panel"}
                  (h "div" {:class "head error"} "Polku could not record")
                  (h "p" {:class "msg"} (:message state))
                  (h "div" {:class "row"}
                     (h "button" {:class "ghost" :onclick (fn [_] (dismiss))} "Close")))))

(defn mount
  "Mounts the control on the document element and returns `{:host :render :destroy}`.
  `actions` is a map of `:start`, `:stop` (takes the typed note), `:cancel` and `:dismiss`."
  [actions]
  (let [^js host (js/document.createElement host-tag)
        shadow   (.attachShadow host #js {:mode "open"})
        ^js root (h "div" {})
        !ui      (atom {:note "" :view nil :ticker nil})]
    (.append shadow (h "style" {} css) root)
    (.appendChild js/document.documentElement host)
    {:host    host
     :render  (fn [state heard-only?]
                ;; A full redraw would take focus away from the note field
                (if (and heard-only? (= :recording (:view state) (:view @!ui)))
                  (set! (.-textContent ^js (:heard-el @!ui)) (or (:heard state) ""))
                  (do (js/clearInterval (:ticker @!ui))
                      (swap! !ui assoc :view (:view state) :ticker nil)
                      (.replaceChildren root (view state actions !ui))
                      (when (= :recording (:view state))
                        (swap! !ui assoc :ticker
                               (js/setInterval
                                #(set! (.-textContent ^js (:time-el @!ui))
                                       (clock (- (js/Date.now) (:started-at state))))
                                500))))))
     :destroy (fn []
                (js/clearInterval (:ticker @!ui))
                (.remove host))}))
