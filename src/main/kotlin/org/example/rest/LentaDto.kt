package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

// ================================================================ лента (/api/lenta)

/** Куда ведёт строка. type: post / video / stream / room / track / playlist / event / discussion / community / user */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class LentaTargetOut(
    val type: String,
    val id: UUID,
    /** slug сообщества, если дело было в сообществе. */
    val slug: String? = null,
    /** username хозяина комнаты / стены / человека. */
    val username: String? = null,
    /** для ответов: запись, под которой комментарий. */
    val postId: UUID? = null,
)

/** Трек «слушает сейчас»: играет до endsAt (как в музыке). */
data class LentaListeningOut(val startedAt: Instant, val endsAt: Instant)

/**
 * Одна строка ленты. Карточка рисуется по kind, а все действия в ней —
 * обычные ручки соответствующей сущности (запись, видео, трек, событие…).
 * Заполнено только поле, нужное для этого kind, остальные — null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class LentaItemOut(
    /** Стабильный ключ для {#each … (item.id)}: "<kind>:<id>[:<actor>]". */
    val id: String,
    /**
     * post / video / stream / room / guestbook / listening / track / community_track /
     * playlist / event / discussion / join / reply / friend
     */
    val kind: String,
    /** communities / video / rooms / music / friends / replies — для тумблеров. */
    val source: String,
    /** Когда это случилось (для «когда» слева и для курсора). */
    val at: Instant,
    /** Кто сделал. null — от имени сообщества (см. community) или автор удалён. */
    val actor: UserShortOut?,
    val community: PostCommunityOut?,
    /** Готовая фраза после имени: «написал(а) в «ночная смена»», «переклеил(а) обои в комнате». */
    val summary: String,
    /** Вторая строка мелким: «Artist — Title», «начало 3 окт, 20:00» и т.п. */
    val detail: String?,
    /** Предлагаемый адрес на фронте (можно пересобрать из target). */
    val link: String,
    val target: LentaTargetOut,
    /** Оттенок для обводки/заглушки: сообщества, иначе стабильный от человека. */
    val hue: Int,

    // ---- содержимое по kind ----
    /** post, reply: запись целиком — апвоут/лайк/комменты/поделиться/прочтение через /api/posts/{id}/… */
    val post: PostOut?,
    /** video: ролик (у записи с видео — ещё и post). */
    val video: VideoOut?,
    val stream: StreamOut?,
    /** listening, track, community_track: трек — играть audioUrl, «+ себе» PUT /api/music/library/{id}. */
    val track: TrackOut?,
    val listening: LentaListeningOut?,
    val playlist: PlaylistOut?,
    val event: EventOut?,
    val discussion: DiscussionOut?,
    /** guestbook: запись в гостевой; roomOwner — чья комната. */
    val guestbook: GuestbookEntryOut?,
    /** room, guestbook: хозяин комнаты. */
    val roomOwner: UserShortOut?,
    /** reply: ответ мне; ответить — POST /api/posts/{post.id}/comments {parentId: comment.id}. */
    val comment: CommentOut?,
)

/** Тумблер источника: ключ, подпись и сколько нового за 24 часа. */
data class LentaSourceOut(val key: String, val label: String, val today: Long)

data class LentaPageOut(
    val items: List<LentaItemOut>,
    val hasMore: Boolean,
    /** Следующая страница — ?before=<nextBefore>. */
    val nextBefore: Instant?,
    /** Все источники с числом нового за 24 часа (для «N новых за сегодня» и бейджей). */
    val sources: List<LentaSourceOut>,
    /** Сумма today по всем источникам. */
    val today: Long,
)
