-- Дополнения к V1, без которых сокет-часть работает некорректно.
-- V1 не трогаем (если он уже накатан flyway'ем, правка сломает checksum).

-- ---------------------------------------------------------------------------
-- 1. Первичные ключи / уникальности.
--    Без них: JPA-сущности с составным ключом ведут себя непредсказуемо,
--    upsert в chat_seq невозможен (гонка -> дубли seq), а повтор
--    message.send с тем же clientToken создаёт дубль сообщения.
-- ---------------------------------------------------------------------------
alter table chat_member   add constraint pk_chat_member   primary key (chat_id, user_id);
alter table chat_seq      add constraint pk_chat_seq      primary key (chat_id);
alter table user_presence add constraint pk_user_presence primary key (user_id);

alter table message add constraint uq_message_chat_seq     unique (chat_id, seq);
alter table message add constraint uq_message_client_token unique (chat_id, user_id, client_token);

create index ix_chat_member_user   on chat_member (user_id) where not is_deleted;
create index ix_friendship_init    on friendship (initiator_id) where is_accepted;
create index ix_friendship_accept  on friendship (acceptor_id)  where is_accepted;

-- ---------------------------------------------------------------------------
-- 2. message.edited_at в V1 был NOT NULL DEFAULT now() — тогда любое
--    новое сообщение выглядит "отредактированным" (клиент нарисует
--    "(изменено)" под каждым). Делаем nullable: null = не редактировалось.
-- ---------------------------------------------------------------------------
alter table message alter column edited_at drop not null;
alter table message alter column edited_at drop default;
update message set edited_at = null where edited_at = created_at;

-- ---------------------------------------------------------------------------
-- 3. Звонки (сигналинг WebRTC). Медиа идёт мимо сервера.
-- ---------------------------------------------------------------------------
create table call
(
    id         uuid primary key,                          -- генерирует клиент (call.invite.callId)
    chat_id    uuid        not null references chat (id),
    kind       text        not null check (kind in ('audio', 'video')),
    started_by uuid        not null references users (id),
    status     text        not null default 'ringing' check (status in ('ringing', 'active', 'ended')),
    started_at timestamptz not null default now(),
    ended_at   timestamptz,
    end_reason text
);
-- в одном чате одновременно не больше одного живого звонка
create unique index uq_call_live_per_chat on call (chat_id) where status <> 'ended';

create table call_participant
(
    call_id   uuid not null references call (id) on delete cascade,
    user_id   uuid not null references users (id),
    state     text not null default 'invited'
        check (state in ('invited', 'accepted', 'declined', 'missed', 'left')),
    joined_at timestamptz,
    left_at   timestamptz,
    primary key (call_id, user_id)
);

-- offer/answer/ICE: складываем строкой, по NOTIFY шлём только указатель
-- (NOTIFY режет пейлоад на 8000 байт, SDP бывает больше). Чистится джобой.
create table call_signal
(
    id           bigserial primary key,
    call_id      uuid        not null references call (id) on delete cascade,
    chat_id      uuid        not null,
    from_user_id uuid        not null,
    to_user_id   uuid,
    kind         text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null default now()
);
create index ix_call_signal_created on call_signal (created_at);
