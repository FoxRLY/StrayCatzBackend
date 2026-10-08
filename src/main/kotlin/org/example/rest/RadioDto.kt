package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/** PATCH /api/communities/{slug}/radio (admin). Не передано — не менять. */
data class RadioIn(
    val name: String? = null,
    /** "" — очистить. */
    val description: String? = null,
    /** Очередь пуста — подбирать треки из музыки сообщества. */
    val autoDj: Boolean? = null,
    /** Слушатели могут заказывать треки. */
    val requestsOpen: Boolean? = null,
)

/** POST /api/radio/{id}/start: название эфира (будет в «Реплеях»). */
data class RadioStartIn(val title: String? = null)

/**
 * POST /api/radio/{id}/queue. Диджей ставит в очередь (next: true — следующим),
 * слушатель — «заказывает» (если requestsOpen; не больше 2 своих заказов в очереди).
 */
data class RadioQueueIn(val trackId: UUID? = null, val next: Boolean? = null)

/** PUT /api/radio/{id}/queue/order: id элементов очереди в новом порядке (можно часть — остальные после). */
data class RadioOrderIn(val ids: List<UUID> = emptyList())

/** PATCH /api/radio/sessions/{id} (admin). */
data class RadioSessionPatchIn(val title: String? = null)

/** Что играет. Позиция: positionSec на момент serverTime; дальше — по своим часам. */
data class RadioNowOut(
    val playId: UUID,
    val track: TrackOut,
    val startedAt: Instant,
    val endsAt: Instant,
    /** Секунда трека на момент serverTime. */
    val positionSec: Double,
    val requestedBy: UserShortOut?,
    /** Подобрал автодиджей. */
    val auto: Boolean,
)

data class RadioQueueItemOut(
    val id: UUID,
    val track: TrackOut,
    val addedBy: UserShortOut?,
    /** Заказ слушателя (а не трек от диджея). */
    val requested: Boolean,
)

data class RadioMyOut(
    /** admin/owner сообщества: настройки, диджеи, удаление реплеев. */
    val canManage: Boolean,
    /** Может включать/выключать эфир, пропускать, менять очередь. */
    val isDj: Boolean,
    /** Можно заказать трек (requestsOpen или диджей). */
    val canRequest: Boolean,
    /** Сколько моих заказов сейчас в очереди (максимум 2). */
    val myRequests: Int,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RadioOut(
    val id: UUID,
    val communityId: UUID,
    val name: String,
    val description: String?,
    val autoDj: Boolean,
    val requestsOpen: Boolean,
    /** off / on */
    val status: String,
    /** Текущий эфир (null — выключено). */
    val session: RadioSessionOut?,
    /** null — эфир выключен или тишина (очередь пуста, автодиджей выключен). */
    val now: RadioNowOut?,
    /** Первые 50 в очереди. */
    val queue: List<RadioQueueItemOut>,
    val queueSize: Int,
    /** Слушают сейчас (пинг за 2 минуты). */
    val listeners: Int,
    /** До 12 аватарок слушателей. */
    val listenersSample: List<UserShortOut>,
    val djs: List<UserShortOut>,
    /** Часы сервера — для синхронизации позиции. */
    val serverTime: Instant,
    /** Только в REST; в кадре сокета radio.state — null. */
    val my: RadioMyOut?,
)

/** Эфир — карточка в «Реплеях». */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class RadioSessionOut(
    val id: UUID,
    val stationId: UUID,
    val communityId: UUID,
    val title: String,
    val startedBy: UserShortOut?,
    val startedAt: Instant,
    /** null — идёт сейчас. */
    val endedAt: Instant?,
    val live: Boolean,
    /** Длительность эфира (идущего — на сейчас). */
    val durationSec: Long,
    val trackCount: Int,
    val peakListeners: Int,
    /** До 4 обложек треков эфира (для коллажа). */
    val covers: List<String>,
)

data class RadioReplayPageOut(
    val items: List<RadioSessionOut>,
    val hasMore: Boolean,
    /** ?before=<next> */
    val next: Instant?,
)

/** Строка треклиста эфира. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class RadioPlayOut(
    val id: UUID,
    /** null — трек с тех пор удалён. */
    val track: TrackOut?,
    /** Секунда эфира, на которой трек начался («00:42:10»). */
    val offsetSec: Long,
    val startedAt: Instant,
    /** Сколько реально проиграл. */
    val playedSec: Long,
    /** Пропустили раньше конца. */
    val skipped: Boolean,
    val requestedBy: UserShortOut?,
    val auto: Boolean,
)

data class RadioSessionDetailOut(
    val session: RadioSessionOut,
    val tracks: List<RadioPlayOut>,
    val canManage: Boolean,
)
