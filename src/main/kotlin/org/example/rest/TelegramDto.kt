package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

// ================================================================ зеркала Telegram-каналов

/** POST /api/telegram/channels — ссылка на публичный канал: https://t.me/имя, t.me/s/имя, @имя. */
data class MirrorIn(val link: String? = null)

/** Зеркало канала: и в списке /api/telegram/channels, и в CommunityPageOut.mirror. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class MirrorOut(
    val slug: String,
    val name: String,
    val avatar: String?,
    val hue: Int,
    /** telegram */
    val source: String,
    val username: String,
    /** https://t.me/<username> — «открыть в Telegram». */
    val url: String,
    val subscribers: Int?,
    val paused: Boolean,
    val lastSyncedAt: Instant?,
    /** Последняя ошибка синхронизации (канал закрыли, t.me недоступен…). */
    val lastError: String?,
    val addedBy: UserShortOut?,
    /** Зеркало передано настоящему владельцу. */
    val claimed: Boolean,
    /** Сколько постов перенесено. */
    val posts: Long,
    /** Я добавил — могу ставить на паузу, обновлять, скрывать записи. */
    val canManage: Boolean,
)

// ================================================================ импорт наборов стикеров

/** POST /api/stickers/import/telegram — ссылка t.me/addstickers/ИМЯ или просто имя набора. */
data class StickerImportIn(val link: String? = null)

/** Ход импорта набора (стикеры качаются фоном пачками). */
data class StickerImportOut(
    /** pending / running / done / failed */
    val status: String,
    val done: Int,
    val total: Int,
    val error: String?,
)
