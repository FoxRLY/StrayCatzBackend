-- ===========================================================================
-- V10: стена, «видео одной папкой», стримы.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Стена. Запись на стене — обычная запись (post) без сообщества и не пульс,
-- у которой wall_user_id = чья это стена. Апвоут/лайк/комментарии/репост —
-- через те же /api/posts/{id}/…
-- ---------------------------------------------------------------------------
alter table post add column wall_user_id uuid references users (id);
create index ix_post_wall on post (wall_user_id, created_at desc) where wall_user_id is not null and not is_deleted;

-- ---------------------------------------------------------------------------
-- Видео. Источник правды — вложения (media_attachment + media video/*).
-- Отдельно храним только то, чего во вложениях нет.
-- ---------------------------------------------------------------------------

-- Подпись/длительность/обложка ролика (правит тот, кто загрузил файл).
create table video_meta
(
    media_id        uuid primary key references media (id),
    title           text,
    duration_sec    int check (duration_sec between 1 and 86400),
    poster_media_id uuid references media (id) on delete set null,
    updated_at      timestamptz not null default now()
);

-- «Мои видео»: загруженные прямо во вкладку «видео» (без записи) и добавленные себе чужие.
create table user_video
(
    user_id  uuid        not null references users (id),
    media_id uuid        not null references media (id),
    added_at timestamptz not null default now(),
    primary key (user_id, media_id)
);
create index ix_user_video_added on user_video (user_id, added_at desc);

-- Просмотры: уникальные по человеку.
create table video_view
(
    media_id  uuid        not null references media (id),
    user_id   uuid        not null references users (id),
    viewed_at timestamptz not null default now(),
    primary key (media_id, user_id)
);
create index ix_video_view_time on video_view (viewed_at);

-- Для выборок по видео-вложениям записей.
create index ix_media_attachment_media on media_attachment (media_id) where owner_type = 'post';

-- Все видео сети одним списком. Ролик, прикреплённый к нескольким записям,
-- берётся из самой ранней. Загруженное без записи — source 'upload'.
create view video_item as
select posted.*
from (select distinct on (m.id) m.id                               as media_id,
                                 m.owner_id                         as uploader_id,
                                 p.creator_id                       as author_id,
                                 p.community_id                     as community_id,
                                 p.id                               as post_id,
                                 case
                                     when p.wall_user_id is not null then 'wall'
                                     when p.is_pulse then 'pulse'
                                     else 'community' end           as source,
                                 p.as_community                     as as_community,
                                 p.created_at                       as created_at
      from media_attachment a
               join media m on m.id = a.media_id and m.content_type like 'video/%'
               join post p on p.id = a.owner_id and not p.is_deleted
               left join community c on c.id = p.community_id
      where a.owner_type = 'post'
        and (p.community_id is null or not c.is_deleted)
      order by m.id, p.created_at) posted
union all
select uv.media_id, m.owner_id, uv.user_id, null::uuid, null::uuid, 'upload', false, uv.added_at
from user_video uv
         join media m on m.id = uv.media_id and m.content_type like 'video/%'
where m.owner_id = uv.user_id
  and not exists (select 1
                  from media_attachment a
                           join post p on p.id = a.owner_id and not p.is_deleted
                  where a.owner_type = 'post'
                    and a.media_id = uv.media_id);

-- ---------------------------------------------------------------------------
-- Стримы.
-- Канал — «куда стримить»: у человека один личный, у сообщества один общий.
-- В OBS: сервер rtmp://<host>:1935/live, ключ "<code>?pass=<secret>".
-- code публичный (из него же собирается адрес для зрителей), secret хранится хэшем.
-- ---------------------------------------------------------------------------
create table stream_channel
(
    id             uuid primary key,
    code           text        not null unique check (code ~ '^[a-z0-9]{6,32}$'),
    user_id        uuid references users (id),
    community_id   uuid references community (id),
    secret_hash    text,                             -- sha-256(hex); null — ключ ещё не выпускали
    key_rotated_at timestamptz,
    key_rotated_by uuid references users (id),     -- кто выпустил ключ: от его имени создаются эфиры из OBS
    created_at     timestamptz not null default now(),
    check ((user_id is null) <> (community_id is null))
);
create unique index ux_stream_channel_user on stream_channel (user_id) where user_id is not null;
create unique index ux_stream_channel_community on stream_channel (community_id) where community_id is not null;

-- Эфир. idle — подготовлен (название есть, ждём OBS), live — идёт, ended — закончился.
create table stream
(
    id              uuid primary key,
    channel_id      uuid        not null references stream_channel (id),
    created_by      uuid        not null references users (id),
    title           text        not null,
    description     text,
    status          text        not null default 'idle' check (status in ('idle', 'live', 'ended')),
    chat_id         uuid        not null references chat (id),
    created_at      timestamptz not null default now(),
    started_at      timestamptz,
    ended_at        timestamptz,
    last_ready_at   timestamptz,                     -- когда медиасервер последний раз видел поток
    publisher_type  text,                            -- rtmpConn / srtConn / ... — чтобы кикнуть
    publisher_id    text,
    peak_viewers    int         not null default 0,
    ended_manually  boolean     not null default false
);
-- у канала не больше одного неоконченного эфира
create unique index ux_stream_active on stream (channel_id) where status in ('idle', 'live');
create index ix_stream_live on stream (started_at desc) where status = 'live';
create index ix_stream_channel_time on stream (channel_id, created_at desc);

-- Кто смотрит: пинг раз в 30 секунд, зритель живёт 60 секунд.
create table stream_viewer
(
    stream_id uuid        not null references stream (id),
    user_id   uuid        not null references users (id),
    seen_at   timestamptz not null default now(),
    primary key (stream_id, user_id)
);
create index ix_stream_viewer_seen on stream_viewer (stream_id, seen_at);
