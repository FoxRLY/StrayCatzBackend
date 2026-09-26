-- Базовая схема. Presence, чаты, сообщения — по протоколу из ТЗ.
-- Звонки (call.*) — расширение поверх протокола, сигналинг WebRTC,
-- сам медиапоток идёт мимо сервера (P2P или через отдельный TURN/SFU).

create table users
(
    id         uuid primary key     default gen_random_uuid(),
    username   text        not null unique,
    is_deleted bool                 default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamp            default null

);

create table user_cosmetics
(
    user_id uuid not null,
    avatar  text default null,
    color   text default null,
    tagline text default null,
    constraint fk_user_id
        foreign key (user_id)
            references users (id)
);

create table user_level
(
    user_id uuid not null,
    xp      int default 0,
    level   int default 0,
    constraint fk_user_id
        foreign key (user_id)
            references users (id)

);

create table friendship
(
    initiator_id uuid not null,
    acceptor_id  uuid not null,
    is_accepted  bool        default false,
    accepted_at  timestamptz default null,
    constraint fk_initiator_id
        foreign key (initiator_id)
            references users (id),
    constraint fk_acceptor_id
        foreign key (acceptor_id)
            references users (id)
);

create table post
(
    id          uuid primary key     default gen_random_uuid(),
    creator_id  uuid        not null,
    parent_post uuid        not null,
    post_data   json        not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    is_deleted  bool        not null default false,
    deleted_at  timestamptz          default null,
    constraint fk_creator_id
        foreign key (creator_id)
            references users (id),
    constraint fk_parent_post
        foreign key (parent_post)
            references post (id)
);

create table chat
(
    id               uuid primary key     default gen_random_uuid(),
    name             text,
    room_type        text        not null,
    permission_rules json,
    created_at       timestamptz not null default now(),
    is_deleted       bool        not null default false,
    deleted_at       timestamptz          default null
);

create table chat_member
(
    chat_id       uuid        not null,
    user_id       uuid        not null,
    permissions   json,
    is_deleted    bool        not null default false,
    created_at    timestamptz not null default now(),
    deleted_at    timestamptz          default null,
    last_read_seq bigint      not null default 0,
    constraint fk_chat_id
        foreign key (chat_id)
            references chat (id),
    constraint fk_user_id
        foreign key (user_id)
            references users (id)
);

create table community
(
    id               uuid primary key default gen_random_uuid(),
    name             text not null,
    permission_rules json,
    is_deleted       bool not null    default false,
    deleted_at       timestamptz      default null
);

create table community_member
(
    community_id uuid        not null,
    user_id      uuid        not null,
    permissions  json,
    leave_reason text                 default null,
    created_at   timestamptz not null default now(),
    left_at      timestamptz          default null,
    constraint fk_community_id
        foreign key (community_id)
            references community (id),
    constraint fk_user_id
        foreign key (user_id)
            references users (id)
);

-- seq — монотонный per-chat счётчик, как того требует протокол
-- (клиент детектит дыры и дозагружает историю через HTTP).
create table message
(
    id           uuid primary key     default gen_random_uuid(), -- совпадает с id кадра message.new
    chat_id      uuid        not null,
    seq          bigint      not null,
    user_id      uuid        not null,
    client_token uuid        not null,                           -- для дедупа/ack на стороне клиента
    body         text,
    media_id     uuid,
    created_at   timestamptz not null default now(),
    edited_at    timestamptz not null default now(),
    deleted_at   timestamptz          default null,
    constraint fk_chat_id
        foreign key (chat_id)
            references chat (id),
    constraint fk_user_id
        foreign key (user_id)
            references users (id)
);

-- монотонный seq на чат выдаём через отдельную последовательность-таблицу,
-- чтобы не ловить гонки на max(seq)+1 под конкурентной записью
create table chat_seq
(
    chat_id  uuid   not null,
    next_seq bigint not null default 1,
    constraint fk_chat_id
        foreign key (chat_id)
            references chat (id)
);

create table user_presence
(
    user_id      uuid        not null,
    status       text        not null default 'offline', -- online / away / offline / ...
    doing        text,
    track_id     text,
    position_sec integer,
    updated_at   timestamptz not null default now(),
    constraint fk_user_id
        foreign key (user_id)
            references users (id)
);


