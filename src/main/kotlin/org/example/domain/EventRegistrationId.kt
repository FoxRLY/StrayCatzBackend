package org.example.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.io.Serializable
import java.util.UUID

@Embeddable
data class EventRegistrationId(
    @Column(name = "event_id") var eventId: UUID = UUID(0, 0),
    @Column(name = "user_id") var userId: UUID = UUID(0, 0),
) : Serializable