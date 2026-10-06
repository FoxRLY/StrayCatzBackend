package org.example.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.time.Duration
import java.util.UUID

/**
 * «Только одна нода»: задачи по расписанию Quarkus запускает на каждой ноде,
 * а превью эфиров, сверку с медиасервером, чистки и т.п. должна делать одна.
 *
 * Аренда в таблице job_lease: кто взял — тот и делает, пока продлевает
 * (каждый запуск). Нода упала — через [ttl] задачу подхватит другая.
 * Без привязки к соединению (в отличие от advisory lock) — работает с пулом.
 */
@ApplicationScoped
class JobLease(private val em: EntityManager) {
    /** Этот процесс. */
    val nodeId: String = UUID.randomUUID().toString()

    /** true — эта нода держит задачу [name] ещё [ttl]; false — делает другая. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun acquire(name: String, ttl: Duration): Boolean =
        em.createNativeQuery(
            """
            insert into job_lease (name, holder, until) values (?1, ?2, now() + make_interval(secs => ?3))
            on conflict (name) do update set holder = excluded.holder, until = excluded.until
            where job_lease.until < now() or job_lease.holder = excluded.holder
            """.trimIndent(),
        ).setParameter(1, name).setParameter(2, nodeId).setParameter(3, ttl.seconds.toDouble()).executeUpdate() > 0
}
