package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "call_participant")
class CallParticipant : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: CallParticipantId

    /** invited / accepted / declined / missed / left */
    var state: String = "invited"

    @Column(name = "joined_at")
    var joinedAt: Instant? = null

    @Column(name = "left_at")
    var leftAt: Instant? = null

    /** Сейчас в комнате LiveKit (вебхуки / сверка). */
    var connected: Boolean = false

    @Column(name = "connected_at")
    var connectedAt: Instant? = null

    companion object : PanacheCompanionBase<CallParticipant, CallParticipantId>
}