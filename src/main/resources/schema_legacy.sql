-- =========================================================================
-- straycatz — набросок схемы Postgres (16+).
-- Это не финальная миграция, а отправная точка: имена, связи и те решения,
-- которые вытекают из продукта, а не из привычки.
--
-- Три вещи, из-за которых схема выглядит именно так:
--   1) у каждого места (комната, сообщество) свой словарь интерфейса;
--   2) видео и записи попадают в ленту с указанием социальной причины;
--   3) лента материализуется в feed_event, а не собирается джойнами на лету.
-- =========================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- поиск по людям и сообществам

-- ------------------------------------------------------------------ люди

CREATE TYPE presence_status AS ENUM ('online', 'away', 'busy', 'invisible');

CREATE TABLE app_user (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  handle         citext UNIQUE NOT NULL,          -- нужен CREATE EXTENSION citext
  display_name   text NOT NULL,
  hue            smallint NOT NULL DEFAULT 200 CHECK (hue BETWEEN 0 AND 359),
  tagline        text,
  mood           text,
  level          integer NOT NULL DEFAULT 1,
  xp             real NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz
);

-- Присутствие меняется часто и живёт отдельно от профиля: свои индексы,
-- свой цикл записи, и таблицу не жалко держать частично в памяти.
CREATE TABLE user_presence (
  user_id        uuid PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  status         presence_status NOT NULL DEFAULT 'offline'::text::presence_status,
  doing          text,                             -- «в беседе «ночная смена»»
  track_id       uuid,                             -- что играет прямо сейчас
  track_position integer,                          -- секунды
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON user_presence (status) WHERE status <> 'invisible';

CREATE TABLE friendship (
  user_id        uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  friend_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, friend_id),
  CHECK (user_id <> friend_id)
);
CREATE INDEX ON friendship (friend_id);

-- --------------------------------------------------------------- картинки

