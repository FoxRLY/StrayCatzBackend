package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import org.example.proto.MessageOut
import org.example.auth.KcTokenResponse
import java.time.Instant
import java.util.UUID

// Входные DTO — все поля nullable с дефолтами: валидируем сами и отдаём
// понятную ошибку, а не безликий 400 от Jackson на отсутствующее поле.

// ---------------------------------------------------------------- auth

data class RegisterIn(val username: String? = null, val email: String? = null, val password: String? = null)

/** login — username или email. */
data class LoginIn(val login: String? = null, val password: String? = null)

data class RefreshIn(val refreshToken: String? = null)

data class ChangePasswordIn(val currentPassword: String? = null, val newPassword: String? = null)

data class TokensOut(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val refreshExpiresIn: Long,
    val tokenType: String,
) {
    companion object {
        fun of(t: KcTokenResponse) = TokensOut(t.accessToken, t.refreshToken, t.expiresIn, t.refreshExpiresIn, t.tokenType)
    }
}

data class AuthOut(val user: AccountOut, val tokens: TokensOut)

// ---------------------------------------------------------------- users

/** Свой профиль: всё публичное + email. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class AccountOut(
    val id: UUID,
    val username: String,
    val email: String?,
    val avatar: String?,
    val color: String?,
    val tagline: String?,
    val xp: Int,
    val level: Int,
    val createdAt: Instant,
)

/** Чужой профиль по id. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class UserProfileOut(
    val id: UUID,
    val username: String,
    val avatar: String?,
    val color: String?,
    val tagline: String?,
    val xp: Int,
    val level: Int,
    val createdAt: Instant,
)

/** Короткая карточка — для списков (участники, друзья, поиск). */
data class UserShortOut(val id: UUID, val username: String, val avatar: String?, val color: String?)

/** PATCH /api/users/me: поле не передано/null — не меняем, "" — очищаем. */
data class UpdateProfileIn(val avatar: String? = null, val color: String? = null, val tagline: String? = null)

// ---------------------------------------------------------------- chats

data class ChatListItemOut(
    val chatId: UUID,
    val roomType: String,
    val name: String?,
    /** Для direct — собеседник, для групп null. */
    val peer: UserShortOut?,
    val lastSeq: Long,
    val lastReadSeq: Long,
    val unread: Long,
    val lastMessage: MessageOut?,
)

data class ChatMemberOut(val user: UserShortOut, val lastReadSeq: Long, val joinedAt: Instant)

data class ChatDetailsOut(
    val chatId: UUID,
    val roomType: String,
    val name: String?,
    val createdAt: Instant,
    val lastSeq: Long,
    val members: List<ChatMemberOut>,
)

/**
 * POST /api/chats
 *   {"type":"direct","userId":"..."}                 — личка (идемпотентно: вернёт существующую)
 *   {"type":"group","name":"...","memberIds":[...]}  — группа, создатель добавляется сам
 */
data class CreateChatIn(
    val type: String? = null,
    val userId: UUID? = null,
    val name: String? = null,
    val memberIds: List<UUID> = emptyList(),
)

data class AddMemberIn(val userId: UUID? = null)

/** Сообщения всегда по возрастанию seq. hasMore — есть ли ещё в запрошенную сторону. */
data class MessagePageOut(val messages: List<MessageOut>, val hasMore: Boolean)

// ---------------------------------------------------------------- friends

data class FriendsOut(
    val friends: List<UserShortOut>,
    /** Заявки мне — принять: PUT /api/friends/{id}, отклонить: DELETE. */
    val incoming: List<UserShortOut>,
    /** Мои заявки — отменить: DELETE /api/friends/{id}. */
    val outgoing: List<UserShortOut>,
)

/** none / outgoing / incoming / friends */
data class FriendStateOut(val userId: UUID, val state: String)
