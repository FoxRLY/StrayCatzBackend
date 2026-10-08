package org.example.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.example.auth.AuthTicket
import org.example.auth.KeycloakClient
import org.example.auth.Staff
import org.example.bus.EventBus
import org.example.bus.PointerKinds
import org.example.domain.Community
import org.example.proto.Envelope
import org.example.rest.ApiException
import org.example.rest.ModActionIn
import org.example.rest.ModActionKindOut
import org.example.rest.ModActionOut
import org.example.rest.ModActionPageOut
import org.example.rest.ModCommunityOut
import org.example.rest.ModReasonCountOut
import org.example.rest.ModReportOut
import org.example.rest.ModStatsOut
import org.example.rest.ModTargetStateOut
import org.example.rest.ModTicketDetailOut
import org.example.rest.ModTicketOut
import org.example.rest.ModTicketPageOut
import org.example.rest.ModUserOut
import org.example.rest.MyModEventOut
import org.example.rest.MyReportOut
import org.example.rest.MyReportsPageOut
import org.example.rest.MyStandingOut
import org.example.rest.PostCommunityOut
import org.example.rest.ReportOut
import org.example.rest.ReportReasonOut
import org.example.rest.SanctionOut
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Модерация «как в ВК», без излишеств.
 *
 *  1. Жалоба (кнопка «Пожаловаться» на чём угодно): причина + комментарий. Жалобы на одну и ту же
 *     сущность копятся в одном тикете (`collecting`). Набралось [threshold] разных людей
 *     (по умолчанию 5; на сообщение в чате на N человек — не больше N−1, в личке — 1) —
 *     тикет попадает в очередь модераторов (`open`). Жалоба модератора открывает тикет сразу.
 *  2. Модератор берёт тикет (`in_progress`), видит снимок «как было в момент жалобы», текущее
 *     состояние, все жалобы с комментариями, историю нарушений автора — и выносит решение:
 *     `no_violation` (тикет `dismissed`) или `violation` с мерами (тикет `resolved`).
 *  3. Меры (их же можно применять напрямую, без тикета):
 *     - контент: удалить / вернуть;
 *     - человек: предупредить, ограничить (только читать) на срок или навсегда, заблокировать
 *       (не может ничего, его сокеты закрываются) на срок или навсегда, сбросить профиль;
 *     - сообщество: заморозить (только читать) / заблокировать (скрыто ото всех).
 *     Аккаунты и сообщества не удаляются — всё обратимо.
 *  4. Модератор не может тронуть модератора, диктатора не может тронуть никто; себя — нельзя.
 *  5. Каждое действие пишется в журнал `mod_action`.
 */
