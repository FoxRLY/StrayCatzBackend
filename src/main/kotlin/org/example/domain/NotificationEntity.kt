package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notification")
class NotificationEntity : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "user_id")
    lateinit var userId: UUID

    lateinit var kind: String

    @Column(name = "actor_id")
    var actorId: UUID? = null

    @ColumnTransformer(write = "?::jsonb")
    var payload: String = "{}"

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "read_at")
    var readAt: Instant? = null

    companion object : PanacheCompanionBase<NotificationEntity, UUID>
}
