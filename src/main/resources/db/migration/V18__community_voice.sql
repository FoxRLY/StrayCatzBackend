-- V18: голосовые каналы сообществ («мини-дискорд») на LiveKit.
--  - kind = channel    — постоянные каналы во вкладке «Голос» (создаёт admin);
--  - kind = event      — голос события (создаётся сам при первом входе);
--  - kind = discussion — голос обсуждения (тоже сам).
-- Внутри канала можно говорить (talk) или только смотреть стрим (watch);
-- «мини-стрим» — демонстрация экрана/камеры участником канала.

create table voice_channel
(
    id           uuid primary key,
    community_id uuid        not null references community (id),
    kind         text        not null check (kind in ('channel', 'event', 'discussion')),
    ref_id       uuid,                                   -- community_event.id / chat.id
    name         text        not null check (length(name) between 1 and 60),
    position     int         not null default 0,
    max_talkers  int         not null default 25 check (max_talkers between 2 and 100),
    created_by   uuid references users (id),
    created_at   timestamptz not null default now(),
    deleted_at   timestamptz
);
create unique index uq_voice_ref on voice_channel (kind, ref_id) where ref_id is not null and deleted_at is null;
create index ix_voice_community on voice_channel (community_id, position) where deleted_at is null;

-- кто сейчас в канале. Строка появляется при «войти» (connected = false — подключается),
-- connected/streaming/camera обновляют вебхуки LiveKit и сверка раз в 20 с.
create table voice_presence
(
    channel_id      uuid        not null references voice_channel (id) on delete cascade,
    user_id         uuid        not null references users (id),
    mode            text        not null check (mode in ('talk', 'watch')),
    connected       boolean     not null default false,
    streaming       boolean     not null default false,   -- показывает экран
    camera          boolean     not null default false,
    joined_at       timestamptz not null default now(),
    disconnected_at timestamptz,
    primary key (channel_id, user_id)
);
create index ix_voice_presence_user on voice_presence (user_id);

-- admin отключил микрофон в канале: держится и после выхода/входа, пока не вернут
create table voice_mute
(
    channel_id uuid        not null references voice_channel (id) on delete cascade,
    user_id    uuid        not null references users (id),
    muted_by   uuid references users (id),
    created_at timestamptz not null default now(),
    primary key (channel_id, user_id)
);

-- в каждом обычном сообществе — канал «общий» и вкладка «Голос»
insert into voice_channel (id, community_id, kind, name, position)
select gen_random_uuid(), c.id, 'channel', 'общий', 0
from community c
where not c.is_deleted and c.source is null and c.parent_id is null;

update community
set sections = sections || '["voice"]'::jsonb
where source is null and parent_id is null and not (sections ? 'voice');
