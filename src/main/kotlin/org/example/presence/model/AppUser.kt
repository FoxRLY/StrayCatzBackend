package org.example.presence.model

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class Status {
    online, away, dnd, invisible, offline
}

@Entity
@Table(name = "app_user")
class AppUser : PanacheEntityBase {

    @Id
    @GeneratedValue
    var id: UUID? = null

    @Column(nullable = false, unique = true, length = 32)
    lateinit var handle: String

    @Column(name = "display_name", nullable = false, length = 80)
    lateinit var displayName: String

    @Column(name = "avatar_url")
    var avatarUrl: String? = null

    @Column(unique = true)
    var email: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.offline

    @Column(length = 140)
    var mood: String? = null

    @Column(length = 140)
    var tagline: String? = null

    @Column(name = "doing_app")
    var doingApp: String? = null

    @Column(name = "doing_activity")
    var doingActivity: String? = null

    @Column(name = "listening_track")
    var listeningTrack: String? = null

    @Column(name = "listening_artist")
    var listeningArtist: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<AppUser, UUID> {
        fun findByHandle(handle: String): AppUser? = find("handle", handle).firstResult()
    }
}
