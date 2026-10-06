-- V16: под 100k одновременных — аренда задач, индексы поиска, чистка уведомлений,
-- редкий опрос «тихих» каналов Telegram.

-- ---------------------------------------------------------------------------
-- 1. Аренда задач по расписанию: одна нода делает, остальные пропускают (JobLease).
-- ---------------------------------------------------------------------------
create table job_lease
(
    name   text primary key,
    holder text        not null,              -- id процесса
    until  timestamptz not null
);

-- ---------------------------------------------------------------------------
-- 2. Поиск: триграммы вместо полного прохода таблицы на каждый lower(x) like '%q%'.
--    Индексы — ровно на те выражения, что стоят в запросах (SearchService,
--    MusicService, CommunityService, MarketService). На большой живой базе их
--    лучше создать заранее через CREATE INDEX CONCURRENTLY с теми же именами — миграция их пропустит.
-- ---------------------------------------------------------------------------
create extension if not exists pg_trgm;

create index if not exists ix_trgm_users_username   on users using gin (username gin_trgm_ops);
create index if not exists ix_trgm_community_name   on community using gin (lower(name) gin_trgm_ops);
create index if not exists ix_trgm_community_slug   on community using gin (slug gin_trgm_ops);
create index if not exists ix_trgm_community_desc   on community using gin (lower(coalesce(description, '')) gin_trgm_ops);
create index if not exists ix_trgm_post_title       on post using gin (lower(coalesce(title, '')) gin_trgm_ops);
create index if not exists ix_trgm_post_body        on post using gin (lower(body) gin_trgm_ops);
create index if not exists ix_trgm_room_title       on room using gin (lower(title) gin_trgm_ops);
create index if not exists ix_trgm_room_mood        on room using gin (lower(coalesce(mood, '')) gin_trgm_ops);
create index if not exists ix_trgm_room_about       on room using gin (lower(coalesce(about, '')) gin_trgm_ops);
create index if not exists ix_trgm_track_title      on track using gin (lower(title) gin_trgm_ops);
create index if not exists ix_trgm_track_artist     on track using gin (lower(artist) gin_trgm_ops);
create index if not exists ix_trgm_track_album      on track using gin (lower(coalesce(album, '')) gin_trgm_ops);
create index if not exists ix_trgm_stream_title     on stream using gin (lower(title) gin_trgm_ops);
create index if not exists ix_trgm_event_title      on community_event using gin (lower(title) gin_trgm_ops);
create index if not exists ix_trgm_video_title      on video_meta using gin (title gin_trgm_ops);
create index if not exists ix_trgm_market_title     on market_item using gin (lower(title) gin_trgm_ops);
create index if not exists ix_trgm_market_desc      on market_item using gin (lower(description) gin_trgm_ops);
-- автодополнение тегов: tag like 'нач%'
create index if not exists ix_tag_link_tag_prefix   on tag_link (tag text_pattern_ops);

-- ---------------------------------------------------------------------------
-- 3. Чистка прочитанных уведомлений старше 90 дней (PostReadCleanupJob).
-- ---------------------------------------------------------------------------
create index if not exists ix_notification_read_old on notification (created_at) where read_at is not null;

-- ---------------------------------------------------------------------------
-- 4. Telegram: «тихие» каналы опрашиваются реже — каждая пустая синхронизация
--    подряд удлиняет паузу (10 мин × (1 + idle_streak), не больше часа).
-- ---------------------------------------------------------------------------
alter table telegram_channel add column idle_streak int not null default 0;
