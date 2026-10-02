package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Эфир: idle (подготовлен) → live → ended. */
@Entity
@Table(name = "stream")
class Stream : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "channel_id")
    lateinit var channelId: UUID

    @Column(name = "created_by")
    lateinit var createdBy: UUID

    lateinit var title: String
    var description: String? = null

    var status: String = "idle"

    @Column(name = "chat_id")
    lateinit var chatId: UUID

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "started_at")
    var startedAt: Instant? = null

    @Column(name = "ended_at")
    var endedAt: Instant? = null

    @Column(name = "last_ready_at")
    var lastReadyAt: Instant? = null

    @Column(name = "publisher_type")
    var publisherType: String? = null

    @Column(name = "publisher_id")
    var publisherId: String? = null

    @Column(name = "peak_viewers")
    var peakViewers: Int = 0

    @Column(name = "ended_manually")
    var endedManually: Boolean = false

    /** Своя обложка эфира. */
    @Column(name = "poster_media_id")
    var posterMediaId: UUID? = null

    /** Ключ живого кадра в хранилище (streams/{id}/thumb.jpg) и когда он снят. */
    @Column(name = "thumb_key")
    var thumbKey: String? = null

    @Column(name = "thumb_at")
    var thumbAt: Instant? = null

    companion object : PanacheCompanionBase<Stream, UUID>
}
