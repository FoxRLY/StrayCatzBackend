package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

// ================================================================ опыт

/** Уровень = xp / 100. Полоска: xpInLevel из perLevel. */
data class LevelOut(
    val level: Int,
    val xp: Int,
    /** Сколько набрано внутри текущего уровня (0..99). */
    val xpInLevel: Int,
    val perLevel: Int,
    /** При скольки xp будет следующий уровень. */
    val nextLevelAt: Int,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class XpEventOut(
    val id: UUID,
    val amount: Int,
    /** upvote_wall / upvote_community / badge */
    val reason: String,
    /** Запись, за которую дали (для upvote_*). */
    val refId: UUID?,
    /** Код значка (для badge). */
    val badge: String?,
    val at: Instant,
    /** Готовая подпись: «апвоут на запись на стене от @kot». */
    val text: String,
)

data class XpPageOut(val level: LevelOut, val items: List<XpEventOut>, val hasMore: Boolean)

// ================================================================ значки

@JsonInclude(JsonInclude.Include.ALWAYS)
data class BadgeProgressOut(
    /** Сколько есть (не больше goal). */
    val value: Long,
    val goal: Long,
    /** Единица для подписи: «ч» или null. */
    val unit: String?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class BadgeOut(
    val code: String,
    val title: String,
    val description: String,
    /** Эмодзи-иконка (фронт может подменить своей картинкой по code). */
    val icon: String,
    /** Сколько опыта даёт. */
    val xp: Int,
    val earned: Boolean,
    val earnedAt: Instant?,
    val progress: BadgeProgressOut,
)

/** GET /api/rooms/{username}/badges — «4 из 6». */
data class BadgesOut(
    val earned: Int,
    val total: Int,
    val level: LevelOut,
    /** Сначала полученные (свежие сверху), потом по близости к цели. */
    val items: List<BadgeOut>,
)

// ================================================================ «чем занят»

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DoingTargetOut(
    /** post / community / track / room / user / badge / market */
    val type: String,
    val id: UUID?,
    /** slug сообщества (post в сообществе, community). */
    val slug: String? = null,
    /** Чья комната / стена / с кем подружился. */
    val username: String? = null,
    /** Название (сообщество, трек, объявление) или код значка. */
    val title: String? = null,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DoingItemOut(
    /** post / comment / join / track / guestbook / room / friend / badge / market */
    val kind: String,
    val at: Instant,
    /** Готовая фраза без имени: «написал(а) в сообществе «Двор»». */
    val text: String,
    val icon: String,
    /** Начало текста записи/комментария, цена объявления, описание значка. */
    val preview: String?,
    /** Куда вести по клику (null — некуда). */
    val target: DoingTargetOut?,
)

data class DoingCommunityOut(val id: UUID, val slug: String?, val name: String, val avatar: String?, val actions: Long)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class DoingSummaryOut(
    val days: Int,
    /** Готовая строка: «за 30 дней: 12 записей, 40 комментариев, 300 треков, +85 опыта; чаще всего появляется ночью». */
    val text: String,
    val posts: Long,
    val comments: Long,
    val upvotesReceived: Long,
    val xpEarned: Long,
    /** Разных треков. */
    val tracksListened: Long,
    val communitiesJoined: Long,
    /** Записей в его гостевой от других. */
    val guestbookReceived: Long,
    val newFriends: Long,
    val badgesEarned: Long,
    /** В скольких днях из days что-то делал. */
    val activeDays: Int,
    /** night / morning / day / evening — когда чаще появляется; null — не из чего судить. */
    val partOfDay: String?,
    /** Где больше всего писал за период. */
    val topCommunity: DoingCommunityOut?,
    /** «Исполнитель — Трек», который чаще всего включал. */
    val onRepeat: String?,
    val level: LevelOut,
)

/** GET /api/rooms/{username}/doing. summary — только на первой странице (без before). */
data class DoingOut(val summary: DoingSummaryOut?, val items: List<DoingItemOut>, val hasMore: Boolean)
