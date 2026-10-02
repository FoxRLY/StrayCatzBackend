package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

// ================================================================ виджеты боковой колонки

/** Человек в виджете «онлайн». doing и listening видны только для друзей. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class OnlineUserOut(
    val user: UserShortOut,
    /** online / away / dnd / offline (invisible показывается как offline). */
    val status: String,
    /** «чем занят» — только у друзей. */
    val doing: String?,
    /** что играет прямо сейчас — только у друзей. */
    val listening: TrackOut?,
    val listeningEndsAt: Instant?,
    val friend: Boolean,
)

data class OnlineWidgetOut(
    /** friends — мои друзья (все, онлайн сверху); global — кто сейчас в сети вообще. */
    val scope: String,
    /** Сколько не offline (online + away + dnd). */
    val online: Long,
    /** friends: всего друзей; global: сколько людей в сети (то же, что online). */
    val total: Long,
    val byStatus: Map<String, Long>,
    val items: List<OnlineUserOut>,
)

/** Строка «что делают люди» — совместима с ActivityStream.svelte (who / what / when / hue). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ActivityItemOut(
    val id: String,
    val kind: String,
    val who: UserShortOut?,
    /** Готовая фраза после имени. */
    val what: String,
    val at: Instant,
    val hue: Int,
    val community: PostCommunityOut?,
    val link: String,
    val target: LentaTargetOut,
)

data class ActivityWidgetOut(val scope: String, val items: List<ActivityItemOut>)

/** Тег в «о чём говорят» — совместим с Trending.svelte (tag / where / heat). */
data class TrendingTagOut(
    val tag: String,
    /** Где больше всего обсуждают: «в «ночная смена»», «в пульсе», «в эфирах»… */
    val where: String,
    /** slug сообщества, если where — сообщество. */
    val whereSlug: String?,
    /** 0..1 относительно самого горячего тега в списке. */
    val heat: Double,
    /** Сырые очки активности за окно. */
    val score: Long,
    /** Сколько разных вещей с этим тегом были активны в окне. */
    val items: Long,
    val link: String,
)

data class TrendingWidgetOut(
    /** global — вся сеть; mine — мои сообщества, друзья и я. */
    val scope: String,
    /** Окно, за которое посчитано: 1h; если в последний час тихо — 24h или 7d. */
    val window: String,
    val items: List<TrendingTagOut>,
)
