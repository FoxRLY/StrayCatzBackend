-- ===========================================================================
-- V11: общая лента (/api/lenta).
-- Лента не хранится — собирается запросом из того, что уже есть (записи, видео,
-- эфиры, музыка, события, обсуждения, гостевые, ответы, дружба).
-- Не хватало только истории «перестановок в комнатах»: у room одна updated_at.
-- ===========================================================================

create table room_activity
(
    id         uuid primary key,
    owner_id   uuid        not null references users (id),
    detail     text        not null,              -- готовая фраза: «переклеил(а) обои в комнате»
    created_at timestamptz not null default now()
);
create index ix_room_activity_owner on room_activity (owner_id, created_at desc);

-- выборки ленты идут «всё новее/старее момента» по времени
create index if not exists ix_post_created on post (created_at desc) where not is_deleted;
create index if not exists ix_track_created on track (created_at desc) where deleted_at is null;
create index if not exists ix_community_member_joined on community_member (user_id, created_at desc) where left_at is null;
create index if not exists ix_post_comment_created on post_comment (post_id, created_at desc) where deleted_at is null;
create index if not exists ix_community_event_created on community_event (community_id, created_at desc);
