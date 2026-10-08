package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.AppUser
import org.example.domain.Community
import org.example.domain.Media
import org.example.rest.ApiException
import org.example.rest.MarketCategoryOut
import org.example.rest.MarketContactOut
import org.example.rest.MarketItemIn
import org.example.rest.MarketItemOut
import org.example.rest.MarketPageOut
import org.example.rest.MyMarketOut
import org.example.rest.PostCommunityOut
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Барахолка: простые объявления «продам / отдам». Фото, описание, цена,
 * город, контакты свободным текстом и кнопка «написать продавцу» (личка).
 * Статусы: active → reserved (забронировано) → sold; удаление — status = deleted.
 * Лента — по bumped_at: новое и «поднятое» (раз в сутки) сверху.
 *
 * Барахолка сообщества (вкладка «Барахолка», V19): объявление может принадлежать сообществу
 * (market_item.community_id). Выставить туда может участник (member+), если у сообщества включён
 * раздел market; в зеркале Telegram — нельзя. Админ сообщества может «снять» объявление
 * со своей барахолки (оно остаётся у продавца и в общей ленте). В общей ленте /api/market
 * такие объявления видны с плашкой community.
 */
@ApplicationScoped
class MarketService(
    private val em: EntityManager,
    private val media: MediaService,
    private val attachments: AttachmentService,
    private val profiles: UserProfileService,
    private val chatAdmin: ChatManagementService,
    private val messages: MessageService,
    private val badges: BadgeService,
    private val communities: CommunityService,
) {
    companion object {
        const val MAX_PAGE = 50
        const val MAX_PHOTOS = 10
        const val MAX_TITLE = 120
        const val MAX_DESCRIPTION = 5000
        const val MAX_CITY = 60
        const val MAX_CONTACTS = 300
        const val PER_DAY = 20
        val MAX_PRICE = BigDecimal("9999999999.99")
        val BUMP_EVERY: Duration = Duration.ofHours(24)

        val CURRENCIES = linkedMapOf("RUB" to "₽", "USD" to "$", "EUR" to "€", "KZT" to "₸", "BYN" to "Br", "UAH" to "₴", "GEL" to "₾", "AMD" to "֏")
        val CONDITIONS = setOf("new", "used", "broken")
        val STATUSES = setOf("active", "reserved", "sold")

        /** code → (название, иконка). */
        val CATEGORIES: LinkedHashMap<String, Pair<String, String>> = linkedMapOf(
            "electronics" to ("техника" to "📱"),
            "clothes" to ("одежда и обувь" to "👟"),
            "home" to ("для дома" to "🛋️"),
            "music" to ("музыка и винил" to "🎸"),
            "books" to ("книги и комиксы" to "📚"),
            "games" to ("игры и приставки" to "🎮"),
            "hobby" to ("хобби и спорт" to "🛹"),
            "kids" to ("детское" to "🧸"),
            "transport" to ("транспорт и запчасти" to "🚲"),
            "pets" to ("для животных" to "🐈"),
            "other" to ("разное" to "📦"),
        )
    }

    // ================================================================ чтение

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun categories(communitySlug: String? = null): List<MarketCategoryOut> {
        val cid = communitySlug?.let { communities.bySlug(it).id }
        val q = em.createNativeQuery(
            "select category, count(*) from market_item where status = 'active'" +
                (if (cid != null) " and community_id = ?1" else "") + " group by category",
        )
        if (cid != null) q.setParameter(1, cid)
        val counts = (q.resultList as List<Array<Any?>>).associate { (it[0] as String) to (it[1] as Number).toLong() }
        return CATEGORIES.map { (code, v) -> MarketCategoryOut(code, v.first, v.second, counts[code] ?: 0) }
    }

    /**
     * Лента и поиск. status: active (по умолчанию — активные и забронированные),
     * sold, all. q — по названию и описанию.
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun search(
        me: UUID, q: String?, category: String?, city: String?, minPrice: BigDecimal?, maxPrice: BigDecimal?,
        free: Boolean?, condition: String?, seller: String?, status: String?, before: Instant?, limit: Int,
        community: String? = null,
    ): MarketPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        val params = mutableListOf<Any>()
        fun p(v: Any): String { params += v; return "?${params.size}" }
        val where = mutableListOf<String>()
        where += when (status?.lowercase()) {
            null, "", "active" -> "m.status in ('active', 'reserved')"
            "sold" -> "m.status = 'sold'"
            "all" -> "m.status <> 'deleted'"
            else -> throw ApiException.badRequest("invalid_status", "status: active, sold или all")
        }
        q?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val like = "%" + it.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            val ph = p(like)
            where += "(lower(m.title) like $ph or lower(m.description) like $ph)"
        }
        category?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in CATEGORIES) throw ApiException.badRequest("invalid_category", "нет такой категории")
            where += "m.category = ${p(it)}"
        }
        city?.trim()?.takeIf { it.isNotEmpty() }?.let { where += "lower(m.city) = ${p(it.lowercase())}" }
        minPrice?.let { where += "m.price >= ${p(it)}" }
        maxPrice?.let { where += "m.price <= ${p(it)}" }
        if (free == true) where += "m.price = 0"
        condition?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in CONDITIONS) throw ApiException.badRequest("invalid_condition", "condition: new, used или broken")
            where += "m.condition = ${p(it)}"
        }
        seller?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val uid = if (it.equals("me", true)) me else
                AppUser.find("username = ?1 and isDeleted = false", it.lowercase()).firstResult()?.id
                    ?: return MarketPageOut(emptyList(), false, null)
            where += "m.seller_id = ${p(uid)}"
        }
        community?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where += "m.community_id = ${p(communities.bySlug(it).id)}"
        }
        before?.let { where += "m.bumped_at < ${p(it)}" }
        val sql = "select m.id from market_item m where ${where.joinToString(" and ")} order by m.bumped_at desc limit ${p(size + 1)}"
        val q2 = em.createNativeQuery(sql, UUID::class.java)
        params.forEachIndexed { i, v -> q2.setParameter(i + 1, v) }
        val ids = q2.resultList as List<UUID>
        val items = render(ids.take(size), me)
        return MarketPageOut(items, ids.size > size, if (ids.size > size) items.lastOrNull()?.bumpedAt else null)
    }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun favorites(me: UUID, before: Instant?, limit: Int): MarketPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = em.createNativeQuery(
            """
            select f.item_id, f.created_at from market_favorite f join market_item m on m.id = f.item_id and m.status <> 'deleted'
            where f.user_id = ?1 and f.created_at < ?2 order by f.created_at desc limit ?3
            """.trimIndent(),
        ).setParameter(1, me).setParameter(2, before ?: Instant.now().plusSeconds(60)).setParameter(3, size + 1)
            .resultList as List<Array<Any?>>
        val items = render(rows.take(size).map { it[0] as UUID }, me)
        return MarketPageOut(items, rows.size > size, if (rows.size > size) toInstant(rows[size - 1][1]) else null)
    }

    /** Карточка. Чужой просмотр — +1 к views. */
    @Transactional
    fun get(me: UUID, id: UUID): MarketItemOut {
        val sellerId = sellerOf(id)
        if (sellerId != me) em.createNativeQuery("update market_item set views = views + 1 where id = ?1").setParameter(1, id).executeUpdate()
        return render(listOf(id), me).first()
    }

    // ================================================================ правка

    @Transactional
    fun create(me: UUID, req: MarketItemIn, communitySlug: String? = null): MarketItemOut {
        val community = (communitySlug ?: req.communitySlug)?.trim()?.takeIf { it.isNotEmpty() }?.let { marketCommunity(me, it) }
        val today = (em.createNativeQuery("select count(*) from market_item where seller_id = ?1 and created_at > now() - interval '1 day'")
            .setParameter(1, me).singleResult as Number).toLong()
        if (today >= PER_DAY) throw ApiException(429, "market_quota", "не больше $PER_DAY объявлений в сутки")
        val photos = photos(me, req.mediaIds.orEmpty())
        val id = UUID.randomUUID()
        em.createNativeQuery(
            """
            insert into market_item (id, seller_id, title, description, price, currency, category, condition, city, contacts, community_id)
            values (?1, ?2, ?3, ?4, nullif(?5, -1), ?6, ?7, ?8, nullif(?9, ''), nullif(?10, ''), cast(nullif(?11, '') as uuid))
            """.trimIndent(),
        ).setParameter(1, id).setParameter(2, me)
            .setParameter(3, title(req.title))
            .setParameter(4, description(req.description))
            // null Hibernate биндит как bytea: «договорная» = -1 → nullif
            .setParameter(5, (if (req.priceNegotiable == true) null else price(req.price)) ?: BigDecimal.ONE.negate())
            .setParameter(6, currency(req.currency ?: "RUB"))
            .setParameter(7, category(req.category ?: "other"))
            .setParameter(8, condition(req.condition ?: "used"))
            .setParameter(9, optional(req.city, MAX_CITY, "city") ?: "")
            .setParameter(10, optional(req.contacts, MAX_CONTACTS, "contacts") ?: "")
            .setParameter(11, community?.id?.toString() ?: "")
            .executeUpdate()
        attachments.attach(AttachmentService.Owner.MARKET, id, photos)
        return render(listOf(id), me).first()
    }

    @Transactional
    fun update(me: UUID, id: UUID, req: MarketItemIn): MarketItemOut {
        own(me, id)
        fun set(col: String, v: Any?) {
            em.createNativeQuery("update market_item set $col = ?2, updated_at = now() where id = ?1")
                .setParameter(1, id).setParameter(2, v).executeUpdate()
        }
        req.title?.let { set("title", title(it)) }
        req.description?.let { set("description", description(it)) }
        when {
            req.priceNegotiable == true -> em.createNativeQuery("update market_item set price = null, updated_at = now() where id = ?1")
                .setParameter(1, id).executeUpdate()
            req.price != null -> set("price", price(req.price)!!)
        }
        req.currency?.let { set("currency", currency(it)) }
        req.category?.let { set("category", category(it)) }
        req.condition?.let { set("condition", condition(it)) }
        req.city?.let { v ->
            val c = optional(v, MAX_CITY, "city")
            if (c == null) em.createNativeQuery("update market_item set city = null where id = ?1").setParameter(1, id).executeUpdate() else set("city", c)
        }
        req.contacts?.let { v ->
            val c = optional(v, MAX_CONTACTS, "contacts")
            if (c == null) em.createNativeQuery("update market_item set contacts = null where id = ?1").setParameter(1, id).executeUpdate() else set("contacts", c)
        }
        req.communitySlug?.let { v ->
            val c = v.trim().takeIf { it.isNotEmpty() }?.let { marketCommunity(me, it) }
            em.createNativeQuery("update market_item set community_id = cast(nullif(?2, '') as uuid), updated_at = now() where id = ?1")
                .setParameter(1, id).setParameter(2, c?.id?.toString() ?: "").executeUpdate()
        }
        req.mediaIds?.let { ids ->
            val photos = photos(me, ids)
            em.createNativeQuery("delete from media_attachment where owner_type = 'market' and owner_id = ?1").setParameter(1, id).executeUpdate()
            attachments.attach(AttachmentService.Owner.MARKET, id, photos)
        }
        return render(listOf(id), me).first()
    }

    @Transactional
    fun setStatus(me: UUID, id: UUID, raw: String?): MarketItemOut {
        own(me, id)
        val st = raw?.trim()?.lowercase()
        if (st == null || st !in STATUSES) throw ApiException.badRequest("invalid_status", "status: active, reserved или sold")
        em.createNativeQuery("update market_item set status = ?2, updated_at = now() where id = ?1")
            .setParameter(1, id).setParameter(2, st).executeUpdate()
        if (st == "sold") badges.mark(me) // «Барыга»
        return render(listOf(id), me).first()
    }

    /** «Поднять»: снова наверх ленты, не чаще раза в сутки. */
    @Transactional
    fun bump(me: UUID, id: UUID): MarketItemOut {
        own(me, id)
        val n = em.createNativeQuery(
            "update market_item set bumped_at = now() where id = ?1 and status = 'active' and bumped_at < now() - interval '24 hours'",
        ).setParameter(1, id).executeUpdate()
        if (n == 0) throw ApiException(429, "too_often", "поднять можно активное объявление раз в сутки")
        return render(listOf(id), me).first()
    }

    @Transactional
    fun delete(me: UUID, id: UUID) {
        own(me, id)
        em.createNativeQuery("update market_item set status = 'deleted', updated_at = now() where id = ?1").setParameter(1, id).executeUpdate()
    }

    /**
     * Админ сообщества убирает объявление со своей барахолки. Объявление не удаляется:
     * остаётся у продавца и в общей ленте. Продавец может и сам убрать — PATCH communitySlug: "".
     */
    @Transactional
    fun removeFromCommunity(me: UUID, slug: String, id: UUID) {
        val c = communities.bySlug(slug)
        val n = em.createNativeQuery("select seller_id from market_item where id = ?1 and community_id = ?2 and status <> 'deleted'", UUID::class.java)
            .setParameter(1, id).setParameter(2, c.id).resultList.firstOrNull() as UUID?
            ?: throw ApiException.notFound("в барахолке сообщества такого объявления нет")
        if (n != me && communities.roleOf(c.id, me) !in setOf("admin", "owner")) {
            throw ApiException.forbidden("убрать объявление может продавец или админ сообщества")
        }
        em.createNativeQuery("update market_item set community_id = null, updated_at = now() where id = ?1").setParameter(1, id).executeUpdate()
    }

    @Transactional
    fun favorite(me: UUID, id: UUID, on: Boolean): MarketItemOut {
        sellerOf(id)
        if (on) {
            em.createNativeQuery("insert into market_favorite (item_id, user_id) values (?1, ?2) on conflict do nothing")
                .setParameter(1, id).setParameter(2, me).executeUpdate()
        } else {
            em.createNativeQuery("delete from market_favorite where item_id = ?1 and user_id = ?2")
                .setParameter(1, id).setParameter(2, me).executeUpdate()
        }
        return render(listOf(id), me).first()
    }

    /** «Написать продавцу»: личка с продавцом + первое сообщение со ссылкой на объявление. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun contact(me: UUID, id: UUID, text: String?): MarketContactOut {
        val row = em.createNativeQuery("select seller_id, title, status from market_item where id = ?1")
            .setParameter(1, id).resultList.firstOrNull() as Array<Any?>? ?: throw ApiException.notFound("объявление не найдено")
        val seller = row[0] as UUID
        if (row[2] == "deleted") throw ApiException.notFound("объявление не найдено")
        if (seller == me) throw ApiException.badRequest("own_item", "это твоё объявление")
        val chatId = chatAdmin.directChatId(me, seller)
        val first = text?.trim()?.takeIf { it.isNotEmpty() }?.take(MessageService.MAX_BODY_LEN - 200) ?: "Привет! Ещё продаётся?"
        val body = "$first\n\n🏷️ «${row[1]}» — /market/$id"
        val ack = messages.send(chatId, me, body, null, UUID.randomUUID(), "market")
        return MarketContactOut(chatId, ack.id)
    }

    // ------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun render(ids: List<UUID>, me: UUID): List<MarketItemOut> {
        if (ids.isEmpty()) return emptyList()
        val rows = em.createNativeQuery(
            """
            select m.id, m.seller_id, m.title, m.description, m.price, m.currency, m.category, m.condition, m.city, m.contacts,
                   m.status, m.views, m.created_at, m.updated_at, m.bumped_at,
                   (select count(*) from market_favorite f where f.item_id = m.id),
                   exists(select 1 from market_favorite f where f.item_id = m.id and f.user_id = ?2),
                   m.community_id,
                   (select cm.role from community_member cm where cm.community_id = m.community_id and cm.user_id = ?2 and cm.left_at is null)
            from market_item m where m.id in (?1) and m.status <> 'deleted'
            """.trimIndent(),
        ).setParameter(1, ids).setParameter(2, me).resultList as List<Array<Any?>>
        val sellers = profiles.shorts(rows.map { it[1] as UUID })
        val photos = attachments.load(AttachmentService.Owner.MARKET, ids)
        val commIds = rows.mapNotNull { it[17] as UUID? }.distinct()
        val comms = if (commIds.isEmpty()) emptyMap() else
            Community.list("id in ?1 and isDeleted = false", commIds).associate { it.id to PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) }
        val byId = rows.associateBy { it[0] as UUID }
        return ids.mapNotNull { id ->
            val r = byId[id] ?: return@mapNotNull null
            val seller = r[1] as UUID
            val price = r[4]?.let { if (it is BigDecimal) it else BigDecimal(it.toString()) }
            val currency = r[5] as String
            val category = r[6] as String
            val bumpedAt = toInstant(r[14])
            val pics = photos[id] ?: emptyList()
            MarketItemOut(
                id = id,
                seller = sellers[seller],
                title = r[2] as String,
                description = r[3] as String,
                price = price,
                currency = currency,
                priceText = priceText(price, currency),
                category = category,
                categoryTitle = CATEGORIES[category]?.first ?: category,
                condition = r[7] as String,
                city = r[8] as String?,
                contacts = r[9] as String?,
                status = r[10] as String,
                photos = pics,
                cover = pics.firstOrNull()?.url,
                views = (r[11] as Number).toLong(),
                favorites = (r[15] as Number).toLong(),
                my = MyMarketOut(
                    seller == me, r[16] == true,
                    canRemoveFromCommunity = r[17] != null && (seller == me || r[18] in setOf("admin", "owner")),
                ),
                community = (r[17] as UUID?)?.let { comms[it] },
                createdAt = toInstant(r[12]),
                updatedAt = toInstant(r[13]),
                bumpedAt = bumpedAt,
                canBumpAt = if (seller == me) bumpedAt.plus(BUMP_EVERY) else null,
            )
        }
    }

    private fun priceText(price: BigDecimal?, currency: String): String {
        if (price == null) return "договорная"
        if (price.signum() == 0) return "даром"
        val fmt = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.forLanguageTag("ru")).also { it.groupingSeparator = ' ' })
        return fmt.format(price) + " " + (CURRENCIES[currency] ?: currency)
    }

    private fun photos(me: UUID, ids: List<UUID>): List<Media> {
        val unique = ids.distinct()
        if (unique.size > MAX_PHOTOS) throw ApiException.badRequest("too_many_media", "не больше $MAX_PHOTOS фото")
        return unique.map { media.requireOwned(it, me) }.onEach {
            if (!it.contentType.startsWith("image/")) throw ApiException.badRequest("not_image", "к объявлению — только картинки")
        }
    }

    /** Сообщество, куда я могу выставить объявление. */
    private fun marketCommunity(me: UUID, slug: String): Community {
        val c = communities.bySlug(slug)
        communities.requireRole(c, me, "member") // зеркало Telegram → mirror_readonly
        if ("market" !in communities.parseSections(c.sections)) {
            throw ApiException(403, "market_off", "в этом сообществе нет раздела «Барахолка»")
        }
        return c
    }

    private fun sellerOf(id: UUID): UUID {
        val row = em.createNativeQuery("select seller_id from market_item where id = ?1 and status <> 'deleted'", UUID::class.java)
            .setParameter(1, id).resultList.firstOrNull()
        return row as UUID? ?: throw ApiException.notFound("объявление не найдено")
    }

    private fun own(me: UUID, id: UUID) {
        if (sellerOf(id) != me) throw ApiException.forbidden("это не твоё объявление")
    }

    private fun title(v: String?): String {
        val t = v?.trim().orEmpty()
        if (t.length < 3 || t.length > MAX_TITLE) throw ApiException.badRequest("invalid_title", "название: 3–$MAX_TITLE символов")
        return t
    }

    private fun description(v: String?): String {
        val t = v?.trim().orEmpty()
        if (t.length > MAX_DESCRIPTION) throw ApiException.badRequest("invalid_description", "описание длиннее $MAX_DESCRIPTION")
        return t
    }

    private fun price(v: BigDecimal?): BigDecimal? {
        if (v == null) return null
        if (v.signum() < 0 || v > MAX_PRICE) throw ApiException.badRequest("invalid_price", "цена: от 0 до $MAX_PRICE")
        return v.setScale(2, java.math.RoundingMode.HALF_UP)
    }

    private fun currency(v: String): String {
        val c = v.trim().uppercase()
        if (c !in CURRENCIES) throw ApiException.badRequest("invalid_currency", "валюта: ${CURRENCIES.keys.joinToString()}")
        return c
    }

    private fun category(v: String): String {
        val c = v.trim().lowercase()
        if (c !in CATEGORIES) throw ApiException.badRequest("invalid_category", "категория: ${CATEGORIES.keys.joinToString()}")
        return c
    }

    private fun condition(v: String): String {
        val c = v.trim().lowercase()
        if (c !in CONDITIONS) throw ApiException.badRequest("invalid_condition", "condition: new, used или broken")
        return c
    }

    private fun optional(v: String?, max: Int, field: String): String? {
        val t = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length > max) throw ApiException.badRequest("invalid_$field", "$field длиннее $max")
        return t
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}
