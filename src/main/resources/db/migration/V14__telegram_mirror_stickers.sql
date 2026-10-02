-- ===========================================================================
-- V14: зеркала Telegram-каналов (автоследование) и импорт наборов стикеров.
-- ===========================================================================

-- Системный пользователь: от его имени публикуются записи зеркал и хранятся
-- импортированные наборы стикеров. В Keycloak его нет — войти им нельзя.
insert into users (id, username, created_at, updated_at)
values ('00000000-0000-0000-0000-00000000007e', 'telegram', now(), now())
on conflict do nothing;
insert into user_cosmetics (user_id, color, tagline)
values ('00000000-0000-0000-0000-00000000007e', '#2aabee', 'зеркала Telegram-каналов')
on conflict do nothing;

-- ---------------------------------------------------------------------------
-- Сообщество-зеркало: source = 'telegram'. Писать туда нельзя никому (кроме
-- синхронизации), администрировать — только паузу/обновление тому, кто добавил.
-- Когда настоящий владелец канала докажет, что он владелец, — claimed_by и
-- передача сообщества (см. README, «Передать зеркало владельцу»).
-- ---------------------------------------------------------------------------
alter table community add column source     text check (source in ('telegram'));
alter table community add column source_ref text;            -- username канала
create unique index ux_community_source on community (source, lower(source_ref)) where source is not null;

create table telegram_channel
(
    id               uuid primary key,
    username         text        not null,
    community_id     uuid        not null references community (id),
    title            text,
    description      text,
    subscribers      int,
    last_post_id     bigint      not null default 0,     -- последний перенесённый пост (номер в канале)
    paused           boolean     not null default false,
    added_by         uuid        not null references users (id),
    claimed_by       uuid references users (id),         -- реальный владелец, которому отдали зеркало
    created_at       timestamptz not null default now(),
    last_synced_at   timestamptz,
    last_meta_at     timestamptz,                        -- когда обновляли название/аватар/описание
    last_error       text,
    errors_in_row    int         not null default 0
);
create unique index ux_telegram_channel_username on telegram_channel (lower(username));

-- Какой пост канала каким постом у нас стал (без дублей при повторной синхронизации).
create table telegram_post
(
    channel_id uuid        not null references telegram_channel (id),
    tg_post_id bigint      not null,
    post_id    uuid        not null references post (id),
    created_at timestamptz not null default now(),
    primary key (channel_id, tg_post_id)
);

-- Ссылка на оригинал (t.me/<канал>/<номер>) у записей-зеркал.
alter table post add column source_url text;

-- ---------------------------------------------------------------------------
-- Импорт наборов стикеров из Telegram (t.me/addstickers/NAME).
-- format: static (webp/png), video (webm), animated (tgs — Lottie в gzip).
-- ---------------------------------------------------------------------------
alter table sticker add column format text not null default 'static' check (format in ('static', 'video', 'animated'));
alter table sticker add column source_ref text;               -- file_unique_id стикера в Telegram

alter table sticker_pack add column source     text check (source in ('telegram'));
alter table sticker_pack add column source_ref text;          -- имя набора в Telegram
create unique index ux_sticker_pack_source on sticker_pack (source, lower(source_ref)) where source is not null and deleted_at is null;

create table sticker_import
(
    pack_id     uuid primary key references sticker_pack (id),
    set_name    text        not null,
    status      text        not null default 'pending' check (status in ('pending', 'running', 'done', 'failed')),
    total       int         not null default 0,
    done        int         not null default 0,
    items       jsonb       not null default '[]'::jsonb,   -- [{fileId, uniqueId, emoji, format}] — что качать
    error       text,
    requested_by uuid       not null references users (id),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
