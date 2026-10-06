-- V15: живой онлайн (отошёл / не в сети по бездействию), опыт и значки,
-- «чем занят», кнопка «ИИ слоп», папки чатов, барахолка, любые эмодзи в
-- реакциях, подписи к видео из Telegram.

-- ---------------------------------------------------------------------------
-- 1. Presence
--    status          — то, что видят другие (online / away / dnd / invisible / offline),
--                      пересчитывается сервером;
--    manual_status   — что человек выставил сам (away / dnd / invisible) или null;
--    last_active_at  — последнее действие (сообщение, клик, запрос…);
--    seen_at         — последний «пульс» ноды, у которой открыт сокет человека.
--                      Старше 2 минут — нода умерла, человек offline.
-- ---------------------------------------------------------------------------
alter table user_presence add column manual_status text check (manual_status in ('away', 'dnd', 'invisible'));
alter table user_presence add column last_active_at timestamptz not null default now();
alter table user_presence add column seen_at timestamptz not null default now();
-- seen_at в индекс не кладём: его пульс обновляет каждые 30 секунд, и с ним в индексе
-- каждое обновление было бы не-HOT (пухнут индекс и таблица)
create index ix_presence_live on user_presence (status) where status <> 'offline';
-- всех «зависших» онлайн — в offline: при подключении статус выставится заново
update user_presence set status = 'offline', doing = null, track_id = null, position_sec = null;

-- ---------------------------------------------------------------------------
-- 2. Опыт: уровень = xp / 100. История начислений — xp_event.
-- ---------------------------------------------------------------------------
update user_level set xp = coalesce(xp, 0);
update user_level set level = xp / 100;
alter table user_level alter column xp set not null;
alter table user_level alter column xp set default 0;
alter table user_level alter column level set not null;
alter table user_level alter column level set default 0;

create table xp_event
(
    id         uuid primary key,
    user_id    uuid        not null references users (id),
    amount     int         not null,
    reason     text        not null,          -- upvote_wall / upvote_community / badge
    ref_id     uuid,                          -- запись / …
    actor_id   uuid references users (id),    -- кто апвоутнул
    badge      text,                          -- код значка для reason = badge
    created_at timestamptz not null default now()
);
create index ix_xp_event_user on xp_event (user_id, created_at desc);

-- ---------------------------------------------------------------------------
-- 3. Значки. Каталог — в коде (BadgeCatalog), здесь только кто что получил.
-- ---------------------------------------------------------------------------
create table user_badge
(
    user_id   uuid        not null references users (id),
    code      text        not null,
    earned_at timestamptz not null default now(),
    primary key (user_id, code)
);
create index ix_user_badge_recent on user_badge (earned_at desc);

-- счётчики, которые нельзя посчитать запросом по готовым таблицам
create table user_stat
(
    user_id        uuid primary key references users (id),
    night_seconds  bigint      not null default 0,   -- в сети (не «отошёл») с 00:00 до 06:00
    together_count int         not null default 0,   -- включал тот же трек, что друг слушает прямо сейчас
    updated_at     timestamptz not null default now()
);

-- какие треки человек слушал (разные) — для «Тысяча треков» и саммари
create table track_listen
(
    user_id  uuid        not null references users (id),
    track_id uuid        not null references track (id),
    first_at timestamptz not null default now(),
    last_at  timestamptz not null default now(),
    plays    int         not null default 1,
    primary key (user_id, track_id)
);
create index ix_track_listen_user_last on track_listen (user_id, last_at desc);

-- для саммари «чем занят» и значков
create index if not exists ix_post_author on post (creator_id, created_at desc) where not is_deleted;
create index if not exists ix_post_comment_author on post_comment (author_id, created_at desc) where deleted_at is null;

-- ---------------------------------------------------------------------------
-- 4. «ИИ слоп». views — сколько разных людей открывали запись (прочтение
--    после суточной чистки post_read засчитывается заново). ai_slop_at —
--    когда повесили плашку; снимается только вручную.
-- ---------------------------------------------------------------------------
alter table post add column views bigint not null default 0;
alter table post add column ai_slop_at timestamptz;

create table post_slop_vote
(
    post_id    uuid        not null references post (id),
    user_id    uuid        not null references users (id),
    created_at timestamptz not null default now(),
    primary key (post_id, user_id)
);

-- просмотры для уже существующих записей: кто читал / апвоутил / лайкал / комментировал
update post p set views = coalesce((
    select count(*) from (
        select user_id from post_read r where r.post_id = p.id
        union select user_id from post_upvote u where u.post_id = p.id
        union select user_id from post_like l where l.post_id = p.id
        union select author_id from post_comment c where c.post_id = p.id
    ) x), 0);

