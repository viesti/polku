create table shopping_list (
  id         serial primary key,
  name       text not null,
  created_at timestamptz not null default now()
);

create table shopping_item (
  id         serial primary key,
  list_id    integer not null references shopping_list (id) on delete cascade,
  name       text not null,
  done       boolean not null default false,
  created_at timestamptz not null default now()
);

insert into shopping_list (name) values ('Grocery list'), ('Home renovation');
