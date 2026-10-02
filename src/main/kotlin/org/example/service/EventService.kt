package org.example.service

import io.quarkus.logging.Log
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.domain.Community
import org.example.domain.CommunityEvent
import org.example.domain.EventRegistration
import org.example.domain.EventRegistrationId
import org.example.rest.ApiException
import org.example.rest.EventIn
import org.example.rest.EventOut
import org.example.rest.UserShortOut
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * События сообщества. Уведомления:
 *  - новое событие — всем участникам (не «читающим без вступления»);
 *  - записался — подтверждение самому;
 *  - за час до начала — напоминание записавшимся;
 *  - отменено — записавшимся.
 */
@ApplicationScoped
class EventService(
    private val em: EntityManager,
    private val communities: CommunityService,
    private val profiles: UserProfileService,
    private val notifications: NotificationService,
    private val tags: TagService,
) {
    companion object {
        const val MAX_TITLE = 200
        const val MAX_DESCRIPTION = 4000
        const val MAX_LOCATION = 200
        val REMIND_BEFORE: Duration = Duration.ofHours(1)
    }

    /** Предстоящие (и идущие — до 6 часов после начала). past=true — все. */
    @Transactional
    fun list(slug: String, me: UUID, past: Boolean): List<EventOut> {
        val c = communities.bySlug(slug)
        val rows = if (past) {
            CommunityEvent.list("communityId = ?1 order by startsAt desc", c.id)
        } else {
            CommunityEvent.list(
                "communityId = ?1 and startsAt > ?2 order by startsAt", c.id, Instant.now().minus(Duration.ofHours(6)),
            )
        }
        return toOut(rows, me)
    }

    @Transactional
    fun get(id: UUID, me: UUID): EventOut = toOut(listOf(event(id)), me).first()

    /** Создать может admin/owner сообщества. */
    @Transactional
    fun create(me: UUID, slug: String, req: EventIn): EventOut {
        val c = communities.bySlug(slug)
        communities.requireRole(c, me, "admin")
        val e = CommunityEvent().also {
            it.id = UUID.randomUUID()
            it.communityId = c.id
            it.createdBy = me
        }
        apply(e, req, creating = true)
        e.persist()
        tags.sync(TagService.Owner.EVENT, e.id, req.tags, e.title, e.description)

        val payload = payload(e, c)
        communities.memberIds(c.id).filter { it != me }.forEach {
            notifications.notify(it, NotificationService.EVENT_NEW, me, payload)
        }
        return toOut(listOf(e), me).first()
    }

    @Transactional
    fun update(me: UUID, id: UUID, req: EventIn): EventOut {
        val e = event(id)
        communities.requireRole(community(e), me, "admin")
        apply(e, req, creating = false)
        tags.sync(TagService.Owner.EVENT, e.id, req.tags, e.title, e.description)
        return toOut(listOf(e), me).first()
    }

    @Transactional
    fun cancel(me: UUID, id: UUID) {
        val e = event(id)
        val c = community(e)
        communities.requireRole(c, me, "admin")
        if (e.cancelledAt != null) return
        e.cancelledAt = Instant.now()
        EventRegistration.list("id.eventId = ?1", e.id).map { it.id.userId }.filter { it != me }.forEach {
            notifications.notify(it, NotificationService.EVENT_CANCELLED, me, payload(e, c))
        }
    }

    /** «Пойду». Только участники сообщества. Приходит уведомление-подтверждение. */
    @Transactional
    fun register(me: UUID, id: UUID): EventOut {
        val e = event(id)
        val c = community(e)
        communities.requireRole(c, me, "member")
        if (e.cancelledAt != null) throw ApiException.badRequest("event_cancelled", "событие отменено")
        if ((e.endsAt ?: e.startsAt).isBefore(Instant.now())) throw ApiException.badRequest("event_past", "событие уже прошло")
        if (EventRegistration.findById(EventRegistrationId(e.id, me)) == null) {
            EventRegistration().also { it.id = EventRegistrationId(e.id, me) }.persist()
            notifications.notify(me, NotificationService.EVENT_REGISTERED, null, payload(e, c))
        }
        return toOut(listOf(e), me).first()
    }

    @Transactional
    fun unregister(me: UUID, id: UUID): EventOut {
        val e = event(id)
        EventRegistration.findById(EventRegistrationId(e.id, me))?.delete()
        return toOut(listOf(e), me).first()
    }

    @Transactional
    fun attendees(id: UUID): List<UserShortOut> {
        val e = event(id)
        val ids = EventRegistration.list("id.eventId = ?1 order by createdAt", e.id).map { it.id.userId }
        val users = profiles.shorts(ids)
        return ids.mapNotNull { users[it] }
    }

    /** Напоминания за час до начала — раз в минуту смотрим, кому пора. */
    @Transactional
    fun sendReminders() {
        val now = Instant.now()
        val soon = CommunityEvent.list(
            "cancelledAt is null and startsAt > ?1 and startsAt <= ?2", now, now.plus(REMIND_BEFORE),
        )
        for (e in soon) {
            val c = Community.findById(e.communityId) ?: continue
            val pending = EventRegistration.list("id.eventId = ?1 and remindedAt is null", e.id)
            for (r in pending) {
                notifications.notify(r.id.userId, NotificationService.EVENT_REMINDER, null, payload(e, c))
                r.remindedAt = now
            }
            if (pending.isNotEmpty()) Log.debugf("напомнили о «%s» %d людям", e.title, pending.size)
        }
    }

    // ------------------------------------------------------------------

    private fun apply(e: CommunityEvent, req: EventIn, creating: Boolean) {
        req.title?.let {
            e.title = it.trim().takeIf { t -> t.isNotEmpty() && t.length <= MAX_TITLE }
                ?: throw ApiException.badRequest("invalid_title", "название: 1–$MAX_TITLE символов")
        } ?: if (creating) throw ApiException.badRequest("invalid_title", "нужно название") else Unit
        req.description?.let {
            if (it.length > MAX_DESCRIPTION) throw ApiException.badRequest("invalid_description", "описание длиннее $MAX_DESCRIPTION")
            e.description = it.trim().ifEmpty { null }
        }
        req.location?.let {
            if (it.length > MAX_LOCATION) throw ApiException.badRequest("invalid_location", "место длиннее $MAX_LOCATION")
            e.location = it.trim().ifEmpty { null }
        }
        req.startsAt?.let {
            if (it.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
                throw ApiException.badRequest("invalid_startsAt", "startsAt в прошлом")
            }
            e.startsAt = it
        } ?: if (creating) throw ApiException.badRequest("invalid_startsAt", "нужен startsAt (ISO, например 2026-10-01T20:00:00Z)") else Unit
        req.endsAt?.let { e.endsAt = it }
        e.endsAt?.let { if (!it.isAfter(e.startsAt)) throw ApiException.badRequest("invalid_endsAt", "endsAt раньше startsAt") }
    }

    private fun payload(e: CommunityEvent, c: Community) = mapOf(
        "eventId" to e.id.toString(),
        "title" to e.title,
        "startsAt" to e.startsAt.toString(),
        "communitySlug" to c.slug,
        "communityName" to c.name,
    )

    private fun event(id: UUID): CommunityEvent {
        val e = CommunityEvent.findById(id) ?: throw ApiException.notFound("событие не найдено")
        community(e)
        return e
    }

    private fun community(e: CommunityEvent): Community {
        val c = Community.findById(e.communityId)
        if (c == null || c.isDeleted) throw ApiException.notFound("событие не найдено")
        return c
    }

    /** Для ленты: события пачкой. */
    @Transactional
    fun byIds(ids: Collection<UUID>, me: UUID): Map<UUID, EventOut> {
        if (ids.isEmpty()) return emptyMap()
        return toOut(CommunityEvent.list("id in ?1", ids.distinct()), me).associateBy { it.id }
    }

    @Suppress("UNCHECKED_CAST")
    private fun toOut(events: List<CommunityEvent>, me: UUID): List<EventOut> {
        if (events.isEmpty()) return emptyList()
        val ids = events.map { it.id }
        val going = (em.createNativeQuery(
            "select event_id, count(*) from event_registration where event_id in (?1) group by event_id",
        ).setParameter(1, ids).resultList as List<Array<Any?>>).associate { it[0] as UUID to (it[1] as Number).toLong() }
        val mine = EventRegistration.list("id.userId = ?1 and id.eventId in ?2", me, ids).map { it.id.eventId }.toSet()
        val creators = profiles.shorts(events.map { it.createdBy })
        val eventTags = tags.tagsOf(TagService.Owner.EVENT, ids)
        return events.map {
            EventOut(
                it.id, it.title, it.description, it.location, it.startsAt, it.endsAt,
                going[it.id] ?: 0, it.id in mine, it.cancelledAt != null, creators[it.createdBy],
                tags = eventTags[it.id] ?: emptyList(),
            )
        }
    }
}

@ApplicationScoped
class EventReminderJob(private val events: EventService) {
    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun remind() = events.sendReminders()
}
