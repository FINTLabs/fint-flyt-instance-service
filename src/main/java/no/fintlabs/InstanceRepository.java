package no.fintlabs;

import no.fintlabs.model.instance.Instance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstanceRepository extends JpaRepository<Instance, Long> {

}
