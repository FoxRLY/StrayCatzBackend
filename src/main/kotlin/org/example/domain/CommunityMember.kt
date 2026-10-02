package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "community_member")
class CommunityMember : PanacheEntityBase {
    @EmbeddedId
    lateinit var id: CommunityMemberId

    /** owner / admin / member */
    var role: String = "member"

    /** Репутация в сообществе. Пока у всех 0 — формулу придумаем позже. */
    var reputation: Int = 0

    @Column(insertable = false, updatable = false)
    var permissions: String? = null

    @Column(name = "leave_reason")
    var leaveReason: String? = null

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    /** null — состоит сейчас. */
    @Column(name = "left_at")
    var leftAt: Instant? = null

    companion object : PanacheCompanionBase<CommunityMember, CommunityMemberId>
}