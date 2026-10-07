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
@Table(name = "call")
class CallEntity : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "chat_id")
    lateinit var chatId: UUID

    lateinit var kind: String

    @Column(name = "started_by")
    lateinit var startedBy: UUID

    var status: String = "ringing"

    @Column(name = "started_at")
    var startedAt: Instant = Instant.now()

    @Column(name = "ended_at")
    var endedAt: Instant? = null

    @Column(name = "end_reason")
    var endReason: String? = null

    /** Комната LiveKit: call-<id>. */
    @Column(name = "room_name")
    var roomName: String? = null

    /** true — участникам звонили; false — «открытый» звонок большой группы. */
    var ring: Boolean = true

    companion object : PanacheCompanionBase<CallEntity, UUID>
}