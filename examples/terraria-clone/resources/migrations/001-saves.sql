create table saves (
  id bigserial primary key,
  name text,
  data text not null,
  created_at timestamptz not null default now()
);
