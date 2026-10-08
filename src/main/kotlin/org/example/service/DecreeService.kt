package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.auth.AuthTicket
import org.example.auth.KeycloakClient
import org.example.auth.Staff
import org.example.rest.ApiException
import org.example.rest.DecreeAdminOut
import org.example.rest.DecreeIn
import org.example.rest.DecreeOut
import org.example.rest.StaffOut
import java.time.Instant
import java.util.UUID

/**
 * Верховный диктатор платформы (realm-роль dictator):
 *  - указ — текстовая плашка в профиле человека. Сам человек её не снимет, модераторы тоже
 *    (сброс профиля её не трогает); отозвать может только диктатор. Одна живая на человека,
 *    новый указ заменяет старый;
 *  - назначает и снимает модераторов (staff_grant, по желанию — ещё и роль в Keycloak)
 *    и, с подтверждением, других диктаторов.
 * Всё пишется в журнал модерации.
 */
@ApplicationScoped
class DecreeService(
    private val em: EntityManager,
    private val profiles: UserProfileService,
    private val notifications: NotificationService,
    private val moderation: ModerationService,
    private val keycloak: KeycloakClient,
    @ConfigProperty(name = "straycatz.moderation.moderator-role", defaultValue = "moderator") private val moderatorRole: String,
    /** Дублировать назначения модераторов realm-ролью в Keycloak (нужен view-realm у сервисного аккаунта). */
    @ConfigProperty(name = "straycatz.moderation.keycloak-sync", defaultValue = "false") private val keycloakSync: Boolean,
) {
    companion object {
        const val MAX_TEXT = 120
        const val MAX_EMOJI = 16
        private val COLOR_RE = Regex("^#[0-9a-fA-F]{6}$")
        const val N_DECREE_ISSUED = "decree_issued"
        const val N_DECREE_REVOKED = "decree_revoked"
    }

    @Transactional
    fun issue(me: AuthTicket, userId: UUID, req: DecreeIn): DecreeOut {
        requireUser(userId)
        val text = req.text?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_TEXT) throw ApiException.badRequest("invalid_text", "текст указа: 1–$MAX_TEXT символов")
        val emoji = req.emoji?.trim()?.takeIf { it.isNotEmpty() }
        if (emoji != null && emoji.length > MAX_EMOJI) throw ApiException.badRequest("invalid_emoji", "эмодзи: один символ")
        val color = req.color?.trim()?.takeIf { it.isNotEmpty() }
        if (color != null && !COLOR_RE.matches(color)) throw ApiException.badRequest("invalid_color", "color в формате #rrggbb")
        em.createNativeQuery("update user_decree set revoked_at = now(), revoked_by = ?2 where user_id = ?1 and revoked_at is null")
            .setParameter(1, userId).setParameter(2, me.userId).executeUpdate()
        em.createNativeQuery(
            "insert into user_decree (id, user_id, text, emoji, color, issued_by) values (?1, ?2, ?3, nullif(?4, ''), nullif(?5, ''), ?6)",
        ).setParameter(1, UUID.randomUUID()).setParameter(2, userId).setParameter(3, text)
            .setParameter(4, emoji ?: "").setParameter(5, color ?: "").setParameter(6, me.userId).executeUpdate()
        moderation.logStaff(me.userId, "decree_issue", userId, text)
        notifications.notify(userId, N_DECREE_ISSUED, null, mapOf("text" to text, "emoji" to emoji))
        return profiles.decreesOf(listOf(userId))[userId]!!
    }

    @Transactional
    fun revoke(me: AuthTicket, userId: UUID) {
        val n = em.createNativeQuery("update user_decree set revoked_at = now(), revoked_by = ?2 where user_id = ?1 and revoked_at is null")
            .setParameter(1, userId).setParameter(2, me.userId).executeUpdate()
        if (n == 0) throw ApiException.notFound("у человека нет указа")
        moderation.logStaff(me.userId, "decree_revoke", userId, null)
        notifications.notify(userId, N_DECREE_REVOKED, null, emptyMap())
    }

    /** Указы: действующие (по умолчанию) или вся история человека. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun list(userId: UUID?, limit: Int): List<DecreeAdminOut> {
        val q = em.createNativeQuery(
            "select id, user_id, text, emoji, color, issued_at, revoked_at from user_decree where " +
                (if (userId != null) "user_id = ?1" else "revoked_at is null") + " order by issued_at desc limit " + limit.coerceIn(1, 500),
        )
        if (userId != null) q.setParameter(1, userId)
        val rows = q.resultList as List<Array<Any?>>
        val users = profiles.shorts(rows.map { it[1] as UUID })
        return rows.map {
            DecreeAdminOut(
                it[0] as UUID, users[it[1] as UUID], DecreeOut(it[2] as String, it[3] as String?, it[4] as String?, toInstant(it[5])),
                it[6]?.let { v -> toInstant(v) },
            )
        }
    }

    // ---------------------------------------------------------------- модераторы

    /**
     * Модераторы и диктаторы: из staff_grant (назначены через API / сид / настройку) и те,
     * у кого роль пришла только из Keycloak (users.staff_role — кэш последнего токена).
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun staff(): List<StaffOut> {
        val rows = em.createNativeQuery(
            """
            select u.id, coalesce(g.role, u.staff_role), g.granted_by, g.granted_at, g.note,
                   case when g.user_id is null then 'keycloak' else 'grant' end
            from users u left join staff_grant g on g.user_id = u.id
            where (g.user_id is not null or u.staff_role is not null) and not u.is_deleted
            order by (coalesce(g.role, u.staff_role) = 'dictator') desc, u.username
            """.trimIndent(),
        ).resultList as List<Array<Any?>>
        val users = profiles.shorts(rows.flatMap { listOfNotNull(it[0] as UUID, it[2] as UUID?) })
        return rows.mapNotNull { r ->
            users[r[0] as UUID]?.let {
                StaffOut(
                    user = it, role = r[1] as String,
                    source = r[5] as String,
                    grantedBy = (r[2] as UUID?)?.let { g -> users[g] },
                    grantedAt = r[3]?.let { v -> toInstant(v) },
                    note = r[4] as String?,
                )
            }
        }
    }

    /**
     * Назначить / снять модератора. Пишется в staff_grant и действует со следующего запроса
     * (токен перевыпускать не нужно). Если straycatz.moderation.keycloak-sync=true —
     * дублируется realm-ролью в Keycloak.
     */
    @Transactional
    fun setModerator(me: AuthTicket, userId: UUID, on: Boolean) {
        requireUser(userId)
        if (userId == me.userId) throw ApiException.badRequest("self", "свою роль так не меняют")
        val current = grantOf(userId)
        if (current == Staff.DICTATOR) throw ApiException.forbidden("это диктатор — снимать его через DELETE /api/dictator/dictators/{id}")
        if (on) {
            em.createNativeQuery(
                "insert into staff_grant (user_id, role, granted_by) values (?1, 'moderator', ?2) on conflict (user_id) do update set role = 'moderator', granted_by = ?2, granted_at = now()",
            ).setParameter(1, userId).setParameter(2, me.userId).executeUpdate()
            em.createNativeQuery("update users set staff_role = 'moderator' where id = ?1 and staff_role is null").setParameter(1, userId).executeUpdate()
        } else {
            if (current == null) throw ApiException.notFound("человек не модератор (или роль выдана в Keycloak — снимать там)")
            em.createNativeQuery("delete from staff_grant where user_id = ?1").setParameter(1, userId).executeUpdate()
            em.createNativeQuery("update users set staff_role = null where id = ?1").setParameter(1, userId).executeUpdate()
            // взятые тикеты — обратно в очередь
            em.createNativeQuery("update mod_ticket set assignee_id = null, assigned_at = null, status = 'open' where assignee_id = ?1 and status = 'in_progress'")
                .setParameter(1, userId).executeUpdate()
        }
        if (keycloakSync) {
            keycloak.setRealmRole(userId, moderatorRole, on)
            if (!on) runCatching { keycloak.logoutAllSessions(userId) }
        }
        moderation.logStaff(me.userId, if (on) "grant_moderator" else "revoke_moderator", userId, null)
    }

    /**
     * Назначить ещё одного диктатора. Защита от случайности: в теле нужно повторить его username.
     * Снять можно только того, кого назначил сам; «первых диктаторов» из настройки и Keycloak — нельзя.
     */
    @Transactional
    fun setDictator(me: AuthTicket, userId: UUID, on: Boolean, confirmUsername: String?) {
        requireUser(userId)
        if (userId == me.userId) throw ApiException.badRequest("self", "свою роль так не меняют")
        val username = em.createNativeQuery("select username from users where id = ?1").setParameter(1, userId).singleResult as String
        if (on) {
            if (!username.equals(confirmUsername?.trim(), ignoreCase = true)) {
                throw ApiException.badRequest("confirm_required", "подтверди: confirmUsername = \"$username\"")
            }
            em.createNativeQuery(
                "insert into staff_grant (user_id, role, granted_by) values (?1, 'dictator', ?2) on conflict (user_id) do update set role = 'dictator', granted_by = ?2, granted_at = now()",
            ).setParameter(1, userId).setParameter(2, me.userId).executeUpdate()
            em.createNativeQuery("update users set staff_role = 'dictator' where id = ?1").setParameter(1, userId).executeUpdate()
        } else {
            @Suppress("UNCHECKED_CAST")
            val row = (em.createNativeQuery("select role, granted_by from staff_grant where user_id = ?1")
                .setParameter(1, userId).resultList as List<Array<Any?>>).firstOrNull()
            if (row == null || row[0] != Staff.DICTATOR) throw ApiException.notFound("человек не диктатор (или роль выдана в Keycloak)")
            if (row[1] != me.userId) throw ApiException.forbidden("снять можно только диктатора, которого назначил ты сам")
            em.createNativeQuery("delete from staff_grant where user_id = ?1").setParameter(1, userId).executeUpdate()
            em.createNativeQuery("update users set staff_role = null where id = ?1").setParameter(1, userId).executeUpdate()
        }
        moderation.logStaff(me.userId, if (on) "grant_dictator" else "revoke_dictator", userId, null)
    }

    private fun grantOf(userId: UUID): String? =
        em.createNativeQuery("select role from staff_grant where user_id = ?1").setParameter(1, userId).resultList.firstOrNull() as String?

    private fun requireUser(userId: UUID) {
        val n = (em.createNativeQuery("select count(*) from users where id = ?1 and not is_deleted").setParameter(1, userId).singleResult as Number).toLong()
        if (n == 0L) throw ApiException.notFound("человек не найден")
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}
