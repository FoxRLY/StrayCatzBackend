package org.example.service

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.example.bus.EventBus
import org.example.domain.AppUser
import org.example.proto.Envelope
import org.example.proto.FrameTypes
import org.example.proto.FriendUpdatedOut
import org.example.rest.ApiException
import org.example.rest.FriendStateOut
import org.example.rest.FriendsOut
import java.util.UUID

/**
 * Дружба поверх таблицы friendship (initiator -> acceptor, is_accepted).
 * В V3 на неё повешен unique на пару, так что строка на пару одна.
 * От дружбы зависит presence: presence.changed ходит только друзьям.
 */
@ApplicationScoped
class FriendService(
    private val em: EntityManager,
    private val bus: EventBus,
    private val mapper: ObjectMapper,
    private val profiles: UserProfileService,
    private val notifications: NotificationService,
) {
    private data class Row(val initiator: UUID, val acceptor: UUID, val accepted: Boolean)

    @Transactional
    fun list(me: UUID): FriendsOut {
        val rows = rowsOf(me)
        val cards = profiles.shorts(rows.map { if (it.initiator == me) it.acceptor else it.initiator })
        fun pick(filter: (Row) -> Boolean) = rows.filter(filter)
            .mapNotNull { cards[if (it.initiator == me) it.acceptor else it.initiator] }
            .sortedBy { it.username }
        return FriendsOut(
            friends = pick { it.accepted },
            incoming = pick { !it.accepted && it.acceptor == me },
            outgoing = pick { !it.accepted && it.initiator == me },
        )
    }

    /**
     * PUT /api/friends/{id}:
     *  - ничего нет            -> заявка (outgoing), тому прилетает friend.updated incoming
     *  - есть входящая от него -> принимаем (friends)
     *  - уже друзья / уже моя заявка -> no-op
     */
    @Transactional
    fun requestOrAccept(me: UUID, other: UUID): FriendStateOut {
        if (me == other) throw ApiException.badRequest("invalid_friend", "нельзя дружить с собой")
        val u = AppUser.findById(other)
        if (u == null || u.isDeleted) throw ApiException.notFound("пользователь не найден")

        val row = pair(me, other)
        return when {
            row == null -> {
                em.createNativeQuery("insert into friendship (initiator_id, acceptor_id, is_accepted) values (?1, ?2, false)")
                    .setParameter(1, me).setParameter(2, other).executeUpdate()
                push(other, me, "incoming")
                // колокольчик: «X хочет дружить» (не чаще раза в сутки от одного человека — на случай отозвал/отправил снова)
                if (!notifications.sentRecently(other, me, NotificationService.FRIEND_REQUEST, java.time.Duration.ofDays(1))) {
                    notifications.notify(other, NotificationService.FRIEND_REQUEST, me, mapOf("userId" to me.toString()))
                }
                FriendStateOut(other, "outgoing")
            }
            row.accepted -> FriendStateOut(other, "friends")
            row.initiator == other -> {
                em.createNativeQuery(
                    "update friendship set is_accepted = true, accepted_at = now() where initiator_id = ?1 and acceptor_id = ?2",
                ).setParameter(1, other).setParameter(2, me).executeUpdate()
                push(other, me, "friends")
                notifications.notify(other, NotificationService.FRIEND_ACCEPTED, me, mapOf("userId" to me.toString()))
                FriendStateOut(other, "friends")
            }
            else -> FriendStateOut(other, "outgoing")
        }
    }

    /** DELETE /api/friends/{id}: удалить из друзей / отклонить входящую / отменить свою. */
    @Transactional
    fun remove(me: UUID, other: UUID): FriendStateOut {
        val n = em.createNativeQuery(
            """
            delete from friendship
            where (initiator_id = ?1 and acceptor_id = ?2) or (initiator_id = ?2 and acceptor_id = ?1)
            """.trimIndent(),
        ).setParameter(1, me).setParameter(2, other).executeUpdate()
        if (n > 0) push(other, me, "none")
        return FriendStateOut(other, "none")
    }

    /** Подтверждённые друзья любого пользователя (для комнаты). */
    @Transactional
    fun friendIdsOf(userId: UUID): List<UUID> =
        rowsOf(userId).filter { it.accepted }.map { if (it.initiator == userId) it.acceptor else it.initiator }

    /** Отношение me к other: self / none / outgoing / incoming / friends. */
    @Transactional
    fun stateBetween(me: UUID, other: UUID): String {
        if (me == other) return "self"
        val row = pair(me, other) ?: return "none"
        return when {
            row.accepted -> "friends"
            row.initiator == me -> "outgoing"
            else -> "incoming"
        }
    }

    // ------------------------------------------------------------------

    /** friend.updated получателю [to]: "что теперь у тебя с [about]". */
    private fun push(to: UUID, about: UUID, state: String) {
        val frame = Envelope(t = FrameTypes.FRIEND_UPDATED, d = mapper.valueToTree(FriendUpdatedOut(about, state)))
        bus.publishToUsers(listOf(to), frame)
    }

    private fun pair(a: UUID, b: UUID): Row? = query(
        """
        select initiator_id, acceptor_id, is_accepted from friendship
        where (initiator_id = ?1 and acceptor_id = ?2) or (initiator_id = ?2 and acceptor_id = ?1)
        """.trimIndent(),
        a, b,
    ).firstOrNull()

    private fun rowsOf(me: UUID): List<Row> = query(
        "select initiator_id, acceptor_id, is_accepted from friendship where initiator_id = ?1 or acceptor_id = ?1",
        me,
    )

    @Suppress("UNCHECKED_CAST")
    private fun query(sql: String, vararg params: Any): List<Row> {
        val q = em.createNativeQuery(sql)
        params.forEachIndexed { i, p -> q.setParameter(i + 1, p) }
        return (q.resultList as List<Array<Any?>>).map {
            Row(it[0] as UUID, it[1] as UUID, (it[2] as Boolean?) ?: false)
        }
    }
}
