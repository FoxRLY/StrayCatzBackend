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

-- у каждого — пустая комната (V5 выполняется раньше сида, поэтому и здесь)
insert into room (owner_id, title) select id, username from users on conflict do nothing;

-- комната alice заполнена целиком — чтобы на фронте были видны все блоки.
-- upsert, а не "do nothing": пустая строка к этому моменту уже создана выше.
insert into room (owner_id, title, mood, about, sticker, theme, wallpaper, wall_image_url, wall_fit,
                  wall_veil, wall_blur, tilt, dialect, words, blocks)
values ('11111111-1111-1111-1111-111111111111', 'двор', 'слушаю громко, отвечаю медленно',
        'Собираю комнаты, которые выглядят как комнаты, а не как анкеты. Нужен трек — просто спроси.',
        'заходи, не стесняйся', 'led', 'stars', null, 'tile', 0.40, 0, 1.00, 'yard',
        '{"join_community": "Влиться", "guestbook_title": "Стена", "guestbook_sign": "Черкнуть"}'::jsonb,
        '["about", "friends", "guestbook", "communities", "links"]'::jsonb)
on conflict (owner_id) do update set
                                     title = excluded.title, mood = excluded.mood, about = excluded.about, sticker = excluded.sticker,
                                     theme = excluded.theme, wallpaper = excluded.wallpaper, wall_fit = excluded.wall_fit,
                                     dialect = excluded.dialect, words = excluded.words, blocks = excluded.blocks;

-- уровень/опыт alice — целые числа, фронт делит xp на 100 сам
update user_level set level = 3, xp = 340 where user_id = '11111111-1111-1111-1111-111111111111';

-- carol тоже дружит с alice — у alice два друга в блоке
insert into friendship (initiator_id, acceptor_id, is_accepted, accepted_at)
select '33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', true, now()
where not exists (
    select 1 from friendship
    where least(initiator_id, acceptor_id) = '11111111-1111-1111-1111-111111111111'
      and greatest(initiator_id, acceptor_id) = '33333333-3333-3333-3333-333333333333');

