package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// Таблицы из V9__music.sql. Связки (user_track, community_track,
// playlist_track, now_playing, track_attachment) пишем нативным SQL.

@Entity
@Table(name = "playlist")
class Playlist : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "owner_id")
    lateinit var ownerId: UUID

    @Column(name = "community_id")
    var communityId: UUID? = null

    lateinit var title: String
    var description: String? = null

    @Column(name = "cover_media_id")
    var coverMediaId: UUID? = null

    @Column(name = "is_public")
    var isPublic: Boolean = true

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<Playlist, UUID>
}
