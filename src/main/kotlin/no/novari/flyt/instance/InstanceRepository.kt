package no.novari.flyt.instance

import no.novari.flyt.instance.model.entities.InstanceObject
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.sql.Timestamp

interface InstanceRepository : JpaRepository<InstanceObject, Long> {
    @Query("SELECT i FROM InstanceObject i WHERE i.createdAt < :thresholdDate")
    fun findAllOlderThan(
        @Param("thresholdDate") thresholdDate: Timestamp,
    ): List<InstanceObject>
}
