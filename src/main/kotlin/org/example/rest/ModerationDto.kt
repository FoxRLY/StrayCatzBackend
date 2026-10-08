package org.example.rest

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

// ================================================================ жалобы (все)

/**
 * POST /api/reports.
 * targetType: user / community / post / comment / message / market / track / video / guestbook / event.
 * reason — код из GET /api/reports/reasons.
 */
data class ReportIn(
    val targetType: String? = null,
    val targetId: UUID? = null,
    val reason: String? = null,
    /** Комментарий для модератора, до 1000 символов. */
    val comment: String? = null,
)

data class ReportOut(
    /** Жалоба принята (или уже была — тогда alreadyReported). */
    val reported: Boolean,
    val alreadyReported: Boolean,
)

data class ReportReasonOut(val code: String, val title: String)

/** Мои жалобы: на что жаловался и чем кончилось. */
data class MyReportOut(
    val id: UUID,
    val targetType: String,
    val targetId: UUID,
    val reason: String,
    val reasonTitle: String,
    val comment: String?,
    val createdAt: Instant,
    /** pending (рассматривается / копятся жалобы) / violation (меры приняты) / no_violation (нарушения нет). */
    val outcome: String,
)

data class MyReportsPageOut(val items: List<MyReportOut>, val hasMore: Boolean, val next: Instant?)

/** Ограничение или бан: until = null и forever = true — навсегда. */
data class SanctionOut(val until: Instant?, val forever: Boolean, val reason: String?)

/** Моё положение: ограничен ли, последние предупреждения и меры против моего контента. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class MyStandingOut(
    val restriction: SanctionOut?,
    val recent: List<MyModEventOut>,
)

data class MyModEventOut(
    /** warn_user, remove_content, restrict_user, reset_profile, … */
    val action: String,
    val title: String,
    val targetType: String,
    val targetId: UUID,
    val reason: String?,
    val until: Instant?,
    val createdAt: Instant,
)

// ================================================================ панель модератора

/** POST /api/mod/actions и элементы actions в resolve. */
data class ModActionIn(
    /**
     * remove_content / restore_content (контент);
     * warn_user / restrict_user / unrestrict_user / ban_user / unban_user / reset_profile (человек:
     *   сам target, если targetType = user, иначе автор контента);
     * freeze_community / unfreeze_community / block_community / unblock_community (сообщество:
     *   сам target, если targetType = community, иначе сообщество, где был контент).
     */
    val action: String? = null,
    /** В resolve можно не указывать — берётся из тикета. */
    val targetType: String? = null,
    val targetId: UUID? = null,
    /** restrict / ban / freeze: срок в часах; null или 0 — навсегда. */
    val hours: Int? = null,
    /** Причина — видит человек (кроме снятия мер). Обязательна для мер. */
    val reason: String? = null,
    /** Привязать к тикету (для журнала). В resolve — подставляется сам. */
    val ticketId: UUID? = null,
)

/** POST /api/mod/tickets/{id}/resolve */
data class ModResolveIn(
    /** violation — нарушение (нужен хотя бы один action) / no_violation — нарушения нет. */
    val verdict: String? = null,
    val actions: List<ModActionIn> = emptyList(),
    /** Внутренняя заметка модератора (людям не видна). */
    val note: String? = null,
)

