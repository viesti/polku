(ns demo.db
  "Database connection and the HugSQL query functions."
  (:require [hugsql.adapter.next-jdbc :as adapter]
            [hugsql.core :as hugsql]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(hugsql/set-adapter! (adapter/hugsql-adapter-next-jdbc {:builder-fn rs/as-unqualified-kebab-maps}))

;; Every query in resources/sql/queries.sql becomes a function in this namespace
(hugsql/def-db-fns "sql/queries.sql")

(defmethod ig/init-key :demo/db [_ {:keys [jdbc-url]}]
  (jdbc/get-datasource {:jdbcUrl jdbc-url}))
