-- :name db-now :? :1
-- :doc Returns the database server time, which the health check uses to prove connectivity
select now() as now

-- :name lists :? :*
-- :doc Returns every shopping list with its item counts, oldest first
select l.id, l.name,
       count(i.id)::int as item_count,
       count(i.id) filter (where not i.done)::int as open_count
from shopping_list l
left join shopping_item i on i.list_id = l.id
group by l.id
order by l.id

-- :name create-list! :<! :1
insert into shopping_list (name) values (:name)
returning id, name, 0 as item_count, 0 as open_count

-- :name delete-list! :! :n
delete from shopping_list where id = :id

-- :name list-items :? :*
-- :doc Returns a list's items, unfinished ones first, each group in the order added
select id, list_id, name, done
from shopping_item
where list_id = :list-id
order by done, id

-- :name create-item! :<! :1
insert into shopping_item (list_id, name) values (:list-id, :name)
returning id, list_id, name, done

-- :name update-item! :<! :1
update shopping_item set done = :done where id = :id
returning id, list_id, name, done

-- :name delete-item! :! :n
delete from shopping_item where id = :id
