-- Вложения (картинки/гифки/видео) у всего, что пишут люди: записи и пульс,
-- комментарии, сообщения в чатах, записи в гостевой. Одна таблица на всех
-- вместо post_media (V7) и одиночных колонок media_id.

create table media_attachment
(
    owner_type text not null check (owner_type in ('post', 'comment', 'message', 'guestbook')),
    owner_id   uuid not null,
    media_id   uuid not null references media (id),
    position   int  not null default 0,
    primary key (owner_type, owner_id, media_id)
);
create index ix_media_attachment_owner on media_attachment (owner_type, owner_id, position);

-- переносим то, что уже было прикреплено
insert into media_attachment (owner_type, owner_id, media_id, position)
select 'post', post_id, media_id, position from post_media;

insert into media_attachment (owner_type, owner_id, media_id, position)
select 'post', id, media_id, 0 from post where media_id is not null
on conflict do nothing;

insert into media_attachment (owner_type, owner_id, media_id, position)
select 'message', id, media_id, 0 from message where media_id is not null
on conflict do nothing;

drop table post_media;

-- запись в гостевой может быть только картинкой
alter table room_guestbook_entry alter column body set default '';
