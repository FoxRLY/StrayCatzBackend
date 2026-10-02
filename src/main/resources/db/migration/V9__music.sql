-- Музыка «как в ВК»: треки грузят сами люди, у каждого своя библиотека
-- («моя музыка»), музыка сообществ, плейлисты, «сейчас слушает», и треки
-- можно прикреплять к записям/пульсу/комментариям/гостевой/сообщениям.

create table track
(
    id             uuid primary key,
    uploader_id    uuid        not null references users (id),
    audio_media_id uuid        not null references media (id),
    cover_media_id uuid references media (id),
    title          text        not null,
    artist         text        not null,
    album          text,
    duration_sec   int         not null check (duration_sec between 1 and 7200),
    bpm            int check (bpm between 20 and 400),           -- определяет фронт, может быть пустым
    tags           jsonb       not null default '[]'::jsonb,
    plays          bigint      not null default 0,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    deleted_at     timestamptz
);
create index ix_track_uploader on track (uploader_id, created_at desc) where deleted_at is null;
create index ix_track_artist on track (lower(artist)) where deleted_at is null;
create index ix_track_title on track (lower(title)) where deleted_at is null;

-- «Моя музыка»: что человек загрузил или добавил себе
create table user_track
(
    user_id  uuid        not null references users (id),
    track_id uuid        not null references track (id),
    added_at timestamptz not null default now(),
    primary key (user_id, track_id)
);
create index ix_user_track_recent on user_track (user_id, added_at desc);

-- Музыка сообщества
create table community_track
(
    community_id uuid        not null references community (id),
    track_id     uuid        not null references track (id),
    added_by     uuid        not null references users (id),
    added_at     timestamptz not null default now(),
    primary key (community_id, track_id)
);
create index ix_community_track_recent on community_track (community_id, added_at desc);

-- Плейлисты: личные или сообщества
create table playlist
(
    id             uuid primary key,
    owner_id       uuid        not null references users (id),
    community_id   uuid references community (id),
    title          text        not null,
    description    text,
    cover_media_id uuid references media (id),
    is_public      boolean     not null default true,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    deleted_at     timestamptz
);
create index ix_playlist_owner on playlist (owner_id) where deleted_at is null and community_id is null;
create index ix_playlist_community on playlist (community_id) where deleted_at is null;

create table playlist_track
(
    playlist_id uuid        not null references playlist (id),
    track_id    uuid        not null references track (id),
    position    int         not null default 0,
    added_at    timestamptz not null default now(),
    primary key (playlist_id, track_id)
);
create index ix_playlist_track_pos on playlist_track (playlist_id, position);

-- «Сейчас слушает»: трек играет до ends_at (старт + оставшаяся длительность)
create table now_playing
(
    user_id    uuid primary key references users (id),
    track_id   uuid        not null references track (id),
    started_at timestamptz not null default now(),
    ends_at    timestamptz not null
);

-- Треки, прикреплённые к записям, комментариям, сообщениям, гостевой
create table track_attachment
(
    owner_type text not null check (owner_type in ('post', 'comment', 'message', 'guestbook')),
    owner_id   uuid not null,
    track_id   uuid not null references track (id),
    position   int  not null default 0,
    primary key (owner_type, owner_id, track_id)
);
create index ix_track_attachment_owner on track_attachment (owner_type, owner_id, position);
