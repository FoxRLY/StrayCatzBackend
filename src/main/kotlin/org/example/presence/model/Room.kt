package org.example.model

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Комната пользователя: раскладка, обои, словарь и т.д.
 * Ровно одна комната на владельца, handle комнаты = handle владельца.
 */
@Entity
@Table(name = "room")
class Room : PanacheEntityBase {

    @Id
    @GeneratedValue
    var id: UUID? = null

    @Column(name = "owner_id", nullable = false, unique = true)
    lateinit var ownerId: UUID

    /** Денормализованный handle владельца — чтобы не джойнить на каждый /rooms/{handle}. */
    @Column(nullable = false, unique = true, length = 32)
    lateinit var handle: String

    @Column(nullable = false, length = 120)
    var title: String = ""

    @Column(name = "wallpaper_url")
    var wallpaperUrl: String? = null

    /** Произвольная раскладка виджетов/мебели — структура на усмотрение фронта. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var layout: Map<String, Any?>? = null

    /** Словарь: локальные словечки/эмодзи-алиасы комнаты. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var dictionary: Map<String, String>? = null

    /**
     * Затемнение подложки под текстом. Сервер обязан не пускать значения
     * ниже 0.25 — это гарантия читаемости, а не настройка вкуса.
     */
    @Column(name = "backdrop_dim", nullable = false)
    var backdropDim: Double = 1.0

    /** Открыта ли комната для захода посторонних. */
    @Column(nullable = false)
    var open: Boolean = true

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<Room, UUID> {
        fun findByHandle(handle: String): Room? = find("handle", handle).firstResult()
        fun findByOwnerId(ownerId: UUID): Room? = find("ownerId", ownerId).firstResult()

        fun listOpen(): List<Room> = list("open", true)
        // listAll() для получения вообще всех комнат уже есть в PanacheCompanionBase
    }
}
