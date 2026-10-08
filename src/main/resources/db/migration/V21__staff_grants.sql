-- V21: кто модератор и диктатор — теперь и в нашей базе, а не только в Keycloak.
-- Итоговая роль человека = роль из базы (staff_grant) ∪ realm-роль из токена Keycloak
-- ∪ список «первых диктаторов» из настройки straycatz.moderation.dictators (env STRAYCATZ_DICTATORS).
-- Модераторов назначает диктатор через API (PUT /api/dictator/moderators/{userId}) — пишется сюда,
-- действует сразу, без перевыпуска токена.

create table staff_grant
(
    user_id    uuid primary key references users (id),
    role       text        not null check (role in ('moderator', 'dictator')),
    granted_by uuid references users (id),         -- null — из настройки / сида / руками в SQL
    note       text,
    granted_at timestamptz not null default now()
);
create index ix_staff_grant_role on staff_grant (role);
