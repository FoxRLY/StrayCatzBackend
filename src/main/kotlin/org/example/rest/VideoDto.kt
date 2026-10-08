package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

// ================================================================ видео

/**
 * Один ролик во вкладке «видео». id — это id файла (media), он же
 * используется во всех /api/video/{id}/…
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class VideoOut(
    val id: UUID,
    /** "/api/media/{id}" — в <video src={API + url}>. */
    val url: String,
    val contentType: String,
    val sizeBytes: Long,
    /** Подпись: своя (video_meta) → заголовок записи → первая строка текста записи. */
    val title: String?,
    /** Текст записи, к которой прикреплён ролик. */
    val description: String?,
    val durationSec: Int?,
    val posterUrl: String?,
    /** community / pulse / wall / upload (загружено прямо во вкладку «видео»). */
    val source: String,
    /** Запись, в которой ролик опубликован (null для upload). Лайки/комменты — через /api/posts/{postId}/… */
    val postId: UUID?,
    val author: UserShortOut?,
    val community: PostCommunityOut?,
    /** Опубликовано от имени сообщества: показывать community вместо author. */
    val asCommunity: Boolean,
    val createdAt: Instant,
    /** Уникальные просмотры. */
    val views: Long,
    val likes: Long,
    val comments: Long,
    val up: Long,
    /** Есть в «моих видео» (своё или добавленное себе). */
    val inMine: Boolean,
    /** Я загрузил файл → можно править подпись/обложку. */
    val mine: Boolean,
    /** Когда добавлено в «мои видео» (только в scope=mine). */
    val addedAt: Instant?,
    /** Теги ролика + теги записи, в которой он опубликован. */
    val tags: List<String> = emptyList(),
    /** Обложку сделал сервер (кадр из видео), а не автор. */
    val posterAuto: Boolean = false,
    /** Только в умной ленте (?algo=true): почему ролик показан. */
    val reason: RecommendReasonOut? = null,
)

data class VideoPageOut(
    val items: List<VideoOut>,
    val hasMore: Boolean,
    val scope: String,
    val sort: String,
    /** Для sort=new: следующая страница — ?before=<nextBefore>. */
    val nextBefore: Instant?,
    /** Для sort=popular: следующая страница — ?offset=<nextOffset>. */
    val nextOffset: Int?,
    /** Умная лента (?algo=true): следующая страница — ?algo=true&cursor=<nextCursor>. */
    val nextCursor: String? = null,
)

/** POST /api/video — загрузить ролик прямо в «мои видео» (без записи). */
data class VideoUploadIn(
    val mediaId: UUID? = null,
    val title: String? = null,
    val durationSec: Int? = null,
    val posterMediaId: UUID? = null,
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

/** PATCH /api/video/{id}: не передано — не меняем; title "" — убрать подпись. */
data class VideoPatchIn(
    val title: String? = null,
    val durationSec: Int? = null,
    val posterMediaId: UUID? = null,
    val clearPoster: Boolean = false,
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

data class VideoViewOut(val views: Long)
