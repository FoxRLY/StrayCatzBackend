package org.example.auth

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.AppUser
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class UserProvisioning(private val em: EntityManager) {

    @Transactional
    fun existing(userId: UUID): AuthResult {
        val u = AppUser.findById(userId) ?: return AuthResult.Invalid
        if (u.isDeleted) return AuthResult.Blocked
        return AuthResult.Ok(AuthTicket(u.id, u.username))
    }

    /**
     * Пользователь из Keycloak -> строка в users (+ профильные строки).
     * Keycloak — источник правды про логин/пароль/email/username;
     * у нас — всё остальное (профиль, друзья, чаты).
     */
    @Transactional
    fun ensure(userId: UUID, username: String): AuthResult {
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
            return AuthResult.Ok(AuthTicket(u.id, u.username))
        }

        if (AppUser.count("username", username) > 0) {
            Log.errorf("username %s уже есть в users с другим id, чем sub=%s в Keycloak", username, userId)
            return AuthResult.Invalid
        }
        createLocal(userId, username)
        Log.infof("создали users(%s, %s) по первому входу из Keycloak", userId, username)
        return AuthResult.Ok(AuthTicket(userId, username))
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