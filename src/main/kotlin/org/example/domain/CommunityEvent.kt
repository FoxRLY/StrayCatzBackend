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
@Table(name = "community_event")
class CommunityEvent : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "community_id")
    lateinit var communityId: UUID

    lateinit var title: String
    var description: String? = null
    var location: String? = null

    @Column(name = "starts_at")
    lateinit var startsAt: Instant

    @Column(name = "ends_at")
    var endsAt: Instant? = null

    @Column(name = "created_by")
    lateinit var createdBy: UUID

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null

    companion object : PanacheCompanionBase<CommunityEvent, UUID>
}