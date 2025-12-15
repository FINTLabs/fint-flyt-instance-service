package no.novari.flyt.instance;

import no.novari.flyt.instance.model.entities.InstanceObject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.List;

public interface InstanceRepository extends JpaRepository<InstanceObject, Long> {
    @Query("SELECT i FROM InstanceObject i WHERE i.createdAt < :thresholdDate")
    List<InstanceObject> findAllOlderThan(@Param("thresholdDate") Timestamp thresholdDate);
}
