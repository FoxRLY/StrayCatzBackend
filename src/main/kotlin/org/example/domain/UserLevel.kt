package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "user_level")
class UserLevel : PanacheEntityBase {
    @Id
    @Column(name = "user_id")
    lateinit var userId: UUID

    var xp: Int = 0
    var level: Int = 0

    companion object : PanacheCompanionBase<UserLevel, UUID>
}