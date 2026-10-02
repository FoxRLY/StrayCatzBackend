package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.rest.ApiException
import org.example.rest.TagCountOut
import java.util.UUID

/**
 * Теги на всём: записи (сообщества, пульс, стена), видео, эфиры, треки,
 * сообщества, события. Одна таблица tag_link.
 *
 * Два способа поставить тег:
 *  - руками: поле tags: ["lowpoly", "ночь"] в запросе создания/правки;
 *  - автоматически: #хэштеги из текста (заголовок, тело, описание) — их
 *    пересчитываем при каждой правке текста.
 * Ручные теги без поля tags в запросе не трогаются; tags: [] — убрать все ручные.
 */
@ApplicationScoped
class TagService(private val em: EntityManager) {

    enum class Owner(val db: String) {
        POST("post"), VIDEO("video"), STREAM("stream"), TRACK("track"), COMMUNITY("community"), EVENT("event")
    }

    companion object {
        const val MAX_TAGS = 15
        const val MAX_LEN = 40
        /** #хэштег: буквы любых алфавитов, цифры, _; перед # не буква/цифра (иначе это не тег, а «C#» или якорь). */
        private val HASHTAG = Regex("(?<![\\p{L}\\p{N}_#&/])#([\\p{L}\\p{N}_]{2,40})")
        private val VALID = Regex("^[\\p{L}\\p{N}_]{2,40}$")

        /** «#Drum and Bass» → «drum_and_bass». null — после чистки ничего не осталось. */
        fun normalize(raw: String): String? {
            val t = raw.trim().removePrefix("#").lowercase()
                .replace(Regex("[\\s\\-]+"), "_")
                .replace(Regex("[^\\p{L}\\p{N}_]"), "")
                .trim('_')
            return t.takeIf { VALID.matches(it) }
        }

        /** Все #хэштеги из текстов, без повторов, в порядке появления. */
        fun hashtags(vararg texts: String?): List<String> =
            texts.filterNotNull().flatMap { t -> HASHTAG.findAll(t).map { it.groupValues[1].lowercase() } }.distinct()
    }

    /** Ручные теги из запроса: нормализуем, плохие — 400 (чтобы человек видел, что тег не встал). */
    fun manual(raw: List<String>?): List<String>? = raw?.let { list ->
        val out = list.map { r ->
            normalize(r) ?: throw ApiException.badRequest("invalid_tag", "тег «$r»: 2–$MAX_LEN букв/цифр/_")
        }.distinct()
        if (out.size > MAX_TAGS) throw ApiException.badRequest("too_many_tags", "не больше $MAX_TAGS тегов")
        out
    }

    /**
     * Пересчитать теги объекта.
     * @param explicit ручные теги (null — не трогать уже стоящие ручные)
     * @param texts    тексты, из которых берутся #хэштеги
     */
    @Transactional
    fun sync(owner: Owner, id: UUID, explicit: List<String>?, vararg texts: String?) {
        val manual = manual(explicit)
        em.createNativeQuery("delete from tag_link where owner_type = ?1 and owner_id = ?2 and auto")
            .setParameter(1, owner.db).setParameter(2, id).executeUpdate()
        if (manual != null) {
            em.createNativeQuery("delete from tag_link where owner_type = ?1 and owner_id = ?2 and not auto")
                .setParameter(1, owner.db).setParameter(2, id).executeUpdate()
            insert(owner, id, manual, auto = false)
        }
        val have = tagsOf(owner, listOf(id))[id]?.size ?: 0
        insert(owner, id, hashtags(*texts).take((MAX_TAGS - have).coerceAtLeast(0)), auto = true)
    }

    /** Убрать все теги объекта (удалили запись/эфир…). */
    @Transactional
    fun clear(owner: Owner, id: UUID) {
        em.createNativeQuery("delete from tag_link where owner_type = ?1 and owner_id = ?2")
            .setParameter(1, owner.db).setParameter(2, id).executeUpdate()
    }

    /** Теги пачкой: ручные первыми, дальше по алфавиту. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun tagsOf(owner: Owner, ids: Collection<UUID>): Map<UUID, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery(
            "select owner_id, tag from tag_link where owner_type = ?1 and owner_id in (?2) order by owner_id, auto, tag",
        ).setParameter(1, owner.db).setParameter(2, ids.distinct()).resultList as List<Array<Any?>>
        return rows.groupBy({ it[0] as UUID }) { it[1] as String }
    }

    /** Автодополнение: самые используемые теги, начинающиеся с префикса. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun suggest(prefix: String?, limit: Int): List<TagCountOut> {
        val p = prefix?.let { normalize(it) ?: it.trim().removePrefix("#").lowercase() }.orEmpty()
        val like = p.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val rows = em.createNativeQuery(
            "select tag, count(*) from tag_link where tag like ?1 group by tag order by count(*) desc, tag limit ?2",
        ).setParameter(1, like).setParameter(2, limit.coerceIn(1, 50)).resultList as List<Array<Any?>>
        return rows.map { TagCountOut(it[0] as String, (it[1] as Number).toLong()) }
    }

    /** id объектов с тегом, свежие сверху. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun ownersWith(owner: Owner, tag: String, limit: Int): List<UUID> =
        em.createNativeQuery(
            "select owner_id from tag_link where tag = ?1 and owner_type = ?2 order by created_at desc limit ?3",
            UUID::class.java,
        ).setParameter(1, tag).setParameter(2, owner.db).setParameter(3, limit).resultList as List<UUID>

    /** Сколько объектов каждого типа с тегом. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun counts(tag: String): Map<String, Long> =
        (em.createNativeQuery("select owner_type, count(*) from tag_link where tag = ?1 group by owner_type")
            .setParameter(1, tag).resultList as List<Array<Any?>>).associate { it[0] as String to (it[1] as Number).toLong() }

    private fun insert(owner: Owner, id: UUID, tags: List<String>, auto: Boolean) {
        tags.forEach { t ->
            em.createNativeQuery(
                "insert into tag_link (tag, owner_type, owner_id, auto) values (?1, ?2, ?3, ?4) on conflict do nothing",
            ).setParameter(1, t).setParameter(2, owner.db).setParameter(3, id).setParameter(4, auto).executeUpdate()
        }
    }
}
