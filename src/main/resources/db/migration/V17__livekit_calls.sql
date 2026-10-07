-- V17: звонки через LiveKit (SFU) — личные и групповые.
-- Медиа больше не идёт напрямую между браузерами (P2P + наш сигналинг):
-- каждый звонок — комната LiveKit, бэкенд выдаёт токены и следит за состоянием
-- по вебхукам LiveKit и сверке раз в минуту.

-- комната LiveKit звонка: call-<callId>
alter table call add column room_name text;
-- true — участникам чата «звонили» (личка и маленькие группы); false — «открытый» звонок
-- в большой группе/обсуждении: висит плашка «идёт звонок», заходит кто хочет
alter table call add column ring boolean not null default true;

-- connected — человек сейчас в комнате LiveKit (по вебхукам participant_joined/left)
alter table call_participant add column connected boolean not null default false;
alter table call_participant add column connected_at timestamptz;

-- kicked — выгнали из звонка: повторный вход с тем же токеном LiveKit сервер отобьёт
alter table call_participant drop constraint if exists call_participant_state_check;
alter table call_participant add constraint call_participant_state_check
    check (state in ('invited', 'accepted', 'declined', 'missed', 'left', 'kicked'));

update call set room_name = 'call-' || id where room_name is null;
create unique index uq_call_room on call (room_name);
create index ix_call_live on call (started_at) where status <> 'ended';
-- «где я сейчас в звонке»
create index ix_call_participant_user on call_participant (user_id) where state = 'accepted';
