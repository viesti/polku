(ns polku.server.approvals
  "Holds the agent's permission requests until the user approves or denies them in the browser.
  Requests live in memory, so a server restart drops them and the agent falls back to its own prompt.")

(def ^:private keep-ms (* 10 60 1000))

(defonce ^:private requests (atom {}))

(defn- prune [m now]
  (into {} (remove (fn [[_ r]] (< (+ (:createdAt r) keep-ms) now))) m))

(defn create!
  "Stores a pending request for `tool` with its `input` and returns it."
  [{:keys [tool input]}]
  (let [now (js/Date.now)
        r   {:id        (str (random-uuid))
             :tool      (str tool)
             :input     input
             :status    "pending"
             :createdAt now}]
    (swap! requests #(assoc (prune % now) (:id r) r))
    r))

(defn fetch [id]
  (or (get @requests id)
      (throw (ex-info "no such request" {:status 404}))))

(defn pending
  "Lists the requests nobody has answered yet, oldest first."
  []
  (->> (vals @requests)
       (filter #(= "pending" (:status %)))
       (sort-by :createdAt)
       vec))

(defn decide!
  "Records the answer to a pending request. A request answered or withdrawn earlier keeps its status."
  [id status]
  (when-not (#{"allow" "deny" "withdrawn"} status)
    (throw (ex-info "status must be allow, deny or withdrawn" {:status 400})))
  (fetch id)
  (get (swap! requests update id #(cond-> % (= "pending" (:status %)) (assoc :status status)))
       id))