insert into room_link (id, owner_id, title, url, position) values
                                                               ('bbbbbbbb-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'вебринг: моя страница', '/c/webring', 0),
                                                               ('bbbbbbbb-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'плейлист на ночь', 'https://example.com/playlist', 1)
on conflict do nothing;

insert into room_guestbook_entry (id, owner_id, author_id, body) values
                                                                     ('cccccccc-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
                                                                      '22222222-2222-2222-2222-222222222222', 'комната огонь, откуда шрифт'),
                                                                     ('cccccccc-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111',
                                                                      '33333333-3333-3333-3333-333333333333', 'зашла, красиво тут')
on conflict do nothing;

-- ============================================================ сообщества (V4 + V6)
-- Шесть сообществ из фронтового data.ts. created_at задаёт год «с 2013».
-- upsert: сид повторяемый, а часть строк могла появиться в прошлых версиях сида.
insert into community (id, name, slug, description, color, hue, owner_id, created_at, rules_text, dialect, lexicon, sections) values
                                                                                                                                  ('dddddddd-0000-0000-0000-000000000001', 'ночная смена', 'night',
                                                                                                                                   'Кто не спит — сюда. Разговоры, музыка, обсуждения до утра.', '#7b61ff', 96,
                                                                                                                                   '11111111-1111-1111-1111-111111111111', '2013-06-01',
                                                                                                                                   E'Ночь — не повод срываться на людей\nСпойлеры прячем под кат\nРеклама только в «Событиях» и только своя',
                                                                                                                                   'dvor', '{"online": "не спят", "events": "сходки"}',
                                                                                                                                   '["posts", "discussions", "media", "events", "members"]'),
                                                                                                                                  ('dddddddd-0000-0000-0000-000000000002', 'вебринг', 'webring',
                                                                                                                                   'Личные сайты и комнаты. Кто что собрал руками.', '#2bb673', 168,
                                                                                                                                   '22222222-2222-2222-2222-222222222222', '2011-06-01',
                                                                                                                                   E'Своя работа обязательно\nСсылки без сокращателей',
                                                                                                                                   'forum', '{"join": "Войти в кольцо", "joined": "Ты в кольце", "members": "сайтов"}',
                                                                                                                                   '["posts", "rooms", "discussions", "wiki", "members"]'),
                                                                                                                                  ('dddddddd-0000-0000-0000-000000000003', 'низкополигональная ночь', 'lowpoly',
                                                                                                                                   'Рендеры, шейдеры, кто что накрутил за выходные.', null, 200,
                                                                                                                                   '33333333-3333-3333-3333-333333333333', '2016-06-01',
                                                                                                                                   E'Показываешь рендер — прикладывай сетку\nКритика по работе, не по автору',
                                                                                                                                   'default', '{"posts": "работы", "up": "Респект", "save": "утащить в папку"}',
                                                                                                                                   '["posts", "media", "guides", "discussions", "projects", "members"]'),
                                                                                                                                  ('dddddddd-0000-0000-0000-000000000004', 'ритм-клуб', 'rhythm',
                                                                                                                                   'Карты, реплеи, разбор попаданий. Своя таблица и турнир по выходным.', null, 330,
                                                                                                                                   '22222222-2222-2222-2222-222222222222', '2012-06-01',
                                                                                                                                   E'Читы — бан без разговоров\nЧужой реплей выкладываем с автором',
                                                                                                                                   'dvor', '{"join": "Влиться", "joined": "Свой в клубе", "members": "игроков", "up": "Зачёт"}',
                                                                                                                                   '["posts", "discussions", "replays", "events", "leaderboard", "members"]'),
                                                                                                                                  ('dddddddd-0000-0000-0000-000000000005', 'клавиатуры и прочий пластик', 'kb',
                                                                                                                                   'Свитчи, кейкапы, звуковые тесты, барахолка.', null, 26,
                                                                                                                                   '33333333-3333-3333-3333-333333333333', '2018-06-01',
                                                                                                                                   E'В барахолке фото своего товара, не из интернета\nЦена в первом сообщении',
                                                                                                                                   'forum', '{"posts": "темы", "save": "в закладки"}',
                                                                                                                                   '["posts", "media", "market", "guides", "members"]'),
                                                                                                                                  ('dddddddd-0000-0000-0000-000000000006', 'monstercat архив', 'archive',
                                                                                                                                   'Всё, что выходило с 2011 года, и бесконечные споры об этом.', null, 268,
                                                                                                                                   '33333333-3333-3333-3333-333333333333', '2014-06-01',
                                                                                                                                   E'В вики правки с источником\nНе переливать чужие рипы',
                                                                                                                                   'strict', '{"members": "слушателей", "online": "слушают"}',
                                                                                                                                   '["posts", "music", "discussions", "wiki", "members"]')
on conflict (id) do update set
                               name = excluded.name, slug = excluded.slug, description = excluded.description, hue = excluded.hue,
                               owner_id = excluded.owner_id, created_at = excluded.created_at, rules_text = excluded.rules_text,
                               dialect = excluded.dialect, lexicon = excluded.lexicon, sections = excluded.sections;

-- проект внутри «низкополигональной ночи»
insert into community (id, name, slug, description, hue, owner_id, kind, parent_id, sections, created_at) values
    ('dddddddd-0000-0000-0000-000000000011', 'город за вечер', 'lowpoly-city',
     'Собираем общий город из 800 полигонов, по кварталу на человека.', 200,
     '33333333-3333-3333-3333-333333333333', 'project', 'dddddddd-0000-0000-0000-000000000003',
     '["posts", "discussions", "events"]', '2025-06-01')
on conflict (id) do nothing;

-- участники: alice состоит в night (owner), rhythm, webring; следит за lowpoly и archive
insert into community_member (community_id, user_id, role) values
                                                               ('dddddddd-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'owner'),
                                                               ('dddddddd-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'member'),
                                                               ('dddddddd-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', 'admin'),
                                                               ('dddddddd-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'owner'),
                                                               ('dddddddd-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'member'),
                                                               ('dddddddd-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333', 'owner'),
                                                               ('dddddddd-0000-0000-0000-000000000004', '22222222-2222-2222-2222-222222222222', 'owner'),
                                                               ('dddddddd-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'member'),
                                                               ('dddddddd-0000-0000-0000-000000000005', '33333333-3333-3333-3333-333333333333', 'owner'),
                                                               ('dddddddd-0000-0000-0000-000000000006', '33333333-3333-3333-3333-333333333333', 'owner'),
                                                               ('dddddddd-0000-0000-0000-000000000011', '33333333-3333-3333-3333-333333333333', 'owner')
on conflict do nothing;

insert into community_follow (community_id, user_id) values
                                                         ('dddddddd-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111'),
                                                         ('dddddddd-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111'),
                                                         ('dddddddd-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333')
on conflict do nothing;

-- записи (p1..p6 из data.ts)
insert into post (id, creator_id, community_id, title, body, kind, meta, created_at, updated_at) values
                                                                                                     ('eeeeeeee-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', 'dddddddd-0000-0000-0000-000000000003',
                                                                                                      'собрала сцену целиком на одном шейдере',
                                                                                                      'Никаких текстур, всё считается в рантайме. Падает до 40 fps на встройке, но выглядит так, как я хотела в 2013 и не умела.',
                                                                                                      'image', null, now() - interval '14 minutes', now() - interval '14 minutes'),
                                                                                                     ('eeeeeeee-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333', 'dddddddd-0000-0000-0000-000000000006',
                                                                                                      'переслушала Pink Cloud подряд, десять лет спустя',
                                                                                                      'Держится лучше половины того, что выходит сейчас. Трек прикрепила — можно включить прямо отсюда.',
                                                                                                      'track', '4:18', now() - interval '40 minutes', now() - interval '40 minutes'),
                                                                                                     ('eeeeeeee-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222', 'dddddddd-0000-0000-0000-000000000004',
                                                                                                      'почему на 174 все ломаются на втором куплете',
                                                                                                      'Дело не в скорости, а в том, что паттерн меняет руку ровно там, где ты уже устал. Показываю на замедленном реплее.',
                                                                                                      'video', '11:04', now() - interval '1 hour', now() - interval '1 hour'),
                                                                                                     ('eeeeeeee-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333', 'dddddddd-0000-0000-0000-000000000005',
                                                                                                      'шесть свитчей, один микрофон, никакого поролона',
                                                                                                      'Записал в одинаковых условиях, чтобы наконец закрыть вечный спор. Файлы в комментариях.',
                                                                                                      'video', '8:37', now() - interval '2 hours', now() - interval '2 hours'),
                                                                                                     ('eeeeeeee-0000-0000-0000-000000000005', '22222222-2222-2222-2222-222222222222', 'dddddddd-0000-0000-0000-000000000002',
                                                                                                      'переписала комнату с нуля, третий раз за год',
                                                                                                      'Теперь там гостевая на всю стену и счётчик посещений. Счётчик показывает 1471, и половина из них я.',
                                                                                                      'text', null, now() - interval '3 hours', now() - interval '3 hours'),
                                                                                                     ('eeeeeeee-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', 'dddddddd-0000-0000-0000-000000000001',
                                                                                                      'кто не спит — вспоминаем, что играло в наушниках в 2014',
                                                                                                      'У меня плейлист на 400 треков, и я до сих пор помню, где качала каждый.',
                                                                                                      'text', null, now() - interval '4 hours', now() - interval '4 hours'),
                                                                                                     ('eeeeeeee-0000-0000-0000-000000000007', '33333333-3333-3333-3333-333333333333', 'dddddddd-0000-0000-0000-000000000003',
                                                                                                      'гайд: запекаем свет в вершинные цвета',
                                                                                                      E'Длинный разбор по шагам.\n\n1. Готовим сетку...\n2. Считаем AO...\n3. Пишем в vertex color...',
                                                                                                      'guide', null, now() - interval '2 days', now() - interval '2 days')
on conflict (id) do nothing;

insert into post_comment (id, post_id, author_id, parent_id, body, created_at) values
                                                                                   ('ffffffff-0000-0000-0000-000000000001', 'eeeeeeee-0000-0000-0000-000000000006', '22222222-2222-2222-2222-222222222222', null,
                                                                                    'Pegboard Nerds и половина монстеркэта, классика', now() - interval '3 hours'),
                                                                                   ('ffffffff-0000-0000-0000-000000000002', 'eeeeeeee-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111',
                                                                                    'ffffffff-0000-0000-0000-000000000001', 'вот-вот, Disconnected до дыр', now() - interval '2 hours'),
                                                                                   ('ffffffff-0000-0000-0000-000000000003', 'eeeeeeee-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', null,
                                                                                    'на замедлении наконец понятно, спасибо', now() - interval '50 minutes')
on conflict do nothing;

insert into post_like (post_id, user_id) values
                                             ('eeeeeeee-0000-0000-0000-000000000006', '22222222-2222-2222-2222-222222222222'),
                                             ('eeeeeeee-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111'),
                                             ('eeeeeeee-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111')
on conflict do nothing;

insert into post_upvote (post_id, user_id, created_at) values
                                                           ('eeeeeeee-0000-0000-0000-000000000006', '22222222-2222-2222-2222-222222222222', now() - interval '3 days'),
                                                           ('eeeeeeee-0000-0000-0000-000000000006', '33333333-3333-3333-3333-333333333333', now() - interval '3 days'),
                                                           ('eeeeeeee-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333', now() - interval '3 days')
on conflict do nothing;

-- обсуждения «ночной смены» — чаты сообщества
insert into chat (id, name, room_type, community_id, pinned, created_by) values
                                                                             ('aaaaaaaa-0000-0000-0000-000000000010', 'правила: обсуждаем третий пункт', 'community',
                                                                              'dddddddd-0000-0000-0000-000000000001', true, '11111111-1111-1111-1111-111111111111'),
                                                                             ('aaaaaaaa-0000-0000-0000-000000000011', 'как вы вообще дожили до утра вчера', 'community',
                                                                              'dddddddd-0000-0000-0000-000000000001', false, '22222222-2222-2222-2222-222222222222')
on conflict do nothing;
insert into chat_member (chat_id, user_id) values
                                               ('aaaaaaaa-0000-0000-0000-000000000010', '11111111-1111-1111-1111-111111111111'),
                                               ('aaaaaaaa-0000-0000-0000-000000000011', '22222222-2222-2222-2222-222222222222')
on conflict do nothing;

-- события «ночной смены»; bob уже записался на первое
insert into community_event (id, community_id, title, description, starts_at, created_by) values
                                                                                              ('abababab-0000-0000-0000-000000000001', 'dddddddd-0000-0000-0000-000000000001',
                                                                                               'общее прослушивание: Pink Cloud целиком', 'Встречаемся в обсуждении, включаем одновременно.',
                                                                                               date_trunc('day', now()) + interval '1 day 20 hours', '11111111-1111-1111-1111-111111111111'),
                                                                                              ('abababab-0000-0000-0000-000000000002', 'dddddddd-0000-0000-0000-000000000001',
                                                                                               'разбор чужих комнат, приносите свои', null,
                                                                                               date_trunc('day', now()) + interval '7 days 19 hours', '33333333-3333-3333-3333-333333333333')
on conflict do nothing;
insert into event_registration (event_id, user_id) values
    ('abababab-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222')
on conflict do nothing;

-- вики «вебринга»
insert into wiki_page (id, community_id, slug, title, body, created_by, updated_by) values
    ('acacacac-0000-0000-0000-000000000001', 'dddddddd-0000-0000-0000-000000000002', 'kak-popast-v-kolco',
     'как попасть в кольцо', E'1. Собери комнату руками.\n2. Добавь ссылку «вебринг: моя страница».\n3. Напиши в обсуждение.',
     '22222222-2222-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222')
on conflict do nothing;

-- ============================================================ пульс (V7)
-- Пульс показывает последние 24 часа, поэтому время записей обновляется при
-- каждом перезапуске сида (он перезапускается, когда меняется этот файл).
insert into post (id, creator_id, community_id, body, kind, is_pulse, as_community, created_at, updated_at) values
                                                                                                                ('efefefef-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', null,
                                                                                                                 'кто тоже не спит и слушает Pegboard Nerds в три часа ночи — отзовитесь', 'text', true, false,
                                                                                                                 now() - interval '20 minutes', now() - interval '20 minutes'),
                                                                                                                ('efefefef-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', null,
                                                                                                                 'что лучше для первой механики?', 'poll', true, false,
                                                                                                                 now() - interval '2 hours', now() - interval '2 hours'),
                                                                                                                ('efefefef-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333', 'dddddddd-0000-0000-0000-000000000003',
                                                                                                                 'в эти выходные собираем город из 800 полигонов — присоединяйтесь', 'text', true, true,
                                                                                                                 now() - interval '5 hours', now() - interval '5 hours'),
                                                                                                                ('efefefef-0000-0000-0000-000000000004', '22222222-2222-2222-2222-222222222222', 'dddddddd-0000-0000-0000-000000000004',
                                                                                                                 'поставил личный рекорд на 174, пальцы до сих пор дрожат', 'text', true, false,
                                                                                                                 now() - interval '9 hours', now() - interval '9 hours')
on conflict (id) do update set created_at = excluded.created_at, updated_at = excluded.updated_at;

insert into poll (post_id, multiple, closes_at) values
    ('efefefef-0000-0000-0000-000000000002', false, now() + interval '22 hours')
on conflict (post_id) do update set closes_at = excluded.closes_at;
insert into poll_option (id, post_id, text, position) values
                                                          ('fafafafa-0000-0000-0000-000000000001', 'efefefef-0000-0000-0000-000000000002', 'красные свитчи', 0),
                                                          ('fafafafa-0000-0000-0000-000000000002', 'efefefef-0000-0000-0000-000000000002', 'коричневые', 1),
                                                          ('fafafafa-0000-0000-0000-000000000003', 'efefefef-0000-0000-0000-000000000002', 'сразу синие, пусть все слышат', 2)
on conflict do nothing;
insert into poll_vote (post_id, option_id, user_id) values
    ('efefefef-0000-0000-0000-000000000002', 'fafafafa-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333')
on conflict do nothing;

insert into post_comment (id, post_id, author_id, parent_id, body, created_at) values
                                                                                   ('ffffffff-0000-0000-0000-000000000011', 'efefefef-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', null,
                                                                                    'тут, Disconnected на повторе', now() - interval '15 minutes'),
                                                                                   ('ffffffff-0000-0000-0000-000000000012', 'efefefef-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333',
                                                                                    'ffffffff-0000-0000-0000-000000000011', 'классика жанра', now() - interval '10 minutes')
on conflict (id) do update set created_at = excluded.created_at;

insert into post_like (post_id, user_id) values
                                             ('efefefef-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222'),
                                             ('efefefef-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111')
on conflict do nothing;

-- ============================================================ музыка (V9)
-- Файлов под этими media в хранилище нет: списки, поиск, плейлисты и
-- «сейчас слушает» работают, а сам звук появится у треков, загруженных руками.
insert into media (id, owner_id, content_type, size_bytes, storage_key) values
                                                                            ('a0a0a0a0-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'audio/mpeg', 1, 'seed/disconnected.mp3'),
                                                                            ('a0a0a0a0-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'audio/mpeg', 1, 'seed/till-its-over.mp3'),
                                                                            ('a0a0a0a0-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222', 'audio/mpeg', 1, 'seed/cabin-fever.mp3'),
                                                                            ('a0a0a0a0-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333', 'audio/mpeg', 1, 'seed/valkyrie.mp3')
on conflict do nothing;

insert into track (id, uploader_id, audio_media_id, title, artist, album, duration_sec, bpm, tags) values
                                                                                                       ('b0b0b0b0-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'a0a0a0a0-0000-0000-0000-000000000001',
                                                                                                        'Disconnected', 'Pegboard Nerds', 'Pink Cloud', 258, 174, '["drum and bass", "monstercat"]'),
                                                                                                       ('b0b0b0b0-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'a0a0a0a0-0000-0000-0000-000000000002',
                                                                                                        'Till It''s Over', 'Tristam', null, 222, 150, '["glitch hop"]'),
                                                                                                       ('b0b0b0b0-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222', 'a0a0a0a0-0000-0000-0000-000000000003',
                                                                                                        'Cabin Fever', 'Stephen Walking', null, 190, 128, '["chiptune"]'),
                                                                                                       ('b0b0b0b0-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333', 'a0a0a0a0-0000-0000-0000-000000000004',
                                                                                                        'Valkyrie', 'Varien', null, 245, 140, '[]')
on conflict do nothing;

insert into user_track (user_id, track_id) values
                                               ('11111111-1111-1111-1111-111111111111', 'b0b0b0b0-0000-0000-0000-000000000001'),
                                               ('11111111-1111-1111-1111-111111111111', 'b0b0b0b0-0000-0000-0000-000000000002'),
                                               ('11111111-1111-1111-1111-111111111111', 'b0b0b0b0-0000-0000-0000-000000000004'),
                                               ('22222222-2222-2222-2222-222222222222', 'b0b0b0b0-0000-0000-0000-000000000003'),
                                               ('22222222-2222-2222-2222-222222222222', 'b0b0b0b0-0000-0000-0000-000000000001'),
                                               ('33333333-3333-3333-3333-333333333333', 'b0b0b0b0-0000-0000-0000-000000000004')
on conflict do nothing;

-- музыка «monstercat архива»
insert into community_track (community_id, track_id, added_by) values
                                                                   ('dddddddd-0000-0000-0000-000000000006', 'b0b0b0b0-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333'),
                                                                   ('dddddddd-0000-0000-0000-000000000006', 'b0b0b0b0-0000-0000-0000-000000000004', '33333333-3333-3333-3333-333333333333')
on conflict do nothing;

insert into playlist (id, owner_id, title, description) values
    ('c0c0c0c0-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'на ночь', 'чтобы не уснуть до трёх')
on conflict do nothing;
insert into playlist_track (playlist_id, track_id, position) values
                                                                 ('c0c0c0c0-0000-0000-0000-000000000001', 'b0b0b0b0-0000-0000-0000-000000000001', 0),
                                                                 ('c0c0c0c0-0000-0000-0000-000000000001', 'b0b0b0b0-0000-0000-0000-000000000004', 1)
on conflict do nothing;

-- bob «слушает» Disconnected (актуально первые 4 минуты после прогона сида)
insert into now_playing (user_id, track_id, started_at, ends_at) values
    ('22222222-2222-2222-2222-222222222222', 'b0b0b0b0-0000-0000-0000-000000000001', now(), now() + interval '258 seconds')
on conflict (user_id) do update set started_at = excluded.started_at, ends_at = excluded.ends_at;

-- ============================================================ стена, видео, стримы (V10)
-- Файлов под видео тоже нет: списки, «мои видео», пересылка работают, а
-- плеер заиграет у роликов, загруженных руками.
insert into media (id, owner_id, content_type, size_bytes, storage_key) values
                                                                            ('a1a1a1a1-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'video/mp4', 1, 'seed/174-replay.mp4'),
                                                                            ('a1a1a1a1-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'video/mp4', 1, 'seed/night-walk.mp4'),
                                                                            ('a1a1a1a1-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333', 'video/webm', 1, 'seed/shader-city.webm'),
                                                                            ('a1a1a1a1-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', 'video/mp4', 1, 'seed/cat-on-keyboard.mp4')
on conflict do nothing;

-- записи на стенах
insert into post (id, creator_id, wall_user_id, title, body, kind, created_at, updated_at) values
                                                                                               ('e1e1e1e1-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
                                                                                                null, 'прошлась ночью по району с камерой, вышло лучше, чем думала', 'video',
                                                                                                now() - interval '3 hours', now() - interval '3 hours'),
                                                                                               ('e1e1e1e1-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111',
                                                                                                'переехала', 'комната теперь в фонарной теме, заходите в гостевую', 'text',
                                                                                                now() - interval '2 days', now() - interval '2 days'),
                                                                                               ('e1e1e1e1-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222',
                                                                                                null, 'собрал клавиатуру на коричневых, всем спасибо за опрос', 'text',
                                                                                                now() - interval '1 hour', now() - interval '1 hour')
on conflict (id) do update set created_at = excluded.created_at, updated_at = excluded.updated_at;
update post set pinned = true where id = 'e1e1e1e1-0000-0000-0000-000000000002';

-- видео во вложениях: запись сообщества (p3 «почему на 174…»), стена alice, пульс carol
insert into post (id, creator_id, community_id, body, kind, is_pulse, as_community, created_at, updated_at) values
    ('efefefef-0000-0000-0000-000000000005', '33333333-3333-3333-3333-333333333333', null,
     'город из одного шейдера, 40 секунд', 'video', true, false,
     now() - interval '4 hours', now() - interval '4 hours')
on conflict (id) do update set created_at = excluded.created_at, updated_at = excluded.updated_at;

insert into media_attachment (owner_type, owner_id, media_id, position) values
                                                                            ('post', 'eeeeeeee-0000-0000-0000-000000000003', 'a1a1a1a1-0000-0000-0000-000000000001', 0),
                                                                            ('post', 'e1e1e1e1-0000-0000-0000-000000000001', 'a1a1a1a1-0000-0000-0000-000000000002', 0),
                                                                            ('post', 'efefefef-0000-0000-0000-000000000005', 'a1a1a1a1-0000-0000-0000-000000000003', 0)
on conflict do nothing;

-- alice загрузила ролик прямо во вкладку «видео» и добавила себе ролик bob
insert into user_video (user_id, media_id, added_at) values
                                                         ('11111111-1111-1111-1111-111111111111', 'a1a1a1a1-0000-0000-0000-000000000004', now() - interval '6 hours'),
                                                         ('11111111-1111-1111-1111-111111111111', 'a1a1a1a1-0000-0000-0000-000000000001', now() - interval '30 minutes')
on conflict do nothing;
insert into video_meta (media_id, title, duration_sec) values
                                                           ('a1a1a1a1-0000-0000-0000-000000000001', '174 bpm: замедленный реплей', 664),
                                                           ('a1a1a1a1-0000-0000-0000-000000000002', null, 95),
                                                           ('a1a1a1a1-0000-0000-0000-000000000004', 'кот прошёлся по клавиатуре', 12)
on conflict (media_id) do nothing;
insert into video_view (media_id, user_id) values
                                               ('a1a1a1a1-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111'),
                                               ('a1a1a1a1-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333'),
                                               ('a1a1a1a1-0000-0000-0000-000000000003', '22222222-2222-2222-2222-222222222222')
on conflict do nothing;

-- стримы: личный канал alice с известным ключом (для OBS в dev):
--   сервер rtmp://localhost:1935/live, ключ alicelive?pass=devsecret
-- и канал «ночной смены» с прошедшим эфиром
insert into stream_channel (id, code, user_id, community_id, secret_hash, key_rotated_at, key_rotated_by) values
                                                                                                              ('5c5c5c5c-0000-0000-0000-000000000001', 'alicelive', '11111111-1111-1111-1111-111111111111', null,
                                                                                                               encode(sha256('devsecret'::bytea), 'hex'), now(), '11111111-1111-1111-1111-111111111111'),
                                                                                                              ('5c5c5c5c-0000-0000-0000-000000000002', 'nightshift', null, 'dddddddd-0000-0000-0000-000000000001',
                                                                                                               encode(sha256('nightsecret'::bytea), 'hex'), now(), '11111111-1111-1111-1111-111111111111')
on conflict do nothing;

insert into chat (id, name, room_type, created_by) values
                                                       ('5d5d5d5d-0000-0000-0000-000000000001', 'ночной эфир: слушаем архив', 'stream', '11111111-1111-1111-1111-111111111111'),
                                                       ('5d5d5d5d-0000-0000-0000-000000000002', 'рисую комнату вживую', 'stream', '11111111-1111-1111-1111-111111111111')
on conflict do nothing;
insert into chat_member (chat_id, user_id) values
                                               ('5d5d5d5d-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111'),
                                               ('5d5d5d5d-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222'),
                                               ('5d5d5d5d-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111')
on conflict do nothing;

insert into stream (id, channel_id, created_by, title, description, status, chat_id, created_at, started_at, ended_at, peak_viewers) values
                                                                                                                                         ('5e5e5e5e-0000-0000-0000-000000000001', '5c5c5c5c-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111',
                                                                                                                                          'ночной эфир: слушаем архив', 'крутим monstercat 2013 и болтаем', 'ended', '5d5d5d5d-0000-0000-0000-000000000001',
                                                                                                                                          now() - interval '1 day 2 hours', now() - interval '1 day 2 hours', now() - interval '1 day', 14),
                                                                                                                                         -- подготовленный эфир alice: запусти OBS с ключом выше — станет live
                                                                                                                                         ('5e5e5e5e-0000-0000-0000-000000000002', '5c5c5c5c-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
                                                                                                                                          'рисую комнату вживую', null, 'idle', '5d5d5d5d-0000-0000-0000-000000000002',
                                                                                                                                          now(), null, null, 0)
on conflict do nothing;

-- членство из сида — «давно», иначе в нексусе «что произошло» — сплошные «вступил(а) в …»
update community_member set created_at = now() - interval '40 days'
where user_id in ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333')
  and created_at > now() - interval '10 minutes';

-- ============================================================ лента (V11)
-- перестановки в комнатах друзей alice — для источника «комнаты»
insert into room_activity (id, owner_id, detail, created_at) values
                                                                 ('5f5f5f5f-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'переклеил(а) обои в комнате', now() - interval '25 minutes'),
                                                                 ('5f5f5f5f-0000-0000-0000-000000000002', '33333333-3333-3333-3333-333333333333', 'сменил(а) настроение: собираю город', now() - interval '2 hours')
on conflict (id) do update set created_at = excluded.created_at;
-- всё, что сид создал «сейчас» без явного времени, отодвигаем в прошлое —
-- иначе верх ленты после каждого перезапуска занят одинаковыми строками сида
update friendship set accepted_at = now() - interval '30 days'
where is_accepted and accepted_at > now() - interval '10 minutes'
  and initiator_id in ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333');
update community_event set created_at = now() - interval '3 days'
where created_at > now() - interval '10 minutes' and created_by in ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333');
update chat set created_at = now() - interval '2 days'
where room_type = 'community' and created_at > now() - interval '10 minutes';
update community_track set added_at = now() - interval '4 days' where added_at > now() - interval '10 minutes';
update track set created_at = now() - interval '5 days' where id::text like 'b0b0b0b0-%' and created_at > now() - interval '10 minutes';
update room_guestbook_entry set created_at = now() - interval '1 day' - (random() * interval '10 hours')
where created_at > now() - interval '10 minutes'
  and author_id in ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333');

-- ============================================================ теги, превью, шапки (V12)
-- хэштеги прямо в тексте нескольких записей (как их пишут люди) + связи tag_link,
-- которые сервер сделал бы сам при сохранении
update post set body = body || E'\n#lowpoly #шейдеры'
where id = 'eeeeeeee-0000-0000-0000-000000000001' and body not like '%#lowpoly%';
update post set body = body || E'\n#dnb #174'
where id = 'eeeeeeee-0000-0000-0000-000000000003' and body not like '%#dnb%';
update post set body = body || ' #monstercat #ночь'
where id = 'efefefef-0000-0000-0000-000000000001' and body not like '%#monstercat%';
update post set body = body || ' #механика'
where id = 'efefefef-0000-0000-0000-000000000002' and body not like '%#механика%';
update post set body = body || ' #lowpoly'
where id = 'efefefef-0000-0000-0000-000000000005' and body not like '%#lowpoly%';

insert into tag_link (tag, owner_type, owner_id, auto, created_at) values
                                                                       ('lowpoly', 'post', 'eeeeeeee-0000-0000-0000-000000000001', true, now() - interval '14 minutes'),
                                                                       ('шейдеры', 'post', 'eeeeeeee-0000-0000-0000-000000000001', true, now() - interval '14 minutes'),
                                                                       ('dnb', 'post', 'eeeeeeee-0000-0000-0000-000000000003', true, now() - interval '1 hour'),
                                                                       ('174', 'post', 'eeeeeeee-0000-0000-0000-000000000003', true, now() - interval '1 hour'),
                                                                       ('monstercat', 'post', 'efefefef-0000-0000-0000-000000000001', true, now() - interval '20 minutes'),
                                                                       ('ночь', 'post', 'efefefef-0000-0000-0000-000000000001', true, now() - interval '20 minutes'),
                                                                       ('механика', 'post', 'efefefef-0000-0000-0000-000000000002', true, now() - interval '2 hours'),
                                                                       ('lowpoly', 'post', 'efefefef-0000-0000-0000-000000000005', true, now() - interval '4 hours'),
                                                                       ('кот', 'video', 'a1a1a1a1-0000-0000-0000-000000000004', false, now() - interval '6 hours'),
                                                                       ('ночь', 'video', 'a1a1a1a1-0000-0000-0000-000000000002', false, now() - interval '3 hours'),
                                                                       ('рисую', 'stream', '5e5e5e5e-0000-0000-0000-000000000002', false, now()),
                                                                       ('monstercat', 'stream', '5e5e5e5e-0000-0000-0000-000000000001', false, now() - interval '1 day'),
                                                                       ('ночь', 'community', 'dddddddd-0000-0000-0000-000000000001', false, now() - interval '30 days'),
                                                                       ('monstercat', 'track', 'b0b0b0b0-0000-0000-0000-000000000001', false, now() - interval '5 days'),
                                                                       ('dnb', 'track', 'b0b0b0b0-0000-0000-0000-000000000001', false, now() - interval '5 days')
on conflict do nothing;
-- чтобы «о чём говорят» не был пустым сразу после запуска — пара свежих реакций
insert into post_like (post_id, user_id) values
                                             ('eeeeeeee-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111'),
                                             ('eeeeeeee-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222')
on conflict do nothing;

-- шапка «ночной смены» — внешняя картинка (свою можно загрузить PUT /api/communities/night/banner)
update community set banner_focus = 0.40 where id = 'dddddddd-0000-0000-0000-000000000001';

-- онлайн для виджетов: bob в сети, carol отошла
insert into user_presence (user_id, status, doing, updated_at) values
                                                                   ('22222222-2222-2222-2222-222222222222', 'online', 'собираю клавиатуру', now()),
                                                                   ('33333333-3333-3333-3333-333333333333', 'away', null, now())
on conflict (user_id) do update set status = excluded.status, doing = excluded.doing, updated_at = excluded.updated_at;

-- Модерация (V20/V21): alice — верховный диктатор, carol — модератор.
-- Роль из staff_grant действует и с dev-токеном (Dev <uuid>), и с настоящим JWT из Keycloak.
-- Себя сделать диктатором: straycatz.moderation.dictators=<твой username> (или env STRAYCATZ_DICTATORS).
insert into staff_grant (user_id, role, note) values
                                                  ('11111111-1111-1111-1111-111111111111', 'dictator', 'dev seed'),
                                                  ('33333333-3333-3333-3333-333333333333', 'moderator', 'dev seed')
on conflict (user_id) do update set role = excluded.role;
