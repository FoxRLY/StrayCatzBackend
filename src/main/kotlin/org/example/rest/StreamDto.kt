package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

// ================================================================ стримы

/** Чей эфир: человека (user) или сообщества (community). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class StreamOwnerOut(
    /** user / community */
    val type: String,
    val user: UserShortOut?,
    val community: PostCommunityOut?,
)

/** Адреса для плеера. Раздаются как есть, без перекодирования. */
data class PlaybackOut(
    /** HLS (LL-HLS): hls.js или нативно в Safari. Основной вариант. */
    val hls: String,
    /** WebRTC WHEP: задержка < 1 с, но звук только если OBS шлёт Opus (AAC браузер по WebRTC не играет). */
    val webrtc: String,
    /** Готовая страничка-плеер медиасервера (для iframe/отладки). */
    val page: String,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class StreamOut(
    val id: UUID,
    val title: String,
    val description: String?,
    /** idle — подготовлен, ждём OBS; live — идёт; ended — закончился. */
    val status: String,
    val owner: StreamOwnerOut,
    /** Кто создал эфир (для сообщества — какой admin). */
    val createdBy: UserShortOut?,
    /** Чат под видео: сначала POST /api/streams/{id}/chat, дальше обычный сокет. */
    val chatId: UUID,
    val createdAt: Instant,
    val startedAt: Instant?,
    val endedAt: Instant?,
    /** Смотрят сейчас (пинг за последние 60 секунд). */
    val viewers: Long,
    val peakViewers: Int,
    val playback: PlaybackOut,
    /** Могу править/завершить и смотреть настройки OBS. */
    val canManage: Boolean,
    /** Я уже в чате стрима. */
    val inChat: Boolean,
    /**
     * Что показывать в карточке: идёт — свежий кадр эфира (если есть), иначе обложка;
     * не идёт — обложка, иначе последний кадр. null — рисовать заглушку по hue.
     */
    val previewUrl: String? = null,
    /** Своя обложка эфира (её ставит стример). */
    val posterUrl: String? = null,
    /** Живой кадр (обновляется раз в ~20 с, в адресе ?t= — время кадра). */
    val thumbnailUrl: String? = null,
    val thumbnailAt: Instant? = null,
    val tags: List<String> = emptyList(),
)

data class StreamPageOut(val items: List<StreamOut>, val hasMore: Boolean)

/** POST /api/streams — подготовить эфир (название/описание); communitySlug — от имени сообщества (admin). */
data class StreamIn(
    val title: String? = null,
    val description: String? = null,
    val communitySlug: String? = null,
    /** Обложка эфира: картинка из POST /api/media. */
    val posterMediaId: UUID? = null,
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

data class StreamPatchIn(
    val title: String? = null,
    val description: String? = null,
    val posterMediaId: UUID? = null,
    val clearPoster: Boolean = false,
    /** Ручные теги: ["lowpoly", "ночь"]. #хэштеги из текста добавятся сами. null — не трогать. */
    val tags: List<String>? = null,
)

/**
 * Настройки для OBS: Настройки → Трансляция → Сервис «Настраиваемый…».
 * streamKey приходит только сразу после выпуска (POST …/key) — сервер хранит лишь хэш.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class IngestOut(
    /** Поле «Сервер» в OBS. */
    val server: String,
    /** Поле «Ключ потока» в OBS: "<channel>?pass=<secret>". null — ключ уже выдан раньше (или ещё не выпущен). */
    val streamKey: String?,
    /** Публичный код канала (часть адреса для зрителей). */
    val channel: String,
    val hasKey: Boolean,
    val keyRotatedAt: Instant?,
    val owner: StreamOwnerOut,
    /** Текущий неоконченный эфир канала (idle/live), если есть. */
    val current: StreamOut?,
    /** Рекомендации для OBS — чтобы раздавалось без перекодирования. */
    val obsHints: List<String>,
)

data class StreamViewersOut(val viewers: Long, val status: String)

/** Кадр сокета stream.state — участникам чата стрима. */
data class StreamStateOut(
    val streamId: UUID,
    val status: String,
    val viewers: Long,
    val startedAt: Instant?,
    val endedAt: Instant?,
)
