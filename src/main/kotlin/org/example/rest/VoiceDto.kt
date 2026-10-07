package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/** Кто в голосовом канале. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class VoicePeerOut(
    val user: UserShortOut?,
    /** talk — в голосе; watch — только смотрит стрим (без микрофона). */
    val mode: String,
    /** false — нажал «войти», подключается (или переподключается). */
    val connected: Boolean,
    /** Показывает экран — «в эфире» (🔴 у ника в списке). */
    val streaming: Boolean,
    /** Включил камеру. */
    val camera: Boolean,
    /** Модератор отключил ему микрофон. */
    val serverMuted: Boolean,
    val joinedAt: Instant,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class VoiceChannelOut(
    val id: UUID,
    val communityId: UUID,
    /** channel — вкладка «Голос»; event — голос события; discussion — голос обсуждения. */
    val kind: String,
    /** id события / чата обсуждения для event/discussion. */
    val refId: UUID?,
    val name: String,
    val position: Int,
    val maxTalkers: Int,
    /** Сначала говорящие, стримящие выше. */
    val peers: List<VoicePeerOut>,
    val talkers: Int,
    val watchers: Int,
    /** Кто-то показывает экран. */
    val live: Boolean,
)

/** GET /api/communities/{slug}/voice */
data class VoiceCommunityOut(
    val communityId: UUID,
    /** Постоянные каналы по порядку. */
    val channels: List<VoiceChannelOut>,
    /** Голос событий и обсуждений, где сейчас кто-то есть. */
    val active: List<VoiceChannelOut>,
    /** Я admin/owner — можно создавать, переименовывать, удалять, глушить, выгонять. */
    val canManage: Boolean,
    /** Где я сейчас (в любом сообществе) — id канала или null. */
    val myChannelId: UUID?,
)

data class VoiceChannelIn(val name: String? = null, val maxTalkers: Int? = null)

data class VoiceOrderIn(val channelIds: List<UUID> = emptyList())

/** mode: talk (по умолчанию) или watch — только смотреть стрим, без микрофона. */
data class VoiceJoinIn(val mode: String? = null)

data class VoiceMuteIn(val muted: Boolean = true)

data class VoiceJoinOut(val channel: VoiceChannelOut, val livekit: LiveKitConnectOut)
