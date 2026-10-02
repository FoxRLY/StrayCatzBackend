package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

// ================================================================ гифки

/** Гифка или внешний стикер. id — наш (для message.send gifId). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class GifOut(
    val id: UUID,
    /** gif / sticker */
    val kind: String,
    val title: String?,
    /** Что показывать в сообщении: наша копия (/api/media/…) или, пока не скачана, адрес провайдера. */
    val url: String,
    /** Лёгкое превью для пикера. */
    val previewUrl: String,
    val width: Int?,
    val height: Int?,
    /** Файл уже лежит у нас. */
    val cached: Boolean,
    /** klipy / giphy */
    val provider: String,
)

data class GifPageOut(
    val items: List<GifOut>,
    val hasNext: Boolean,
    val page: Int,
    /** Откуда выдача: klipy / giphy / local (своя библиотека) / recent. */
    val source: String,
)

/** GET /api/gifs/status — диагностика провайдера. */
data class GifStatusOut(
    val provider: String,
    val configured: Boolean,
    val ok: Boolean,
    /** Сколько шёл запрос. */
    val millis: Long,
    /** Сколько гифок пришло в пробном trending. */
    val items: Int,
    /** Что не так: «сервер не ответил за 10 с», «HTTP 401 — …», «нет ключа…». */
    val error: String?,
)

// ================================================================ стикеры (наборы)

/**
 * format: static — картинка (webp/png); video — <video autoplay loop muted playsinline> (webm);
 * animated — Lottie в gzip (.tgs из Telegram): pako.ungzip + lottie-web.
 */
data class StickerOut(val id: UUID, val packId: UUID, val url: String, val emoji: String?, val format: String = "static")

@JsonInclude(JsonInclude.Include.ALWAYS)
data class StickerPackOut(
    val id: UUID,
    val title: String,
    val owner: UserShortOut?,
    val isPublic: Boolean,
    /** Сколько людей добавили себе. */
    val installs: Long,
    val count: Int,
    /** Первый стикер — обложка набора. */
    val cover: StickerOut?,
    /** Добавлен ко мне. */
    val installed: Boolean,
    /** Мой набор — можно править. */
    val mine: Boolean,
    /** Стикеры — в /mine и GET /{id}; в каталоге null. */
    val stickers: List<StickerOut>?,
    /** telegram — импортирован из Telegram; null — собран здесь. */
    val source: String? = null,
    /** https://t.me/addstickers/ИМЯ для импортированных. */
    val sourceUrl: String? = null,
    /** Идёт импорт: показать прогресс; null — не импортировался или уже готов давно. */
    val importing: StickerImportOut? = null,
)

data class StickerPackIn(val title: String? = null, val isPublic: Boolean? = null)

/** JSON-вариант добавления стикера: картинка уже загружена через POST /api/media. */
data class StickerIn(val mediaId: UUID? = null, val emoji: String? = null)

data class StickerOrderIn(val stickerIds: List<UUID> = emptyList())

// ================================================================ сообщения: ответ, пересылка, реакции

/** Превью сообщения, на которое ответили. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReplyPreviewOut(
    val id: UUID,
    val seq: Long,
    val user: UserShortOut?,
    /** Начало текста (до 120). */
    val text: String?,
    /** Если текста нет — что там: photo / video / gif / sticker / track / post. */
    val kind: String?,
    val deleted: Boolean,
)

/** «Переслано от …». */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ForwardOriginOut(
    /** Автор оригинала. */
    val user: UserShortOut?,
    /** Оригинал (может быть уже удалён или в чате, куда у меня нет доступа). */
    val messageId: UUID?,
    val chatId: UUID?,
    /** Когда был написан оригинал. */
    val at: Instant?,
)

data class ReactionOut(
    val emoji: String,
    val count: Long,
    /** Кто поставил (до 50) — по этому списку фронт понимает «моя ли». */
    val userIds: List<UUID>,
)

/** Ответ на PUT/DELETE реакции и кадр сокета message.reactions. */
data class MessageReactionsOut(val chatId: UUID, val messageId: UUID, val reactions: List<ReactionOut>)

/**
 * POST /api/chats/{chatId}/messages/forward — переслать сообщения этого чата.
 * Куда: toChatIds (где я состою) и/или toUserIds (в личку), всего до 10 адресатов.
 */
data class ForwardIn(
    val messageIds: List<UUID> = emptyList(),
    val toChatIds: List<UUID> = emptyList(),
    val toUserIds: List<UUID> = emptyList(),
    /** Подпись — уходит отдельным сообщением перед пересланными. */
    val comment: String? = null,
)

data class ForwardSentOut(val chatId: UUID, val userId: UUID?, val messageIds: List<UUID>, val lastSeq: Long)

data class ForwardResultOut(val sent: List<ForwardSentOut>)

// ================================================================ беседа

/** PATCH /api/chats/{id}: только для групп. */
data class ChatPatchIn(val name: String? = null)

data class ChatAvatarIn(val mediaId: UUID? = null)
