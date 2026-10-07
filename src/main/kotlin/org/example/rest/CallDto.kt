package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/** POST /api/chats/{chatId}/call */
data class CallStartIn(
    /** audio / video — с чего начать (камеру можно включить и позже, если звонок video). */
    val kind: String? = null,
)

data class CallDeclineIn(val reason: String? = null)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CallParticipantOut(
    val user: UserShortOut?,
    /** invited — звоним; accepted — принял (получил токен); declined / missed / left / kicked */
    val state: String,
    /** Сейчас в комнате LiveKit (по вебхукам) — можно рисовать плитку «подключается…», пока false. */
    val connected: Boolean,
    val joinedAt: Instant?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class CallOut(
    val id: UUID,
    val chatId: UUID,
    /** audio — камеры нет вовсе; video — можно камеру. */
    val kind: String,
    /** ringing / active / ended */
    val status: String,
    /** true — участникам звонили; false — «открытый» звонок большой группы (плашка «идёт звонок»). */
    val ring: Boolean,
    val startedBy: UserShortOut?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val endReason: String?,
    /** Без declined/missed — только те, кто звонит, в звонке или вышел. */
    val participants: List<CallParticipantOut>,
    /** Сколько сейчас в комнате. */
    val connectedCount: Int,
    val maxParticipants: Int,
    /** Мне можно выгонять (начал звонок или создал беседу). */
    val canKick: Boolean,
)

/** Чем подключаться к LiveKit: `new Room().connect(url, token)`. */
data class LiveKitConnectOut(
    val url: String,
    val token: String,
    val room: String,
    /** Мой identity в комнате = мой id. */
    val identity: String,
)

/** Ответ на «начать» / «войти»: состояние звонка + доступ к комнате. */
data class CallJoinOut(val call: CallOut, val livekit: LiveKitConnectOut)