data class ModTakeIn(
    /** Забрать тикет, даже если его взял другой модератор. */
    val force: Boolean? = null,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ModTicketOut(
    val id: UUID,
    val targetType: String,
    val targetId: UUID,
    /** collecting / open / in_progress / resolved / dismissed */
    val status: String,
    val reports: Int,
    val threshold: Int,
    val topReason: String?,
    val topReasonTitle: String?,
    /** Автор контента / сам человек / владелец сообщества. */
    val owner: UserShortOut?,
    val community: PostCommunityOut?,
    /** Как выглядело в момент первой жалобы: text, title, media[{url, kind}], link, … */
    val snapshot: JsonNode,
    /** Строка для списка: начало текста / название. */
    val preview: String,
    val assignee: UserShortOut?,
    val assignedAt: Instant?,
    /** violation / no_violation */
    val verdict: String?,
    val note: String?,
    val resolvedBy: UserShortOut?,
    val resolvedAt: Instant?,
    val createdAt: Instant,
    /** Когда набрался порог (попал в очередь). */
    val openedAt: Instant?,
)

data class ModTicketPageOut(val items: List<ModTicketOut>, val hasMore: Boolean, val nextOffset: Int?)

data class ModReportOut(
    val id: UUID,
    val reporter: UserShortOut?,
    val reason: String,
    val reasonTitle: String,
    val comment: String?,
    val createdAt: Instant,
    /** Сколько жалоб этого человека раньше отклонили (no_violation) — «ложный жалобщик». */
    val reporterDismissed: Int,
)

data class ModReasonCountOut(val reason: String, val title: String, val count: Int)

/** Что сейчас с объектом жалобы. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ModTargetStateOut(
    val exists: Boolean,
    /** Удалён (автором или модератором), сообщество — заблокировано. */
    val removed: Boolean,
    /** Как выглядит сейчас (если поменяли после жалобы). */
    val current: JsonNode?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ModTicketDetailOut(
    val ticket: ModTicketOut,
    val target: ModTargetStateOut,
    val reasons: List<ModReasonCountOut>,
    val reports: List<ModReportOut>,
    /** Автор / человек: баны, ограничения, сколько нарушений. */
    val owner: ModUserOut?,
    /** История мер по этому объекту и его автору (новые сверху). */
    val history: List<ModActionOut>,
    /** Какие action можно применить к этому тикету. */
    val actions: List<ModActionKindOut>,
)

data class ModActionKindOut(val action: String, val title: String, val needsReason: Boolean, val hasDuration: Boolean)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ModActionOut(
    val id: UUID,
    val moderator: UserShortOut?,
    val action: String,
    val title: String,
    val targetType: String,
    val targetId: UUID,
    val user: UserShortOut?,
    val community: PostCommunityOut?,
    val ticketId: UUID?,
    val reason: String?,
    val until: Instant?,
    val forever: Boolean,
    val createdAt: Instant,
)

data class ModActionPageOut(val items: List<ModActionOut>, val hasMore: Boolean, val next: Instant?)

/** Карточка человека для модератора. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ModUserOut(
    val user: UserShortOut,
    /** moderator / dictator / null */
    val staffRole: String?,
    val createdAt: Instant,
    val deleted: Boolean,
    val ban: SanctionOut?,
    val restriction: SanctionOut?,
    val decree: DecreeOut?,
    /** Тикетов против него всего / с нарушением. */
    val ticketsAgainst: Int,
    val violations: Int,
    /** Сколько он сам пожаловался / сколько из этого отклонили. */
    val reportsFiled: Int,
    val reportsDismissed: Int,
    val openTickets: List<ModTicketOut>,
    val history: List<ModActionOut>,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ModCommunityOut(
    val community: PostCommunityOut,
    val owner: UserShortOut?,
    val mirror: Boolean,
    val blocked: SanctionOut?,
    val frozen: SanctionOut?,
    val ticketsAgainst: Int,
    val violations: Int,
    val openTickets: List<ModTicketOut>,
    val history: List<ModActionOut>,
)

data class ModStatsOut(
    val open: Int,
    val inProgress: Int,
    val mine: Int,
    val collecting: Int,
    val resolvedToday: Int,
    val dismissedToday: Int,
    val bannedNow: Int,
    val restrictedNow: Int,
)

data class ModMeOut(val moderator: Boolean, val dictator: Boolean, val staffRole: String?)

// ================================================================ указы диктатора

/** Плашка в профиле. Человек её не снимает; отозвать может только диктатор. */
data class DecreeOut(
    val text: String,
    val emoji: String?,
    /** #rrggbb или null — цвет по умолчанию. */
    val color: String?,
    val issuedAt: Instant,
)

/** PUT /api/dictator/users/{id}/decree */
data class DecreeIn(val text: String? = null, val emoji: String? = null, val color: String? = null)

data class DecreeAdminOut(
    val id: UUID,
    val user: UserShortOut?,
    val decree: DecreeOut,
    val revokedAt: Instant?,
)

data class StaffOut(
    val user: UserShortOut,
    /** moderator / dictator */
    val role: String,
    /** grant — назначен через API / сид / настройку (снимается здесь); keycloak — роль в Keycloak (снимать там). */
    val source: String = "grant",
    val grantedBy: UserShortOut? = null,
    val grantedAt: Instant? = null,
    val note: String? = null,
)

/** PUT /api/dictator/dictators/{userId}: повторить username назначаемого — защита от промаха. */
data class DictatorGrantIn(val confirmUsername: String? = null)
