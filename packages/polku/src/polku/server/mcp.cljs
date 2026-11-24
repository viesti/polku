(ns polku.server.mcp
  "Model Context Protocol server over stdio that exposes recorded sessions as tools."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:readline" :as readline]
            [clojure.string :as str]
            [polku.server.store :as store :refer [fmt-t]]
            [polku.server.summary :as summary]))

(def ^:private protocol-versions ["2025-11-25" "2025-06-18" "2025-03-26" "2024-11-05"])
(def ^:private default-frames 6)
(def ^:private max-frames 20)
(def ^:private default-events 500)

(def ^:private instructions
  (str "Polku records what a person says, points at and sees in a running web app. "
       "Call get_context_summary first for what they asked and a timeline, "
       "get_session_recording to see the screen at chosen moments, "
       "and get_interaction_events for the raw pointer and input events. "
       "Every tool defaults to the session being worked on."))

(def ^:private session-id-prop
  {:type        "string"
   :description "Session id. Defaults to the latest session the agent is planning or building, or the latest session when there is none."})

(def ^:private range-props
  {:from_ms {:type "number" :description "Only include material at or after this offset in milliseconds."}
   :to_ms   {:type "number" :description "Only include material at or before this offset in milliseconds."}})

(defn- tool [name description properties]
  {:name        name
   :description description
   :inputSchema {:type "object" :properties properties}
   :annotations {:readOnlyHint true}})

(def ^:private tools
  [(tool "list_sessions"
         "Lists recorded sessions, oldest first, with their status, page and duration."
         {:status {:type        "string"
                   :enum        ["recording" "complete" "claimed" "planned" "approved" "building"
                                 "rejected" "done"]
                   :description "Only sessions with this status."}})
   (tool "get_context_summary"
         (str "Returns the brief for a session: what the user typed and said, a timeline of clicks and "
              "pointer rests with the element under the pointer, and where the artifacts are.")
         {:session_id session-id-prop})
   (tool "get_session_recording"
         (str "Returns screen frames from a session as images, each labelled with its offset. "
              "Frames are sampled evenly across the requested range and always include the last one. "
              "Narrow the range with from_ms and to_ms to look closely at one moment.")
         (merge {:session_id session-id-prop
                 :max_frames {:type        "integer"
                              :minimum     1
                              :maximum     max-frames
                              :description (str "How many frames to return (default " default-frames ").")}}
                range-props))
   (tool "get_interaction_events"
         (str "Returns a session's raw events as JSON lines, ordered by offset `t` in milliseconds. "
              "Event types are move, dwell, click, input, scroll, nav, resize, speech and note. "
              "Pointer positions (move) are left out unless asked for by type.")
         (merge {:session_id session-id-prop
                 :types      {:type        "array"
                              :items       {:type "string"}
                              :description "Only these event types. Defaults to every type except move."}
                 :limit      {:type        "integer"
                              :minimum     1
                              :description (str "Maximum number of events (default " default-events ").")}}
                range-props))])

(defn- text [s]
  {:type "text" :text s})

