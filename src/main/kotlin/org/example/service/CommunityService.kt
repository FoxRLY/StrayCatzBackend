package org.example.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Community
import org.example.domain.CommunityFollow
import org.example.domain.CommunityFollowId
import org.example.domain.CommunityMember
import org.example.domain.CommunityMemberId
import org.example.rest.ApiException
import org.example.rest.MirrorOut
import org.example.rest.CommunitiesPageOut
import org.example.rest.CommunityCardOut
import org.example.rest.CommunityCreateIn
import org.example.rest.CommunityListOut
import org.example.rest.CommunityMemberOut
import org.example.rest.CommunityMembersPageOut
import org.example.rest.CommunityPageOut
import org.example.rest.CommunityPatchIn
import org.example.rest.CommunityShortOut
import org.example.rest.LeaderboardRowOut
import org.example.rest.LexiconIn
import org.example.rest.ModOut
import org.example.rest.MyCommunityOut
import org.example.rest.SectionOut
import org.example.rest.SectionsIn
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Сообщества: каталог, страница, участие (вступить / «читать без вступления»),
 * участники, модераторы, язык, разделы, правила, проекты, таблица активности.
 *
 * Права:
 *  - кто угодно (залогиненный): смотреть всё, апвоутить и репостить записи,
 *    «читать без вступления»;
 *  - follower: то же + записи в ленте; ни уведомлений, ни обсуждений, ни комментариев;
 *  - member: записи, лайки, комментарии, обсуждения, события, вики;
 *  - admin: + правка сообщества/языка/разделов/правил, события, закрепы, исключение member'ов;
 *  - owner: + удаление, назначение admin'ов.
 */
