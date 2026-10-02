-- Комната есть у каждого пользователя всегда (пустая, для кастомизации).
-- Новым её создаёт UserProvisioning.createLocal, здесь — тем, кто уже есть.
insert into room (owner_id, title)
select id, username from users
on conflict do nothing;
