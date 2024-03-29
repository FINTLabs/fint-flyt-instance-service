package no.fintlabs;

import no.fintlabs.model.instance.entities.InstanceObject;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceRepository extends JpaRepository<InstanceObject, Long> {

}
