(ns demo.music
  "Plays looping background music with the Web Audio API and crossfades between tracks.
  The game names the track it wants on every frame with `want!`; browsers start audio
  only after a click or key press, so the music begins with the first one.")

(def ^:private base "audio/")

;; loop ends are exact sample counts, so codec padding at the end of a file never plays.
;; `:fade` is the crossfade in seconds into the track, and `:beat-fade` the shorter one
;; from the other track of its `:sync` pair.
(def ^:private tracks
  {:overworld-day {:file "overworld-day-loop" :loop-end (/ 851642 48000) :sync :overworld
                   :fade 2 :beat-fade 2}
   :overworld-night {:file "overworld-night-loop" :loop-end (/ 851641 48000) :sync :overworld
                     :fade 2 :beat-fade 2}
   :slime-hollow {:file "slime-hollow-loop" :loop-end (/ 3005218 48000) :fade 3}
   :boss-ground {:file "boss-ground-loop" :loop-end (/ 719360 48000) :sync :boss
                 :fade 0.5 :beat-fade 0.3}
   :boss-throw {:file "boss-throw-loop" :loop-end (/ 719360 48000) :sync :boss
                :fade 0.5 :beat-fade 0.3}})

(def ^:private volume 0.6)
(def ^:private stop-fade 2)
(def ^:private muted-key "sandbox-music-muted")

(defonce ^:private player
  (atom {:ctx nil :master nil :buffers {} :current nil :wanted nil
         :muted? (= "1" (try (js/localStorage.getItem muted-key) (catch :default _ nil)))}))

(defn- ext []
  (if (seq (.canPlayType (js/Audio.) "audio/ogg; codecs=opus")) "ogg" "m4a"))

(defn- load [ctx id]
  (or (get-in @player [:buffers id])
      (let [url (str base (:file (tracks id)) "." (ext))
            p (-> (js/fetch url)
                  (.then (fn [^js r]
                           (if (.-ok r)
                             (.arrayBuffer r)
                             (throw (js/Error. (str url ": " (.-status r)))))))
                  (.then #(.decodeAudioData ctx %)))]
        ;; a failed fetch is forgotten so the next request tries again
        (.catch p #(swap! player update :buffers dissoc id))
        (swap! player assoc-in [:buffers id] p)
        p)))

(defn- fade-out! [^js ctx entry fade]
  (when entry
    (let [t (.-currentTime ctx)
          g (.-gain ^js (:gain entry))]
      (.cancelScheduledValues g t)
      (.setValueAtTime g (.-value g) t)
      (.linearRampToValueAtTime g 0 (+ t fade))
      (.stop ^js (:src entry) (+ t fade 0.05)))))

(defn- start! [^js ctx id buffer]
  (let [{:keys [loop-end sync fade beat-fade]} (tracks id)
        now (.-currentTime ctx)
        prev (:current @player)
        ;; the tracks of a pair share chords and tempo, so the new one picks up on the same beat
        pair? (and prev sync (= sync (:sync (tracks (:id prev)))))
        offset (if pair? (mod (- now (:started prev)) loop-end) 0)
        fade (if pair? beat-fade fade)
        src (.createBufferSource ctx)
        gain (.createGain ctx)]
    (set! (.-buffer src) buffer)
    (set! (.-loop src) true)
    (set! (.-loopStart src) 0)
    (set! (.-loopEnd src) loop-end)
    (.setValueAtTime (.-gain gain) 0 now)
    (.linearRampToValueAtTime (.-gain gain) 1 (+ now fade))
    (.connect (.connect src gain) (:master @player))
    (.start src now offset)
    (fade-out! ctx prev fade)
    (swap! player assoc :current {:id id :src src :gain gain :started (- now offset)})))

(defn- sync!
  "Starts, switches or stops the music so that it matches the wanted track."
  []
  (let [{:keys [^js ctx current wanted muted?]} @player
        target (when-not muted? wanted)]
    (when (and ctx (= "running" (.-state ctx)) (not= target (:id current)))
      (if target
        (when-not (= target (:loading @player))
          (swap! player assoc :loading target)
          ;; the other track of a pair starts loading too, so switching to it later is instant
          (doseq [[id t] tracks
                  :when (and (:sync t) (= (:sync t) (:sync (tracks target))))]
            (load ctx id))
          (.then (load ctx target)
                 (fn [buffer]
                   (swap! player #(cond-> % (= target (:loading %)) (dissoc :loading)))
                   (let [{:keys [current wanted muted?]} @player]
                     (when (and (= target wanted) (not muted?) (not= target (:id current)))
                       (start! ctx target buffer))))))
        (do (fade-out! ctx current stop-fade)
            (swap! player assoc :current nil))))))

(defn want!
  "Names the track that should play, or nil for silence."
  [id]
  (when (not= id (:wanted @player))
    (swap! player assoc :wanted id)
    (sync!)))

(defn- unlock! []
  (let [{:keys [^js ctx]} @player]
    (if ctx
      (when (= "suspended" (.-state ctx))
        (.then (.resume ctx) sync!))
      (let [ctx (js/AudioContext.)
            master (.createGain ctx)]
        (set! (.. master -gain -value) volume)
        (.connect master (.-destination ctx))
        (swap! player assoc :ctx ctx :master master)
        (if (= "running" (.-state ctx))
          (sync!)
          (.then (.resume ctx) sync!))))))

(defonce listeners
  (do (.addEventListener js/window "pointerdown" #(unlock!))
      (.addEventListener js/window "keydown" #(unlock!))
      true))

(defn muted? [] (:muted? @player))

(defn toggle-muted!
  "Switches the music off or back on, remembering the choice in this browser."
  []
  (let [m (:muted? (swap! player update :muted? not))]
    (try (js/localStorage.setItem muted-key (if m "1" "0")) (catch :default _ nil))
    (sync!)
    m))
