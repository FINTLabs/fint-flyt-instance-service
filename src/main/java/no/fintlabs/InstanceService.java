package no.fintlabs;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.InstanceDeletedEventProducerService;
import no.fintlabs.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService;
import no.fintlabs.model.instance.InstanceMappingService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class InstanceService {

    private final InstanceRepository instanceRepository;
    private final InstanceMappingService instanceMappingService;
    private final InstanceDeletedEventProducerService instanceDeletedEventProducerService;
    private final InstanceFlowHeadersForRegisteredInstanceRequestProducerService instanceFlowHeadersForRegisteredInstanceRequestProducerService;

    public InstanceService(
            InstanceRepository instanceRepository,
            InstanceMappingService instanceMappingService,
            InstanceDeletedEventProducerService instanceDeletedEventProducerService,
            InstanceFlowHeadersForRegisteredInstanceRequestProducerService instanceFlowHeadersForRegisteredInstanceRequestProducerService
    ) {
        this.instanceRepository = instanceRepository;
        this.instanceMappingService = instanceMappingService;
        this.instanceDeletedEventProducerService = instanceDeletedEventProducerService;
        this.instanceFlowHeadersForRegisteredInstanceRequestProducerService = instanceFlowHeadersForRegisteredInstanceRequestProducerService;
    }

    public InstanceObjectDto save(InstanceObjectDto instanceObjectDto) {
        return instanceMappingService.toInstanceObjectDto(
                instanceRepository.save(instanceMappingService.toInstanceObject(instanceObjectDto))
        );
    }

    public InstanceObjectDto getById(Long instanceId) {
        return instanceMappingService.toInstanceObjectDto(instanceRepository.getReferenceById(instanceId));
    }

    public List<InstanceObjectDto> getAllOlderThan(int days) {
        LocalDateTime thresholdDate = LocalDateTime.now().minusDays(days);
        Timestamp timestamp = Timestamp.valueOf(thresholdDate);  // Convert LocalDateTime to Timestamp

        return instanceRepository.findAllOlderThan(timestamp).stream()
                .map(instanceMappingService::toInstanceObjectDto)
                .toList();
    }

    public void deleteAllOlderThan(int days) {
        this.getAllOlderThan(days).forEach(instance -> {
            InstanceFlowHeaders instanceFlowHeaders = instanceFlowHeadersForRegisteredInstanceRequestProducerService
                    .get(instance.getId())
                    .map(ifh -> ifh.toBuilder()
                            .correlationId(UUID.randomUUID())
                            .build()
                    )
                    .orElseThrow(() -> new NoInstanceFlowHeadersException(instance.getId()));

            this.deleteInstanceByInstanceFlowHeaders(instanceFlowHeaders);
        });
    }

    public void deleteInstanceByInstanceFlowHeaders(InstanceFlowHeaders instanceFlowHeaders) {
        instanceRepository.deleteById(instanceFlowHeaders.getInstanceId());
        instanceDeletedEventProducerService.publish(instanceFlowHeaders);
        log.info("Instance with id={}, timestamp={} deleted", instanceFlowHeaders.getInstanceId(), Timestamp.valueOf(LocalDateTime.now()));
    }

}
