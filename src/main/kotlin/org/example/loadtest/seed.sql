-- Нагрузочные данные: N пользователей lt1…ltN, у каждого
--   * группа на 20 человек (идут подряд: lt1–lt20, lt21–lt40, …),
--   * большой чат на 1000 человек (lt1–lt1000, lt1001–lt2000, …),
--   * 10 друзей (соседи по номеру) — чтобы ходил presence.
-- id детерминированные — k6 считает их сам (см. ws-chat.js):
--   человек n: 00000000-0000-4000-8000-<n в hex, 12 знаков>
--   группа g:  00000000-0000-4000-9000-<g в hex>
--   большой b: 00000000-0000-4000-a000-<b в hex>
--
-- psql -h … -U straycatz -d straycatz -v users=100000 -f loadtest/seed.sql
-- Только для стенда! Сокет пускает по dev.<uuid> лишь с straycatz.auth.allow-dev-tokens=true.

\if :{?users}
\else
  \set users 10000
\endif

begin;

create or replace function pg_temp.lt_id(prefix text, n bigint) returns uuid language sql immutable as
$$ select (prefix || lpad(to_hex(n), 12, '0'))::uuid $$;

insert into users (id, username)
select pg_temp.lt_id('00000000-0000-4000-8000-', n), 'lt' || n from generate_series(1, :users) n
on conflict do nothing;

insert into user_cosmetics (user_id) select pg_temp.lt_id('00000000-0000-4000-8000-', n) from generate_series(1, :users) n
on conflict do nothing;
insert into user_level (user_id) select pg_temp.lt_id('00000000-0000-4000-8000-', n) from generate_series(1, :users) n
on conflict do nothing;
insert into room (owner_id, title) select pg_temp.lt_id('00000000-0000-4000-8000-', n), 'lt' || n from generate_series(1, :users) n
on conflict do nothing;

-- группы по 20
insert into chat (id, name, room_type)
select pg_temp.lt_id('00000000-0000-4000-9000-', g), 'нагрузка ' || g, 'group'
from generate_series(1, (:users + 19) / 20) g on conflict do nothing;
insert into chat_seq (chat_id) select pg_temp.lt_id('00000000-0000-4000-9000-', g)
from generate_series(1, (:users + 19) / 20) g on conflict do nothing;
insert into chat_member (chat_id, user_id)
select pg_temp.lt_id('00000000-0000-4000-9000-', (n - 1) / 20 + 1), pg_temp.lt_id('00000000-0000-4000-8000-', n)
from generate_series(1, :users) n on conflict do nothing;

-- большие чаты по 1000
insert into chat (id, name, room_type)
select pg_temp.lt_id('00000000-0000-4000-a000-', b), 'большой ' || b, 'group'
from generate_series(1, (:users + 999) / 1000) b on conflict do nothing;
insert into chat_seq (chat_id) select pg_temp.lt_id('00000000-0000-4000-a000-', b)
from generate_series(1, (:users + 999) / 1000) b on conflict do nothing;
insert into chat_member (chat_id, user_id)
select pg_temp.lt_id('00000000-0000-4000-a000-', (n - 1) / 1000 + 1), pg_temp.lt_id('00000000-0000-4000-8000-', n)
from generate_series(1, :users) n on conflict do nothing;

-- друзья: n дружит с n+1 … n+5 (у каждого ~10 друзей)
insert into friendship (initiator_id, acceptor_id, is_accepted, accepted_at)
select pg_temp.lt_id('00000000-0000-4000-8000-', n), pg_temp.lt_id('00000000-0000-4000-8000-', n + k), true, now()
from generate_series(1, :users) n, generate_series(1, 5) k
where n + k <= :users
  and not exists (select 1 from friendship f
                  where f.initiator_id = pg_temp.lt_id('00000000-0000-4000-8000-', n)
                    and f.acceptor_id = pg_temp.lt_id('00000000-0000-4000-8000-', n + k));

commit;

analyze users; analyze chat; analyze chat_member; analyze friendship;
