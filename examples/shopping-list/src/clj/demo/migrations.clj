(ns demo.migrations
  "Applies the SQL files in resources/migrations in file name order, each one once."
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]))

(defn- migration-files []
  (->> (file-seq (io/file (io/resource "migrations")))
       (filter #(re-matches #".*\.sql" (.getName %)))
       (sort-by #(.getName %))))

(defn migrate!
  "Applies the migrations that have not run yet and returns their file names."
  [db]
  (jdbc/execute! db ["create table if not exists schema_migrations
                      (name text primary key, applied_at timestamptz not null default now())"])
  (let [applied (into #{} (map :schema_migrations/name)
                      (jdbc/execute! db ["select name from schema_migrations"]))]
    (vec
     (for [file  (migration-files)
           :let  [name (.getName file)]
           :when (not (applied name))]
       (jdbc/with-transaction [tx db]
         (jdbc/execute! tx [(slurp file)])
         (jdbc/execute! tx ["insert into schema_migrations (name) values (?)" name])
         name)))))

(defmethod ig/init-key :demo/migrations [_ {:keys [db]}]
  (migrate! db))
