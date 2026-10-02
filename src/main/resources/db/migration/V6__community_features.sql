-- Сообщества целиком: разделы, язык, правила, подписка «читать без вступления»,
-- записи (post из V1) с апвоутами/лайками/комментариями/прочтениями/репостами,
-- обсуждения (чаты сообщества), события, вики, проекты (подсообщества).

-- ---------------------------------------------------------------------------
-- community: оформление, язык, разделы, правила, проекты
-- ---------------------------------------------------------------------------
alter table community add column hue        int  not null default 200 check (hue between 0 and 359);
alter table community add column rules_text text;
alter table community add column dialect    text not null default 'default';
alter table community add column lexicon    jsonb not null default '{}'::jsonb;
-- ключи разделов в порядке показа, см. CommunityService.SECTION_LABELS
alter table community add column sections   jsonb not null default '["posts", "discussions", "events", "members"]'::jsonb;
-- проект = подсообщество с урезанными разделами (записи, обсуждения, события)
alter table community add column kind       text not null default 'community' check (kind in ('community', 'project'));
alter table community add column parent_id  uuid references community (id);
create index ix_community_parent on community (parent_id) where parent_id is not null and not is_deleted;

-- репутация в сообществе (пока у всех 0, формула — потом)
alter table community_member add column reputation int not null default 0;

-- «Читать без вступления»: запись в ленте есть, прав и уведомлений нет
create table community_follow
(
    community_id uuid        not null references community (id),
    user_id      uuid        not null references users (id),
    created_at   timestamptz not null default now(),
    primary key (community_id, user_id)
);
create index ix_community_follow_user on community_follow (user_id);

-- ---------------------------------------------------------------------------
-- post (V1) → записи сообществ. parent_post в V1 был NOT NULL со ссылкой на
-- post — первую запись создать было невозможно. Чиним.
-- ---------------------------------------------------------------------------
alter table post alter column parent_post drop not null;
alter table post alter column post_data set default '{}';
alter table post add column community_id uuid references community (id);
alter table post add column title        text;
alter table post add column body         text not null default '';
alter table post add column kind         text not null default 'text'
    check (kind in ('text', 'image', 'video', 'track', 'guide'));
alter table post add column meta         text;          -- «4:18», «11:04» — подпись к медиа
alter table post add column media_id     uuid references media (id);
alter table post add column pinned       boolean not null default false;
create index ix_post_community on post (community_id, created_at desc) where not is_deleted;
create index ix_post_creator on post (creator_id, created_at desc) where not is_deleted;

-- Апвоут: не больше 5 в сутки (UTC) на человека, не отзывается.
create table post_upvote
(
    post_id    uuid        not null references post (id),
    user_id    uuid        not null references users (id),
    created_at timestamptz not null default now(),
    primary key (post_id, user_id)
);
create index ix_post_upvote_user_day on post_upvote (user_id, created_at);

-- Лайк: «мне понравилось», сохраняется в избранное, снимается.
create table post_like
(
    post_id    uuid        not null references post (id),
    user_id    uuid        not null references users (id),
    created_at timestamptz not null default now(),
    primary key (post_id, user_id)
);
create index ix_post_like_user on post_like (user_id, created_at desc);

-- Комментарии с ответами (parent_id — на какой комментарий ответ).
create table post_comment
(
    id         uuid primary key,
    post_id    uuid        not null references post (id),
    author_id  uuid        not null references users (id),
    parent_id  uuid references post_comment (id),
    body       text        not null,
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);
create index ix_post_comment_post on post_comment (post_id, created_at);

-- Прочтение живёт 5 минут: «читают сейчас» = read_at за последние 5 минут.
-- Онлайн сообщества = уникальные user_id с прочтениями за 30 минут.
create table post_read
(
    post_id      uuid        not null references post (id),
    user_id      uuid        not null references users (id),
    community_id uuid,
    read_at      timestamptz not null default now(),
    primary key (post_id, user_id)
);
create index ix_post_read_recent on post_read (post_id, read_at);
create index ix_post_read_community on post_read (community_id, read_at);

-- Репосты в чаты (для счётчика и истории)
create table post_share
(
    id         uuid primary key,
    post_id    uuid        not null references post (id),
    user_id    uuid        not null references users (id),
    chat_id    uuid        not null references chat (id),
    created_at timestamptz not null default now()
);
create index ix_post_share_post on post_share (post_id);

-- сообщение в чате может быть репостом записи
alter table message add column shared_post_id uuid references post (id);

-- ---------------------------------------------------------------------------
-- Обсуждения = чаты сообщества (room_type 'community'), вход по кнопке.
-- ---------------------------------------------------------------------------
alter table chat add column community_id uuid references community (id);
alter table chat add column pinned       boolean not null default false;
alter table chat add column created_by   uuid references users (id);
create index ix_chat_community on chat (community_id) where community_id is not null and not is_deleted;

-- ---------------------------------------------------------------------------
-- События
-- ---------------------------------------------------------------------------
create table community_event
(
    id           uuid primary key,
    community_id uuid        not null references community (id),
    title        text        not null,
    description  text,
    location     text,
    starts_at    timestamptz not null,
    ends_at      timestamptz,
    created_by   uuid        not null references users (id),
    created_at   timestamptz not null default now(),
    cancelled_at timestamptz
);
create index ix_event_community on community_event (community_id, starts_at);

create table event_registration
(
    event_id    uuid        not null references community_event (id),
    user_id     uuid        not null references users (id),
    created_at  timestamptz not null default now(),
    reminded_at timestamptz,
    primary key (event_id, user_id)
);

-- ---------------------------------------------------------------------------
-- Вики: страницы с историей правок
-- ---------------------------------------------------------------------------
create table wiki_page
(
    id           uuid primary key,
    community_id uuid        not null references community (id),
    slug         text        not null,
    title        text        not null,
    body         text        not null default '',
    created_by   uuid        not null references users (id),
    updated_by   uuid        not null references users (id),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz
);
create unique index uq_wiki_page_slug on wiki_page (community_id, slug) where deleted_at is null;

create table wiki_revision
(
    id         uuid primary key,
    page_id    uuid        not null references wiki_page (id),
    title      text        not null,
    body       text        not null,
    editor_id  uuid        not null references users (id),
    created_at timestamptz not null default now()
);
create index ix_wiki_revision_page on wiki_revision (page_id, created_at desc);
