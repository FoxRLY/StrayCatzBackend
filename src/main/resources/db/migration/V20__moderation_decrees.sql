-- V20: модерация (жалобы → тикеты → меры), блокировки и ограничения людей и сообществ,
-- журнал действий модераторов, «указы диктатора» — неснимаемые плашки в профиль.
-- Роли — в Keycloak (realm roles moderator / dictator); здесь только кэш роли для отображения.

-- ---------------------------------------------------------------------------
-- 1. Люди: блокировка (нельзя ничего, даже читать) и ограничение (только читать).
--    'infinity' — навсегда. Удалять не удаляем: аккаунт и всё созданное остаётся.
-- ---------------------------------------------------------------------------
alter table users add column banned_until     timestamptz;
alter table users add column ban_reason       text;
alter table users add column restricted_until timestamptz;
alter table users add column restrict_reason  text;
-- роль из последнего токена (moderator / dictator) — чтобы модератор не мог тронуть модератора
alter table users add column staff_role       text check (staff_role in ('moderator', 'dictator'));

create index ix_users_banned on users (banned_until) where banned_until is not null;
create index ix_users_restricted on users (restricted_until) where restricted_until is not null;
create index ix_users_staff on users (staff_role) where staff_role is not null;

-- ---------------------------------------------------------------------------
-- 2. Сообщества: заморозка (только читать) и блокировка (скрыто ото всех).
--    Заблокированное помечается is_deleted = true (так оно пропадает из всех лент и каталога
--    без правки десятков запросов) + blocked_at; разблокировка возвращает is_deleted = false.
-- ---------------------------------------------------------------------------
alter table community add column blocked_at    timestamptz;
alter table community add column block_reason  text;
alter table community add column frozen_until  timestamptz;
alter table community add column freeze_reason text;

-- ---------------------------------------------------------------------------
-- 3. Тикет — одна сущность, на которую жалуются (пока он не закрыт).
--    collecting — жалоб меньше порога, модераторы его не видят в очереди;
--    open — порог набран, ждёт модератора; in_progress — модератор взял;
--    resolved — нарушение, меры приняты; dismissed — нарушения нет.
-- ---------------------------------------------------------------------------
create table mod_ticket
(
    id            uuid primary key,
    target_type   text        not null check (target_type in
        ('user', 'community', 'post', 'comment', 'message', 'market', 'track', 'video', 'guestbook', 'event')),
    target_id     uuid        not null,
    owner_id      uuid references users (id),          -- автор контента / сам человек / владелец сообщества
    community_id  uuid references community (id),     -- где это было (для фильтра)
    status        text        not null default 'collecting'
        check (status in ('collecting', 'open', 'in_progress', 'resolved', 'dismissed')),
    reports       int         not null default 0,
    threshold     int         not null,                -- сколько жалоб нужно (в личке — 1)
    top_reason    text,                                -- самая частая причина
    snapshot      jsonb       not null default '{}'::jsonb,  -- как выглядело в момент первой жалобы
    assignee_id   uuid references users (id),
    assigned_at   timestamptz,
    verdict       text check (verdict in ('violation', 'no_violation')),
    note          text,                                -- комментарий модератора
    resolved_by   uuid references users (id),
    resolved_at   timestamptz,
    created_at    timestamptz not null default now(),
    opened_at     timestamptz,
    updated_at    timestamptz not null default now()
);
-- на одну сущность — один живой тикет
create unique index ux_mod_ticket_live on mod_ticket (target_type, target_id)
    where status in ('collecting', 'open', 'in_progress');
create index ix_mod_ticket_queue on mod_ticket (status, opened_at);
create index ix_mod_ticket_owner on mod_ticket (owner_id, created_at desc);
create index ix_mod_ticket_assignee on mod_ticket (assignee_id, status) where assignee_id is not null;

create table mod_report
(
    id          uuid primary key,
    ticket_id   uuid        not null references mod_ticket (id) on delete cascade,
    reporter_id uuid        not null references users (id),
    reason      text        not null check (reason in
        ('spam', 'abuse', 'hate', 'nsfw', 'violence', 'illegal', 'fraud', 'impersonation', 'self_harm', 'copyright', 'other')),
    comment     text check (length(comment) <= 1000),
    created_at  timestamptz not null default now(),
    unique (ticket_id, reporter_id)
);
create index ix_mod_report_reporter on mod_report (reporter_id, created_at desc);

-- ---------------------------------------------------------------------------
-- 4. Журнал: каждое действие модератора и диктатора.
-- ---------------------------------------------------------------------------
create table mod_action
(
    id           uuid primary key,
    moderator_id uuid        not null references users (id),
    action       text        not null,
    target_type  text        not null,
    target_id    uuid        not null,
    user_id      uuid references users (id),           -- чей контент / кого касается
    community_id uuid references community (id),
    ticket_id    uuid references mod_ticket (id),
    reason       text,
    until        timestamptz,                          -- для ban/restrict/freeze; 'infinity' — навсегда
    created_at   timestamptz not null default now()
);
create index ix_mod_action_user on mod_action (user_id, created_at desc);
create index ix_mod_action_target on mod_action (target_type, target_id, created_at desc);
create index ix_mod_action_moderator on mod_action (moderator_id, created_at desc);
create index ix_mod_action_recent on mod_action (created_at desc);

-- ---------------------------------------------------------------------------
-- 5. Указ диктатора: текстовая плашка в профиле. Человек её не снимает,
--    модераторы — тоже; отозвать может только диктатор. Одна живая на человека.
-- ---------------------------------------------------------------------------
create table user_decree
(
    id         uuid primary key,
    user_id    uuid        not null references users (id),
    text       text        not null check (length(text) between 1 and 120),
    emoji      text check (length(emoji) <= 16),
    color      text check (color ~ '^#[0-9a-fA-F]{6}$'),
    issued_by  uuid        not null references users (id),
    issued_at  timestamptz not null default now(),
    revoked_at timestamptz,
    revoked_by uuid references users (id)
);
create unique index ux_user_decree_live on user_decree (user_id) where revoked_at is null;
create index ix_user_decree_history on user_decree (user_id, issued_at desc);
