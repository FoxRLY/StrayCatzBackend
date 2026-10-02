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
@Table(name = "room")
class Room : PanacheEntityBase {
    @Id
    @Column(name = "owner_id")
    lateinit var ownerId: UUID

    lateinit var title: String
    var mood: String? = null
    var about: String? = null
    var sticker: String? = null
    var theme: String = "dvor"
    var wallpaper: String = "grid"

    @Column(name = "wall_media_id")
    var wallMediaId: UUID? = null

    @Column(name = "wall_image_url")
    var wallImageUrl: String? = null

    @Column(name = "wall_fit")
    var wallFit: String = "cover"

    @Column(name = "wall_veil")
    var wallVeil: Double = 0.40

    @Column(name = "wall_blur")
    var wallBlur: Int = 0

    var tilt: Double = 1.0
    var dialect: String = "normal"

    /** JSON-объект {ключ: своё слово}. */
    @ColumnTransformer(write = "?::jsonb")
    var words: String = "{}"

    /** JSON-массив видимых блоков в порядке показа. */
    @ColumnTransformer(write = "?::jsonb")
    var blocks: String = "[]"

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<Room, UUID>
}