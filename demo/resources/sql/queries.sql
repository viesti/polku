-- :name db-now :? :1
-- :doc Returns the database server time, which the health check uses to prove connectivity
select now() as now
