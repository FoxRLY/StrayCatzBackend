-- Пульс: общие короткие записи (как лента твиттера/реддита) поверх той же
-- таблицы post. Апвоуты, лайки, комментарии, репосты, прочтения — общие с
-- записями сообществ. Запись пульса может быть привязана к сообществу:
-- тогда она видна и там, с пометкой «пульсар».

alter table post add column is_pulse     boolean not null default false;
-- от имени сообщества (автор — admin, показывается сообщество)
alter table post add column as_community boolean not null default false;

alter table post drop constraint if exists post_kind_check;
alter table post add constraint post_kind_check
    check (kind in ('text', 'image', 'video', 'track', 'guide', 'poll'));

create index ix_post_pulse on post (created_at desc) where is_pulse and not is_deleted;

-- Вложения: до 4 картинок/гифок или одно видео
create table post_media
(
    post_id  uuid not null references post (id),
    media_id uuid not null references media (id),
    position int  not null default 0,
    primary key (post_id, media_id)
);

-- Опросы
create table poll
(
    post_id   uuid primary key references post (id),
    multiple  boolean     not null default false,
    closes_at timestamptz not null
);

create table poll_option
(
    id       uuid primary key,
    post_id  uuid not null references poll (post_id),
    text     text not null,
    position int  not null
);
create index ix_poll_option_post on poll_option (post_id, position);

create table poll_vote
(
    post_id    uuid        not null references poll (post_id),
    option_id  uuid        not null references poll_option (id),
    user_id    uuid        not null references users (id),
    created_at timestamptz not null default now(),
    primary key (post_id, user_id, option_id)
);
create index ix_poll_vote_option on poll_vote (option_id);
