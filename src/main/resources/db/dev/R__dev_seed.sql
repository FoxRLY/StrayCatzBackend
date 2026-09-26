-- Тестовые данные ТОЛЬКО для %dev-профиля (flyway.locations в dev включает db/dev).
-- id пользователей совпадают с id в keycloak/straycatz-realm.json, так что
-- можно заходить и dev-токеном (dev.<uuid>), и настоящим JWT из Keycloak.
-- Повторяемая миграция: все вставки идемпотентны.

insert into users (id, username) values
    ('11111111-1111-1111-1111-111111111111', 'alice'),
    ('22222222-2222-2222-2222-222222222222', 'bob'),
    ('33333333-3333-3333-3333-333333333333', 'carol')
on conflict do nothing;

insert into chat (id, name, room_type) values
    ('aaaaaaaa-0000-0000-0000-000000000001', null,          'direct'),
    ('aaaaaaaa-0000-0000-0000-000000000002', 'Бродячие коты', 'group')
on conflict do nothing;

insert into chat_member (chat_id, user_id) values
    ('aaaaaaaa-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111'),
    ('aaaaaaaa-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222'),
    ('aaaaaaaa-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111'),
    ('aaaaaaaa-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222'),
    ('aaaaaaaa-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333')
on conflict do nothing;

-- alice <-> bob дружат (presence ходит между друзьями), carol — нет
insert into friendship (initiator_id, acceptor_id, is_accepted, accepted_at)
select '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', true, now()
where not exists (
    select 1 from friendship
    where initiator_id = '11111111-1111-1111-1111-111111111111'
      and acceptor_id  = '22222222-2222-2222-2222-222222222222');

-- профильные строки (V3 выполняется раньше сида, поэтому дублируем здесь)
insert into user_cosmetics (user_id, color, tagline) values
    ('11111111-1111-1111-1111-111111111111', '#f5a623', 'рыжая и наглая'),
    ('22222222-2222-2222-2222-222222222222', '#4a90e2', null),
    ('33333333-3333-3333-3333-333333333333', null, null)
on conflict do nothing;
insert into user_level (user_id) select id from users on conflict do nothing;

-- личка alice<->bob имеет direct_key (колонка из V3)
update chat set direct_key = '11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222'
where id = 'aaaaaaaa-0000-0000-0000-000000000001' and direct_key is null;