@ApplicationScoped
class ModerationService(
    private val em: EntityManager,
    private val mapper: ObjectMapper,
    private val bus: EventBus,
    private val profiles: UserProfileService,
    private val notifications: NotificationService,
    private val media: MediaService,
    private val gate: ModerationGate,
    private val keycloak: KeycloakClient,
    @ConfigProperty(name = "straycatz.moderation.report-threshold", defaultValue = "5") private val threshold: Int,
    @ConfigProperty(name = "straycatz.moderation.reports-per-day", defaultValue = "30") private val reportsPerDay: Int,
) {
    companion object {
        val TYPES = setOf("user", "community", "post", "comment", "message", "market", "track", "video", "guestbook", "event")
        val CONTENT = setOf("post", "comment", "message", "market", "track", "video", "guestbook", "event")

        val REASONS: LinkedHashMap<String, String> = linkedMapOf(
            "spam" to "спам",
            "abuse" to "оскорбления и травля",
            "hate" to "разжигание ненависти",
            "nsfw" to "контент 18+",
            "violence" to "насилие и жестокость",
            "illegal" to "запрещено законом",
            "fraud" to "мошенничество",
            "impersonation" to "выдаёт себя за другого",
            "self_harm" to "суицид и селфхарм",
            "copyright" to "нарушение авторских прав",
            "other" to "другое",
        )

        /** action → (название, нужна причина, есть срок). */
        val ACTIONS: LinkedHashMap<String, Triple<String, Boolean, Boolean>> = linkedMapOf(
            "remove_content" to Triple("удалить", true, false),
            "restore_content" to Triple("вернуть удалённое", false, false),
            "warn_user" to Triple("предупредить", true, false),
            "restrict_user" to Triple("ограничить (только читать)", true, true),
            "unrestrict_user" to Triple("снять ограничение", false, false),
            "ban_user" to Triple("заблокировать аккаунт", true, true),
            "unban_user" to Triple("разблокировать аккаунт", false, false),
            "reset_profile" to Triple("сбросить профиль", true, false),
            "freeze_community" to Triple("заморозить сообщество", true, true),
            "unfreeze_community" to Triple("разморозить сообщество", false, false),
            "block_community" to Triple("заблокировать сообщество", true, false),
            "unblock_community" to Triple("разблокировать сообщество", false, false),
        )
        private val USER_ACTIONS = setOf("warn_user", "restrict_user", "unrestrict_user", "ban_user", "unban_user", "reset_profile")
        private val COMMUNITY_ACTIONS = setOf("freeze_community", "unfreeze_community", "block_community", "unblock_community")

        /** Подписи для журнала (служебные действия тоже). */
        private val LOG_TITLES = ACTIONS.mapValues { it.value.first } + mapOf(
            "ticket_resolve" to "тикет: нарушение",
            "ticket_dismiss" to "тикет: нарушения нет",
            "ticket_reopen" to "тикет открыт заново",
            "decree_issue" to "указ диктатора выдан",
            "decree_revoke" to "указ диктатора отозван",
            "grant_moderator" to "назначен модератором",
            "revoke_moderator" to "снят с модераторов",
            "grant_dictator" to "назначен диктатором",
            "revoke_dictator" to "снят с диктаторов",
        )

        const val MAX_REASON = 500
        const val MAX_COMMENT = 1000
        const val MAX_NOTE = 2000
        const val MAX_PAGE = 100
        const val MAX_HOURS = 24 * 365 * 10
        val REREPORT_COOLDOWN: Duration = Duration.ofDays(30)

        // уведомления человеку
        const val N_WARNING = "mod_warning"
        const val N_CONTENT_REMOVED = "mod_content_removed"
        const val N_CONTENT_RESTORED = "mod_content_restored"
        const val N_RESTRICTED = "mod_restricted"
        const val N_UNRESTRICTED = "mod_unrestricted"
        const val N_UNBANNED = "mod_unbanned"
        const val N_PROFILE_RESET = "mod_profile_reset"
        const val N_COMMUNITY_FROZEN = "mod_community_frozen"
        const val N_COMMUNITY_UNFROZEN = "mod_community_unfrozen"
        const val N_COMMUNITY_BLOCKED = "mod_community_blocked"
        const val N_COMMUNITY_UNBLOCKED = "mod_community_unblocked"
        const val N_REPORT_RESOLVED = "report_resolved"
    }

    /** Объект жалобы как он есть сейчас. */
    private data class Target(
        val type: String,
        val id: UUID,
        val ownerId: UUID?,
        val communityId: UUID?,
        val chatId: UUID?,
        val removed: Boolean,
        val snapshot: Map<String, Any?>,
    )

    private data class Ticket(
        val id: UUID, val type: String, val targetId: UUID, val ownerId: UUID?, val communityId: UUID?, val status: String,
        val assigneeId: UUID?,
    )

    // ================================================================ жалобы

    fun reasons(): List<ReportReasonOut> = REASONS.map { ReportReasonOut(it.key, it.value) }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun report(me: AuthTicket, typeRaw: String?, targetId: UUID?, reasonRaw: String?, commentRaw: String?): ReportOut {
        val type = typeRaw?.trim()?.lowercase()
        if (type == null || type !in TYPES) throw ApiException.badRequest("invalid_target_type", "targetType: ${TYPES.joinToString()}")
        val id = targetId ?: throw ApiException.badRequest("invalid_target", "нужен targetId")
        val reason = reasonRaw?.trim()?.lowercase()
        if (reason == null || reason !in REASONS) throw ApiException.badRequest("invalid_reason", "reason: ${REASONS.keys.joinToString()}")
        val comment = commentRaw?.trim()?.takeIf { it.isNotEmpty() }
        if (comment != null && comment.length > MAX_COMMENT) throw ApiException.badRequest("invalid_comment", "комментарий длиннее $MAX_COMMENT")

        val today = count("select count(*) from mod_report where reporter_id = ?1 and created_at > now() - interval '1 day'", me.userId)
        if (today >= reportsPerDay) throw ApiException(429, "report_quota", "не больше $reportsPerDay жалоб в сутки")

        val t = target(type, id)
        if (t == null || t.removed) throw ApiException.notFound("не найдено или уже удалено")
        if (t.ownerId == me.userId) throw ApiException.badRequest("own_content", "на себя жаловаться нельзя")
        if (type == "message") {
            val member = count("select count(*) from chat_member where chat_id = ?1 and user_id = ?2 and not is_deleted", t.chatId!!, me.userId)
            if (member == 0L) throw ApiException.notFound("не найдено или уже удалено")
        }

        // одна очередь на объект: жалобы на него идут по одной
        em.createNativeQuery("select 1 from (select pg_advisory_xact_lock(hashtext(?1))) as l").setParameter(1, "report:$type:$id").singleResult

        // недавно уже жаловался, и нарушения не нашли — повторно не принимаем
        val recentlyDismissed = count(
            """
            select count(*) from mod_report r join mod_ticket t on t.id = r.ticket_id
            where r.reporter_id = ?1 and t.target_type = ?2 and t.target_id = ?3 and t.status = 'dismissed'
              and t.resolved_at > now() - interval '30 days'
            """.trimIndent(), me.userId, type, id,
        )
        if (recentlyDismissed > 0) return ReportOut(reported = true, alreadyReported = true)

        val ticketId = liveTicket(type, id)?.id ?: run {
            val tid = UUID.randomUUID()
            em.createNativeQuery(
                """
                insert into mod_ticket (id, target_type, target_id, owner_id, community_id, threshold, snapshot)
                values (?1, ?2, ?3, cast(nullif(?4, '') as uuid), cast(nullif(?5, '') as uuid), ?6, cast(?7 as jsonb))
                """.trimIndent(),
            ).setParameter(1, tid).setParameter(2, type).setParameter(3, id)
                .setParameter(4, t.ownerId?.toString() ?: "").setParameter(5, t.communityId?.toString() ?: "")
                .setParameter(6, thresholdFor(t)).setParameter(7, mapper.writeValueAsString(t.snapshot))
                .executeUpdate()
            tid
        }
        val n = em.createNativeQuery(
            """
            insert into mod_report (id, ticket_id, reporter_id, reason, comment) values (?1, ?2, ?3, ?4, nullif(?5, ''))
            on conflict (ticket_id, reporter_id) do nothing
            """.trimIndent(),
        ).setParameter(1, UUID.randomUUID()).setParameter(2, ticketId).setParameter(3, me.userId)
            .setParameter(4, reason).setParameter(5, comment ?: "").executeUpdate()
        if (n == 0) return ReportOut(reported = true, alreadyReported = true)

        // пересчёт; порог набран (или жалуется модератор) — в очередь
        em.createNativeQuery(
            """
            update mod_ticket t set
                reports = r.cnt,
                top_reason = r.top,
                updated_at = now(),
                status = case when t.status = 'collecting' and (r.cnt >= t.threshold or ?2) then 'open' else t.status end,
                opened_at = case when t.status = 'collecting' and (r.cnt >= t.threshold or ?2) then now() else t.opened_at end
            from (
                select count(*) as cnt,
                       (select reason from mod_report where ticket_id = ?1 group by reason order by count(*) desc, max(created_at) desc limit 1) as top
                from mod_report where ticket_id = ?1
            ) r
            where t.id = ?1
            """.trimIndent(),
        ).setParameter(1, ticketId).setParameter(2, me.isModerator).executeUpdate()
        return ReportOut(reported = true, alreadyReported = false)
    }

    /** Мои жалобы и чем они кончились. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun myReports(me: UUID, before: Instant?, limit: Int): MyReportsPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        val rows = em.createNativeQuery(
            """
            select r.id, t.target_type, t.target_id, r.reason, r.comment, r.created_at, t.status, t.verdict
            from mod_report r join mod_ticket t on t.id = r.ticket_id
            where r.reporter_id = ?1 and r.created_at < ?2 order by r.created_at desc limit ?3
            """.trimIndent(),
        ).setParameter(1, me).setParameter(2, before ?: Instant.now().plusSeconds(60)).setParameter(3, size + 1)
            .resultList as List<Array<Any?>>
        val items = rows.take(size).map {
            val outcome = when (it[6]) {
                "resolved" -> "violation"
                "dismissed" -> "no_violation"
                else -> "pending"
            }
            MyReportOut(
                it[0] as UUID, it[1] as String, it[2] as UUID, it[3] as String, REASONS[it[3] as String] ?: it[3] as String,
                it[4] as String?, toInstant(it[5]), outcome,
            )
        }
        return MyReportsPageOut(items, rows.size > size, if (rows.size > size) items.lastOrNull()?.createdAt else null)
    }

    /** Моё положение: ограничение и последние меры против меня (бан сюда не дойдёт — забаненному 403). */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun myStanding(me: UUID): MyStandingOut {
        val u = (em.createNativeQuery("select restricted_until, restrict_reason from users where id = ?1")
            .setParameter(1, me).resultList as List<Array<Any?>>).firstOrNull()
        val restricted = u?.get(0)?.let { toInstant(it) }?.takeIf { it.isAfter(Instant.now()) }
        val rows = em.createNativeQuery(
            """
            select action, target_type, target_id, reason, until, created_at from mod_action
            where user_id = ?1 and action in ('warn_user', 'remove_content', 'restrict_user', 'reset_profile', 'unrestrict_user', 'restore_content')
            order by created_at desc limit 20
            """.trimIndent(),
        ).setParameter(1, me).resultList as List<Array<Any?>>
        return MyStandingOut(
            restriction = restricted?.let { sanction(it, u[1] as String?) },
            recent = rows.map {
                val until = it[4]?.let { v -> toInstant(v) }
                MyModEventOut(it[0] as String, LOG_TITLES[it[0] as String] ?: it[0] as String, it[1] as String, it[2] as UUID,
                    it[3] as String?, until?.takeIf { u2 -> u2 < Staff.FOREVER }, toInstant(it[5]))
            },
        )
    }

    // ================================================================ очередь

    /**
     * status: open (по умолчанию: open + in_progress) / collecting / in_progress / resolved / dismissed / closed / all.
     * assigned: me / none. sort: oldest (по умолчанию) / reports / newest.
     */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun tickets(me: AuthTicket, status: String?, type: String?, assigned: String?, sort: String?, communityId: UUID?, ownerId: UUID?, offset: Int, limit: Int): ModTicketPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        val params = mutableListOf<Any>()
        fun p(v: Any): String { params += v; return "?${params.size}" }
        val where = mutableListOf<String>()
        where += when (status?.lowercase()) {
            null, "", "open" -> "t.status in ('open', 'in_progress')"
            "collecting", "in_progress", "resolved", "dismissed" -> "t.status = ${p(status.lowercase())}"
            "closed" -> "t.status in ('resolved', 'dismissed')"
            "all" -> "true"
            else -> throw ApiException.badRequest("invalid_status", "status: open, collecting, in_progress, resolved, dismissed, closed, all")
        }
        type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            if (it !in TYPES) throw ApiException.badRequest("invalid_target_type", "type: ${TYPES.joinToString()}")
            where += "t.target_type = ${p(it)}"
        }
        when (assigned?.lowercase()) {
            null, "" -> {}
            "me" -> where += "t.assignee_id = ${p(me.userId)}"
            "none" -> where += "t.assignee_id is null"
            else -> throw ApiException.badRequest("invalid_assigned", "assigned: me или none")
        }
        communityId?.let { where += "t.community_id = ${p(it)}" }
        ownerId?.let { where += "t.owner_id = ${p(it)}" }
        val order = when (sort?.lowercase()) {
            null, "", "oldest" -> "coalesce(t.opened_at, t.created_at) asc"
            "reports" -> "t.reports desc, coalesce(t.opened_at, t.created_at) asc"
            "newest" -> "coalesce(t.resolved_at, t.opened_at, t.created_at) desc"
            else -> throw ApiException.badRequest("invalid_sort", "sort: oldest, reports, newest")
        }
        val from = offset.coerceAtLeast(0)
        val q = em.createNativeQuery(
            "select t.id from mod_ticket t where ${where.joinToString(" and ")} order by $order, t.id limit ${p(size + 1)} offset ${p(from)}",
            UUID::class.java,
        )
        params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
        val ids = q.resultList as List<UUID>
        val items = renderTickets(ids.take(size))
        return ModTicketPageOut(items, ids.size > size, if (ids.size > size) from + size else null)
    }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun ticket(me: AuthTicket, id: UUID): ModTicketDetailOut {
        val t = renderTickets(listOf(id)).firstOrNull() ?: throw ApiException.notFound("тикет не найден")
        val now = target(t.targetType, t.targetId)
        val reports = em.createNativeQuery(
            """
            select r.id, r.reporter_id, r.reason, r.comment, r.created_at,
                   (select count(*) from mod_report r2 join mod_ticket t2 on t2.id = r2.ticket_id
                    where r2.reporter_id = r.reporter_id and t2.status = 'dismissed')
            from mod_report r where r.ticket_id = ?1 order by r.created_at limit 300
            """.trimIndent(),
        ).setParameter(1, id).resultList as List<Array<Any?>>
        val reporters = profiles.shorts(reports.map { it[1] as UUID })
        val reasonCounts = reports.groupingBy { it[2] as String }.eachCount().entries.sortedByDescending { it.value }
            .map { ModReasonCountOut(it.key, REASONS[it.key] ?: it.key, it.value) }
        val ownerId = t.owner?.id
        val history = actions(
            "(a.target_type = ?1 and a.target_id = ?2) or a.ticket_id = ?3" + (if (ownerId != null) " or a.user_id = ?4" else ""),
            listOfNotNull(t.targetType, t.targetId, t.id, ownerId), 50,
        )
        return ModTicketDetailOut(
            ticket = t,
            target = ModTargetStateOut(
                exists = now != null,
                removed = now?.removed ?: true,
                current = now?.let { mapper.valueToTree<JsonNode>(it.snapshot) },
            ),
            reasons = reasonCounts,
            reports = reports.map {
                ModReportOut(
                    it[0] as UUID, reporters[it[1] as UUID], it[2] as String, REASONS[it[2] as String] ?: it[2] as String,
                    it[3] as String?, toInstant(it[4]), (it[5] as Number).toInt(),
                )
            },
            owner = ownerId?.let { userCardOrNull(it, withLists = false) },
            history = history,
            actions = actionsFor(t.targetType, now?.communityId ?: t.community?.id).map {
                val a = ACTIONS[it]!!
                ModActionKindOut(it, a.first, a.second, a.third)
            },
        )
    }

    /** Взять тикет себе. force — забрать у другого модератора. */
    @Transactional
    fun take(me: AuthTicket, id: UUID, force: Boolean): ModTicketDetailOut {
        val t = lockTicket(id)
        if (t.status !in setOf("collecting", "open", "in_progress")) throw ApiException.conflict("closed", "тикет уже закрыт")
        if (t.assigneeId != null && t.assigneeId != me.userId && !force) {
            throw ApiException.conflict("taken", "тикет уже взял другой модератор (force: true — забрать)")
        }
        em.createNativeQuery(
            """
            update mod_ticket set assignee_id = ?2, assigned_at = now(), status = 'in_progress',
                opened_at = coalesce(opened_at, now()), updated_at = now() where id = ?1
            """.trimIndent(),
        ).setParameter(1, id).setParameter(2, me.userId).executeUpdate()
        return ticket(me, id)
    }

    @Transactional
    fun release(me: AuthTicket, id: UUID): ModTicketDetailOut {
        val t = lockTicket(id)
        if (t.status != "in_progress") throw ApiException.conflict("not_taken", "тикет не в работе")
        if (t.assigneeId != me.userId && !me.isDictator) throw ApiException.forbidden("тикет взял другой модератор")
        em.createNativeQuery("update mod_ticket set assignee_id = null, assigned_at = null, status = 'open', updated_at = now() where id = ?1")
            .setParameter(1, id).executeUpdate()
        return ticket(me, id)
    }

    /** Решение по тикету. */
    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun resolve(me: AuthTicket, id: UUID, verdictRaw: String?, actions: List<ModActionIn>, noteRaw: String?): ModTicketDetailOut {
        val t = lockTicket(id)
        if (t.status !in setOf("collecting", "open", "in_progress")) throw ApiException.conflict("closed", "тикет уже закрыт")
        if (t.assigneeId != null && t.assigneeId != me.userId && !me.isDictator) {
            throw ApiException.conflict("taken", "тикет взял другой модератор — сначала забери его (take с force)")
        }
        val verdict = verdictRaw?.trim()?.lowercase()
        if (verdict != "violation" && verdict != "no_violation") throw ApiException.badRequest("invalid_verdict", "verdict: violation или no_violation")
        val note = noteRaw?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > MAX_NOTE) throw ApiException.badRequest("invalid_note", "заметка длиннее $MAX_NOTE")
        if (verdict == "violation" && actions.isEmpty()) throw ApiException.badRequest("actions_required", "при нарушении нужна хотя бы одна мера")
        if (verdict == "no_violation" && actions.isNotEmpty()) throw ApiException.badRequest("no_actions", "без нарушения меры не применяются")

        actions.forEach { a ->
            act(me, a.copy(targetType = a.targetType ?: t.type, targetId = a.targetId ?: t.targetId, ticketId = id))
        }
        em.createNativeQuery(
            """
            update mod_ticket set status = ?2, verdict = ?3, note = nullif(?4, ''), resolved_by = ?5, resolved_at = now(),
                assignee_id = coalesce(assignee_id, ?5), updated_at = now() where id = ?1
            """.trimIndent(),
        ).setParameter(1, id).setParameter(2, if (verdict == "violation") "resolved" else "dismissed")
            .setParameter(3, verdict).setParameter(4, note ?: "").setParameter(5, me.userId).executeUpdate()
        log(me.userId, if (verdict == "violation") "ticket_resolve" else "ticket_dismiss", t.type, t.targetId, t.ownerId, t.communityId, id, note, null)

        // жалобщикам — «по вашей жалобе …» (без подробностей)
        val reporters = em.createNativeQuery("select reporter_id from mod_report where ticket_id = ?1", UUID::class.java)
            .setParameter(1, id).resultList as List<UUID>
        notifications.notifyMany(
            reporters, N_REPORT_RESOLVED, null,
            mapOf("ticketId" to id, "targetType" to t.type, "targetId" to t.targetId, "verdict" to verdict),
        )
        return ticket(me, id)
    }

    /** Открыть закрытый тикет заново (например, решение было ошибочным). */
    @Transactional
    fun reopen(me: AuthTicket, id: UUID): ModTicketDetailOut {
        val t = lockTicket(id)
        if (t.status !in setOf("resolved", "dismissed")) throw ApiException.conflict("not_closed", "тикет и так открыт")
        if (liveTicket(t.type, t.targetId) != null) throw ApiException.conflict("live_exists", "на этот объект уже есть открытый тикет")
        em.createNativeQuery(
            """
            update mod_ticket set status = 'in_progress', assignee_id = ?2, assigned_at = now(), verdict = null,
                resolved_by = null, resolved_at = null, opened_at = coalesce(opened_at, now()), updated_at = now() where id = ?1
            """.trimIndent(),
        ).setParameter(1, id).setParameter(2, me.userId).executeUpdate()
        log(me.userId, "ticket_reopen", t.type, t.targetId, t.ownerId, t.communityId, id, null, null)
        return ticket(me, id)
    }

    // ================================================================ меры

    /** Одна мера. Возвращает запись журнала. */
    @Transactional
    fun act(me: AuthTicket, req: ModActionIn): ModActionOut {
        val action = req.action?.trim()?.lowercase()
        if (action == null || action !in ACTIONS) throw ApiException.badRequest("invalid_action", "action: ${ACTIONS.keys.joinToString()}")
        val type = req.targetType?.trim()?.lowercase()
        if (type == null || type !in TYPES) throw ApiException.badRequest("invalid_target_type", "targetType: ${TYPES.joinToString()}")
        val targetId = req.targetId ?: throw ApiException.badRequest("invalid_target", "нужен targetId")
        val (title, needsReason, hasDuration) = ACTIONS[action]!!
        val reason = req.reason?.trim()?.takeIf { it.isNotEmpty() }
        if (needsReason && reason == null) throw ApiException.badRequest("reason_required", "для «$title» нужна причина")
        if (reason != null && reason.length > MAX_REASON) throw ApiException.badRequest("invalid_reason", "причина длиннее $MAX_REASON")
        val until: Instant? = if (!hasDuration) null else {
            val h = req.hours ?: 0
            if (h < 0 || h > MAX_HOURS) throw ApiException.badRequest("invalid_hours", "hours: 1–$MAX_HOURS, 0 или null — навсегда")
            if (h == 0) Staff.FOREVER else Instant.now().plus(Duration.ofHours(h.toLong()))
        }
        req.ticketId?.let { tid ->
            if (count("select count(*) from mod_ticket where id = ?1", tid) == 0L) throw ApiException.notFound("тикет не найден")
        }

        val t = target(type, targetId) ?: if (action == "restore_content" || action == "unblock_community") {
            null
        } else throw ApiException.notFound("объект не найден")

        val logId = when {
            action == "remove_content" || action == "restore_content" -> {
                if (type !in CONTENT) throw ApiException.badRequest("not_content", "это не контент — удалять можно записи, комментарии, сообщения, объявления, треки, видео, записи гостевой, события")
                content(me, action, type, targetId, t, reason, req.ticketId)
            }
            action in USER_ACTIONS -> {
                val userId = if (type == "user") targetId else t?.ownerId
                    ?: throw ApiException.badRequest("no_owner", "у этого объекта нет автора")
                user(me, action, userId, type, targetId, until, reason, req.ticketId)
            }
            else -> {
                val communityId = if (type == "community") targetId else t?.communityId
                    ?: throw ApiException.badRequest("no_community", "это было не в сообществе")
                community(me, action, communityId, type, targetId, until, reason, req.ticketId)
            }
        }
        return actions("a.id = ?1", listOf(logId), 1).first()
    }

    // ---------------------------------------------------------------- контент

    private fun content(me: AuthTicket, action: String, type: String, id: UUID, t: Target?, reason: String?, ticketId: UUID?): UUID {
        val owner = t?.ownerId ?: ownerOfRemoved(type, id)
        owner?.let { guardUser(me, it) }
        if (action == "remove_content") {
            if (t == null || t.removed) throw ApiException.conflict("already_removed", "уже удалено")
            when (type) {
                "post" -> exec("update post set is_deleted = true, deleted_at = now() where id = ?1", id)
                "comment" -> exec("update post_comment set deleted_at = now() where id = ?1", id)
                "message" -> {
                    exec("update message set deleted_at = now() where id = ?1", id)
                    bus.publishPointerToChat(t.chatId!!, PointerKinds.MESSAGE_UPDATED, id.toString())
                }
                "market" -> exec("update market_item set status = 'deleted', updated_at = now() where id = ?1", id)
                "track" -> exec("update track set deleted_at = now() where id = ?1", id)
                "guestbook" -> exec("update room_guestbook_entry set deleted_at = now() where id = ?1", id)
                "event" -> exec("update community_event set cancelled_at = now() where id = ?1", id)
                "video" -> {
                    // ролик в записи — удаляем запись; загруженный без записи — убираем из «моих видео» у всех
                    val postId = t.snapshot["postId"] as UUID?
                    if (postId != null) exec("update post set is_deleted = true, deleted_at = now() where id = ?1", postId)
                    exec("delete from user_video where media_id = ?1", id)
                }
            }
            owner?.let {
                notifications.notify(it, N_CONTENT_REMOVED, null,
                    mapOf("targetType" to type, "targetId" to id, "reason" to reason, "preview" to preview(t.snapshot)))
            }
        } else {
            // вернуть можно только то, что удалил модератор (и ещё не вернули)
            val last = em.createNativeQuery(
                "select action from mod_action where target_type = ?1 and target_id = ?2 and action in ('remove_content', 'restore_content') order by created_at desc limit 1",
            ).setParameter(1, type).setParameter(2, id).resultList.firstOrNull() as String?
            if (last != "remove_content") throw ApiException.conflict("not_removed_by_mod", "вернуть можно только удалённое модератором")
            when (type) {
                "post" -> exec("update post set is_deleted = false, deleted_at = null where id = ?1", id)
                "comment" -> exec("update post_comment set deleted_at = null where id = ?1", id)
                "message" -> {
                    exec("update message set deleted_at = null where id = ?1", id)
                    val chatId = em.createNativeQuery("select chat_id from message where id = ?1", UUID::class.java)
                        .setParameter(1, id).resultList.firstOrNull() as UUID?
                    chatId?.let { bus.publishPointerToChat(it, PointerKinds.MESSAGE_UPDATED, id.toString()) }
                }
                "market" -> exec("update market_item set status = 'active', updated_at = now() where id = ?1", id)
                "track" -> exec("update track set deleted_at = null where id = ?1", id)
                "guestbook" -> exec("update room_guestbook_entry set deleted_at = null where id = ?1", id)
                "event" -> exec("update community_event set cancelled_at = null where id = ?1", id)
                "video" -> {
                    val postId = em.createNativeQuery(
                        "select a.owner_id from media_attachment a where a.owner_type = 'post' and a.media_id = ?1 limit 1", UUID::class.java,
                    ).setParameter(1, id).resultList.firstOrNull() as UUID?
                    if (postId != null) exec("update post set is_deleted = false, deleted_at = null where id = ?1", postId)
                    else em.createNativeQuery(
                        "insert into user_video (user_id, media_id) select owner_id, id from media where id = ?1 on conflict do nothing",
                    ).setParameter(1, id).executeUpdate()
                }
            }
            owner?.let { notifications.notify(it, N_CONTENT_RESTORED, null, mapOf("targetType" to type, "targetId" to id)) }
        }
        val communityId = t?.communityId
        return log(me.userId, action, type, id, owner, communityId, ticketId, reason, null)
    }

    // ---------------------------------------------------------------- человек

    @Suppress("UNCHECKED_CAST")
    private fun user(me: AuthTicket, action: String, userId: UUID, type: String, targetId: UUID, until: Instant?, reason: String?, ticketId: UUID?): UUID {
        guardUser(me, userId)
        val now = Instant.now()
        val row = (em.createNativeQuery("select banned_until, restricted_until, username from users where id = ?1")
            .setParameter(1, userId).resultList as List<Array<Any?>>).firstOrNull() ?: throw ApiException.notFound("человек не найден")
        val banned = row[0]?.let { toInstant(it) }?.isAfter(now) == true
        val restricted = row[1]?.let { toInstant(it) }?.isAfter(now) == true
        when (action) {
            "warn_user" -> notifications.notify(userId, N_WARNING, null, mapOf("reason" to reason, "targetType" to type, "targetId" to targetId))
            "restrict_user" -> {
                em.createNativeQuery("update users set restricted_until = ?2, restrict_reason = ?3 where id = ?1")
                    .setParameter(1, userId).setParameter(2, until!!).setParameter(3, reason!!).executeUpdate()
                notifications.notify(userId, N_RESTRICTED, null, sanctionMap(until, reason))
                bus.publishToUsers(listOf(userId), Envelope(t = "account.restricted", d = mapper.valueToTree(sanctionMap(until, reason))))
            }
            "unrestrict_user" -> {
                if (!restricted) throw ApiException.conflict("not_restricted", "человек не ограничен")
                exec("update users set restricted_until = null, restrict_reason = null where id = ?1", userId)
                notifications.notify(userId, N_UNRESTRICTED, null, emptyMap())
                bus.publishToUsers(listOf(userId), Envelope(t = "account.unrestricted", d = mapper.createObjectNode()))
            }
            "ban_user" -> {
                em.createNativeQuery("update users set banned_until = ?2, ban_reason = ?3 where id = ?1")
                    .setParameter(1, userId).setParameter(2, until!!).setParameter(3, reason!!).executeUpdate()
                // все соединения: кадр account.banned и закрыть (4410); refresh-токены — в утиль
                bus.kickUser(userId, Envelope(t = "account.banned", d = mapper.valueToTree(sanctionMap(until, reason))))
                runCatching { keycloak.logoutAllSessions(userId) }
                // выйти из голосовых каналов, чтобы не висел в списке
                exec("delete from voice_presence where user_id = ?1", userId)
            }
            "unban_user" -> {
                if (!banned) throw ApiException.conflict("not_banned", "человек не заблокирован")
                exec("update users set banned_until = null, ban_reason = null where id = ?1", userId)
                notifications.notify(userId, N_UNBANNED, null, emptyMap())
            }
            "reset_profile" -> {
                exec("update user_cosmetics set avatar = null, tagline = null where user_id = ?1", userId)
                em.createNativeQuery(
                    "update room set title = ?2, mood = null, about = null, sticker = null, wall_media_id = null, wall_image_url = null where owner_id = ?1",
                ).setParameter(1, userId).setParameter(2, row[2] as String).executeUpdate()
                notifications.notify(userId, N_PROFILE_RESET, null, mapOf("reason" to reason))
            }
        }
        gate.forget(userId)
        return log(me.userId, action, type, targetId, userId, null, ticketId, reason, until)
    }

    /** Модератор не трогает модераторов, диктатора не трогает никто, себя — нельзя. */
    private fun guardUser(me: AuthTicket, userId: UUID) {
        if (userId == me.userId) throw ApiException.forbidden("к себе меры не применяются")
        val role = em.createNativeQuery(
            "select coalesce((select role from staff_grant where user_id = ?1), (select staff_role from users where id = ?1))",
        ).setParameter(1, userId).singleResult as String?
        when (role) {
            Staff.DICTATOR -> throw ApiException.forbidden("верховного диктатора модерировать нельзя")
            Staff.MODERATOR -> if (!me.isDictator) throw ApiException.forbidden("модератора может модерировать только диктатор")
        }
    }

    // ---------------------------------------------------------------- сообщество

    @Suppress("UNCHECKED_CAST")
    private fun community(me: AuthTicket, action: String, communityId: UUID, type: String, targetId: UUID, until: Instant?, reason: String?, ticketId: UUID?): UUID {
        val row = (em.createNativeQuery("select owner_id, blocked_at, frozen_until, name, slug from community where id = ?1")
            .setParameter(1, communityId).resultList as List<Array<Any?>>).firstOrNull() ?: throw ApiException.notFound("сообщество не найдено")
        val owner = row[0] as UUID?
        owner?.let { guardUser(me, it) }
        val payload = mapOf("communityId" to communityId, "name" to row[3], "slug" to row[4])
        val admins = adminsOf(communityId)
        when (action) {
            "freeze_community" -> {
                em.createNativeQuery("update community set frozen_until = ?2, freeze_reason = ?3 where id = ?1")
                    .setParameter(1, communityId).setParameter(2, until!!).setParameter(3, reason!!).executeUpdate()
                notifications.notifyMany(admins, N_COMMUNITY_FROZEN, null, payload + sanctionMap(until, reason))
            }
            "unfreeze_community" -> {
                if (row[2]?.let { toInstant(it) }?.isAfter(Instant.now()) != true) throw ApiException.conflict("not_frozen", "сообщество не заморожено")
                exec("update community set frozen_until = null, freeze_reason = null where id = ?1", communityId)
                notifications.notifyMany(admins, N_COMMUNITY_UNFROZEN, null, payload)
            }
            "block_community" -> {
                if (row[1] != null) throw ApiException.conflict("already_blocked", "сообщество уже заблокировано")
                em.createNativeQuery("update community set blocked_at = now(), block_reason = ?2, is_deleted = true where id = ?1")
                    .setParameter(1, communityId).setParameter(2, reason!!).executeUpdate()
                notifications.notifyMany(admins, N_COMMUNITY_BLOCKED, null, payload + mapOf("reason" to reason))
            }
            "unblock_community" -> {
                if (row[1] == null) throw ApiException.conflict("not_blocked", "сообщество не заблокировано")
                exec("update community set blocked_at = null, block_reason = null, is_deleted = false where id = ?1", communityId)
                notifications.notifyMany(admins, N_COMMUNITY_UNBLOCKED, null, payload)
            }
        }
        return log(me.userId, action, type, targetId, owner, communityId, ticketId, reason, until)
    }

    private fun adminsOf(communityId: UUID): List<UUID> {
        @Suppress("UNCHECKED_CAST")
        return em.createNativeQuery(
            "select user_id from community_member where community_id = ?1 and left_at is null and role in ('owner', 'admin')", UUID::class.java,
        ).setParameter(1, communityId).resultList as List<UUID>
    }

    // ================================================================ карточки и журнал

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun userCard(key: String): ModUserOut {
        val id = runCatching { UUID.fromString(key) }.getOrNull()
            ?: em.createNativeQuery("select id from users where username = ?1", UUID::class.java)
                .setParameter(1, key.trim().lowercase()).resultList.firstOrNull() as UUID?
            ?: throw ApiException.notFound("человек не найден")
        return userCardOrNull(id, withLists = true) ?: throw ApiException.notFound("человек не найден")
    }

    @Suppress("UNCHECKED_CAST")
    private fun userCardOrNull(id: UUID, withLists: Boolean): ModUserOut? {
        val r = (em.createNativeQuery(
            """
            select u.staff_role, u.created_at, u.is_deleted, u.banned_until, u.ban_reason, u.restricted_until, u.restrict_reason,
                   (select count(*) from mod_ticket t where t.owner_id = u.id and t.status <> 'collecting'),
                   (select count(*) from mod_ticket t where t.owner_id = u.id and t.status = 'resolved'),
                   (select count(*) from mod_report r where r.reporter_id = u.id),
                   (select count(*) from mod_report r join mod_ticket t on t.id = r.ticket_id where r.reporter_id = u.id and t.status = 'dismissed')
            from users u where u.id = ?1
            """.trimIndent(),
        ).setParameter(1, id).resultList as List<Array<Any?>>).firstOrNull() ?: return null
        val short = profiles.shorts(listOf(id))[id] ?: return null
        val now = Instant.now()
        val ban = r[3]?.let { toInstant(it) }?.takeIf { it.isAfter(now) }
        val restr = r[5]?.let { toInstant(it) }?.takeIf { it.isAfter(now) }
        val openTickets = if (!withLists) emptyList() else {
            val ids = em.createNativeQuery(
                "select id from mod_ticket where owner_id = ?1 and status in ('collecting', 'open', 'in_progress') order by created_at desc limit 20",
                UUID::class.java,
            ).setParameter(1, id).resultList as List<UUID>
            renderTickets(ids)
        }
        return ModUserOut(
            user = short, staffRole = r[0] as String?, createdAt = toInstant(r[1]), deleted = r[2] == true,
            ban = ban?.let { sanction(it, r[4] as String?) },
            restriction = restr?.let { sanction(it, r[6] as String?) },
            decree = profiles.decreesOf(listOf(id))[id],
            ticketsAgainst = (r[7] as Number).toInt(), violations = (r[8] as Number).toInt(),
            reportsFiled = (r[9] as Number).toInt(), reportsDismissed = (r[10] as Number).toInt(),
            openTickets = openTickets,
            history = if (withLists) actions("a.user_id = ?1", listOf(id), 50) else actions("a.user_id = ?1", listOf(id), 10),
        )
    }

    @Suppress("UNCHECKED_CAST")
    @Transactional
    fun communityCard(slug: String): ModCommunityOut {
        // заблокированное (is_deleted) тоже ищем — модератору видно всё
        val r = (em.createNativeQuery(
            """
            select c.id, c.owner_id, c.source, c.blocked_at, c.block_reason, c.frozen_until, c.freeze_reason,
                   (select count(*) from mod_ticket t where t.community_id = c.id and t.status <> 'collecting'),
                   (select count(*) from mod_ticket t where t.community_id = c.id and t.status = 'resolved')
            from community c where lower(c.slug) = ?1 order by c.is_deleted limit 1
            """.trimIndent(),
        ).setParameter(1, slug.trim().lowercase()).resultList as List<Array<Any?>>).firstOrNull()
            ?: throw ApiException.notFound("сообщество не найдено")
        val id = r[0] as UUID
        val c = Community.findById(id)!!
        val now = Instant.now()
        val ids = em.createNativeQuery(
            "select id from mod_ticket where community_id = ?1 and status in ('collecting', 'open', 'in_progress') order by created_at desc limit 20",
            UUID::class.java,
        ).setParameter(1, id).resultList as List<UUID>
        return ModCommunityOut(
            community = PostCommunityOut(c.id, c.slug, c.name, c.hue, c.avatar),
            owner = (r[1] as UUID?)?.let { profiles.shorts(listOf(it))[it] },
            mirror = r[2] != null,
            blocked = r[3]?.let { SanctionOut(null, true, r[4] as String?) },
            frozen = r[5]?.let { toInstant(it) }?.takeIf { it.isAfter(now) }?.let { sanction(it, r[6] as String?) },
            ticketsAgainst = (r[7] as Number).toInt(), violations = (r[8] as Number).toInt(),
            openTickets = renderTickets(ids),
            history = actions("a.community_id = ?1", listOf(id), 50),
        )
    }

    /** Журнал. Все фильтры необязательны. */
    @Transactional
    fun log(moderatorId: UUID?, userId: UUID?, communityId: UUID?, targetType: String?, targetId: UUID?, action: String?, before: Instant?, limit: Int): ModActionPageOut {
        val size = limit.coerceIn(1, MAX_PAGE)
        val params = mutableListOf<Any>()
        fun p(v: Any): String { params += v; return "?${params.size}" }
        val where = mutableListOf<String>()
        moderatorId?.let { where += "a.moderator_id = ${p(it)}" }
        userId?.let { where += "a.user_id = ${p(it)}" }
        communityId?.let { where += "a.community_id = ${p(it)}" }
        targetType?.takeIf { it.isNotBlank() }?.let { where += "a.target_type = ${p(it)}" }
        targetId?.let { where += "a.target_id = ${p(it)}" }
        action?.takeIf { it.isNotBlank() }?.let { where += "a.action = ${p(it)}" }
        where += "a.created_at < ${p(before ?: Instant.now().plusSeconds(60))}"
        val items = actions(where.joinToString(" and "), params, size + 1)
        return ModActionPageOut(items.take(size), items.size > size, if (items.size > size) items[size - 1].createdAt else null)
    }

    @Transactional
    fun stats(me: AuthTicket): ModStatsOut {
        @Suppress("UNCHECKED_CAST")
        val r = em.createNativeQuery(
            """
            select
                (select count(*) from mod_ticket where status = 'open'),
                (select count(*) from mod_ticket where status = 'in_progress'),
                (select count(*) from mod_ticket where status = 'in_progress' and assignee_id = ?1),
                (select count(*) from mod_ticket where status = 'collecting'),
                (select count(*) from mod_ticket where status = 'resolved' and resolved_at > now() - interval '1 day'),
                (select count(*) from mod_ticket where status = 'dismissed' and resolved_at > now() - interval '1 day'),
                (select count(*) from users where banned_until > now()),
                (select count(*) from users where restricted_until > now())
            """.trimIndent(),
        ).setParameter(1, me.userId).singleResult as Array<Any?>
        val n = r.map { (it as Number).toInt() }
        return ModStatsOut(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7])
    }

    // ================================================================ для диктатора

    /** Записать в журнал действие диктатора (указы, назначения). */
    @Transactional
    fun logStaff(by: UUID, action: String, userId: UUID, reason: String?) {
        log(by, action, "user", userId, userId, null, null, reason, null)
    }

    // ================================================================ внутреннее

    @Suppress("UNCHECKED_CAST")
    private fun renderTickets(ids: List<UUID>): List<ModTicketOut> {
        if (ids.isEmpty()) return emptyList()
        val rows = em.createNativeQuery(
            """
            select id, target_type, target_id, status, reports, threshold, top_reason, owner_id, community_id, cast(snapshot as text),
                   assignee_id, assigned_at, verdict, note, resolved_by, resolved_at, created_at, opened_at
            from mod_ticket where id in (?1)
            """.trimIndent(),
        ).setParameter(1, ids).resultList as List<Array<Any?>>
        val users = profiles.shorts(rows.flatMap { listOfNotNull(it[7] as UUID?, it[10] as UUID?, it[14] as UUID?) }.distinct())
        val commIds = rows.mapNotNull { it[8] as UUID? }.distinct()
        val comms = if (commIds.isEmpty()) emptyMap() else
            (Community.list("id in ?1", commIds)).associate { it.id to PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) }
        val byId = rows.associateBy { it[0] as UUID }
        return ids.mapNotNull { id ->
            val r = byId[id] ?: return@mapNotNull null
            val snap = mapper.readTree(r[9] as String? ?: "{}")
            ModTicketOut(
                id = id, targetType = r[1] as String, targetId = r[2] as UUID, status = r[3] as String,
                reports = (r[4] as Number).toInt(), threshold = (r[5] as Number).toInt(),
                topReason = r[6] as String?, topReasonTitle = (r[6] as String?)?.let { REASONS[it] },
                owner = (r[7] as UUID?)?.let { users[it] }, community = (r[8] as UUID?)?.let { comms[it] },
                snapshot = snap, preview = preview(mapper.convertValue(snap, Map::class.java) as Map<String, Any?>),
                assignee = (r[10] as UUID?)?.let { users[it] }, assignedAt = r[11]?.let { toInstant(it) },
                verdict = r[12] as String?, note = r[13] as String?,
                resolvedBy = (r[14] as UUID?)?.let { users[it] }, resolvedAt = r[15]?.let { toInstant(it) },
                createdAt = toInstant(r[16]), openedAt = r[17]?.let { toInstant(it) },
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun actions(where: String, params: List<Any>, limit: Int): List<ModActionOut> {
        val q = em.createNativeQuery(
            """
            select a.id, a.moderator_id, a.action, a.target_type, a.target_id, a.user_id, a.community_id, a.ticket_id,
                   a.reason, a.until, a.created_at
            from mod_action a where $where order by a.created_at desc, a.id limit $limit
            """.trimIndent(),
        )
        params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
        val rows = q.resultList as List<Array<Any?>>
        val users = profiles.shorts(rows.flatMap { listOfNotNull(it[1] as UUID?, it[5] as UUID?) }.distinct())
        val commIds = rows.mapNotNull { it[6] as UUID? }.distinct()
        val comms = if (commIds.isEmpty()) emptyMap() else
            (Community.list("id in ?1", commIds)).associate { it.id to PostCommunityOut(it.id, it.slug, it.name, it.hue, it.avatar) }
        return rows.map { r ->
            val until = r[9]?.let { toInstant(it) }
            ModActionOut(
                id = r[0] as UUID, moderator = users[r[1] as UUID], action = r[2] as String,
                title = LOG_TITLES[r[2] as String] ?: r[2] as String,
                targetType = r[3] as String, targetId = r[4] as UUID,
                user = (r[5] as UUID?)?.let { users[it] }, community = (r[6] as UUID?)?.let { comms[it] },
                ticketId = r[7] as UUID?, reason = r[8] as String?,
                until = until?.takeIf { it < Staff.FOREVER }, forever = until != null && until >= Staff.FOREVER,
                createdAt = toInstant(r[10]),
            )
        }
    }

    private fun log(
        moderator: UUID, action: String, type: String, targetId: UUID, userId: UUID?, communityId: UUID?,
        ticketId: UUID?, reason: String?, until: Instant?,
    ): UUID {
        val id = UUID.randomUUID()
        em.createNativeQuery(
            """
            insert into mod_action (id, moderator_id, action, target_type, target_id, user_id, community_id, ticket_id, reason, until)
            values (?1, ?2, ?3, ?4, ?5, cast(nullif(?6, '') as uuid), cast(nullif(?7, '') as uuid), cast(nullif(?8, '') as uuid),
                    nullif(?9, ''), cast(nullif(?10, '') as timestamptz))
            """.trimIndent(),
        ).setParameter(1, id).setParameter(2, moderator).setParameter(3, action).setParameter(4, type).setParameter(5, targetId)
            .setParameter(6, userId?.toString() ?: "").setParameter(7, communityId?.toString() ?: "")
            .setParameter(8, ticketId?.toString() ?: "").setParameter(9, reason ?: "")
            .setParameter(10, until?.toString() ?: "").executeUpdate()
        return id
    }

    private fun actionsFor(type: String, communityId: UUID?): List<String> = buildList {
        if (type in CONTENT) { add("remove_content"); add("restore_content") }
        addAll(USER_ACTIONS)
        if (type == "community" || communityId != null) addAll(COMMUNITY_ACTIONS)
    }

    private fun thresholdFor(t: Target): Int {
        if (t.type == "message" && t.chatId != null) {
            val members = count("select count(*) from chat_member where chat_id = ?1 and not is_deleted", t.chatId).toInt()
            return threshold.coerceAtMost((members - 1).coerceAtLeast(1))
        }
        return threshold.coerceAtLeast(1)
    }

    @Suppress("UNCHECKED_CAST")
    private fun liveTicket(type: String, id: UUID): Ticket? =
        (em.createNativeQuery(
            "select id, target_type, target_id, owner_id, community_id, status, assignee_id from mod_ticket " +
                "where target_type = ?1 and target_id = ?2 and status in ('collecting', 'open', 'in_progress')",
        ).setParameter(1, type).setParameter(2, id).resultList as List<Array<Any?>>).firstOrNull()?.let(::ticketRow)

    @Suppress("UNCHECKED_CAST")
    private fun lockTicket(id: UUID): Ticket =
        (em.createNativeQuery(
            "select id, target_type, target_id, owner_id, community_id, status, assignee_id from mod_ticket where id = ?1 for update",
        ).setParameter(1, id).resultList as List<Array<Any?>>).firstOrNull()?.let(::ticketRow)
            ?: throw ApiException.notFound("тикет не найден")

    private fun ticketRow(r: Array<Any?>) = Ticket(
        r[0] as UUID, r[1] as String, r[2] as UUID, r[3] as UUID?, r[4] as UUID?, r[5] as String, r[6] as UUID?,
    )

    /** Автор уже удалённого объекта (для restore). */
    private fun ownerOfRemoved(type: String, id: UUID): UUID? {
        val sql = when (type) {
            "post" -> "select creator_id from post where id = ?1"
            "comment" -> "select author_id from post_comment where id = ?1"
            "message" -> "select user_id from message where id = ?1"
            "market" -> "select seller_id from market_item where id = ?1"
            "track" -> "select uploader_id from track where id = ?1"
            "video" -> "select owner_id from media where id = ?1"
            "guestbook" -> "select author_id from room_guestbook_entry where id = ?1"
            "event" -> "select created_by from community_event where id = ?1"
            else -> return null
        }
        return em.createNativeQuery(sql, UUID::class.java).setParameter(1, id).resultList.firstOrNull() as UUID?
    }

    /**
     * Объект жалобы: автор, сообщество, удалён ли и снимок для модератора.
     * null — такого нет вовсе.
     */
    @Suppress("UNCHECKED_CAST")
    private fun target(type: String, id: UUID): Target? {
        fun one(sql: String): Array<Any?>? =
            (em.createNativeQuery(sql).setParameter(1, id).resultList as List<Array<Any?>>).firstOrNull()
        return when (type) {
            "user" -> one(
                """
                select u.username, c.avatar, c.tagline, r.title, r.mood, r.about, u.is_deleted
                from users u left join user_cosmetics c on c.user_id = u.id left join room r on r.owner_id = u.id where u.id = ?1
                """.trimIndent(),
            )?.let {
                Target(type, id, id, null, null, it[6] == true, mapOf(
                    "username" to it[0], "avatar" to it[1], "tagline" to it[2], "roomTitle" to it[3], "mood" to it[4],
                    "about" to it[5], "link" to "/rooms/${it[0]}",
                ))
            }
            "community" -> one(
                "select slug, name, description, avatar, banner, owner_id, blocked_at, rules_text, source from community where id = ?1",
            )?.let {
                Target(type, id, it[5] as UUID?, id, null, it[6] != null, mapOf(
                    "slug" to it[0], "name" to it[1], "description" to it[2], "avatar" to it[3], "banner" to it[4],
                    "rules" to it[7], "mirror" to (it[8] != null), "link" to "/c/${it[0]}",
                ))
            }
            "post" -> one(
                """
                select p.creator_id, p.community_id, p.title, p.body, p.kind, p.is_pulse, p.wall_user_id, p.is_deleted, p.as_community,
                       p.created_at, c.slug, (select username from users where id = p.wall_user_id)
                from post p left join community c on c.id = p.community_id where p.id = ?1
                """.trimIndent(),
            )?.let {
                val link = when {
                    it[6] != null -> "/rooms/${it[11]}?post=$id"
                    it[5] == true -> "/pulse?post=$id"
                    else -> "/c/${it[10]}?post=$id"
                }
                Target(type, id, it[0] as UUID, it[1] as UUID?, null, it[7] == true, mapOf(
                    "title" to it[2], "text" to it[3], "kind" to it[4], "asCommunity" to (it[8] == true),
                    "createdAt" to toInstant(it[9]).toString(), "media" to attachments("post", id), "link" to link,
                ))
            }
            "comment" -> one(
                """
                select c.author_id, p.community_id, c.body, c.post_id, c.deleted_at, c.created_at
                from post_comment c join post p on p.id = c.post_id where c.id = ?1
                """.trimIndent(),
            )?.let {
                Target(type, id, it[0] as UUID, it[1] as UUID?, null, it[4] != null, mapOf(
                    "text" to it[2], "postId" to it[3], "createdAt" to toInstant(it[5]).toString(),
                    "media" to attachments("comment", id), "link" to "/posts/${it[3]}?comment=$id",
                ))
            }
            "message" -> one(
                """
                select m.user_id, m.chat_id, m.body, m.deleted_at, m.created_at, ch.room_type, ch.community_id, ch.name
                from message m join chat ch on ch.id = m.chat_id where m.id = ?1
                """.trimIndent(),
            )?.let {
                Target(type, id, it[0] as UUID, it[6] as UUID?, it[1] as UUID, it[3] != null, mapOf(
                    "text" to it[2], "chatId" to it[1], "chatType" to it[5], "chatName" to it[7],
                    "createdAt" to toInstant(it[4]).toString(), "media" to attachments("message", id),
                ))
            }
            "market" -> one(
                "select seller_id, community_id, title, description, price, currency, city, contacts, status from market_item where id = ?1",
            )?.let {
                Target(type, id, it[0] as UUID, it[1] as UUID?, null, it[8] == "deleted", mapOf(
                    "title" to it[2], "text" to it[3], "price" to it[4]?.toString(), "currency" to it[5], "city" to it[6],
                    "contacts" to it[7], "media" to attachments("market", id), "link" to "/market/$id",
                ))
            }
            "track" -> one("select uploader_id, title, artist, album, deleted_at, audio_media_id, cover_media_id from track where id = ?1")?.let {
                Target(type, id, it[0] as UUID, null, null, it[4] != null, mapOf(
                    "title" to it[1], "artist" to it[2], "album" to it[3],
                    "audioUrl" to media.url(it[5] as UUID), "coverUrl" to (it[6] as UUID?)?.let { m -> media.url(m) },
                    "link" to "/music?track=$id",
                ))
            }
            "video" -> {
                val v = one(
                    """
                    select v.author_id, v.community_id, v.post_id, vm.title from video_item v
                    left join video_meta vm on vm.media_id = v.media_id where v.media_id = ?1
                    """.trimIndent(),
                )
                if (v != null) {
                    Target(type, id, v[0] as UUID, v[1] as UUID?, null, false, mapOf(
                        "title" to v[3], "postId" to v[2], "media" to listOf(mapOf("url" to media.url(id), "kind" to "video")),
                        "link" to "/video?v=$id",
                    ))
                } else {
                    one("select owner_id from media where id = ?1 and content_type like 'video/%'")?.let {
                        Target(type, id, it[0] as UUID, null, null, true, mapOf("media" to listOf(mapOf("url" to media.url(id), "kind" to "video"))))
                    }
                }
            }
            "guestbook" -> one(
                "select g.author_id, g.owner_id, g.body, g.deleted_at, g.created_at, u.username from room_guestbook_entry g join users u on u.id = g.owner_id where g.id = ?1",
            )?.let {
                Target(type, id, it[0] as UUID, null, null, it[3] != null, mapOf(
                    "text" to it[2], "roomOwnerId" to it[1], "createdAt" to toInstant(it[4]).toString(),
                    "media" to attachments("guestbook", id), "link" to "/rooms/${it[5]}",
                ))
            }
            "event" -> one(
                "select e.created_by, e.community_id, e.title, e.description, e.location, e.starts_at, e.cancelled_at, c.slug from community_event e join community c on c.id = e.community_id where e.id = ?1",
            )?.let {
                Target(type, id, it[0] as UUID, it[1] as UUID, null, it[6] != null, mapOf(
                    "title" to it[2], "text" to it[3], "location" to it[4], "startsAt" to toInstant(it[5]).toString(),
                    "link" to "/c/${it[7]}?tab=events&event=$id",
                ))
            }
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun attachments(ownerType: String, id: UUID): List<Map<String, String>> =
        (em.createNativeQuery(
            """
            select a.media_id, m.content_type from media_attachment a join media m on m.id = a.media_id
            where a.owner_type = ?1 and a.owner_id = ?2 order by a.position
            """.trimIndent(),
        ).setParameter(1, ownerType).setParameter(2, id).resultList as List<Array<Any?>>).map {
            mapOf("url" to media.url(it[0] as UUID), "kind" to AttachmentService.kindOf(it[1] as String))
        }

    private fun preview(s: Map<String, Any?>): String {
        val parts = listOfNotNull(
            s["title"] as String?, s["name"] as String?, s["username"]?.let { "@$it" },
            (s["text"] as String?)?.takeIf { it.isNotBlank() },
        )
        val p = parts.joinToString(" — ").ifEmpty { "(без текста)" }
        return if (p.length > 160) p.take(157) + "…" else p
    }

    private fun sanction(until: Instant, reason: String?) =
        SanctionOut(until.takeIf { it < Staff.FOREVER }, until >= Staff.FOREVER, reason)

    private fun sanctionMap(until: Instant, reason: String?): Map<String, Any?> =
        mapOf("until" to until.takeIf { it < Staff.FOREVER }?.toString(), "forever" to (until >= Staff.FOREVER), "reason" to reason)

    private fun count(sql: String, vararg params: Any): Long {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, v -> q.setParameter(i + 1, v) }
        return (q.singleResult as Number).toLong()
    }

    private fun exec(sql: String, id: UUID) {
        em.createNativeQuery(sql).setParameter(1, id).executeUpdate()
    }

    private fun toInstant(v: Any?): Instant = when (v) {
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        is java.sql.Timestamp -> v.toInstant()
        else -> Instant.EPOCH
    }
}
