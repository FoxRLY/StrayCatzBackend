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
@Table(name = "users")
class AppUser : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    @Column(nullable = false, unique = true)
    lateinit var username: String

    @Column(name = "is_deleted")
    var isDeleted: Boolean = false

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    /** Заблокирован до (V20). 9999-12-31 — навсегда. */
    @Column(name = "banned_until")
    var bannedUntil: Instant? = null

    @Column(name = "ban_reason")
    var banReason: String? = null

    /** Ограничен (только читать) до. */
    @Column(name = "restricted_until")
    var restrictedUntil: Instant? = null

    @Column(name = "restrict_reason")
    var restrictReason: String? = null

    /** moderator / dictator — из последнего токена Keycloak. */
    @Column(name = "staff_role")
    var staffRole: String? = null

    companion object : PanacheCompanionBase<AppUser, UUID>
}