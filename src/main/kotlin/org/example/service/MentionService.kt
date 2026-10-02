package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.rest.UserShortOut
import java.util.UUID

/**
 * Упоминания @ник. В тексте ищутся @username (как в логинах: a-z, 0-9, _ . -),
 * найденные люди записываются в mention и получают уведомление «mention».
 *  - в сообщении — только участники этого чата (чужим не шлём: они не увидят);
 *  - в записи и комментарии — любой существующий человек.
 * При правке текста уведомление уходит только новым упомянутым.
 */
@ApplicationScoped
class MentionService(
    private val em: EntityManager,
    private val notifications: NotificationService,
    private val profiles: UserProfileService,
) {
    enum class Owner(val db: String) { MESSAGE("message"), POST("post"), COMMENT("comment") }

    companion object {
        const val MAX_PER_TEXT = 20
        private val MENTION = Regex("(?<![\\p{L}\\p{N}_@./])@([a-zA-Z0-9][a-zA-Z0-9_.-]{2,31})")

        /** Ники из текста (без @, в нижнем регистре, без хвостовых точек/дефисов). */
        fun usernames(text: String?): List<String> =
            if (text.isNullOrEmpty()) emptyList()
            else MENTION.findAll(text).map { it.groupValues[1].lowercase().trimEnd('.', '-') }
                .filter { it.length >= 3 }.distinct().take(MAX_PER_TEXT).toList()
    }

    /**
     * Пересчитать упоминания и уведомить новых.
     * @param allowed кого можно упоминать (участники чата); null — любого.
     * @param payload что положить в уведомление (без preview — он добавится).
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun sync(
        owner: Owner, ownerId: UUID, authorId: UUID, text: String?,
        allowed: Set<UUID>?, payload: Map<String, Any?>,
    ): List<UUID> {
        val names = usernames(text)
        val found: List<UUID> = if (names.isEmpty()) emptyList() else
            (em.createNativeQuery("select id from users where username in (?1) and not is_deleted", UUID::class.java)
                .setParameter(1, names).resultList as List<UUID>)
                .filter { it != authorId && (allowed == null || it in allowed) }
        val before = (em.createNativeQuery("select user_id from mention where owner_type = ?1 and owner_id = ?2", UUID::class.java)
            .setParameter(1, owner.db).setParameter(2, ownerId).resultList as List<UUID>).toSet()
        em.createNativeQuery("delete from mention where owner_type = ?1 and owner_id = ?2")
            .setParameter(1, owner.db).setParameter(2, ownerId).executeUpdate()
        found.forEach { uid ->
            em.createNativeQuery("insert into mention (owner_type, owner_id, user_id) values (?1, ?2, ?3) on conflict do nothing")
                .setParameter(1, owner.db).setParameter(2, ownerId).setParameter(3, uid).executeUpdate()
        }
        val preview = text?.replace('\n', ' ')?.take(120)
        (found - before).forEach { uid ->
            notifications.notify(uid, NotificationService.MENTION, authorId, payload + mapOf("where" to owner.db, "preview" to preview))
        }
        return found
    }

    /** Кого упомянули — пачкой, для подсветки на фронте. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun of(owner: Owner, ids: Collection<UUID>): Map<UUID, List<UserShortOut>> {
        if (ids.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery("select owner_id, user_id from mention where owner_type = ?1 and owner_id in (?2)")
            .setParameter(1, owner.db).setParameter(2, ids.distinct()).resultList as List<Array<Any?>>
        if (rows.isEmpty()) return emptyMap()
        val users = profiles.shorts(rows.map { it[1] as UUID })
        return rows.groupBy({ it[0] as UUID }) { it[1] as UUID }.mapValues { (_, us) -> us.mapNotNull { users[it] } }
    }
}
