package no.fintlabs;

import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.InstanceDeletedEventProducerService;
import no.fintlabs.model.instance.InstanceMappingService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;

@Service
public class InstanceService {

    private final InstanceRepository instanceRepository;
    private final InstanceMappingService instanceMappingService;
    private final InstanceDeletedEventProducerService instanceDeletedEventProducerService;

    public InstanceService(
            InstanceRepository instanceRepository,
            InstanceMappingService instanceMappingService,
            InstanceDeletedEventProducerService instanceDeletedEventProducerService
    ) {
        this.instanceRepository = instanceRepository;
        this.instanceMappingService = instanceMappingService;
        this.instanceDeletedEventProducerService = instanceDeletedEventProducerService;
    }

    public InstanceObjectDto save(InstanceObjectDto instanceObjectDto) {
        return instanceMappingService.toInstanceObjectDto(
                instanceRepository.save(instanceMappingService.toInstanceObject(instanceObjectDto))
        );
    }

    public InstanceObjectDto getById(Long instanceId) {
        return instanceMappingService.toInstanceObjectDto(instanceRepository.getReferenceById(instanceId));
    }

    @Transactional
    public void deleteInstanceByInstanceFlowHeaders(InstanceFlowHeaders instanceFlowHeaders) {
        instanceRepository.deleteById(instanceFlowHeaders.getInstanceId());
        instanceDeletedEventProducerService.publish(instanceFlowHeaders);
    }

}
