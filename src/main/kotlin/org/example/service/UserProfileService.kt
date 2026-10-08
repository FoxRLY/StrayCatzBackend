package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.auth.AuthTicket
import org.example.domain.AppUser
import org.example.domain.Room
import org.example.domain.UserCosmetics
import org.example.domain.UserLevel
import org.example.rest.AccountOut
import org.example.rest.ApiException
import org.example.rest.DecreeOut
import org.example.rest.SanctionOut
import org.example.auth.Staff
import org.example.rest.UpdateProfileIn
import org.example.rest.UserProfileOut
import org.example.rest.UserShortOut
import java.time.Instant
import java.util.UUID

/** Профили: users + user_cosmetics + user_level. */
@ApplicationScoped
class UserProfileService(
    private val media: MediaService,
    private val em: EntityManager,
) {

    companion object {
        private val COLOR_RE = Regex("^#[0-9a-fA-F]{6}$")
        const val MAX_TAGLINE = 140
        const val MAX_AVATAR = 1024
        const val MAX_SEARCH = 50
    }

    @Transactional
    fun account(ticket: AuthTicket): AccountOut {
        val u = activeUser(ticket.userId)
        val c = UserCosmetics.findById(u.id)
        val l = UserLevel.findById(u.id)
        return AccountOut(
            id = u.id,
            username = u.username,
            email = ticket.email,
            avatar = c?.avatar,
            color = c?.color,
            tagline = c?.tagline,
            xp = l?.xp ?: 0,
            level = l?.level ?: 0,
            createdAt = u.createdAt,
            // «в сети с 2012» — год регистрации на платформе (users.created_at)
            memberSince = year(u.createdAt),
            mood = Room.findById(u.id)?.mood,
            roomTitle = Room.findById(u.id)?.title,
            decree = decreesOf(listOf(u.id))[u.id],
            staff = ticket.staffRole,
            restriction = ticket.restrictedUntil?.takeIf { ticket.isRestricted }?.let {
                SanctionOut(it.takeIf { u2 -> u2 < Staff.FOREVER }, it >= Staff.FOREVER, ticket.restrictReason)
            },
        )
    }

    @Transactional
    fun profile(userId: UUID): UserProfileOut {
        val u = activeUser(userId)
        val c = UserCosmetics.findById(u.id)
        val l = UserLevel.findById(u.id)
        return UserProfileOut(
            id = u.id,
            username = u.username,
            avatar = c?.avatar,
            color = c?.color,
            tagline = c?.tagline,
            xp = l?.xp ?: 0,
            level = l?.level ?: 0,
            createdAt = u.createdAt,
            memberSince = year(u.createdAt),
            decree = decreesOf(listOf(u.id))[u.id],
            banned = isBanned(u),
        )
    }

    fun isBanned(u: AppUser): Boolean = u.bannedUntil?.isAfter(Instant.now()) == true

    /** Действующие указы диктатора пачкой. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun decreesOf(ids: Collection<UUID>): Map<UUID, DecreeOut> {
        if (ids.isEmpty()) return emptyMap()
        return (em.createNativeQuery(
            "select user_id, text, emoji, color, issued_at from user_decree where user_id in (?1) and revoked_at is null",
        ).setParameter(1, ids.distinct()).resultList as List<Array<Any?>>).associate {
            (it[0] as UUID) to DecreeOut(it[1] as String, it[2] as String?, it[3] as String?, toInstant(it[4]))
        }
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }

    @Transactional
    fun profileByUsername(username: String): UserProfileOut {
        val u = AppUser.find("username = ?1 and isDeleted = false", username.trim().lowercase()).firstResult()
            ?: throw ApiException.notFound("пользователь не найден")
        return profile(u.id)
    }

    @Transactional
    fun update(ticket: AuthTicket, patch: UpdateProfileIn): AccountOut {
        activeUser(ticket.userId)
        val c = UserCosmetics.findById(ticket.userId)
            ?: UserCosmetics().also { it.userId = ticket.userId; it.persist() }

        // аватар: id картинки, ссылка или data:-URL (раньше data:-URL длиннее 1024 молча отбивался
        // 400-й, и фронт видел, что поменялись цвет/подпись, а аватар — нет)
        patch.avatarMediaId?.let { c.avatar = avatarFromMedia(ticket.userId, it) }
        patch.avatar?.let { c.avatar = avatarValue(ticket.userId, it.trim()) }
        patch.color?.let {
            if (it.isNotBlank() && !COLOR_RE.matches(it)) {
                throw ApiException.badRequest("invalid_color", "color в формате #rrggbb")
            }
            c.color = it.ifBlank { null }
        }
        patch.tagline?.let {
            if (it.length > MAX_TAGLINE) throw ApiException.badRequest("invalid_tagline", "tagline длиннее $MAX_TAGLINE")
            c.tagline = it.ifBlank { null }
        }
        AppUser.findById(ticket.userId)?.updatedAt = Instant.now()
        return account(ticket)
    }

    /**
     * Аватар из загруженной картинки: в user_cosmetics.avatar кладётся её адрес
     * (/api/media/{id}) — фронт показывает avatar как и раньше, просто <img src>.
     */
    @Transactional
    fun setAvatar(ticket: AuthTicket, mediaId: UUID): AccountOut {
        activeUser(ticket.userId)
        val m = media.requireOwned(mediaId, ticket.userId)
        if (!m.contentType.startsWith("image/")) throw ApiException.badRequest("not_image", "аватар — картинка: png, jpeg, gif или webp")
        cosmetics(ticket.userId).avatar = media.url(m.id)
        AppUser.findById(ticket.userId)?.updatedAt = Instant.now()
        // в ленту друзей: «сменил(а) аватар» (источник «комнаты»)
        em.createNativeQuery("insert into room_activity (id, owner_id, detail) values (?1, ?2, 'сменил(а) аватар')")
            .setParameter(1, UUID.randomUUID()).setParameter(2, ticket.userId).executeUpdate()
        return account(ticket)
    }

    @Transactional
    fun clearAvatar(ticket: AuthTicket): AccountOut {
        activeUser(ticket.userId)
        cosmetics(ticket.userId).avatar = null
        AppUser.findById(ticket.userId)?.updatedAt = Instant.now()
        return account(ticket)
    }

    private fun avatarValue(userId: UUID, v: String): String? {
        if (v.isEmpty()) return null
        if (v.startsWith("data:", ignoreCase = true)) return avatarFromDataUrl(userId, v)
        runCatching { UUID.fromString(v) }.getOrNull()?.let { return avatarFromMedia(userId, it) }
        if (v.length > MAX_AVATAR) throw ApiException.badRequest("invalid_avatar", "ссылка на аватар длиннее $MAX_AVATAR")
        if (!v.startsWith("https://") && !v.startsWith("http://") && !v.startsWith("/api/media/")) {
            throw ApiException.badRequest("invalid_avatar", "avatar: ссылка http(s)://, /api/media/{id}, id картинки или data:image/…")
        }
        return v
    }

    private fun avatarFromMedia(userId: UUID, mediaId: UUID): String {
        val m = media.requireOwned(mediaId, userId)
        if (!m.contentType.startsWith("image/")) throw ApiException.badRequest("not_image", "аватар — картинка: png, jpeg, gif или webp")
        return media.url(m.id)
    }

    /** data:image/png;base64,… → сохраняем как обычную загрузку (только картинки, до 5 МБ). */
    private fun avatarFromDataUrl(userId: UUID, v: String): String {
        val comma = v.indexOf(',')
        if (comma < 0 || !v.substring(0, comma).contains(";base64", ignoreCase = true)) {
            throw ApiException.badRequest("invalid_avatar", "data:-URL должен быть base64")
        }
        val bytes = try {
            java.util.Base64.getMimeDecoder().decode(v.substring(comma + 1))
        } catch (e: IllegalArgumentException) {
            throw ApiException.badRequest("invalid_avatar", "битый base64 в data:-URL")
        }
        val tmp = java.nio.file.Files.createTempFile("avatar-", ".bin")
        try {
            java.nio.file.Files.write(tmp, bytes)
            val out = media.upload(userId, tmp, bytes.size.toLong(), onlyImages = true, maxOverride = 5L * 1024 * 1024)
            return out.url
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
    }

    private fun cosmetics(userId: UUID): UserCosmetics =
        UserCosmetics.findById(userId) ?: UserCosmetics().also { it.userId = userId; it.persist() }

    /** Поиск по началу username (регистр не важен — username'ы в нижнем регистре). */
    @Transactional
    fun search(query: String, limit: Int, exclude: UUID?): List<UserShortOut> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val like = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val users = AppUser.find("username like ?1 and isDeleted = false order by username", like)
            .range(0, limit.coerceIn(1, MAX_SEARCH) - 1)
            .list()
            .filter { it.id != exclude }
        return shorts(users.map { it.id }).let { m -> users.mapNotNull { m[it.id] } }
    }

    /** Короткие карточки пачкой — удалённых пропускаем. */
    @Transactional
    fun shorts(ids: Collection<UUID>): Map<UUID, UserShortOut> {
        if (ids.isEmpty()) return emptyMap()
        val list = ids.distinct()
        val users = AppUser.list("id in ?1 and isDeleted = false", list)
        val cosmetics = UserCosmetics.list("userId in ?1", list).associateBy { it.userId }
        return users.associate { u ->
            u.id to UserShortOut(u.id, u.username, cosmetics[u.id]?.avatar, cosmetics[u.id]?.color)
        }
    }

    /** Мягкое удаление. Keycloak-аккаунт блокирует AccountService. */
    @Transactional
    fun softDelete(userId: UUID) {
        val u = AppUser.findById(userId) ?: return
        u.isDeleted = true
        u.deletedAt = Instant.now()
        u.updatedAt = Instant.now()
    }

    private fun activeUser(id: UUID): AppUser {
        val u = AppUser.findById(id)
        if (u == null || u.isDeleted) throw ApiException.notFound("пользователь не найден")
        return u
    }

    private fun year(i: Instant) = i.atZone(java.time.ZoneOffset.UTC).year.toString()
}
