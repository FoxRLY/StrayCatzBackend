-- Под REST-слой: профиль, друзья, создание чатов.

-- 1:1 с users — нужен PK, чтобы делать upsert и маппить в JPA
alter table user_cosmetics add constraint pk_user_cosmetics primary key (user_id);
alter table user_level     add constraint pk_user_level     primary key (user_id);

-- Дружба: одна строка на пару, в каком бы направлении ни была заявка.
-- Самому себе заявку кинуть нельзя.
alter table friendship add constraint ck_friendship_not_self check (initiator_id <> acceptor_id);
create unique index uq_friendship_pair
    on friendship (least(initiator_id, acceptor_id), greatest(initiator_id, acceptor_id));

-- Личка между двумя людьми должна быть одна: ключ "<меньший uuid>:<больший uuid>".
-- Для групп null (null'ы в unique не конфликтуют).
alter table chat add column direct_key text;
create unique index uq_chat_direct_key on chat (direct_key) where not is_deleted;

-- поиск пользователей по префиксу username
create index ix_users_username_prefix on users (username text_pattern_ops) where not is_deleted;

-- заводим профильные строки тем, у кого их ещё нет (dev-сид, JIT-юзеры)
insert into user_cosmetics (user_id) select id from users on conflict do nothing;
insert into user_level (user_id)     select id from users on conflict do nothing;
