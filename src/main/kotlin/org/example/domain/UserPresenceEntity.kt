package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "user_presence")
class UserPresenceEntity : PanacheEntityBase {
    @Id
    @Column(name = "user_id")
    lateinit var userId: UUID

    var status: String = "offline"
    var doing: String? = null

    @Column(name = "track_id")
    var trackId: String? = null

    @Column(name = "position_sec")
    var positionSec: Int? = null

    /** Выставлено руками: away / dnd / invisible или null. */
    @Column(name = "manual_status")
    var manualStatus: String? = null

    /** Последнее действие человека. */
    @Column(name = "last_active_at")
    var lastActiveAt: Instant = Instant.now()

    /** Последний пульс ноды, у которой открыт его сокет. */
    @Column(name = "seen_at")
    var seenAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<UserPresenceEntity, UUID>
}