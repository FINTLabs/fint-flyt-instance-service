package no.novari.flyt.instance

import jakarta.persistence.LockModeType
import no.novari.flyt.instance.model.entities.InstanceObject
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface InstanceRepository : JpaRepository<InstanceObject, Long> {
    @Query(
        """
        SELECT i FROM InstanceObject i
        WHERE i.createdAt < :thresholdDate
          AND NOT EXISTS (
              SELECT c.id
              FROM InstanceObjectCollection c
              JOIN c.objects child
              WHERE child.id = i.id
          )
        """,
    )
    fun findAllOlderThan(
        @Param("thresholdDate") thresholdDate: Instant,
    ): List<InstanceObject>

    // Row lock: a concurrent deleter waits, then finds nothing once the first deletion commits.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InstanceObject i WHERE i.id = :id")
    fun findForDeletionById(
        @Param("id") id: Long,
    ): InstanceObject?
}
