-- Комнаты (страница пользователя), гостевая, гости за сутки, ссылки,
-- сообщества (доработка таблиц из V1), загруженные картинки, уведомления.

-- ---------------------------------------------------------------------------
-- Картинки. Сейчас файлы лежат на диске (straycatz.media.dir), storage_key —
-- относительный путь; при переезде в S3 это станет ключом объекта.
-- ---------------------------------------------------------------------------
create table media
(
    id           uuid primary key,
    owner_id     uuid        not null references users (id),
    content_type text        not null,
    size_bytes   bigint      not null,
    storage_key  text        not null,
    created_at   timestamptz not null default now()
);
create index ix_media_owner on media (owner_id, created_at desc);

-- ---------------------------------------------------------------------------
-- Комната: одна на пользователя, создаётся лениво при первом обращении.
-- ---------------------------------------------------------------------------
create table room
(
    owner_id       uuid primary key references users (id),
    title          text        not null,
    mood           text,                                   -- «слушаю громко, отвечаю медленно»
    about          text,                                   -- блок «обо мне»
    sticker        text,                                   -- наклейка на стене, до 40 символов
    theme          text        not null default 'dvor'
        check (theme in ('dvor', 'fonar', 'led', 'malina')),
    wallpaper      text        not null default 'grid'
        check (wallpaper in ('grid', 'asphalt', 'stars', 'stripes', 'none')),
    wall_media_id  uuid references media (id) on delete set null,
    wall_image_url text,                                   -- внешняя ссылка (если не загружали файл)
    wall_fit       text        not null default 'cover'
        check (wall_fit in ('cover', 'contain', 'tile')),
    wall_veil      numeric(3, 2) not null default 0.40
        check (wall_veil between 0.35 and 0.85),           -- ниже 35% нельзя: текст должен читаться
    wall_blur      int         not null default 0 check (wall_blur between 0 and 12),
    tilt           numeric(3, 2) not null default 1.00 check (tilt between 0 and 1),
    dialect        text        not null default 'normal'
        check (dialect in ('normal', 'yard', 'forum', 'cat', 'dry')),
    words          jsonb       not null default '{}'::jsonb,  -- свои названия кнопок: {"guestbook_sign": "Подписать"}
    blocks         jsonb       not null default                -- видимые блоки в порядке показа
        '["about","music","friends","guestbook","activity","badges","video","communities","links"]'::jsonb,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

create table room_link
(
    id         uuid primary key,
    owner_id   uuid        not null references users (id),
    title      text        not null,
    url        text        not null,
    position   int         not null default 0,
    created_at timestamptz not null default now()
);
create index ix_room_link_owner on room_link (owner_id, position);

create table room_guestbook_entry
(
    id         uuid primary key,
    owner_id   uuid        not null references users (id),  -- чья комната
    author_id  uuid        not null references users (id),
    body       text        not null,
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);
create index ix_guestbook_room on room_guestbook_entry (owner_id, created_at desc) where deleted_at is null;
create index ix_guestbook_author on room_guestbook_entry (author_id, owner_id, created_at desc);

-- Гость = залогиненный пользователь (не хозяин), один раз в сутки (UTC-дата).
-- "Гостей за сутки" = уникальные visitor_id с last_at за последние 24 часа.
create table room_visit
(
    owner_id   uuid        not null references users (id),
    visitor_id uuid        not null references users (id),
    day        date        not null,
    first_at   timestamptz not null default now(),
    last_at    timestamptz not null default now(),
    primary key (owner_id, visitor_id, day)
);
create index ix_room_visit_recent on room_visit (owner_id, last_at desc);

-- ---------------------------------------------------------------------------
-- Сообщества: в V1 были только id/name/permission_rules — добиваем до рабочих.
-- ---------------------------------------------------------------------------
alter table community add column slug        text;
alter table community add column description text;
alter table community add column color       text;
alter table community add column avatar      text;
alter table community add column owner_id    uuid references users (id);
alter table community add column created_at  timestamptz not null default now();
update community set slug = id::text where slug is null;
alter table community alter column slug set not null;
create unique index uq_community_slug on community (lower(slug)) where not is_deleted;

-- один человек — одна строка на сообщество; выход = left_at, повторный вход = left_at null
alter table community_member add column role text not null default 'member'
    check (role in ('owner', 'admin', 'member'));
alter table community_member add constraint pk_community_member primary key (community_id, user_id);
create index ix_community_member_user on community_member (user_id) where left_at is null;
create index ix_community_member_active on community_member (community_id) where left_at is null;

-- ---------------------------------------------------------------------------
-- Уведомления (пока: приглашение в гости, запись в гостевой).
-- ---------------------------------------------------------------------------
create table notification
(
    id         uuid primary key,
    user_id    uuid        not null references users (id),  -- кому
    kind       text        not null,                        -- room_invite / guestbook_entry / ...
    actor_id   uuid references users (id),                  -- от кого
    payload    jsonb       not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    read_at    timestamptz
);
create index ix_notification_user on notification (user_id, created_at desc);
create index ix_notification_unread on notification (user_id) where read_at is null;
