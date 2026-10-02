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
@Table(name = "media")
class Media : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "owner_id")
    lateinit var ownerId: UUID

    @Column(name = "content_type")
    lateinit var contentType: String

    @Column(name = "size_bytes")
    var sizeBytes: Long = 0

    /** Сейчас — относительный путь на диске, после переезда в S3 — ключ объекта. */
    @Column(name = "storage_key")
    lateinit var storageKey: String

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<Media, UUID>
}