(defn- resolve-id
  "Returns the requested session id, or the session an agent is most likely working on."
  [root requested]
  (let [sessions (store/list-sessions root)]
    (or requested
        (:id (last (filter #(#{"claimed" "building"} (:status %)) sessions)))
        (:id (last sessions))
        (throw (ex-info "No sessions have been recorded yet." {})))))

(defn- in-range? [{:keys [from_ms to_ms]} t]
  (and (or (nil? from_ms) (>= t from_ms))
       (or (nil? to_ms) (<= t to_ms))))

(defn- sample
  "Picks up to `n` items spread evenly over `items`, keeping the first and the last."
  [items n]
  (let [total (count items)]
    (cond
      (<= total n) items
      (= n 1)      [(peek items)]
      :else        (mapv #(nth items (js/Math.round (* % (/ (dec total) (dec n))))) (range n)))))

(defn- list-sessions [root {:keys [status]}]
  (let [sessions (filter #(or (nil? status) (= status (:status %))) (store/list-sessions root))]
    [(text (if (empty? sessions)
             "No sessions match."
             (str/join "\n" (for [{:keys [id url durationMs counts] :as m} sessions]
                              (str id "  " (:status m) "  " (fmt-t (or durationMs 0))
                                   "  " (:frames counts) " frames  " (or url ""))))))]))

(defn- context-summary [root {:keys [session_id]}]
  [(text (summary/build-summary root (resolve-id root session_id)))])

(defn- session-recording [root {:keys [session_id max_frames] :as args}]
  (let [id       (resolve-id root session_id)
        m        (store/read-manifest root id)
        all      (store/list-frames root id)
        in-range (filterv #(in-range? args (:t %)) all)
        frames   (sample in-range (min max-frames (or max_frames default-frames)))
        vp       (:viewport m)]
    (into [(text (str "Session " id ": " (count frames) " of " (count all) " frames"
                      (when (not= (count in-range) (count all))
                        (str " (" (count in-range) " in the requested range)"))
                      ". "
                      (if (empty? all)
                        "No frames were captured; screen capture was off or denied."
                        (str "Each frame shows the browser tab"
                             (when vp (str ", whose viewport is " (:w vp) "x" (:h vp) " CSS pixels"))
                             ". Event coordinates are in CSS pixels, so scale them to the image size."))))]
          (mapcat (fn [{:keys [t file]}]
                    [(text (str "Frame at " (fmt-t t) " (" t " ms), "
                                (path/relative (js/process.cwd) file)))
                     {:type     "image"
                      :data     (.toString (fs/readFileSync file) "base64")
                      :mimeType "image/jpeg"}]))
          frames)))

(defn- interaction-events [root {:keys [session_id types limit] :as args}]
  (let [id      (resolve-id root session_id)
        wanted  (set types)
        matches (->> (store/read-events root id)
                     (filter #(if (seq wanted) (wanted (:type %)) (not= "move" (:type %))))
                     (filter #(in-range? args (:t %))))
        limit   (or limit default-events)
        shown   (take limit matches)]
    [(text (str "Session " id ": " (count shown) " of " (count matches) " matching events"
                (when (> (count matches) limit) "; raise limit or narrow the range for the rest")
                ".\n"
                (str/join "\n" (map #(js/JSON.stringify (clj->js %)) shown))))]))

(def ^:private handlers
  {"list_sessions"          list-sessions
   "get_context_summary"    context-summary
   "get_session_recording"  session-recording
   "get_interaction_events" interaction-events})

(defn- call-tool
  "Runs a tool and returns its result. A failure inside the tool becomes an error result that the model can read."
  [root {:keys [name arguments]}]
  (if-let [handler (get handlers name)]
    (try
      {:content (handler root (or arguments {}))}
      (catch :default err
        {:content [(text (ex-message err))] :isError true}))
    (throw (ex-info (str "Unknown tool: " name) {:code -32602}))))

(defn- handle-request [root version method params]
  (case method
    "initialize" {:protocolVersion (or (some #{(:protocolVersion params)} protocol-versions)
                                       (first protocol-versions))
                  :capabilities    {:tools {}}
                  :serverInfo      {:name "polku" :version version}
                  :instructions    instructions}
    "ping"       {}
    "tools/list" {:tools tools}
    "tools/call" (call-tool root params)
    (throw (ex-info (str "Method not found: " method) {:code -32601}))))

(defn- respond
  "Returns the JSON-RPC response for one parsed message, or nil for a notification."
  [root version {:keys [id method params]}]
  (when (and method (some? id))
    (try
      {:jsonrpc "2.0" :id id :result (handle-request root version method params)}
      (catch :default err
        {:jsonrpc "2.0"
         :id      id
         :error   {:code (or (:code (ex-data err)) -32603) :message (ex-message err)}}))))

(defn serve
  "Serves MCP requests from stdin until it closes. Stdout carries protocol messages only."
  [{:keys [root version]}]
  (let [send! (fn [message] (js/process.stdout.write (str (js/JSON.stringify (clj->js message)) "\n")))
        ^js lines (readline/createInterface #js {:input js/process.stdin :terminal false})]
    (.on lines "line"
         (fn [line]
           (when-not (str/blank? line)
             (let [message (try (js->clj (js/JSON.parse line) :keywordize-keys true)
                                (catch :default _ ::invalid))]
               (if (= ::invalid message)
                 (send! {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error"}})
                 (some-> (respond root version message) send!))))))
    (js/Promise. (fn [resolve _] (.on lines "close" resolve)))))
