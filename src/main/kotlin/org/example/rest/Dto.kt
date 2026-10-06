package org.example.rest

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonInclude
import org.example.auth.KcTokenResponse
import org.example.proto.MessageOut
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
    /** Год регистрации строкой ("2023") — для «в сети с …». */
    val memberSince: String,
    /** Настроение и название своей комнаты — чтобы шапка и профиль не ходили в /api/rooms. */
    val mood: String? = null,
    val roomTitle: String? = null,
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
    /** Год регистрации строкой ("2023") — для «в сети с …». */
    val memberSince: String,
)

/** Короткая карточка — для списков (участники, друзья, поиск). */
data class UserShortOut(val id: UUID, val username: String, val avatar: String?, val color: String?)

/** PUT /api/users/me/avatar (JSON-вариант): картинка из POST /api/media. */
data class AvatarIn(val mediaId: UUID? = null)

/** PATCH /api/users/me: поле не передано/null — не меняем, "" — очищаем. */
data class UpdateProfileIn(
    /**
     * Аватар — любое из:
     *  - ссылка https://… или /api/media/{id};
     *  - id картинки из POST /api/media (строкой);
     *  - data:image/…;base64,… — сервер сам сохранит картинку (до 5 МБ);
     *  - "" — убрать.
     */
    @JsonAlias("avatarUrl")
    val avatar: String? = null,
    /** То же, что avatar с id картинки. */
    val avatarMediaId: UUID? = null,
    val color: String? = null,
    val tagline: String? = null,
    /** Настроение комнаты («слушаю громко, отвечаю медленно»), до 140; "" — убрать. */
    val mood: String? = null,
    /** Название комнаты, 1–60. */
    @JsonAlias("title")
    val roomTitle: String? = null,
)

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
    /** Аватар: у группы — свой, у лички — собеседника. null — рисовать по имени. */
    val avatar: String? = null,
    /** В каких моих папках лежит чат. */
    val folderIds: List<UUID> = emptyList(),
)

data class ChatMemberOut(val user: UserShortOut, val lastReadSeq: Long, val joinedAt: Instant)

data class ChatDetailsOut(
    val chatId: UUID,
    val roomType: String,
    val name: String?,
    val createdAt: Instant,
    val lastSeq: Long,
    val members: List<ChatMemberOut>,
    val avatar: String? = null,
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
