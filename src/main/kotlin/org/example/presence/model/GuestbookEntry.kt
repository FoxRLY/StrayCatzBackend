package org.example.model

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "guestbook_entry")
class GuestbookEntry : PanacheEntityBase {

    @Id
    @GeneratedValue
    var id: UUID? = null

    @Column(name = "room_id", nullable = false)
    lateinit var roomId: UUID

    @Column(name = "author_id")
    var authorId: UUID? = null

    /** Денормализованный handle автора — на случай если автор потом удалится. */
    @Column(name = "author_handle", nullable = false, length = 32)
    lateinit var authorHandle: String

    @Column(nullable = false, length = 600)
    lateinit var body: String

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<GuestbookEntry, UUID> {

        /** Постраничный список по комнате, свежие сверху, keyset-пагинация. */
        fun page(roomId: UUID, before: Instant?, beforeId: UUID?, limit: Int): List<GuestbookEntry> {
            return if (before == null || beforeId == null) {
                find("roomId = ?1 order by createdAt desc, id desc", roomId)
                    .page(0, limit)
                    .list()
            } else {
                find(
                    "roomId = ?1 and (createdAt < ?2 or (createdAt = ?2 and id < ?3)) order by createdAt desc, id desc",
                    roomId, before, beforeId
                ).page(0, limit).list()
            }
        }
    }
}
