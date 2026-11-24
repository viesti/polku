(ns polku.client.widget
  "Draws the floating recording control inside a shadow root."
  (:require [clojure.string :as str]))

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
.lang { display: flex; align-items: center; gap: 8px; margin: 6px 0; color: #6b7280; font-size: 12px; }
.lang select { flex: 1; min-width: 0; font: inherit; color: #111827; background: #fff;
  border: 1px solid #d1d5db; border-radius: 6px; padding: 3px 6px; }
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
.stack { display: flex; flex-direction: column; align-items: flex-end; gap: 8px; }
.bar { display: flex; gap: 8px; }
.pill.light { background: #fff; color: #111827; border: 1px solid #e5e7eb; }
.history { width: 440px; max-width: calc(100vw - 32px); display: flex; flex-direction: column; max-height: 60vh; }
.history .head .close { margin-left: auto; padding: 2px 8px; }
.history .row { margin-top: 8px; }
.branch { font-weight: 400; color: #6b7280; font-family: ui-monospace, monospace; font-size: 12px; }
.commits { list-style: none; margin: 8px 0 0; padding: 0; overflow-y: auto; min-height: 0; }
.commits li { border-top: 1px solid #f3f4f6; padding: 6px 0; }
.commits .line { display: flex; align-items: center; gap: 8px; }
.commits .subject { flex: 1; min-width: 0; text-align: left; background: none; padding: 2px 0; border-radius: 4px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.commits .open .subject { white-space: normal; font-weight: 600; }
.commits .subject:hover { color: #2563eb; }
.commits .jump { flex: none; padding: 3px 10px; font-size: 12px; background: #f3f4f6; }
.commits .jump:hover { background: #111827; color: #fff; }
.commits .jump:disabled { opacity: .5; cursor: default; }
.commits .here { flex: none; font-size: 12px; color: #15803d; font-weight: 600; }
.commits .current .subject { color: #15803d; }
.meta { margin-top: 4px; color: #6b7280; font-size: 12px; }
.body { margin: 4px 0 0; white-space: pre-line; font-size: 12px; color: #374151;
  background: #f9fafb; border-radius: 6px; padding: 6px 8px; }
.note { margin: 6px 0 0; color: #6b7280; font-size: 12px; }
.approval { width: 440px; max-width: calc(100vw - 32px); border-color: #f59e0b; }
.approval .head::before { content: ''; width: 10px; height: 10px; border-radius: 50%; background: #f59e0b;
  animation: pulse 1.2s infinite; }
.cmd { margin: 8px 0 0; max-height: 30vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere;
  font: 12px/1.45 ui-monospace, monospace; background: #111827; color: #f9fafb; border-radius: 8px; padding: 8px 10px; }
.deny { background: #fee2e2; color: #991b1b; }
.chat textarea { min-height: 90px; margin-top: 8px; }
.plan { border-color: #2563eb; }
.plan .head::before { content: ''; width: 10px; height: 10px; border-radius: 50%; background: #2563eb; flex: none; }
.plan .msg { white-space: pre-line; }
.pill .mark { width: 8px; height: 8px; border-radius: 50%; background: #2563eb; flex: none; }
.stack { position: relative; z-index: 1; }
.canvas { position: fixed; left: 0; top: 0; z-index: 0; cursor: crosshair; touch-action: none;
  box-shadow: inset 0 0 0 3px #ef4444; }
.shots { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.shot { position: relative; }
.shot .thumb { display: block; width: 72px; height: 48px; padding: 0; border-radius: 6px; overflow: hidden;
  border: 1px solid #d1d5db; background: #f3f4f6; }
.shot .thumb img { display: block; width: 100%; height: 100%; object-fit: cover; }
.shot .del { position: absolute; top: -6px; right: -6px; width: 18px; height: 18px; padding: 0;
  font-size: 11px; line-height: 18px; background: #111827; color: #fff; }
.preview { position: fixed; inset: 0; z-index: 2; display: flex; align-items: center; justify-content: center;
  background: rgba(17,24,39,.82); }
.preview img { max-width: 92vw; max-height: 88vh; border-radius: 8px; box-shadow: 0 8px 28px rgba(0,0,0,.4);
  background: #fff; }
.preview .close { position: fixed; top: 16px; right: 16px; width: 36px; height: 36px; padding: 0;
  font-size: 18px; background: #fff; color: #111827; }
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

(defn- lang-name
  "Names the language in that language itself, such as \"Suomi\" for fi-FI."
  [tag]
  (or (try
        (let [n (.of (js/Intl.DisplayNames. #js [tag] #js {:type "language"}) tag)]
          (when (seq n) (str (str/upper-case (subs n 0 1)) (subs n 1))))
        (catch :default _ nil))
      tag))

(defn- lang-picker [lang languages set-lang]
  (h "label" {:class "lang"}
     "Speech"
     (apply h "select" {:onchange (fn [^js e] (set-lang (.-value (.-target e))))}
            (for [tag languages]
              (h "option" (cond-> {:value tag} (= tag lang) (assoc :selected ""))
                 (lang-name tag))))))

(defn- recording-view [{:keys [capture heard started-at lang languages]} {:keys [stop cancel set-lang]} !ui]
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
       (when (and (:speech capture) (next languages))
         (lang-picker lang languages set-lang))
       heard-el
       input
       (h "div" {:class "row"}
          (h "button" {:class "primary" :onclick (fn [_] (stop (take-note)))} "Stop & send")
          (h "button" {:class "ghost" :onclick (fn [_] (take-note) (cancel))} "Cancel")))))

(defn- shot-list [shots {:keys [remove-shot preview-shot]}]
  (when (seq shots)
    (apply h "div" {:class "shots"}
           (for [{:keys [id url]} shots]
             (h "div" {:class "shot"}
                (h "button" {:class "thumb" :title "Show larger" :onclick (fn [_] (preview-shot id))}
                   (h "img" {:src url :alt "Screenshot"}))
                (h "button" {:class "del" :title "Remove this screenshot" :onclick (fn [_] (remove-shot id))}
                   "✕"))))))

(defn- preview [{:keys [shots] :as state} {:keys [preview-shot]}]
  (when-let [{:keys [url]} (some #(when (= (:preview state) (:id %)) %) shots)]
    (h "div" {:class "preview" :onclick (fn [^js e]
                                          (when (= (.-target e) (.-currentTarget e)) (preview-shot nil)))}
       (h "img" {:src url :alt "Screenshot"})
       (h "button" {:class "close" :title "Close" :onclick (fn [_] (preview-shot nil))} "✕"))))

(defn- drawing-view [{:keys [capturing? shot-error]} {:keys [stop-drawing attach-shot]} !ui]
  (h "div" {:class "panel"}
     (h "div" {:class "head"} (when capturing? (h "span" {:class "spin"})) "Draw on the page")
     (h "p" {:class "note"} "Circle or point at what you mean, then attach the screenshot.")
     (when shot-error (h "p" {:class "msg error"} shot-error))
     (h "div" {:class "row"}
        (h "button" (cond-> {:class "primary" :onclick (fn [_] (attach-shot (:canvas @!ui)))}
                      capturing? (assoc :disabled ""))
           "Attach")
        (h "button" {:class "ghost" :title "Erase the drawing"
                     :onclick (fn [_]
                                (when-let [^js c (:canvas @!ui)]
                                  (.clearRect (.getContext c "2d") 0 0 (.-width c) (.-height c))))}
           "Clear")
        (h "button" {:class "ghost" :onclick (fn [_] (stop-drawing))} "Cancel"))))

(defn- drawing-canvas
  "Creates a viewport-sized canvas that the user draws on with the pointer."
  []
  (let [dpr       js/window.devicePixelRatio
        w         js/window.innerWidth
        h'        js/window.innerHeight
        ^js c     (h "canvas" {:class "canvas" :style (str "width:" w "px;height:" h' "px")})
        ^js ctx   (.getContext c "2d")
        !down     (atom false)
        point     (fn [^js e] [(.-clientX e) (.-clientY e)])]
    (set! (.-width c) (js/Math.round (* w dpr)))
    (set! (.-height c) (js/Math.round (* h' dpr)))
    (.scale ctx dpr dpr)
    (set! (.-strokeStyle ctx) "#ef4444")
    (set! (.-lineWidth ctx) 4)
    (set! (.-lineCap ctx) "round")
    (set! (.-lineJoin ctx) "round")
    (.addEventListener c "pointerdown"
                       (fn [^js e]
                         (.setPointerCapture c (.-pointerId e))
                         (reset! !down true)
                         (let [[x y] (point e)]
                           (.beginPath ctx)
                           (.moveTo ctx x y)
                           (.lineTo ctx x y)
                           (.stroke ctx))))
    (.addEventListener c "pointermove"
                       (fn [^js e]
                         (when @!down
                           (let [[x y] (point e)]
                             (.lineTo ctx x y)
                             (.stroke ctx)))))
    (doseq [t ["pointerup" "pointercancel"]]
      (.addEventListener c t (fn [_] (reset! !down false))))
    c))

(defn- chat-view [{:keys [chat-error shots] :as state} {:keys [send-chat close-chat start-drawing] :as actions} !ui]
  (let [^js input (h "textarea"
                     {:placeholder "Write what you want changed"
                      :oninput     (fn [^js e] (swap! !ui assoc :chat (.-value (.-target e))))
                      :onkeydown   (fn [^js e]
                                     (when (and (= "Enter" (.-key e)) (or (.-metaKey e) (.-ctrlKey e)))
                                       (.preventDefault e)
                                       (send-chat (:chat @!ui))))})]
    (set! (.-value input) (:chat @!ui))
    ;; The field is attached to the page only after this view is returned
    (js/setTimeout (fn []
                     (.focus input)
                     (let [n (.. input -value -length)] (.setSelectionRange input n n)))
                   0)
    (h "div" {:class "panel chat"}
       (h "div" {:class "head"} "Message to the agent")
       (when chat-error (h "p" {:class "msg error"} chat-error))
       input
       (shot-list shots actions)
       (h "div" {:class "row"}
          (h "button" {:class "primary" :title "Send (⌘/Ctrl + Enter)"
                       :onclick (fn [_] (send-chat (:chat @!ui)))}
             "Send")
          (h "button" {:class "ghost" :title "Draw on the page and attach a screenshot of it"
                       :onclick (fn [_] (start-drawing (:chat @!ui)))}
             "Screenshot")
          (h "button" {:class "ghost" :onclick (fn [_] (close-chat (:chat @!ui)))} "Cancel")))))

(defn- review-view [{:keys [plan kind text]} {:keys [approve revise discard]}]
  (let [chat? (= "chat" kind)]
    (h "div" {:class "panel plan"}
       (h "div" {:class "head"} "The agent's plan")
       (h "p" {:class "msg"} plan)
       (h "p" {:class "note"} "Nothing changes until you approve the plan.")
       (if chat?
         (h "div" {:class "row"}
            (h "button" {:class "primary" :onclick (fn [_] (approve))} "Approve")
            (h "button" {:class "ghost" :title "Reject the plan and edit your message"
                         :onclick (fn [_] (revise text))}
               "Edit message"))
         (h "div" {:class "row"}
            (h "button" {:class "primary" :onclick (fn [_] (approve))} "Approve")
            (h "button" {:class "ghost" :title "Reject the plan and edit it as a typed message"
                         :onclick (fn [_] (revise plan))}
               "Edit as text")
            (h "button" {:class "deny" :title "Reject the plan, then record again"
                         :onclick (fn [_] (discard))}
               "Discard"))))))

(defn- waiting-head [status]
  (case status
    "claimed"  "The agent is making a plan…"
    "approved" "Approved. Waiting for the agent…"
    "building" "The agent is working on it…"
    "Sent. Waiting for the agent…"))

(defn- steps
  "Lists the last few steps the agent reported, marking the latest as the one in progress."
  [progress]
  (when (seq progress)
    (let [shown (take-last max-steps progress)]
      (apply h "ul" {:class "steps"}
             (concat (for [text (butlast shown)]
                       (h "li" {:class "past"} (str "✓ " text)))
                     [(h "li" {:class "now"} (last shown))])))))

(defn- short-date [iso]
  (.toLocaleString (js/Date. iso) js/undefined
                   #js {:dateStyle "medium" :timeStyle "short"}))

(defn- commit-item [{:keys [head expanded busy]} {:keys [toggle-commit jump]}
                    {:keys [hash short author date subject body]}]
  (let [open?    (contains? expanded hash)
        current? (= hash head)]
    (h "li" {:class (str (when open? "open ") (when current? "current"))}
       (h "div" {:class "line"}
          (h "button" {:class "subject" :title subject :onclick (fn [_] (toggle-commit hash))} subject)
          (if current?
            (h "span" {:class "here"} "You are here")
            (h "button" (cond-> {:class "jump"
                                 :title "Make a new branch at this commit and switch to it"
                                 :onclick (fn [_] (jump hash))}
                          busy (assoc :disabled ""))
               (if (= busy hash) "Jumping…" "Jump"))))
       (when open?
         (h "div" {}
            (h "div" {:class "meta"} (str short " · " author " · " (short-date date)))
            (when (seq body) (h "div" {:class "body"} body)))))))

(defn- history-panel [{:keys [branch main busy commits dirty loading? error notice] :as history}
                      {:keys [toggle-history to-main] :as actions}]
  (h "div" {:class "panel history"}
     (h "div" {:class "head"}
        "History"
        (h "span" {:class "branch"} (if (seq branch) branch "detached"))
        (h "button" {:class "ghost close" :title "Close history" :onclick (fn [_] (toggle-history))} "✕"))
     (when (and main to-main (not= branch main))
       (h "div" {:class "row"}
          (h "button" (cond-> {:class "primary"
                               :title (str "Switch back to the " main " branch")
                               :onclick (fn [_] (to-main))}
                        busy (assoc :disabled ""))
             (if (= busy :main) "Switching…" (str "Back to " main)))))
     (when notice (h "p" {:class "msg"} notice))
     (when error (h "p" {:class "msg error"} error))
     (when dirty
       (h "p" {:class "note"} "There are uncommitted changes. They come along when you jump, if they fit."))
     (if (and loading? (empty? commits))
       (h "div" {:class "row"} (h "span" {:class "spin"}) "Loading…")
       (apply h "ol" {:class "commits"}
              (for [c commits] (commit-item history actions c))))))

(defn- command-text
  "Shows a tool call the way the agent's own prompt would, falling back to the raw input."
  [tool input]
  (let [{:keys [command file_path url pattern]} input]
    (cond
      command   command
      file_path (str tool " " file_path)
      url       (str tool " " url)
      pattern   (str tool " " pattern)
      :else     (str tool " " (js/JSON.stringify (clj->js input) nil 2)))))

(defn- approval-panel [approvals {:keys [decide]}]
  (let [{:keys [id tool input]} (first approvals)
        more (dec (count approvals))]
    (h "div" {:class "panel approval"}
       (h "div" {:class "head"} "The agent asks to run this")
       (when-let [d (:description input)] (h "p" {:class "note"} d))
       (h "pre" {:class "cmd"} (command-text tool input))
       (when (pos? more)
         (h "p" {:class "note"} (str more " more " (if (= 1 more) "request waits" "requests wait") " after this one.")))
       (h "div" {:class "row"}
          (h "button" {:class "primary" :onclick (fn [_] (decide id "allow"))} "Approve")
          (h "button" {:class "deny" :onclick (fn [_] (decide id "deny"))} "Deny")))))

(defn- task-pill [hidden show-task]
  (h "button" {:class "pill light" :title "Show the last request" :onclick (fn [_] (show-task))}
     (case hidden
       :waiting (h "span" {:class "spin"})
       :review  (h "span" {:class "mark"})
       "✓")
     (case hidden
       :waiting "Working…"
       :review  "Plan ready"
       "Last task")))

(defn- view [state {:keys [start dismiss toggle-history show-task open-chat] :as actions} !ui]
  (case (:view state)
    :idle      (h "div" {:class "bar"}
                  (when-let [hidden (and show-task (:hidden state))]
                    (task-pill hidden show-task))
                  (when toggle-history
                    (h "button" {:class "pill light" :title "Show the commit history"
                                 :onclick (fn [_] (toggle-history))}
                       "History"))
                  (when open-chat
                    (h "button" {:class "pill light" :title "Type a change request"
                                 :onclick (fn [_] (open-chat))}
                       "Chat"))
                  (h "button" {:class "pill" :title "Record a change request" :onclick (fn [_] (start))}
                     (h "span" {:class "dot"}) "Record"))
    :starting  (spinner-pill "Starting…")
    :recording (recording-view state actions !ui)
    :chat      (chat-view state actions !ui)
    :drawing   (drawing-view state actions !ui)
    :review    (review-view state actions)
    :sending   (spinner-pill "Sending…")
    :waiting   (h "div" {:class "panel"}
                  (h "div" {:class "head"}
                     (h "span" {:class "spin"})
                     (waiting-head (:status state)))
                  (when (and (:plan state) (#{"approved" "building"} (:status state)))
                    (h "p" {:class "note"} (:plan state)))
                  (steps (:progress state))
                  (h "div" {:class "row"}
                     (h "button" {:class "ghost" :onclick (fn [_] (dismiss))} "Hide")))
    :done      (h "div" {:class "panel"}
                  (h "div" {:class "head"} "✓ Done")
                  (h "p" {:class "msg"} (or (:reply state) "The change is in."))
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
  `actions` is a map of `:start`, `:stop` (takes the typed note), `:cancel`, `:dismiss`,
  `:show-task`, `:open-chat`, `:close-chat` and `:send-chat` (both take the typed text), `:approve`,
  `:revise` (takes the text to edit), `:discard`, `:start-drawing` (takes the typed text),
  `:stop-drawing`, `:attach-shot` (takes the drawing canvas), `:remove-shot` and `:preview-shot`
  (both take a screenshot id; nil closes the preview), `:set-lang` (takes a language tag), `:toggle-history`, `:to-main`, `:toggle-commit`
  and `:jump` (each takes a commit hash), and `:decide` (takes a permission request id and
  \"allow\" or \"deny\")."
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
                      (when (and (= :chat (:view state)) (not= :chat (:view @!ui)))
                        (swap! !ui assoc :chat (or (:draft state) "")))
                      ;; The canvas sits outside `root`, so a redraw keeps the drawing
                      (cond
                        (and (= :drawing (:view state)) (not (:canvas @!ui)))
                        (let [c (drawing-canvas)]
                          (.prepend shadow c)
                          (swap! !ui assoc :canvas c))

                        (and (not= :drawing (:view state)) (:canvas @!ui))
                        (do (.remove ^js (:canvas @!ui))
                            (swap! !ui assoc :canvas nil)))
                      (swap! !ui assoc :view (:view state) :ticker nil)
                      (.replaceChildren root
                                        (h "div" {:class "stack"}
                                           (when (and (seq (:approvals state)) (:decide actions))
                                             (approval-panel (:approvals state) actions))
                                           (when (and (get-in state [:history :open?])
                                                      (not (#{:recording :starting} (:view state))))
                                             (history-panel (:history state) actions))
                                           (view state actions !ui))
                                        (or (preview state actions) ""))
                      (when (= :recording (:view state))
                        (swap! !ui assoc :ticker
                               (js/setInterval
                                #(set! (.-textContent ^js (:time-el @!ui))
                                       (clock (- (js/Date.now) (:started-at state))))
                                500))))))
     :destroy (fn []
                (js/clearInterval (:ticker @!ui))
                (.remove host))}))
