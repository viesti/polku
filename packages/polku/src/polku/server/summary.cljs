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

(defn- chat-summary
  "Describes a typed chat message, which has no recording behind it."
  [root id m]
  (str/join
   "\n"
   [(str "# Polku message " id)
    ""
    (str "- Page: " (or (:url m) "unknown") (when (seq (:title m)) (str " (\"" (:title m) "\")")))
    (str "- Sent: " (:createdAt m))
    (str "- Session directory: "
         (or (not-empty (path/relative (js/process.cwd) (store/session-dir root id))) "."))
    ""
    "## What the user asked for"
    ""
    "The user typed this message in the widget. There is no recording, so there are no frames, speech or timeline."
    ""
    (str "> " (str/replace (:text m) "\n" "\n> "))
    ""]))

(defn- recording-summary
  "Describes a recording with what the user typed and said, a timeline, and where the artifacts are."
  [root id m]
  (let [dir    (store/session-dir root id)
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
        [(str "Spoken (speech recognition output in "
              (str/join ", " (or (seq (distinct (keep :lang speech))) [(or (:lang m) "an unknown language")]))
              ", which may contain mis-heard words):")
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

(defn build-summary
  "Describes one session: what the user asked for and, for a recording, a timeline and where the artifacts are."
  [root id]
  (let [m (store/read-manifest root id)]
    (if (= "chat" (:kind m))
      (chat-summary root id m)
      (recording-summary root id m))))

(defn- plan-steps [id chat?]
  (concat
   (when-not chat?
     ["- Read the frames that matter (they are images) to see what the user was looking at and pointing to."])
   ["- Decide how you would do it. Read the source as much as you need, but do not change any files yet."
    (str "- Send the plan to the user's browser: `npx polku plan " id " -m \"<plan>\"`. Write two to four "
         "short sentences for a non-developer that say what will change on the page, including the choices "
         "they left open (what something looks like, how it behaves). The user approves or rejects it there.")
    (str "- If the message is a question, or something you cannot do, answer it instead with "
         "`npx polku done " id " -m \"<answer>\"` and send no plan.")
    (str "- Then stop working on this request. `npx polku wait` returns it again with the plan once the user "
         "approves it. A rejected plan does not come back; the user sends a new request instead.")]))

(defn- build-steps [id]
  ["1. Change the application source to do what the approved plan says. The app is running with live reload, so saved changes appear in the user's browser without a restart."
   (str "2. Report with `npx polku progress " id " -m \"<step>\"` when you start each part of the plan, when you "
        "make a decision that changes what they will see, and when you start checking the result. The user does "
        "not see this terminal, so send anything here they would care about to the widget too. Do not report "
        "problems in your own work (compile errors, crashes, retries), and do not report every file edit.")
   "3. Check that the change compiled and the app still works."
   (str "4. Report back to the user's browser with a one-sentence, non-technical message: "
        "`npx polku done " id " -m \"<what changed>\"`.")])

(defn build-task
  "Returns the full prompt for an agent. A claimed session asks for a plan; a building one
  carries the plan the user approved and asks for the change."
  [root id]
  (let [m      (store/read-manifest root id)
        build? (= "building" (:status m))]
    (str/join
     "\n"
     (concat
      [(build-summary root id)]
      (if build?
        (concat ["## Approved plan"
                 ""
                 "The user read and approved this plan in their browser:"
                 ""
                 (str "> " (str/replace (or (:plan m) "") "\n" "\n> "))
                 ""
                 "## What to do"
                 ""]
                (build-steps id))
        (concat ["## What to do"
                 ""
                 "Plan the change and ask the user to approve it before you change anything."
                 ""]
                (plan-steps id (= "chat" (:kind m)))))
      [""
       "Write plans, progress and done messages for a non-developer, in the language the user spoke or typed in."
       ""]))))
