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
@Table(name = "room_link")
class RoomLink : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "owner_id")
    lateinit var ownerId: UUID

    lateinit var title: String
    lateinit var url: String
    var position: Int = 0

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<RoomLink, UUID>
}