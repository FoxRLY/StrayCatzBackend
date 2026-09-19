package org.example.presence.model

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
class FriendshipId : Serializable {
    @Column(name = "user_id")
    var userId: UUID? = null

    @Column(name = "friend_id")
    var friendId: UUID? = null

    constructor()
    constructor(userId: UUID, friendId: UUID) {
        this.userId = userId
        this.friendId = friendId
    }

    override fun equals(other: Any?): Boolean =
        other is FriendshipId && other.userId == userId && other.friendId == friendId

    override fun hashCode(): Int = (userId.hashCode() * 31) + friendId.hashCode()
}

@Entity
@Table(name = "friendship")
class Friendship : PanacheEntityBase {

    @EmbeddedId
    lateinit var id: FriendshipId

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<Friendship, FriendshipId> {
        /** Список друзей (как объекты AppUser) для заданного пользователя. */
        fun friendsOf(userId: UUID): List<AppUser> {
            val ids = find("id.userId", userId).list().map { it.id.friendId }
            if (ids.isEmpty()) return emptyList()
            return AppUser.list("id in ?1", ids)
        }
    }
}
