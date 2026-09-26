package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.example.auth.AuthTicket
import org.example.domain.AppUser
import org.example.domain.UserCosmetics
import org.example.domain.UserLevel
import org.example.rest.*
import java.time.Instant
import java.util.*

/** Профили: users + user_cosmetics + user_level. */
@ApplicationScoped
class UserProfileService {

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
            u.id, u.username, ticket.email, c?.avatar, c?.color, c?.tagline,
            l?.xp ?: 0, l?.level ?: 0, u.createdAt,
        )
    }

    @Transactional
    fun profile(userId: UUID): UserProfileOut {
        val u = activeUser(userId)
        val c = UserCosmetics.findById(u.id)
        val l = UserLevel.findById(u.id)
        return UserProfileOut(u.id, u.username, c?.avatar, c?.color, c?.tagline, l?.xp ?: 0, l?.level ?: 0, u.createdAt)
    }

    @Transactional
    fun update(ticket: AuthTicket, patch: UpdateProfileIn): AccountOut {
        activeUser(ticket.userId)
        val c = UserCosmetics.findById(ticket.userId)
            ?: UserCosmetics().also { it.userId = ticket.userId; it.persist() }

        patch.avatar?.let {
            if (it.length > MAX_AVATAR) throw ApiException.badRequest("invalid_avatar", "avatar длиннее $MAX_AVATAR")
            c.avatar = it.ifBlank { null }
        }
        patch.color?.let {
            if (it.isNotBlank() && !COLOR_RE.matches(it)) {
                throw ApiException.badRequest("invalid_color", "color в формате #rrggbb")
            }
            c.color = it.ifBlank { null }
        }
        patch.tagline?.let {
            if (it.length > MAX_TAGLINE) throw ApiException.badRequest(
                "invalid_tagline",
                "tagline длиннее $MAX_TAGLINE"
            )
            c.tagline = it.ifBlank { null }
        }
        AppUser.findById(ticket.userId)?.updatedAt = Instant.now()
        return account(ticket)
    }

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
}
