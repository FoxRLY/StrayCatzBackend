package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "user_cosmetics")
class UserCosmetics : PanacheEntityBase {
    @Id
    @Column(name = "user_id")
    lateinit var userId: UUID

    var avatar: String? = null
    var color: String? = null
    var tagline: String? = null

    companion object : PanacheCompanionBase<UserCosmetics, UUID>
}