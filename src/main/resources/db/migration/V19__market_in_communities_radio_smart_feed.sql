-- V19: барахолка внутри сообществ, радио сообществ с записями эфиров («Реплеи»),
-- индексы под умную ленту (рекомендации).

-- ---------------------------------------------------------------------------
-- 1. Барахолка в сообществе: объявление может принадлежать сообществу (вкладка «Барахолка»).
--    В общей ленте /api/market оно тоже видно — с плашкой сообщества.
-- ---------------------------------------------------------------------------
alter table market_item add column community_id uuid references community (id);
create index ix_market_community on market_item (community_id, bumped_at desc)
    where community_id is not null and status in ('active', 'reserved');

-- ---------------------------------------------------------------------------
-- 2. Радио. Станция — у сообщества (одна). Эфир (session) — от «включить» до «выключить»;
--    что играло — radio_play (это и есть запись эфира для вкладки «Реплеи»: треклист со временем).
--    Звук не пишется и не микшируется: все слушатели играют один и тот же трек с одной позиции
--    (started_at), файлы берут как обычные треки — через CDN/S3.
-- ---------------------------------------------------------------------------
create table radio_station
(
    id              uuid primary key,
    community_id    uuid        not null unique references community (id),
    name            text        not null check (length(name) between 1 and 80),
    description     text,
    auto_dj         boolean     not null default true,      -- очередь пуста — сам подбирает треки сообщества
    requests_open   boolean     not null default true,      -- слушатели могут заказывать треки
    status          text        not null default 'off' check (status in ('off', 'on')),
    session_id      uuid,                                   -- текущий эфир
    play_id         uuid,                                   -- что играет сейчас (radio_play)
    ends_at         timestamptz,                            -- когда кончится текущий трек
    created_at      timestamptz not null default now()
);
create index ix_radio_due on radio_station (ends_at) where status = 'on';

create table radio_dj
(
    station_id uuid        not null references radio_station (id) on delete cascade,
    user_id    uuid        not null references users (id),
    added_at   timestamptz not null default now(),
    primary key (station_id, user_id)
);

create table radio_queue
(
    id         uuid primary key,
    station_id uuid        not null references radio_station (id) on delete cascade,
    track_id   uuid        not null references track (id),
    position   double precision not null,
    added_by   uuid        not null references users (id),
    requested  boolean     not null default false,          -- заказ слушателя (не диджея)
    created_at timestamptz not null default now()
);
create index ix_radio_queue on radio_queue (station_id, position);

create table radio_session
(
    id             uuid primary key,
    station_id     uuid        not null references radio_station (id) on delete cascade,
    title          text        not null,
    started_by     uuid references users (id),
    started_at     timestamptz not null default now(),
    ended_at       timestamptz,
    peak_listeners int         not null default 0,
    deleted_at     timestamptz
);
create index ix_radio_session on radio_session (station_id, started_at desc) where deleted_at is null;

create table radio_play
(
    id           uuid primary key,
    session_id   uuid        not null references radio_session (id) on delete cascade,
    station_id   uuid        not null references radio_station (id) on delete cascade,
    track_id     uuid        not null references track (id),
    started_at   timestamptz not null,
    ended_at     timestamptz,                               -- null — играет; при пропуске — раньше конца трека
    requested_by uuid references users (id),
    auto         boolean     not null default false          -- подобрал автодиджей
);
create index ix_radio_play_session on radio_play (session_id, started_at);
create index ix_radio_play_recent on radio_play (station_id, started_at desc);

-- кто слушает: пинг раз в минуту, слушатель = пинг за 2 минуты
create table radio_listener
(
    station_id uuid        not null references radio_station (id) on delete cascade,
    user_id    uuid        not null references users (id),
    seen_at    timestamptz not null default now(),
    primary key (station_id, user_id)
);
create index ix_radio_listener_seen on radio_listener (station_id, seen_at);

-- ---------------------------------------------------------------------------
-- 3. Умная лента: сигналы по времени (глобально популярное) и по человеку (друзья).
-- ---------------------------------------------------------------------------
create index if not exists ix_post_upvote_created on post_upvote (created_at);
create index if not exists ix_post_like_created on post_like (created_at);
create index if not exists ix_post_comment_created_at on post_comment (created_at) where deleted_at is null;
create index if not exists ix_post_share_created on post_share (created_at);
create index if not exists ix_post_share_user on post_share (user_id, created_at);
create index if not exists ix_video_view_user on video_view (user_id, viewed_at);
create index if not exists ix_tag_link_owner on tag_link (owner_type, owner_id);
