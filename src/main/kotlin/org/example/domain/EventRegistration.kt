package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "event_registration")
class EventRegistration : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: EventRegistrationId

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "reminded_at")
    var remindedAt: Instant? = null

    companion object : PanacheCompanionBase<EventRegistration, EventRegistrationId>
}