@ApplicationScoped
class CommunityService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val profiles: UserProfileService,
    private val presence: PresenceService,
    private val tags: TagService,
    private val media: MediaService,
    private val bus: org.example.bus.EventBus,
) {
    companion object {
        private val SLUG_RE = Regex("^[a-z0-9][a-z0-9-]{1,31}$")
        private val COLOR_RE = Regex("^#[0-9a-fA-F]{6}$")
        private val DIALECT_RE = Regex("^[a-z][a-z0-9_-]{1,19}$")
        private val WORD_KEY_RE = Regex("^[a-z][a-z0-9_]{0,39}$")
        private val RESERVED = setOf("new", "create", "edit", "search", "api", "admin", "me", "feed")
        const val MAX_NAME = 80
        const val MAX_ABOUT = 2000
        const val MAX_RULES = 5000
        const val MAX_WORD = 22
        const val MAX_WORDS = 60
        const val MAX_PAGE = 100
        private val ROLE_RANK = mapOf("member" to 0, "admin" to 1, "owner" to 2)

        /** Ключ раздела -> подпись вкладки (как во фронтовых моках). Порядок — порядок по умолчанию. */
        val SECTION_LABELS: Map<String, String> = linkedMapOf(
            "posts" to "Записи",
            "discussions" to "Обсуждения",
            "media" to "Медиа",
            "events" to "События",
            "members" to "Участники",
            "guides" to "Гайды",
            "projects" to "Проекты",
            "wiki" to "Вики",
            "leaderboard" to "Таблица",
            "market" to "Барахолка",
            "rooms" to "Комнаты",
            "music" to "Музыка",
            "replays" to "Реплеи",
            "voice" to "Голос",
        )
        val DEFAULT_SECTIONS = listOf("posts", "discussions", "events", "members", "voice")

        /** У проекта только записи, обсуждения и события. */
        val PROJECT_SECTIONS = listOf("posts", "discussions", "events")

        private val STRING_LIST = object : TypeReference<List<String>>() {}
        private val STRING_MAP = object : TypeReference<Map<String, String>>() {}
    }

    // ================================================================ каталог и страница

    /**
     * Каталог: ?q — поиск по имени/адресу/описанию, ?filter=all|joined|following.
     * Самые людные сверху. Проекты в каталог не попадают (они внутри сообществ).
     */
    @Transactional
    fun list(me: UUID, query: String?, filter: String?, limit: Int, offset: Int): CommunityListOut {
        val q = query?.trim()?.lowercase().orEmpty()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val scope = when (filter?.lowercase()) {
            null, "", "all" -> ""
            "joined" -> "and exists (select 1 from community_member m2 where m2.community_id = c.id and m2.user_id = ?3 and m2.left_at is null)"
            "following" -> "and exists (select 1 from community_follow f where f.community_id = c.id and f.user_id = ?3)"
            else -> throw ApiException.badRequest("invalid_filter", "filter: all, joined или following")
        }
        val where = """
            not c.is_deleted and c.kind = 'community'
            and (?1 = '' or lower(c.name) like ?2 or lower(c.slug) like ?2 or lower(coalesce(c.description, '')) like ?2)
            and cast(?3 as uuid) is not null $scope
        """.trimIndent()

        val total = (em.createNativeQuery("select count(*) from community c where $where")
            .setParameter(1, q).setParameter(2, like).setParameter(3, me).singleResult as Number).toLong()

        @Suppress("UNCHECKED_CAST")
        val ids = em.createNativeQuery(
            """
            select c.id from community c
            left join community_member m on m.community_id = c.id and m.left_at is null
            where $where
            group by c.id, c.name
            order by count(m.user_id) desc, c.name
            limit ?4 offset ?5
            """.trimIndent(),
            UUID::class.java,
        ).setParameter(1, q).setParameter(2, like).setParameter(3, me)
            .setParameter(4, limit.coerceIn(1, MAX_PAGE)).setParameter(5, offset.coerceAtLeast(0))
            .resultList as List<UUID>

        return CommunityListOut(cards(ids, me), total)
    }

    @Transactional
    fun page(slug: String, me: UUID): CommunityPageOut {
        val c = bySlug(slug)
        val card = cards(listOf(c.id), me).first()
        val projects = if (c.kind == "community") {
            shorts(Community.list("parentId = ?1 and isDeleted = false order by createdAt", c.id).map { it.id })
        } else emptyList()
        return CommunityPageOut(
            card.id, card.slug, card.name, card.hue, card.color, card.avatar, card.about, card.founded,
            card.sections, card.sectionItems, card.members, card.online, card.fresh, card.followers,
            card.dialect, card.lexicon, card.kind, card.parentSlug, card.joined, card.my,
            rules = c.rulesText?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            rulesText = c.rulesText,
            mods = mods(c),
            modlog = emptyList(),
            projects = projects,
            banner = c.banner,
            bannerFocus = c.bannerFocus,
            tags = tags.tagsOf(TagService.Owner.COMMUNITY, listOf(c.id))[c.id] ?: emptyList(),
            mirror = if (c.source != null) mirrorOf(c, me) else null,
            frozen = runCatching { requireNotFrozen(c); null }.getOrElse { e ->
                (e as? ApiException)?.details?.let {
                    org.example.rest.SanctionOut(it["until"] as Instant?, it["forever"] == true, it["reason"] as String?)
                }
            },
        )
    }

    /** Карточка зеркала для страницы сообщества (без зависимости от сервиса зеркал). */
    @Suppress("UNCHECKED_CAST")
    private fun mirrorOf(c: Community, me: UUID): MirrorOut? {
        val r = em.createNativeQuery(
            """
            select t.username, t.subscribers, t.paused, t.last_synced_at, t.last_error, t.added_by, t.claimed_by,
                   (select count(*) from telegram_post tp where tp.channel_id = t.id)
            from telegram_channel t where t.community_id = ?1
            """.trimIndent(),
        ).setParameter(1, c.id).resultList.firstOrNull() as Array<Any?>? ?: return null
        val addedBy = r[5] as UUID
        return MirrorOut(
            slug = c.slug, name = c.name, avatar = c.avatar, hue = c.hue, source = c.source ?: "telegram",
            username = r[0] as String, url = "https://t.me/${r[0]}",
            subscribers = (r[1] as Number?)?.toInt()?.takeIf { it > 0 },
            paused = r[2] as Boolean,
            lastSyncedAt = when (val v = r[3]) {
                is Instant -> v
                is java.time.OffsetDateTime -> v.toInstant()
                is java.sql.Timestamp -> v.toInstant()
                else -> null
            },
            lastError = (r[4] as String?)?.ifEmpty { null },
            addedBy = profiles.shorts(listOf(addedBy))[addedBy],
            claimed = r[6] != null,
            posts = (r[7] as Number).toLong(),
            canManage = addedBy == me,
        )
    }

    /** Модераторы отдельно: владелец — «основал», админы — «выбрали». */
    @Transactional
    fun admins(slug: String): List<ModOut> = mods(bySlug(slug))

    /** Сообщества пользователя (блок в комнате). */
    @Transactional
    fun ofUser(userId: UUID, limit: Int): CommunitiesPageOut {
        val memberships = CommunityMember.list("id.userId = ?1 and leftAt is null", userId)
        if (memberships.isEmpty()) return CommunitiesPageOut(0, emptyList())
        val active = Community.list("id in ?1 and isDeleted = false", memberships.map { it.id.communityId }).map { it.id }
        val sorted = shorts(active).sortedByDescending { it.memberCount }
        return CommunitiesPageOut(sorted.size.toLong(), sorted.take(limit.coerceIn(1, MAX_PAGE)))
    }

    // ================================================================ создание и настройка

    @Transactional
    fun create(me: UUID, req: CommunityCreateIn, parent: Community? = null): CommunityPageOut {
        val slug = req.slug?.trim()?.lowercase()
            ?.takeIf { SLUG_RE.matches(it) && it !in RESERVED }
            ?: throw ApiException.badRequest("invalid_slug", "адрес: 2–32 символа, латиница/цифры/дефис")
        val name = validName(req.name) ?: throw ApiException.badRequest("invalid_name", "название: 1–$MAX_NAME символов")
        if (Community.count("lower(slug) = ?1 and isDeleted = false", slug) > 0) {
            throw ApiException.conflict("slug_taken", "адрес /c/$slug уже занят")
        }
        val sections = if (parent != null) PROJECT_SECTIONS
        else req.sections?.let { normalizeSections(it, project = false) } ?: DEFAULT_SECTIONS

        val c = Community().also {
            it.id = UUID.randomUUID()
            it.slug = slug
            it.name = name
            it.description = validText(req.about, MAX_ABOUT, "about")
            it.hue = validHue(req.hue) ?: parent?.hue ?: (Math.floorMod(slug.hashCode(), 360))
            it.color = validColor(req.color)
            it.avatar = validAvatar(req.avatar)
            it.rulesText = validText(req.rules, MAX_RULES, "rules")
            it.ownerId = me
            it.sections = mapper.writeValueAsString(sections)
            it.kind = if (parent != null) "project" else "community"
            it.parentId = parent?.id
        }
        c.persist()
        CommunityMember().also { it.id = CommunityMemberId(c.id, me); it.role = "owner" }.persist()
        tags.sync(TagService.Owner.COMMUNITY, c.id, req.tags, c.description)
        return page(c.slug, me)
    }

    /** Проект внутри сообщества: создать может любой участник родителя. */
    @Transactional
    fun createProject(me: UUID, parentSlug: String, req: CommunityCreateIn): CommunityPageOut {
        val parent = bySlug(parentSlug)
        if (parent.kind != "community") throw ApiException.badRequest("invalid_parent", "у проекта не бывает своих проектов")
        requireRole(parent, me, "member")
        return create(me, req, parent)
    }

    @Transactional
    fun projects(parentSlug: String, me: UUID): List<CommunityCardOut> {
        val parent = bySlug(parentSlug)
        return cards(Community.list("parentId = ?1 and isDeleted = false order by createdAt", parent.id).map { it.id }, me)
    }

    @Transactional
    fun update(me: UUID, slug: String, patch: CommunityPatchIn): CommunityPageOut {
        val c = bySlug(slug)
        requireRole(c, me, "admin")
        patch.name?.let { c.name = validName(it) ?: throw ApiException.badRequest("invalid_name", "название: 1–$MAX_NAME символов") }
        patch.about?.let { c.description = validText(it, MAX_ABOUT, "about") }
        patch.hue?.let { c.hue = validHue(it)!! }
        patch.color?.let { c.color = validColor(it) }
        patch.avatar?.let { c.avatar = validAvatar(it) }
        patch.rules?.let { c.rulesText = validText(it, MAX_RULES, "rules") }
        if (patch.about != null || patch.tags != null) tags.sync(TagService.Owner.COMMUNITY, c.id, patch.tags, c.description)
        return page(c.slug, me)
    }

    // ---------------------------------------------------------------- аватар и шапка

    /** Аватар сообщества из загруженной картинки (admin). */
    @Transactional
    fun setAvatar(me: UUID, slug: String, mediaId: UUID): CommunityPageOut {
        val c = bySlug(slug)
        requireRole(c, me, "admin")
        c.avatar = media.url(image(me, mediaId))
        return page(c.slug, me)
    }

    @Transactional
    fun clearAvatar(me: UUID, slug: String): CommunityPageOut {
        val c = bySlug(slug)
        requireRole(c, me, "admin")
        c.avatar = null
        return page(c.slug, me)
    }

    /** Шапка: новая картинка и/или сдвиг кадра (focus 0..1 по вертикали). */
    @Transactional
    fun setBanner(me: UUID, slug: String, mediaId: UUID?, focus: Double?): CommunityPageOut {
        val c = bySlug(slug)
        requireRole(c, me, "admin")
        if (mediaId == null && focus == null) throw ApiException.badRequest("invalid_banner", "нужен mediaId или focus")
        mediaId?.let { c.banner = media.url(image(me, it)) }
        focus?.let {
            if (it < 0.0 || it > 1.0) throw ApiException.badRequest("invalid_focus", "focus: от 0 до 1")
            c.bannerFocus = Math.round(it * 100) / 100.0
        }
        return page(c.slug, me)
    }

    @Transactional
    fun clearBanner(me: UUID, slug: String): CommunityPageOut {
        val c = bySlug(slug)
        requireRole(c, me, "admin")
        c.banner = null
        c.bannerFocus = 0.5
        return page(c.slug, me)
    }

    private fun image(me: UUID, mediaId: UUID): UUID {
        val m = media.requireOwned(mediaId, me)
        if (!m.contentType.startsWith("image/")) throw ApiException.badRequest("not_image", "нужна картинка: png, jpeg, gif или webp")
        return m.id
    }

    /** Язык сообщества: говор + свои слова. lexicon полностью заменяет словарь. */
    @Transactional
    fun setLexicon(me: UUID, slug: String, req: LexiconIn): CommunityPageOut {
        val c = bySlug(slug)
        requireRole(c, me, "admin")
        req.dialect?.let {
            val d = it.trim().lowercase()
            if (!DIALECT_RE.matches(d)) throw ApiException.badRequest("invalid_dialect", "dialect: латиница, 2–20 символов")
            c.dialect = d
        }
        req.lexicon?.let { words ->
            val clean = words.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
            if (clean.size > MAX_WORDS) throw ApiException.badRequest("invalid_lexicon", "не больше $MAX_WORDS слов")
            clean.forEach { (k, v) ->
                if (!WORD_KEY_RE.matches(k)) throw ApiException.badRequest("invalid_lexicon", "ключ '$k': латиница snake_case")
                if (v.length > MAX_WORD) throw ApiException.badRequest("invalid_lexicon", "'$k' длиннее $MAX_WORD символов")
            }
            c.lexicon = mapper.writeValueAsString(clean)
        }
        return page(c.slug, me)
    }

    /** Разделы и их порядок. Принимает ключи ("posts") или подписи ("Записи"). */
    @Transactional
    fun setSections(me: UUID, slug: String, req: SectionsIn): CommunityPageOut {
        val c = bySlug(slug)
        requireRole(c, me, "admin")
        val list = req.sections ?: throw ApiException.badRequest("invalid_sections", "нужен массив sections")
        c.sections = mapper.writeValueAsString(normalizeSections(list, project = c.kind == "project"))
        return page(c.slug, me)
    }

    @Transactional
    fun delete(me: UUID, slug: String) {
        val c = bySlug(slug)
        requireRole(c, me, "owner")
        c.isDeleted = true
        c.deletedAt = Instant.now()
    }

    // ================================================================ участие

    /** Вступить. Если человек «читал без вступления» — подписка превращается в участие. */
    @Transactional
    fun join(me: UUID, slug: String): CommunityPageOut {
        val c = bySlug(slug)
        val m = CommunityMember.findById(CommunityMemberId(c.id, me))
        when {
            m == null -> CommunityMember().also { it.id = CommunityMemberId(c.id, me) }.persist()
            m.leftAt != null -> {
                m.leftAt = null; m.leaveReason = null; m.role = "member"; m.createdAt = Instant.now()
            }
        }
        CommunityFollow.findById(CommunityFollowId(c.id, me))?.delete()
        return page(c.slug, me)
    }

    @Transactional
    fun leave(me: UUID, slug: String, reason: String?) {
        val c = bySlug(slug)
        val m = activeMember(c.id, me) ?: return
        if (m.role == "owner") {
            throw ApiException.badRequest("owner_cannot_leave", "владелец не может выйти — удали сообщество")
        }
        m.leftAt = Instant.now()
        m.leaveReason = reason?.trim()?.take(200)?.ifBlank { null }
        leaveDiscussions(c.id, me)
    }

    /** «Читать без вступления»: записи попадают в ленту, прав и уведомлений нет. */
    @Transactional
    fun follow(me: UUID, slug: String): CommunityPageOut {
        val c = bySlug(slug)
        if (activeMember(c.id, me) != null) throw ApiException.badRequest("already_member", "ты уже участник")
        if (CommunityFollow.findById(CommunityFollowId(c.id, me)) == null) {
            CommunityFollow().also { it.id = CommunityFollowId(c.id, me) }.persist()
        }
        return page(c.slug, me)
    }

    @Transactional
    fun unfollow(me: UUID, slug: String) {
        val c = bySlug(slug)
        CommunityFollow.findById(CommunityFollowId(c.id, me))?.delete()
    }

    // ================================================================ участники

    /** Участники с онлайн-статусом, по репутации (пока у всех 0 → затем по дате вступления). */
    @Transactional
    fun members(slug: String, limit: Int, offset: Int): CommunityMembersPageOut {
        val c = bySlug(slug)
        val total = CommunityMember.count("id.communityId = ?1 and leftAt is null", c.id)
        val from = offset.coerceAtLeast(0)
        val rows = CommunityMember.find(
            "id.communityId = ?1 and leftAt is null order by reputation desc, " +
                    "case role when 'owner' then 0 when 'admin' then 1 else 2 end, createdAt",
            c.id,
        ).range(from, from + limit.coerceIn(1, MAX_PAGE) - 1).list()
        val users = profiles.shorts(rows.map { it.id.userId })
        val statuses = presence.publicStatuses(rows.map { it.id.userId })
        return CommunityMembersPageOut(
            items = rows.mapNotNull { m ->
                users[m.id.userId]?.let {
                    CommunityMemberOut(it.id, it.username, it.avatar, it.color, statuses[it.id] ?: "offline", m.role, m.reputation, m.createdAt)
                }
            },
            total = total,
            online = countsBy(ONLINE_SQL, listOf(c.id))[c.id] ?: 0,
        )
    }

    /** Исключить: owner — любого (кроме себя), admin — только member'ов. */
    @Transactional
    fun kick(me: UUID, slug: String, userId: UUID) {
        val c = bySlug(slug)
        val myRole = requireRole(c, me, "admin")
        val target = activeMember(c.id, userId) ?: throw ApiException.notFound("не состоит в сообществе")
        if (userId == me || rank(target.role) >= rank(myRole)) {
            throw ApiException.forbidden("нельзя исключить участника с такой же или старшей ролью")
        }
        target.leftAt = Instant.now()
        target.leaveReason = "kicked"
        leaveDiscussions(c.id, userId)
    }

    /** Назначить admin/member — только owner. */
    @Transactional
    fun setRole(me: UUID, slug: String, userId: UUID, role: String?): ModOut {
        val c = bySlug(slug)
        requireRole(c, me, "owner")
        if (role != "admin" && role != "member") throw ApiException.badRequest("invalid_role", "role: admin или member")
        if (userId == me) throw ApiException.badRequest("invalid_role", "свою роль владельца так не поменять")
        val m = activeMember(c.id, userId) ?: throw ApiException.notFound("не состоит в сообществе")
        m.role = role
        val u = profiles.shorts(listOf(userId))[userId] ?: throw ApiException.notFound("пользователь не найден")
        return ModOut(u.id, u.username, u.username, u.avatar, u.color, m.role, howOf(m.role))
    }

    // ================================================================ таблица активности

    /**
     * Таблица за последние [days] дней. Заглушка скоринга:
     * запись 10, комментарий 3, полученный апвоут 5, полученный лайк 2.
     */
    @Transactional
    fun leaderboard(slug: String, days: Int, limit: Int): List<LeaderboardRowOut> {
        val c = bySlug(slug)
        val interval = "${days.coerceIn(1, 365)} days"
        @Suppress("UNCHECKED_CAST")
        val rows = em.createNativeQuery(
            """
            with pts as (
                select p.creator_id as uid, 10 as s from post p
                 where p.community_id = ?1 and not p.is_deleted and p.created_at > now() - cast(?2 as interval)
                union all
                select c.author_id, 3 from post_comment c join post p on p.id = c.post_id
                 where p.community_id = ?1 and c.deleted_at is null and c.created_at > now() - cast(?2 as interval)
                union all
                select p.creator_id, 5 from post_upvote u join post p on p.id = u.post_id
                 where p.community_id = ?1 and u.created_at > now() - cast(?2 as interval)
                union all
                select p.creator_id, 2 from post_like l join post p on p.id = l.post_id
                 where p.community_id = ?1 and l.created_at > now() - cast(?2 as interval)
            )
            select uid, sum(s) from pts group by uid order by sum(s) desc limit ?3
            """.trimIndent(),
        ).setParameter(1, c.id).setParameter(2, interval).setParameter(3, limit.coerceIn(1, MAX_PAGE))
            .resultList as List<Array<Any?>>
        val users = profiles.shorts(rows.map { it[0] as UUID })
        val top = rows.firstOrNull()?.let { (it[1] as Number).toDouble() } ?: 1.0
        return rows.mapIndexedNotNull { i, r ->
            val score = (r[1] as Number).toLong()
            users[r[0] as UUID]?.let { LeaderboardRowOut(i + 1, it, score, if (top > 0) score / top else 0.0) }
        }
    }

    // ================================================================ для других сервисов

    fun bySlug(slug: String): Community =
        Community.find("lower(slug) = ?1 and isDeleted = false", slug.trim().lowercase()).firstResult()
            ?: throw blockedOrMissing(slug)

    /** Заблокированное модератором (V20) — 451 с причиной; иначе 404. */
    private fun blockedOrMissing(slug: String): ApiException {
        @Suppress("UNCHECKED_CAST")
        val row = (em.createNativeQuery(
            "select block_reason, name from community where lower(slug) = ?1 and blocked_at is not null limit 1",
        ).setParameter(1, slug.trim().lowercase()).resultList as List<Array<Any?>>).firstOrNull()
            ?: return ApiException.notFound("сообщество не найдено")
        return ApiException(
            451, "community_blocked", "сообщество «${row[1]}» заблокировано модерацией" + ((row[0] as String?)?.let { ": $it" } ?: ""),
            mapOf("reason" to row[0], "name" to row[1]),
        )
    }

    /** Заморожено модератором: читать можно, писать/создавать — нет. */
    fun requireNotFrozen(c: Community) {
        @Suppress("UNCHECKED_CAST")
        val row = (em.createNativeQuery("select frozen_until, freeze_reason from community where id = ?1 and frozen_until > now()")
            .setParameter(1, c.id).resultList as List<Array<Any?>>).firstOrNull() ?: return
        val until = when (val v = row[0]) {
            is Instant -> v
            is java.time.OffsetDateTime -> v.toInstant()
            is java.sql.Timestamp -> v.toInstant()
            else -> null
        }
        val forever = until != null && until >= org.example.auth.Staff.FOREVER
        throw ApiException(
            403, "community_frozen",
            "сообщество заморожено модерацией" + (if (forever) "" else " до $until") + ((row[1] as String?)?.let { ": $it" } ?: ""),
            mapOf("until" to until?.takeIf { !forever }, "forever" to forever, "reason" to row[1]),
        )
    }

    fun roleOf(communityId: UUID, userId: UUID): String? = activeMember(communityId, userId)?.role

    fun isFollower(communityId: UUID, userId: UUID): Boolean =
        CommunityFollow.findById(CommunityFollowId(communityId, userId)) != null

    /** @return роль me, если она не ниже [min]; иначе 403. */
    fun requireRole(c: Community, me: UUID, min: String): String {
        // зеркало Telegram: писать/создавать/администрировать нельзя никому — только читать,
        // лайкать и комментировать (это идёт мимо requireRole)
        if (c.source != null) throw ApiException(403, "mirror_readonly", "это зеркало Telegram-канала — сюда нельзя писать")
        requireNotFrozen(c)
        val role = activeMember(c.id, me)?.role
        if (rank(role) < rank(min)) {
            throw ApiException.forbidden(
                if (min == "member") "нужно вступить в сообщество" else "недостаточно прав в сообществе",
            )
        }
        return role!!
    }

    /** id активных участников (для рассылки уведомлений о событиях — только им, не follower'ам). */
    fun memberIds(communityId: UUID): List<UUID> =
        CommunityMember.list("id.communityId = ?1 and leftAt is null", communityId).map { it.id.userId }

    fun parseLexicon(json: String): Map<String, String> =
        runCatching { mapper.readValue(json, STRING_MAP) }.getOrDefault(emptyMap())

    fun parseSections(json: String): List<String> =
        runCatching { mapper.readValue(json, STRING_LIST) }.getOrDefault(DEFAULT_SECTIONS)

    // ================================================================ utils

    private fun cards(ids: List<UUID>, me: UUID): List<CommunityCardOut> {
        if (ids.isEmpty()) return emptyList()
        val byId = Community.list("id in ?1", ids).associateBy { it.id }
        val members = countsBy(MEMBERS_SQL, ids)
        val followers = countsBy(FOLLOWERS_SQL, ids)
        val online = countsBy(ONLINE_SQL, ids)
        val fresh = countsBy(FRESH_SQL, ids)
        val myRoles = CommunityMember.list("id.userId = ?1 and leftAt is null and id.communityId in ?2", me, ids)
            .associate { it.id.communityId to it.role }
        val myFollows = CommunityFollow.list("id.userId = ?1 and id.communityId in ?2", me, ids)
            .map { it.id.communityId }.toSet()
        val parents = byId.values.mapNotNull { it.parentId }.distinct().let { p ->
            if (p.isEmpty()) emptyMap() else Community.list("id in ?1", p).associate { it.id to it.slug }
        }
        return ids.mapNotNull { id ->
            val c = byId[id] ?: return@mapNotNull null
            val keys = parseSections(c.sections)
            val role = myRoles[id]
            CommunityCardOut(
                id = c.id,
                slug = c.slug,
                name = c.name,
                hue = c.hue,
                color = c.color,
                avatar = c.avatar,
                about = c.description,
                founded = year(c.createdAt),
                sections = keys.map { SECTION_LABELS[it] ?: it },
                sectionItems = keys.map { SectionOut(it, SECTION_LABELS[it] ?: it) },
                members = members[id] ?: 0,
                online = online[id] ?: 0,
                fresh = fresh[id] ?: 0,
                followers = followers[id] ?: 0,
                dialect = c.dialect,
                lexicon = parseLexicon(c.lexicon),
                kind = c.kind,
                parentSlug = c.parentId?.let { parents[it] },
                joined = role != null,
                my = MyCommunityOut(role, role != null, id in myFollows),
                banner = c.banner,
                bannerFocus = c.bannerFocus,
                source = c.source,
            )
        }
    }

    private fun shorts(ids: List<UUID>): List<CommunityShortOut> {
        if (ids.isEmpty()) return emptyList()
        val byId = Community.list("id in ?1", ids).associateBy { it.id }
        val counts = countsBy(MEMBERS_SQL, ids)
        return ids.mapNotNull { id ->
            byId[id]?.let { CommunityShortOut(it.id, it.slug, it.name, it.hue, it.color, it.avatar, counts[id] ?: 0) }
        }
    }

    private fun mods(c: Community): List<ModOut> {
        val rows = CommunityMember.list(
            "id.communityId = ?1 and leftAt is null and role in ('owner', 'admin') " +
                    "order by case role when 'owner' then 0 else 1 end, createdAt",
            c.id,
        )
        val users = profiles.shorts(rows.map { it.id.userId })
        return rows.mapNotNull { m ->
            users[m.id.userId]?.let { ModOut(it.id, it.username, it.username, it.avatar, it.color, m.role, howOf(m.role)) }
        }
    }

    private fun howOf(role: String) = when (role) {
        "owner" -> "основал"
        "admin" -> "выбрали"
        else -> "по репутации"
    }

    private fun normalizeSections(input: List<String>, project: Boolean): List<String> {
        val byLabel = SECTION_LABELS.entries.associate { it.value.lowercase() to it.key }
        val keys = input.map { raw ->
            val v = raw.trim()
            when {
                v in SECTION_LABELS -> v
                v.lowercase() in byLabel -> byLabel.getValue(v.lowercase())
                else -> throw ApiException.badRequest("invalid_sections", "неизвестный раздел '$v', допустимы: ${SECTION_LABELS.keys}")
            }
        }
        if (keys.size != keys.distinct().size) throw ApiException.badRequest("invalid_sections", "разделы повторяются")
        if (keys.isEmpty()) throw ApiException.badRequest("invalid_sections", "хотя бы один раздел")
        if (project && keys.any { it !in PROJECT_SECTIONS }) {
            throw ApiException.badRequest("invalid_sections", "у проекта только $PROJECT_SECTIONS")
        }
        return keys
    }

    private fun leaveDiscussions(communityId: UUID, userId: UUID) {
        em.createNativeQuery(
            """
            update chat_member set is_deleted = true, deleted_at = now()
            where user_id = ?1 and not is_deleted
              and chat_id in (select id from chat where community_id = ?2)
            """.trimIndent(),
        ).setParameter(1, userId).setParameter(2, communityId).executeUpdate()
        bus.membershipChanged(listOf(userId))
    }

    private fun activeMember(communityId: UUID, userId: UUID): CommunityMember? =
        CommunityMember.findById(CommunityMemberId(communityId, userId))?.takeIf { it.leftAt == null }

    private fun rank(role: String?) = ROLE_RANK[role] ?: -1

    private fun year(i: Instant) = i.atZone(ZoneOffset.UTC).year.toString()

    private val MEMBERS_SQL =
        "select community_id, count(*) from community_member where left_at is null and community_id in (?1) group by community_id"
    private val FOLLOWERS_SQL =
        "select community_id, count(*) from community_follow where community_id in (?1) group by community_id"
    private val ONLINE_SQL =
        "select community_id, count(distinct user_id) from post_read " +
                "where community_id in (?1) and read_at > now() - interval '30 minutes' group by community_id"
    private val FRESH_SQL =
        "select community_id, count(*) from post where community_id in (?1) and not is_deleted " +
                "and created_at > now() - interval '24 hours' group by community_id"

    @Suppress("UNCHECKED_CAST")
    private fun countsBy(sql: String, ids: List<UUID>): Map<UUID, Long> {
        if (ids.isEmpty()) return emptyMap()
        val rows = em.createNativeQuery(sql).setParameter(1, ids).resultList as List<Array<Any?>>
        return rows.associate { it[0] as UUID to (it[1] as Number).toLong() }
    }

    private fun validName(v: String?) = v?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }

    private fun validText(v: String?, max: Int, field: String): String? {
        val d = v?.trim() ?: return null
        if (d.length > max) throw ApiException.badRequest("invalid_$field", "$field длиннее $max символов")
        return d.ifEmpty { null }
    }

    private fun validHue(v: Int?): Int? {
        if (v == null) return null
        if (v !in 0..359) throw ApiException.badRequest("invalid_hue", "hue: 0–359")
        return v
    }

    private fun validColor(v: String?): String? {
        val c = v?.trim() ?: return null
        if (c.isEmpty()) return null
        if (!COLOR_RE.matches(c)) throw ApiException.badRequest("invalid_color", "color в формате #rrggbb")
        return c
    }

    private fun validAvatar(v: String?): String? {
        val a = v?.trim() ?: return null
        if (a.length > 1024) throw ApiException.badRequest("invalid_avatar", "avatar длиннее 1024")
        return a.ifEmpty { null }
    }
}
