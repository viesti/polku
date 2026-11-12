(ns polku.server.store
  "Reads and writes session artifacts under the workspace directory."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private id-re #"[0-9]{8}-[0-9]{6}-[a-f0-9]{6}")

(defn- fail [message status]
  (throw (ex-info message {:status status})))

(defn sessions-dir
  "Returns the directory that holds one subdirectory per session."
  [root]
  (path/join root "sessions"))

(defn session-dir
  "Returns the session's directory, or throws when the id could escape the sessions directory."
  [root id]
  (when-not (and (string? id) (re-matches id-re id))
    (fail "invalid session id" 400))
  (path/join (sessions-dir root) id))

(defn- pad [n width]
  (.padStart (str n) width "0"))

(defn- new-id [^js now]
  (str (.getFullYear now) (pad (inc (.getMonth now)) 2) (pad (.getDate now) 2)
       "-" (pad (.getHours now) 2) (pad (.getMinutes now) 2) (pad (.getSeconds now) 2)
       "-" (.toString (crypto/randomBytes 3) "hex")))

(defn fmt-t
  "Formats a millisecond offset as `m:ss.s`."
  [ms]
  (let [s (/ (max 0 ms) 1000)
        m (js/Math.floor (/ s 60))]
    (str m ":" (pad (.toFixed (- s (* m 60)) 1) 4))))

(defn read-manifest [root id]
  (let [file (path/join (session-dir root id) "manifest.json")]
    (when-not (fs/existsSync file)
      (fail "unknown session" 404))
    (js->clj (js/JSON.parse (fs/readFileSync file "utf8")) :keywordize-keys true)))

(defn write-manifest!
  "Replaces the manifest through a rename, so that a concurrent reader sees a whole file."
  [root manifest]
  (let [dir (session-dir root (:id manifest))
        tmp (path/join dir (str ".manifest." js/process.pid ".tmp"))]
    (fs/writeFileSync tmp (str (js/JSON.stringify (clj->js manifest) nil 2) "\n"))
    (fs/renameSync tmp (path/join dir "manifest.json"))
    manifest))

(defn update-manifest! [root id f & args]
  (write-manifest! root (apply f (read-manifest root id) args)))

(defn create-session! [root meta]
  (let [now (js/Date.)
        id  (new-id now)]
    (fs/mkdirSync (path/join (session-dir root id) "frames") #js {:recursive true})
    (write-manifest!
     root
     {:id          id
      :status      "recording"
      :createdAt   (.toISOString now)
      ;; Epoch ms on the browser clock at which event offset `t` is zero
      :startedAtMs (or (:startedAtMs meta) (.getTime now))
      :url         (:url meta)
      :title       (:title meta)
      :viewport    (:viewport meta)
      :userAgent   (:userAgent meta)
      :lang        (:lang meta)
      :capture     (or (:capture meta) {})
      :counts      {:events 0 :frames 0 :audioBytes 0}})))

(defn append-events!
  "Appends the events, given as a JavaScript array, and returns how many were written."
  [root id ^js events]
  (if-not (and (array? events) (pos? (.-length events)))
    0
    (do (fs/appendFileSync (path/join (session-dir root id) "events.jsonl")
                           (str (.join (.map events #(js/JSON.stringify %)) "\n") "\n"))
        (update-manifest! root id update-in [:counts :events] + (.-length events))
        (.-length events))))

(defn append-audio! [root id ^js chunk ext]
  (let [ext  (if (and ext (re-matches #"[a-z0-9]{2,5}" ext)) ext "webm")
        file (str "audio." ext)]
    (fs/appendFileSync (path/join (session-dir root id) file) chunk)
    (update-manifest! root id #(-> %
                                   (update-in [:counts :audioBytes] + (.-length chunk))
                                   (assoc :audioFile file)))))

(defn write-frame!
  "Stores a frame under its zero-padded offset, so that a directory listing is in time order."
  [root id t jpeg]
  (let [file (str (pad (max 0 (js/Math.round t)) 7) ".jpg")]
    (fs/writeFileSync (path/join (session-dir root id) "frames" file) jpeg)
    (update-manifest! root id update-in [:counts :frames] inc)
    file))

(defn write-dom! [root id html]
  (fs/writeFileSync (path/join (session-dir root id) "dom.html") html))

(defn read-events
  "Returns the session's events as maps, ordered by offset."
  [root id]
  (let [file (path/join (session-dir root id) "events.jsonl")]
    (if-not (fs/existsSync file)
      []
      (->> (str/split-lines (fs/readFileSync file "utf8"))
           (remove str/blank?)
           (map #(js->clj (js/JSON.parse %) :keywordize-keys true))
           (sort-by :t)
           vec))))

(defn complete-session!
  "Writes the transcript and note files and marks the session ready for an agent."
  [root id duration-ms]
  (let [dir        (session-dir root id)
        events     (read-events root id)
        note       (:text (first (filter #(= "note" (:type %)) events)))
        transcript (->> events
                        (filter #(= "speech" (:type %)))
                        (map #(str "[" (fmt-t (:t %)) "] " (:text %)))
                        (str/join "\n"))]
    (when (seq transcript)
      (fs/writeFileSync (path/join dir "transcript.txt") (str transcript "\n")))
    (when note
      (fs/writeFileSync (path/join dir "note.txt") (str note "\n")))
    (update-manifest! root id assoc
                      :status "complete"
                      :completedAt (.toISOString (js/Date.))
                      :durationMs duration-ms
                      :hasTranscript (boolean (seq transcript))
                      :hasNote (some? note))))

(defn list-frames
  "Returns `{:t :file}` for each stored frame, in time order."
  [root id]
  (let [dir (path/join (session-dir root id) "frames")]
    (if-not (fs/existsSync dir)
      []
      (->> (fs/readdirSync dir)
           (filter #(str/ends-with? % ".jpg"))
           sort
           (mapv (fn [f] {:t (js/parseInt f 10) :file (path/join dir f)}))))))

(defn list-sessions
  "Returns the manifests of all sessions, oldest first."
  [root]
  (let [dir (sessions-dir root)]
    (if-not (fs/existsSync dir)
      []
      (->> (fs/readdirSync dir)
           (filter #(re-matches id-re %))
           sort
           ;; A session directory can exist before its manifest has been written
           (keep #(try (read-manifest root %) (catch :default _ nil)))
           vec))))
