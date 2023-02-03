package no.fintlabs;

import no.fintlabs.model.instance.entities.InstanceElement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceRepository extends JpaRepository<InstanceElement, Long> {

}
