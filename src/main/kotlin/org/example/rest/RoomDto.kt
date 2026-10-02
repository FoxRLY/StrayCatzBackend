package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import org.example.proto.PresenceOut
import java.time.Instant
import java.util.UUID

// ================================================================ media

data class MediaOut(val id: UUID, val url: String, val contentType: String, val sizeBytes: Long, val createdAt: Instant)

// ================================================================ rooms

/** Хозяин комнаты — шапка: аватар, «в сети с 2012», уровень. */
data class RoomOwnerOut(
    val id: UUID,
    val username: String,
    val avatar: String?,
    val color: String?,
    val tagline: String?,
    val level: Int,
    val xp: Int,
    /** Год регистрации строкой: "2023" (для «в сети с 2023»). */
    val memberSince: String,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WallImageOut(
    /** Готовый адрес: /api/media/{id} для загруженной или внешняя ссылка. */
    val url: String,
    val mediaId: UUID?,
    val fit: String,
    val veil: Double,
    val blur: Int,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class RoomLookOut(
    val title: String,
    val mood: String?,
    val about: String?,
    val sticker: String?,
    val theme: String,
    val wallpaper: String,
    val wallImage: WallImageOut?,
    /** То же, что wallImage.url, плоским полем — так ждёт фронт. null — картинки нет. */
    val wallImageUrl: String?,
    /** Вуаль/размытие/подгонка хранятся и без картинки — чтобы не терялись при её смене. */
    val wallFit: String,
    val wallVeil: Double,
    val wallBlur: Int,
    val tilt: Double,
    val dialect: String,
    val words: Map<String, String>,
    val blocks: List<String>,
)

data class RoomLinkOut(val id: UUID, val title: String, val url: String, val position: Int)

data class GuestbookEntryOut(
    val id: UUID,
    val author: UserShortOut,
    val body: String,
    val createdAt: Instant,
    /** Может ли смотрящий удалить (автор или хозяин комнаты). */
    val canDelete: Boolean,
    /** Картинки/гифки к записи. */
    val attachments: List<AttachmentOut> = emptyList(),
    val tracks: List<TrackOut> = emptyList(),
)

/** Контракт фронта: {items, total}; hasMore — сверху, для кнопки «ещё». */
data class GuestbookPageOut(val items: List<GuestbookEntryOut>, val total: Long, val hasMore: Boolean)

/**
 * Друг — плоский объект с `id`, как любой пользователь (UserShortOut) +
 * presence (null, если статус смотрящему не виден).
 */
data class FriendCardOut(
    val id: UUID,
    val username: String,
    val avatar: String?,
    val color: String?,
    val presence: PresenceOut?,
)

/** Контракт фронта: {items, total}. */
data class FriendsPageOut(val items: List<FriendCardOut>, val total: Long)

data class CommunityShortOut(
    val id: UUID,
    val slug: String,
    val name: String,
    val hue: Int,
    val color: String?,
    val avatar: String?,
    val memberCount: Long,
)

data class CommunitiesPageOut(val total: Long, val items: List<CommunityShortOut>)

/**
 * GET /api/rooms/{username}. Блоки, скрытые хозяином, приходят null
 * (кроме случая, когда смотрит сам хозяин — ему нужно всё для редактирования).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class RoomOut(
    val owner: RoomOwnerOut,
    val isOwner: Boolean,
    /** Отношение смотрящего к хозяину: none / outgoing / incoming / friends / self. */
    val friendship: String,
    /** null, если статус смотрящему не виден (не друг и нет общего чата). */
    val presence: PresenceOut?,
    val guestsToday: Long,
    val look: RoomLookOut,
    val links: List<RoomLinkOut>?,
    val friends: FriendsPageOut?,
    /** Контракт фронта: голый массив (первые 12), null — блок скрыт. */
    val communities: List<CommunityShortOut>?,
    val guestbook: GuestbookPageOut?,
)

/** PATCH /api/rooms/me — null/нет поля: не трогаем; "" в текстовых: очистить. */
data class RoomPatchIn(
    val title: String? = null,
    val mood: String? = null,
    val about: String? = null,
    val sticker: String? = null,
    val theme: String? = null,
    val wallpaper: String? = null,
    val wallFit: String? = null,
    val wallVeil: Double? = null,
    val wallBlur: Int? = null,
    val tilt: Double? = null,
    val dialect: String? = null,
)

/** PUT /api/rooms/me/wall-image — ровно одно из двух. */
data class WallImageIn(val mediaId: UUID? = null, val url: String? = null)

data class BlocksIn(val blocks: List<String>? = null)

data class WordsIn(val words: Map<String, String>? = null)

data class LinkIn(val title: String? = null, val url: String? = null)

data class LinkOrderIn(val ids: List<UUID>? = null)

/** body или mediaIds (до 4 картинок/гифок) — хотя бы одно. */
data class GuestbookIn(val body: String? = null, val mediaIds: List<UUID> = emptyList(), val trackIds: List<UUID> = emptyList())

data class VisitOut(val guestsToday: Long, val counted: Boolean)

data class InviteOut(val sent: Boolean, val reason: String? = null)

data class GuestOut(val user: UserShortOut, val lastAt: Instant)

// ================================================================ notifications

data class NotificationOut(
    val id: UUID,
    val kind: String,
    val actor: UserShortOut?,
    val payload: JsonNode,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class NotificationsPageOut(val items: List<NotificationOut>, val unread: Long, val hasMore: Boolean)