-- Пока храним блобами прямо в базе: до первых десятков гигабайт это проще,
-- чем возиться с объектным хранилищем. Отдавать только через /media/{id}
-- с ETag и long-cache, оригинал не переиспользовать как ключ.
CREATE TABLE media_blob (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id       uuid REFERENCES app_user(id) ON DELETE SET NULL,
  mime           text NOT NULL,
  bytes          bytea NOT NULL,
  byte_size      integer NOT NULL,
  width          integer,
  height         integer,
  sha256         bytea NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ON media_blob (sha256);       -- одинаковые файлы не дублируем
CREATE INDEX ON media_blob (owner_id);

-- ------------------------------------------------------------- словари

-- Язык места. Ключ фиксирован кодом (join, up, guestbook, …), меняется
-- только слово. Владельцем может быть и комната, и сообщество.
CREATE TYPE lexicon_owner AS ENUM ('room', 'community');

CREATE TABLE lexicon (
  owner_type     lexicon_owner NOT NULL,
  owner_id       uuid NOT NULL,
  dialect        text NOT NULL DEFAULT 'default',  -- готовый говор
  PRIMARY KEY (owner_type, owner_id)
);

CREATE TABLE lexicon_word (
  owner_type     lexicon_owner NOT NULL,
  owner_id       uuid NOT NULL,
  key            text NOT NULL,                    -- 'join', 'sign', 'members', …
  word           text NOT NULL CHECK (char_length(word) BETWEEN 1 AND 22),
  PRIMARY KEY (owner_type, owner_id, key),
  FOREIGN KEY (owner_type, owner_id) REFERENCES lexicon (owner_type, owner_id) ON DELETE CASCADE
);

-- --------------------------------------------------------------- комнаты

CREATE TYPE wallpaper_kind AS ENUM ('сетка', 'асфальт', 'звёзды', 'полосы', 'пусто');
CREATE TYPE backdrop_fit  AS ENUM ('обрезать', 'уместить', 'плитка');

CREATE TABLE room (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id       uuid UNIQUE NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  title          text NOT NULL,                    -- «подвал», «мостик»
  vibe           text,                             -- как хозяин её описал
  theme          text NOT NULL DEFAULT 'dvor',
  wallpaper      wallpaper_kind NOT NULL DEFAULT 'сетка',
  backdrop_id    uuid REFERENCES media_blob(id) ON DELETE SET NULL,
  backdrop_fit   backdrop_fit NOT NULL DEFAULT 'обрезать',
  backdrop_dim   real NOT NULL DEFAULT 0.55 CHECK (backdrop_dim >= 0.25 AND backdrop_dim <= 0.85),
  backdrop_blur  smallint NOT NULL DEFAULT 0 CHECK (backdrop_blur BETWEEN 0 AND 12),
  sticker        text,
  chaos          real NOT NULL DEFAULT 0.3 CHECK (chaos >= 0 AND chaos <= 1),
  updated_at     timestamptz NOT NULL DEFAULT now()
);

-- Раскладка: порядок важен, поэтому позиция хранится явно.
CREATE TABLE room_block (
  room_id        uuid NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  block          text NOT NULL,                    -- 'about', 'music', 'guestbook', …
  position       smallint NOT NULL,
  wide           boolean NOT NULL DEFAULT false,
  PRIMARY KEY (room_id, block)
);

CREATE TABLE guestbook_entry (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  room_id        uuid NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  author_id      uuid REFERENCES app_user(id) ON DELETE SET NULL,
  body           text NOT NULL CHECK (char_length(body) <= 600),
  created_at     timestamptz NOT NULL DEFAULT now(),
  hidden_at      timestamptz                       -- хозяин может убрать запись
);
CREATE INDEX ON guestbook_entry (room_id, created_at DESC);

CREATE TABLE room_visit (
  room_id        uuid NOT NULL REFERENCES room(id) ON DELETE CASCADE,
  visitor_id     uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  visited_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (room_id, visitor_id, visited_at)
);

-- ----------------------------------------------------------- сообщества

CREATE TABLE community (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug           citext UNIQUE NOT NULL,
  name           text NOT NULL,
  about          text,
  hue            smallint NOT NULL DEFAULT 200,
  founded_at     timestamptz NOT NULL DEFAULT now(),
  member_count   integer NOT NULL DEFAULT 0,       -- денормализация, обновляется триггером
  archived_at    timestamptz
);

-- Разделы сообщество включает себе само; платформа их не навязывает.
CREATE TABLE community_section (
  community_id   uuid NOT NULL REFERENCES community(id) ON DELETE CASCADE,
  key            text NOT NULL,                    -- 'posts', 'threads', 'media', 'wiki', …
  title          text NOT NULL,                    -- как раздел назвали
  position       smallint NOT NULL,
  PRIMARY KEY (community_id, key)
);

CREATE TYPE member_role AS ENUM ('member', 'moderator', 'founder');
CREATE TYPE mod_origin  AS ENUM ('основал', 'выбрали', 'по репутации');

CREATE TABLE community_member (
  community_id   uuid NOT NULL REFERENCES community(id) ON DELETE CASCADE,
  user_id        uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role           member_role NOT NULL DEFAULT 'member',
  mod_origin     mod_origin,                       -- заполнено только у модераторов
  reputation     integer NOT NULL DEFAULT 0,
  joined_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (community_id, user_id)
);
CREATE INDEX ON community_member (user_id);
CREATE INDEX ON community_member (community_id, role) WHERE role <> 'member';

CREATE TABLE community_rule (
  community_id   uuid NOT NULL REFERENCES community(id) ON DELETE CASCADE,
  position       smallint NOT NULL,
  text           text NOT NULL,
  PRIMARY KEY (community_id, position)
);

-- Журнал модерации открыт всем: это часть продукта, а не служебный лог.
CREATE TYPE modlog_kind AS ENUM ('обычное', 'снятие', 'голос', 'права');

CREATE TABLE modlog_entry (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  community_id   uuid NOT NULL REFERENCES community(id) ON DELETE CASCADE,
  actor_id       uuid REFERENCES app_user(id) ON DELETE SET NULL,  -- NULL = решение голосованием
  kind           modlog_kind NOT NULL,
  summary        text NOT NULL,
  target_type    text,
  target_id      uuid,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON modlog_entry (community_id, created_at DESC);

-- ---------------------------------------------------------------- записи

CREATE TYPE post_kind AS ENUM ('text', 'image', 'video', 'track');

CREATE TABLE post (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  community_id   uuid NOT NULL REFERENCES community(id) ON DELETE CASCADE,
  author_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  kind           post_kind NOT NULL DEFAULT 'text',
  title          text,
  body           text,
  track_id       uuid,
  score          integer NOT NULL DEFAULT 0,       -- денормализация голосов
  comment_count  integer NOT NULL DEFAULT 0,
  heat           real NOT NULL DEFAULT 0,          -- активность за последний час
  created_at     timestamptz NOT NULL DEFAULT now(),
  removed_at     timestamptz,
  removed_reason text
);
CREATE INDEX ON post (community_id, created_at DESC);
CREATE INDEX ON post (heat DESC) WHERE removed_at IS NULL;

CREATE TABLE post_media (
  post_id        uuid NOT NULL REFERENCES post(id) ON DELETE CASCADE,
  media_id       uuid NOT NULL REFERENCES media_blob(id) ON DELETE CASCADE,
  position       smallint NOT NULL DEFAULT 0,
  PRIMARY KEY (post_id, media_id)
);

CREATE TABLE post_vote (
  post_id        uuid NOT NULL REFERENCES post(id) ON DELETE CASCADE,
  user_id        uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  value          smallint NOT NULL CHECK (value IN (-1, 1)),
  created_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (post_id, user_id)
);

CREATE TABLE comment (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id        uuid NOT NULL REFERENCES post(id) ON DELETE CASCADE,
  parent_id      uuid REFERENCES comment(id) ON DELETE CASCADE,
  author_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  body           text NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  removed_at     timestamptz
);
CREATE INDEX ON comment (post_id, created_at);

-- ------------------------------------------------- обсуждения и события

CREATE TABLE thread (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  community_id   uuid REFERENCES community(id) ON DELETE CASCADE,
  artist_id      uuid,                              -- обсуждение может висеть на объекте
  title          text NOT NULL,
  author_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  pinned         boolean NOT NULL DEFAULT false,
  reply_count    integer NOT NULL DEFAULT 0,
  last_reply_at  timestamptz NOT NULL DEFAULT now(),
  created_at     timestamptz NOT NULL DEFAULT now(),
  CHECK (community_id IS NOT NULL OR artist_id IS NOT NULL)
);
CREATE INDEX ON thread (community_id, pinned DESC, last_reply_at DESC);

CREATE TABLE thread_reply (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  thread_id      uuid NOT NULL REFERENCES thread(id) ON DELETE CASCADE,
  author_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  body           text NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON thread_reply (thread_id, created_at);

CREATE TABLE community_event (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  community_id   uuid NOT NULL REFERENCES community(id) ON DELETE CASCADE,
  title          text NOT NULL,
  starts_at      timestamptz NOT NULL,
  created_by     uuid REFERENCES app_user(id) ON DELETE SET NULL,
  rsvp_count     integer NOT NULL DEFAULT 0
);

CREATE TABLE event_rsvp (
  event_id       uuid NOT NULL REFERENCES community_event(id) ON DELETE CASCADE,
  user_id        uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  PRIMARY KEY (event_id, user_id)
);

-- Голосования сообщества: ими меняются разделы, правила и права.
CREATE TABLE poll (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  community_id   uuid NOT NULL REFERENCES community(id) ON DELETE CASCADE,
  question       text NOT NULL,
  subject        text,                              -- 'section:projects', 'mod:nyx', …
  closes_at      timestamptz NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE poll_vote (
  poll_id        uuid NOT NULL REFERENCES poll(id) ON DELETE CASCADE,
  user_id        uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  value          boolean NOT NULL,                  -- за / против
  PRIMARY KEY (poll_id, user_id)
);

-- --------------------------------------------------------------- музыка

CREATE TABLE artist (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug           citext UNIQUE NOT NULL,
  name           text NOT NULL,
  about          text,
  hue            smallint NOT NULL DEFAULT 200
);

CREATE TABLE release (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  artist_id      uuid NOT NULL REFERENCES artist(id) ON DELETE CASCADE,
  title          text NOT NULL,
  year           smallint,
  cover_id       uuid REFERENCES media_blob(id) ON DELETE SET NULL
);

CREATE TABLE track (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  release_id     uuid REFERENCES release(id) ON DELETE CASCADE,
  artist_id      uuid NOT NULL REFERENCES artist(id) ON DELETE CASCADE,
  title          text NOT NULL,
  duration_sec   integer NOT NULL,
  bpm            smallint,
  hue            smallint NOT NULL DEFAULT 200,
  audio_id       uuid REFERENCES media_blob(id) ON DELETE SET NULL
);

-- История прослушиваний: из неё же собирается «слушают вместе».
CREATE TABLE listen (
  id             bigserial PRIMARY KEY,
  user_id        uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  track_id       uuid NOT NULL REFERENCES track(id) ON DELETE CASCADE,
  started_at     timestamptz NOT NULL DEFAULT now(),
  finished       boolean NOT NULL DEFAULT false
);
CREATE INDEX ON listen (user_id, started_at DESC);
CREATE INDEX ON listen (track_id, started_at DESC);

ALTER TABLE user_presence
  ADD CONSTRAINT user_presence_track_fk FOREIGN KEY (track_id) REFERENCES track(id) ON DELETE SET NULL;
ALTER TABLE post
  ADD CONSTRAINT post_track_fk FOREIGN KEY (track_id) REFERENCES track(id) ON DELETE SET NULL;
ALTER TABLE thread
  ADD CONSTRAINT thread_artist_fk FOREIGN KEY (artist_id) REFERENCES artist(id) ON DELETE CASCADE;

-- --------------------------------------------------------------- значки

CREATE TABLE badge (
  key            text PRIMARY KEY,
  name           text NOT NULL,
  description    text NOT NULL,
  hue            smallint NOT NULL DEFAULT 200
);

CREATE TABLE user_badge (
  user_id        uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  badge_key      text NOT NULL REFERENCES badge(key) ON DELETE CASCADE,
  granted_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, badge_key)
);

-- ---------------------------------------------------------------- беседы

CREATE TYPE chat_kind AS ENUM ('личное', 'беседа');

CREATE TABLE chat (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind           chat_kind NOT NULL,
  title          text,                              -- у личных пустой
  hue            smallint NOT NULL DEFAULT 200,
  created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE chat_member (
  chat_id        uuid NOT NULL REFERENCES chat(id) ON DELETE CASCADE,
  user_id        uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  joined_at      timestamptz NOT NULL DEFAULT now(),
  last_read_seq  bigint NOT NULL DEFAULT 0,         -- счётчик непрочитанного считается отсюда
  muted          boolean NOT NULL DEFAULT false,
  PRIMARY KEY (chat_id, user_id)
);
CREATE INDEX ON chat_member (user_id);

-- seq монотонный внутри беседы: по нему работают дочитывание и догрузка
-- после разрыва сокета. Глобальный id не годится, нужен именно порядок в чате.
CREATE TABLE message (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  chat_id        uuid NOT NULL REFERENCES chat(id) ON DELETE CASCADE,
  seq            bigint NOT NULL,
  author_id      uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  body           text,
  media_id       uuid REFERENCES media_blob(id) ON DELETE SET NULL,  -- гифка или картинка
  client_token   uuid,                              -- идемпотентность отправки
  created_at     timestamptz NOT NULL DEFAULT now(),
  edited_at      timestamptz,
  deleted_at     timestamptz,
  UNIQUE (chat_id, seq),
  UNIQUE (chat_id, client_token)
);
CREATE INDEX ON message (chat_id, seq DESC);

CREATE SEQUENCE IF NOT EXISTS message_seq_dummy;     -- см. примечание в BACKEND.md

-- ----------------------------------------------------------------- лента

-- Лента материализуется на запись: при появлении события пишем строку
-- каждому, кому оно положено. Так чтение остаётся одним индексным сканом,
-- а правила «почему это здесь» лежат в одном месте, а не в десяти джойнах.
CREATE TYPE feed_kind   AS ENUM ('post', 'clip', 'room', 'music', 'community');
CREATE TYPE feed_source AS ENUM ('сообщества', 'видео', 'комнаты', 'музыка', 'друзья');

CREATE TABLE feed_event (
  id             bigserial PRIMARY KEY,
  recipient_id   uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  kind           feed_kind NOT NULL,
  source         feed_source NOT NULL,
  subject_id     uuid NOT NULL,                     -- post.id, clip.id, room.id, …
  actor_id       uuid REFERENCES app_user(id) ON DELETE SET NULL,
  community_id   uuid REFERENCES community(id) ON DELETE CASCADE,
  -- социальная причина, которую фронт показывает над карточкой
  reason_act     text,                              -- 'загрузил', 'репостнул', 'обсуждают', …
  reason_detail  text,
  payload        jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON feed_event (recipient_id, created_at DESC);
CREATE INDEX ON feed_event (recipient_id, source, created_at DESC);

-- Теги и «о чём говорят»: считается по окну, а не по всему времени.
CREATE TABLE topic_heat (
  tag            citext PRIMARY KEY,
  scope          text,                              -- 'по всей сети' или slug сообщества
  heat           real NOT NULL DEFAULT 0,
  updated_at     timestamptz NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------------ TODO
-- Раздел видео сознательно не описан: в продукте он ещё не решён.
-- Сейчас клипы приходят в ленту как feed_event с kind='clip' и payload,
-- и этого достаточно, чтобы не блокировать остальное.