-- ---------------------------------------------------------------------------
-- 5. Папки чатов (у каждого свои).
-- ---------------------------------------------------------------------------
create table chat_folder
(
    id         uuid primary key,
    user_id    uuid        not null references users (id),
    title      text        not null check (length(title) between 1 and 40),
    emoji      text check (length(emoji) between 1 and 64),
    position   int         not null default 0,
    created_at timestamptz not null default now()
);
create index ix_chat_folder_user on chat_folder (user_id, position);

create table chat_folder_chat
(
    folder_id uuid        not null references chat_folder (id) on delete cascade,
    chat_id   uuid        not null references chat (id),
    position  int         not null default 0,
    added_at  timestamptz not null default now(),
    primary key (folder_id, chat_id)
);
create index ix_chat_folder_chat_chat on chat_folder_chat (chat_id);

-- ---------------------------------------------------------------------------
-- 6. Реакции: любой эмодзи, включая флаги, семьи и цвета кожи (до 16 кодпоинтов).
-- ---------------------------------------------------------------------------
alter table message_reaction drop constraint if exists message_reaction_emoji_check;
alter table message_reaction add constraint message_reaction_emoji_check check (length(emoji) between 1 and 64);

-- ---------------------------------------------------------------------------
-- 7. Барахолка.
-- ---------------------------------------------------------------------------
create table market_item
(
    id          uuid primary key,
    seller_id   uuid          not null references users (id),
    title       text          not null check (length(title) between 3 and 120),
    description text          not null default '',
    price       numeric(12, 2) check (price >= 0),     -- null — «договорная», 0 — «отдам даром»
    currency    text          not null default 'RUB' check (currency in ('RUB', 'USD', 'EUR', 'KZT', 'BYN', 'UAH', 'GEL', 'AMD')),
    category    text          not null default 'other',
    condition   text          not null default 'used' check (condition in ('new', 'used', 'broken')),
    city        text,
    contacts    text,                                   -- «тг @ник, после 18:00» — свободный текст
    status      text          not null default 'active' check (status in ('active', 'reserved', 'sold', 'deleted')),
    views       bigint        not null default 0,
    created_at  timestamptz   not null default now(),
    updated_at  timestamptz   not null default now(),
    bumped_at   timestamptz   not null default now()  -- «поднять» — раз в сутки
);
create index ix_market_feed on market_item (bumped_at desc) where status in ('active', 'reserved');
create index ix_market_seller on market_item (seller_id, created_at desc) where status <> 'deleted';
create index ix_market_category on market_item (category, bumped_at desc) where status in ('active', 'reserved');

create table market_favorite
(
    item_id    uuid        not null references market_item (id),
    user_id    uuid        not null references users (id),
    created_at timestamptz not null default now(),
    primary key (item_id, user_id)
);
create index ix_market_favorite_user on market_favorite (user_id, created_at desc);

-- фото объявлений — в общей таблице вложений
alter table media_attachment drop constraint if exists media_attachment_owner_type_check;
alter table media_attachment add constraint media_attachment_owner_type_check
    check (owner_type in ('post', 'comment', 'message', 'guestbook', 'market'));

-- ---------------------------------------------------------------------------
-- 8. Видео из зеркал Telegram без названия → «Название канала 123».
-- ---------------------------------------------------------------------------
insert into video_meta (media_id)
select a.media_id
from telegram_post tp
join media_attachment a on a.owner_type = 'post' and a.owner_id = tp.post_id
join media m on m.id = a.media_id and m.content_type like 'video/%'
on conflict do nothing;

update video_meta vm set title = left(x.name || ' ' || x.tg_post_id || x.suffix, 200), updated_at = now()
from (
    select a.media_id, c.name, tp.tg_post_id,
           case when count(*) over (partition by tp.post_id) > 1
                then ' (' || row_number() over (partition by tp.post_id order by a.position) || ')' else '' end as suffix
    from telegram_post tp
    join post p on p.id = tp.post_id
    join community c on c.id = p.community_id
    join media_attachment a on a.owner_type = 'post' and a.owner_id = tp.post_id
    join media m on m.id = a.media_id and m.content_type like 'video/%'
) x
where vm.media_id = x.media_id and (vm.title is null or vm.title = '');

-- ---------------------------------------------------------------------------
-- 9. Синхронизация зеркал: бронь канала, чтобы несколько нод не тянули один и тот же.
-- ---------------------------------------------------------------------------
alter table telegram_channel add column sync_lease_until timestamptz;
create index ix_telegram_channel_due on telegram_channel (last_synced_at nulls first) where not paused;
