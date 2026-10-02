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
@Table(name = "track")
class Track : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(name = "uploader_id")
    lateinit var uploaderId: UUID

    @Column(name = "audio_media_id")
    lateinit var audioMediaId: UUID

    @Column(name = "cover_media_id")
    var coverMediaId: UUID? = null

    lateinit var title: String
    lateinit var artist: String
    var album: String? = null

    @Column(name = "duration_sec")
    var durationSec: Int = 0

    var bpm: Int? = null

    /** JSON-массив строк. */
    @ColumnTransformer(write = "?::jsonb")
    var tags: String = "[]"

    var plays: Long = 0

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<Track, UUID>
}