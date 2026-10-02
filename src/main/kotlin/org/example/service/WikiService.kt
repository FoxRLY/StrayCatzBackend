package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.example.domain.WikiPage
import org.example.domain.WikiRevision
import org.example.rest.ApiException
import org.example.rest.WikiIn
import org.example.rest.WikiListItemOut
import org.example.rest.WikiPageOut
import org.example.rest.WikiRevisionOut
import java.time.Instant
import java.util.UUID

/**
 * Вики сообщества: страницы по адресу /c/{slug}/wiki/{page}, правят участники,
 * каждая правка сохраняется в wiki_revision (история). Удаляет admin.
 */
@ApplicationScoped
class WikiService(
    private val communities: CommunityService,
    private val profiles: UserProfileService,
) {
    companion object {
        private val PAGE_RE = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        const val MAX_TITLE = 200
        const val MAX_BODY = 100_000
    }

    @Transactional
    fun list(slug: String): List<WikiListItemOut> {
        val c = communities.bySlug(slug)
        return WikiPage.list("communityId = ?1 and deletedAt is null order by title", c.id)
            .map { WikiListItemOut(it.slug, it.title, it.updatedAt) }
    }

    @Transactional
    fun get(slug: String, page: String): WikiPageOut = toOut(find(slug, page))

    /** Создать или обновить страницу (PUT — идемпотентно по адресу). */
    @Transactional
    fun put(me: UUID, slug: String, page: String, req: WikiIn): WikiPageOut {
        val c = communities.bySlug(slug)
        communities.requireRole(c, me, "member")
        val key = page.trim().lowercase()
        if (!PAGE_RE.matches(key)) throw ApiException.badRequest("invalid_page", "адрес страницы: латиница/цифры/дефис, до 64")
        val body = req.body ?: throw ApiException.badRequest("invalid_body", "нужен body")
        if (body.length > MAX_BODY) throw ApiException.badRequest("invalid_body", "страница длиннее $MAX_BODY символов")

        val existing = WikiPage.find("communityId = ?1 and slug = ?2 and deletedAt is null", c.id, key).firstResult()
        val p = existing ?: WikiPage().also {
            it.id = UUID.randomUUID()
            it.communityId = c.id
            it.slug = key
            it.createdBy = me
        }
        val title = req.title?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.title
            ?: throw ApiException.badRequest("invalid_title", "нужен title")
        if (title.length > MAX_TITLE) throw ApiException.badRequest("invalid_title", "заголовок длиннее $MAX_TITLE")
        p.title = title
        p.body = body
        p.updatedBy = me
        p.updatedAt = Instant.now()
        if (existing == null) p.persist()

        WikiRevision().also {
            it.id = UUID.randomUUID()
            it.pageId = p.id
            it.title = p.title
            it.body = p.body
            it.editorId = me
        }.persist()
        return toOut(p)
    }

    @Transactional
    fun history(slug: String, page: String): List<WikiRevisionOut> {
        val p = find(slug, page)
        val revs = WikiRevision.list("pageId = ?1 order by createdAt desc", p.id)
        val editors = profiles.shorts(revs.map { it.editorId })
        return revs.map { WikiRevisionOut(it.id, it.title, it.body, editors[it.editorId], it.createdAt) }
    }

    @Transactional
    fun delete(me: UUID, slug: String, page: String) {
        val p = find(slug, page)
        communities.requireRole(communities.bySlug(slug), me, "admin")
        p.deletedAt = Instant.now()
    }

    private fun find(slug: String, page: String): WikiPage {
        val c = communities.bySlug(slug)
        return WikiPage.find("communityId = ?1 and slug = ?2 and deletedAt is null", c.id, page.trim().lowercase()).firstResult()
            ?: throw ApiException.notFound("страница не найдена")
    }

    private fun toOut(p: WikiPage) = WikiPageOut(
        p.slug, p.title, p.body, p.createdAt, p.updatedAt, profiles.shorts(listOf(p.updatedBy))[p.updatedBy],
    )
}
