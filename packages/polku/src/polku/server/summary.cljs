(ns polku.server.summary
  "Builds the Markdown brief that an agent reads to act on a session."
  (:require ["node:path" :as path]
            [clojure.string :as str]
            [polku.server.store :as store :refer [fmt-t]]))

(defn- describe-target [{:keys [selector text]}]
  (if selector
    (str "`" selector "`" (when (seq text) (str " \"" text "\"")))
    "the page"))

(defn- describe-event [{:keys [type x y target] :as e}]
  (case type
    "speech" (str "SAID: \"" (:text e) "\"")
    "click"  (str "clicked " (describe-target target) " at (" x ", " y ")")
    "dwell"  (str "pointer resting on " (describe-target target) " at (" x ", " y ")")
    "input"  (str "typed into " (describe-target target) " (" (:length e) " characters)")
    "scroll" (str "scrolled to (" x ", " y ")")
    "nav"    (str "navigated to " (:url e))
    "resize" (str "viewport resized to " (:w e) "x" (:h e))
    nil))

(defn- frame-at
  "Returns the latest frame taken up to shortly after `t`, or the first frame when none is that early."
  [frames t]
  (or (last (take-while #(<= (:t %) (+ t 250)) frames))
      (first frames)))

(defn- timeline
  "Returns one line per describable event, naming a frame the first time each frame applies."
  [events frames rel]
  (loop [[e & more] events
         last-frame nil
         lines      []]
    (if-not e
      lines
      (let [text  (describe-event e)
            frame (when (seq frames) (frame-at frames (:t e)))
            new?  (and text frame (not= frame last-frame))]
        (recur more
               (if new? frame last-frame)
               (cond-> lines
                 text (conj (str "- " (fmt-t (:t e)) " " text
                                 (when new? (str " — frame " (rel (:file frame))))))))))))

(defn build-summary
  "Describes one session: what the user asked for, a timeline, and where the artifacts are."
  [root id]
  (let [m      (store/read-manifest root id)
        dir    (store/session-dir root id)
        rel    (fn [file] (or (not-empty (path/relative (js/process.cwd) file)) "."))
        events (store/read-events root id)
        frames (store/list-frames root id)
        note   (:text (first (filter #(= "note" (:type %)) events)))
        speech (filter #(= "speech" (:type %)) events)
        moves  (count (filter #(= "move" (:type %)) events))
        lines  (timeline events frames rel)
        vp     (:viewport m)]
    (str/join
     "\n"
     (concat
      [(str "# Polku session " id)
       ""
       (str "- Page: " (or (:url m) "unknown") (when (seq (:title m)) (str " (\"" (:title m) "\")")))]
      (when vp
        [(str "- Viewport: " (:w vp) "x" (:h vp) " CSS pixels. Event coordinates use this space; "
              "frames show the same area, possibly scaled.")])
      [(str "- Recorded: " (:createdAt m) ", " (fmt-t (or (:durationMs m) 0)) " long")
       (str "- Session directory: " (rel dir))
       ""
       "## What the user asked for"
       ""]
      (when note
        ["Typed note:" "" (str "> " (str/replace note "\n" "\n> ")) ""])
      (when (seq speech)
        ["Spoken (speech recognition output, which may contain mis-heard words):"
         ""
         (str "> " (str/join " " (map :text speech)))
         ""])
      (when-not (or note (seq speech))
        [(str "The user gave neither a note nor recognizable speech. Infer the intent from the timeline "
              "and frames, and say so in the completion message if it stays unclear.")
         ""])
      (when (seq lines)
        (concat
         ["## Timeline"
          ""
          (str "Offsets are minutes:seconds from the start of the recording. A frame listed beside an "
               "entry shows the screen at that moment.")
          ""]
         lines
         [""]))
      ["## Artifacts" ""]
      [(if (seq frames)
         (str "- " (count frames) (if (= 1 (count frames)) " frame" " frames") " in "
              (rel (path/join dir "frames")) ", named by millisecond offset. The last one, "
              (rel (:file (peek frames))) ", shows the page when the user stopped recording.")
         "- No frames were captured (screen capture was off or denied).")
       (str "- " (rel (path/join dir "events.jsonl")) " holds every event, including the " moves
            " pointer positions left out of the timeline.")]
      (when (:audioFile m)
        [(str "- " (rel (path/join dir (:audioFile m))) " is the raw microphone recording.")])
      [(str "- " (rel (path/join dir "dom.html")) " is the page DOM when the user stopped recording.")
       ""]))))

(defn build-task
  "Returns the full prompt for an agent: the session brief followed by how to finish."
  [root id]
  (str/join
   "\n"
   [(build-summary root id)
    "## What to do"
    ""
    "1. Read the frames that matter (they are images) to see what the user was looking at and pointing to."
    (str "2. Change the application source to do what the user asked. The app is running with live "
         "reload, so saved changes appear in the user's browser without a restart.")
    "3. Check that the change compiled and the app still works."
    (str "4. Report back to the user's browser with a one-sentence, non-technical message: "
         "`npx polku done " id " -m \"<what changed>\"`.")
    ""]))
