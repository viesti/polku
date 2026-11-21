-- :name db-now :? :1
-- :doc Returns the database server time, which the health check uses to prove connectivity
select now() as now

-- :name insert-save! :<! :1
-- :doc Stores a saved game and returns its id, name and creation time
insert into saves (name, data) values (:name, :data)
returning id, name, created_at

-- :name list-saves :? :*
-- :doc Returns every saved game without its data, newest first
select id, name, created_at from saves order by created_at desc

-- :name get-save :? :1
-- :doc Returns one saved game with its data
select id, name, data, created_at from saves where id = :id
