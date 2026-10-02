-- ===========================================================================
-- V13: чат как в телеге — ответы, пересылка, реакции, упоминания, аватар беседы,
-- гифки из внешнего источника (с постепенным кэшированием у себя) и стикеры.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Сообщение: ответ, пересылка, гифка, стикер
-- ---------------------------------------------------------------------------
alter table message add column reply_to_id    uuid;               -- на какое сообщение этого чата ответ
alter table message add column fwd_user_id    uuid references users (id);  -- автор оригинала (пересланное)
alter table message add column fwd_message_id uuid;               -- оригинал (может быть уже удалён)
alter table message add column fwd_chat_id    uuid;               -- откуда переслано
alter table message add column fwd_at         timestamptz;        -- когда был написан оригинал
alter table message add column gif_id         uuid;               -- гифка / внешний стикер (таблица gif)
alter table message add column sticker_id     uuid;               -- стикер из наборов (таблица sticker)

-- Реакции: один человек — до 3 разных эмодзи на сообщение.
create table message_reaction
(
    message_id uuid        not null references message (id),
    user_id    uuid        not null references users (id),
    emoji      text        not null check (length(emoji) between 1 and 32),
    created_at timestamptz not null default now(),
    primary key (message_id, user_id, emoji)
);
create index ix_message_reaction_msg on message_reaction (message_id);

-- Упоминания @ник: в сообщениях, записях, комментариях.
create table mention
(
    owner_type text        not null check (owner_type in ('message', 'post', 'comment')),
    owner_id   uuid        not null,
    user_id    uuid        not null references users (id),
    created_at timestamptz not null default now(),
    primary key (owner_type, owner_id, user_id)
);
create index ix_mention_user on mention (user_id, created_at desc);

-- Аватар беседы (для групп; у лички — аватар собеседника).
alter table chat add column avatar text;

-- ---------------------------------------------------------------------------
-- Гифки и внешние стикеры. Метаданные оседают здесь из каждого поиска (так
-- со временем растёт своя библиотека и работает поиск без провайдера), файл
-- скачивается к себе (media) при первой отправке или фоновой догрузкой.
-- ---------------------------------------------------------------------------
create table gif
(
    id           uuid primary key,
    provider     text        not null,                 -- klipy / giphy
    external_id  text        not null,
    kind         text        not null check (kind in ('gif', 'sticker')),
    title        text,
    source_url   text        not null,                 -- откуда качать к себе
    preview_url  text,                                 -- лёгкое превью у провайдера (для пикера)
    width        int,
    height       int,
    media_id     uuid references media (id),           -- наша копия; null — ещё не скачана
    failed_at    timestamptz,                          -- скачать не вышло (не пробуем снова сутки)
    uses         bigint      not null default 0,
    created_at   timestamptz not null default now(),
    last_used_at timestamptz,
    unique (provider, external_id, kind)
);
create index ix_gif_popular on gif (kind, uses desc);
create index ix_gif_title on gif (kind, lower(title) text_pattern_ops);

-- Ответы провайдера на поиск — на час, чтобы не упираться в лимиты.
create table gif_search_cache
(
    provider   text        not null,
    kind       text        not null,
    query      text        not null,                  -- '' — trending
    page       int         not null,
    gif_ids    jsonb       not null,                  -- наши id в порядке выдачи
    has_next   boolean     not null,
    fetched_at timestamptz not null default now(),
    primary key (provider, kind, query, page)
);

-- Мои недавние гифки и стикеры (для вкладки «недавние»).
create table recent_gif
(
    user_id uuid        not null references users (id),
    gif_id  uuid        not null references gif (id),
    used_at timestamptz not null default now(),
    primary key (user_id, gif_id)
);

-- ---------------------------------------------------------------------------
-- Наборы стикеров как в телеге: человек собирает набор из своих картинок,
-- другие добавляют его себе.
-- ---------------------------------------------------------------------------
create table sticker_pack
(
    id         uuid primary key,
    owner_id   uuid        not null references users (id),
    title      text        not null,
    is_public  boolean     not null default true,
    installs   bigint      not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);
create index ix_sticker_pack_owner on sticker_pack (owner_id) where deleted_at is null;

create table sticker
(
    id         uuid primary key,
    pack_id    uuid        not null references sticker_pack (id),
    media_id   uuid        not null references media (id),
    emoji      text,                                  -- «к какому эмодзи» — для подсказок
    position   int         not null default 0,
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);
create index ix_sticker_pack on sticker (pack_id, position) where deleted_at is null;

create table user_sticker_pack
(
    user_id  uuid        not null references users (id),
    pack_id  uuid        not null references sticker_pack (id),
    position int         not null default 0,
    added_at timestamptz not null default now(),
    primary key (user_id, pack_id)
);

create table recent_sticker
(
    user_id    uuid        not null references users (id),
    sticker_id uuid        not null references sticker (id),
    used_at    timestamptz not null default now(),
    primary key (user_id, sticker_id)
);
