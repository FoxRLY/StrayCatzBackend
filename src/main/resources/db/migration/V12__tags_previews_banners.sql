-- ===========================================================================
-- V12: теги везде, превью видео и стримов, аватар и шапка сообщества.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Теги. Одна таблица связей на всё: запись (в т.ч. пульс и стена), видео
-- (id файла), эфир, трек, сообщество, событие.
-- tag — нормализованный: нижний регистр, без '#', пробелы и дефисы → '_'.
-- auto = true — вытащен из текста (#хэштег), false — указан руками.
-- ---------------------------------------------------------------------------
create table tag_link
(
    tag        text        not null check (length(tag) between 2 and 40 and tag = lower(tag) and tag !~ '[\s#]'),
    owner_type text        not null check (owner_type in ('post', 'video', 'stream', 'track', 'community', 'event')),
    owner_id   uuid        not null,
    auto       boolean     not null default false,
    created_at timestamptz not null default now(),
    primary key (owner_type, owner_id, tag)
);
create index ix_tag_link_tag on tag_link (tag, created_at desc);
create index ix_tag_link_prefix on tag_link (tag text_pattern_ops);
create index ix_tag_link_recent on tag_link (created_at desc);

-- что уже есть: хэштеги из записей и теги треков
insert into tag_link (tag, owner_type, owner_id, auto, created_at)
select distinct lower(m[1]), 'post', p.id, true, p.created_at
from post p,
     regexp_matches(coalesce(p.title, '') || ' ' || p.body, '(?:^|[^0-9A-Za-zА-Яа-яЁё_#])#([0-9A-Za-zА-Яа-яЁё_]{2,40})', 'g') as m
where not p.is_deleted
on conflict do nothing;

insert into tag_link (tag, owner_type, owner_id, auto, created_at)
select distinct t2.tag, 'track', t.id, false, t.created_at
from track t,
     lateral (select regexp_replace(regexp_replace(lower(trim(x)), '[\s\-]+', '_', 'g'), '[^0-9a-zа-яё_]', '', 'g') as tag
              from jsonb_array_elements_text(t.tags) as x) t2
where t.deleted_at is null and length(t2.tag) between 2 and 40
on conflict do nothing;

-- ---------------------------------------------------------------------------
-- Превью видео: сервер сам вытаскивает кадр и длительность (ffmpeg) при загрузке.
-- poster_auto — обложку сделал сервер (своя обложка автора её заменяет);
-- poster_failed_at — кадр вытащить не вышло, фоновая догрузка больше не пробует.
-- ---------------------------------------------------------------------------
alter table video_meta add column poster_auto boolean not null default false;
alter table video_meta add column poster_failed_at timestamptz;

-- ---------------------------------------------------------------------------
-- Превью стримов: своя обложка (poster) + живой кадр, который сервер снимает
-- с идущего эфира раз в ~20 секунд и кладёт в хранилище по постоянному ключу.
-- ---------------------------------------------------------------------------
alter table stream add column poster_media_id uuid references media (id) on delete set null;
alter table stream add column thumb_key text;
alter table stream add column thumb_at timestamptz;

-- ---------------------------------------------------------------------------
-- Сообщество: шапка (верхний бар с картинкой). avatar уже был (строкой).
-- banner — адрес картинки (/api/media/{id} или внешний), banner_focus — какая
-- часть картинки по вертикали в кадре: 0 — верх, 0.5 — центр, 1 — низ.
-- ---------------------------------------------------------------------------
alter table community add column banner text;
alter table community add column banner_focus numeric(3, 2) not null default 0.50 check (banner_focus between 0 and 1);
