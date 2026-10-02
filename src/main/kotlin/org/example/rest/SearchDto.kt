package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

// ================================================================ глобальный поиск (/api/search)

/**
 * Одна строка выпадашки поиска в шапке — одинаковая для всех видов,
 * чтобы рисовать одним компонентом. Полные карточки — в группах ответа.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class SearchHitOut(
    /** user / room / community / track / tag / post / video / stream / event */
    val type: String,
    /** id сущности (для тега — сам тег). */
    val id: String,
    val title: String,
    /** Вторая строка: «комната: ночной двор», «Pegboard Nerds · 4:18», «128 участников»… */
    val subtitle: String?,
    /** Картинка (аватар, обложка, превью эфира) или null — рисовать по hue. */
    val image: String?,
    val hue: Int,
    /** Куда вести по Enter/клику. */
    val link: String,
    /** Подпись бейджа: «в эфире», «друг», «онлайн»… */
    val badge: String?,
)

data class SearchUserOut(
    val user: UserShortOut,
    /** online / away / dnd / offline */
    val status: String,
    val friend: Boolean,
    val roomTitle: String?,
    val mood: String?,
)

data class SearchRoomOut(
    val owner: UserShortOut,
    val title: String,
    val mood: String?,
    val theme: String,
)

data class SearchCommunityOut(
    val id: UUID,
    val slug: String,
    val name: String,
    val hue: Int,
    val avatar: String?,
    val about: String?,
    val members: Long,
    val joined: Boolean,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class SearchOut(
    val q: String,
    /** Что искали: all — всё; или один вид, если ?type= (тогда — с пагинацией offset). */
    val type: String,
    /** Смешанный топ для выпадашки (до ~8): точные совпадения и самое живое сверху. */
    val top: List<SearchHitOut>,
    val users: List<SearchUserOut>,
    val rooms: List<SearchRoomOut>,
    val communities: List<SearchCommunityOut>,
    val tracks: List<TrackOut>,
    val tags: List<TagCountOut>,
    val posts: List<PostOut>,
    val videos: List<VideoOut>,
    val streams: List<StreamOut>,
    val events: List<EventOut>,
    /** При ?type= — есть ли ещё (следующая страница: offset += limit). */
    val hasMore: Boolean,
)

// ================================================================ бегущая строка (/api/ticker)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class TickerItemOut(
    val id: String,
    /** tag / stream / post / listening / event / online */
    val kind: String,
    /** global — горячее по всей сети; mine — из моего окружения. */
    val scope: String,
    /** Готовая строка для ленты. */
    val text: String,
    val link: String?,
    val hue: Int?,
    /** «горячесть» 0..1 — можно подсвечивать самые горячие. */
    val heat: Double,
)

data class TickerOut(
    val items: List<TickerItemOut>,
    val generatedAt: Instant,
    /** Через сколько секунд имеет смысл перечитать. */
    val refreshInSec: Int,
)
