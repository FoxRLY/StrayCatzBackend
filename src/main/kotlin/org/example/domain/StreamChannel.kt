package org.example.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Канал: куда стримить из OBS. Личный (userId) или сообщества (communityId). */
@Entity
@Table(name = "stream_channel")
class StreamChannel : PanacheEntityBase {
    @Id
    lateinit var id: UUID

    /** Публичная часть пути в медиасервере: live/<code>. */
    lateinit var code: String

    @Column(name = "user_id")
    var userId: UUID? = null

    @Column(name = "community_id")
    var communityId: UUID? = null

    /** sha-256 секрета в hex; null — ключ ещё не выпускали. */
    @Column(name = "secret_hash")
    var secretHash: String? = null

    @Column(name = "key_rotated_at")
    var keyRotatedAt: Instant? = null

    /** Кто выпустил ключ — от его имени создаются эфиры, начатые прямо из OBS. */
    @Column(name = "key_rotated_by")
    var keyRotatedBy: UUID? = null

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    companion object : PanacheCompanionBase<StreamChannel, UUID>
}