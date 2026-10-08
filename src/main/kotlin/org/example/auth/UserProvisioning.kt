package org.example.auth

import io.quarkus.logging.Log
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.AppUser
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class UserProvisioning(
    private val em: EntityManager,
    /**
     * «Первые диктаторы»: username или uuid через запятую (env STRAYCATZ_DICTATORS).
     * Так назначается самый первый диктатор на чистой установке — дальше он сам назначает модераторов.
     */
    @ConfigProperty(name = "straycatz.moderation.dictators", defaultValue = " ")
    dictatorsRaw: String,
) {
    private val bootstrap: Set<String> = dictatorsRaw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** На старте: «первым диктаторам», которые уже есть в users, — запись в staff_grant. */
    @Transactional
    fun onStart(@Observes ev: StartupEvent) {
        if (bootstrap.isEmpty()) return
        bootstrap.forEach { key ->
            val n = em.createNativeQuery(
                """
                insert into staff_grant (user_id, role, note)
                select id, 'dictator', 'straycatz.moderation.dictators' from users
                where (lower(username) = ?1 or cast(id as text) = ?1) and not is_deleted
                on conflict (user_id) do update set role = 'dictator'
                """.trimIndent(),
            ).setParameter(1, key).executeUpdate()
            if (n > 0) Log.infof("модерация: %s — верховный диктатор (из настройки)", key)
            else Log.infof("модерация: %s станет диктатором при первом входе", key)
        }
    }

    @Transactional
    fun existing(userId: UUID, roles: Set<String> = emptySet()): AuthResult {
        val u = AppUser.findById(userId) ?: return AuthResult.Invalid
        if (u.isDeleted) return AuthResult.Blocked
        return checked(u, roles)
    }

    /** Бан → Banned; иначе билет с ролями и ограничением. Заодно кэшируем роль в users.staff_role. */
    private fun checked(u: AppUser, tokenRoles: Set<String>): AuthResult {
        val now = Instant.now()
        u.bannedUntil?.let { if (it.isAfter(now)) return AuthResult.Banned(it, u.banReason) }
        val roles = tokenRoles + grantedRoles(u)
        val ticket = AuthTicket(
            u.id, u.username, roles = roles,
            restrictedUntil = u.restrictedUntil?.takeIf { it.isAfter(now) },
            restrictReason = u.restrictReason,
        )
        if (u.staffRole != ticket.staffRole) u.staffRole = ticket.staffRole
        return AuthResult.Ok(ticket)
    }

    /**
     * Роли из нашей базы (staff_grant) и из настройки «первых диктаторов».
     * Итог: роль из токена Keycloak ∪ отсюда.
     */
    private fun grantedRoles(u: AppUser): Set<String> {
        if (u.username.lowercase() in bootstrap || u.id.toString() in bootstrap) {
            em.createNativeQuery(
                "insert into staff_grant (user_id, role, note) values (?1, 'dictator', 'straycatz.moderation.dictators') " +
                    "on conflict (user_id) do update set role = 'dictator' where staff_grant.role <> 'dictator'",
            ).setParameter(1, u.id).executeUpdate()
            return setOf(Staff.DICTATOR, Staff.MODERATOR)
        }
        val role = em.createNativeQuery("select role from staff_grant where user_id = ?1")
            .setParameter(1, u.id).resultList.firstOrNull() as String? ?: return emptySet()
        return if (role == Staff.DICTATOR) setOf(Staff.DICTATOR, Staff.MODERATOR) else setOf(Staff.MODERATOR)
    }

    /**
     * Пользователь из Keycloak -> строка в users (+ профильные строки).
     * Keycloak — источник правды про логин/пароль/email/username;
     * у нас — всё остальное (профиль, друзья, чаты).
     */
    @Transactional
    fun ensure(userId: UUID, username: String, roles: Set<String> = emptySet()): AuthResult {
        val u = AppUser.findById(userId)
        if (u != null) {
            if (u.isDeleted) return AuthResult.Blocked
            if (u.username != username) {
                if (AppUser.count("username = ?1 and id <> ?2", username, userId) == 0L) {
                    u.username = username
                    u.updatedAt = Instant.now()
                } else {
                    Log.warnf("username %s из Keycloak уже занят другим users.id, оставляем %s", username, u.username)
                }
            }
            return checked(u, roles)
        }

        if (AppUser.count("username", username) > 0) {
            Log.errorf("username %s уже есть в users с другим id, чем sub=%s в Keycloak", username, userId)
            return AuthResult.Invalid
        }
        createLocal(userId, username)
        Log.infof("создали users(%s, %s) по первому входу из Keycloak", userId, username)
        AppUser.findById(userId)?.let { if (roles.isNotEmpty()) return checked(it, roles) }
        return AuthResult.Ok(AuthTicket(userId, username, roles = roles))
    }

    /** users + user_cosmetics + user_level + пустая room. Вызывается и из регистрации, и при первом входе. */
    @Transactional
    fun createLocal(userId: UUID, username: String) {
        AppUser().also { it.id = userId; it.username = username }.persist()
        em.flush() // FK user_cosmetics -> users: строка users должна уйти в БД раньше
        em.createNativeQuery("insert into user_cosmetics (user_id) values (?1) on conflict do nothing")
            .setParameter(1, userId).executeUpdate()
        em.createNativeQuery("insert into user_level (user_id) values (?1) on conflict do nothing")
            .setParameter(1, userId).executeUpdate()
        // пустая комната сразу — чтобы /rooms/{username} существовала с первой секунды
        em.createNativeQuery("insert into room (owner_id, title) values (?1, ?2) on conflict do nothing")
            .setParameter(1, userId).setParameter(2, username).executeUpdate()
    }

    @Transactional
    fun usernameTaken(username: String): Boolean = AppUser.count("username", username) > 0
}