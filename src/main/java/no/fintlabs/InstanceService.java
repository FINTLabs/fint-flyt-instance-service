package no.fintlabs;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.InstanceDeletedEventProducerService;
import no.fintlabs.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService;
import no.fintlabs.model.instance.InstanceMappingService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import no.fintlabs.slack.SlackAlertService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class InstanceService {

    private final InstanceRepository instanceRepository;
    private final InstanceMappingService instanceMappingService;
    private final InstanceDeletedEventProducerService instanceDeletedEventProducerService;
    private final InstanceFlowHeadersForRegisteredInstanceRequestProducerService instanceFlowHeadersForRegisteredInstanceRequestProducerService;
    private final SlackAlertService slackAlertService;

    public InstanceService(
            InstanceRepository instanceRepository,
            InstanceMappingService instanceMappingService,
            InstanceDeletedEventProducerService instanceDeletedEventProducerService,
            InstanceFlowHeadersForRegisteredInstanceRequestProducerService instanceFlowHeadersForRegisteredInstanceRequestProducerService,
            SlackAlertService slackAlertService
    ) {
        this.instanceRepository = instanceRepository;
        this.instanceMappingService = instanceMappingService;
        this.instanceDeletedEventProducerService = instanceDeletedEventProducerService;
        this.instanceFlowHeadersForRegisteredInstanceRequestProducerService = instanceFlowHeadersForRegisteredInstanceRequestProducerService;
        this.slackAlertService = slackAlertService;
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
        Timestamp timestamp = Timestamp.valueOf(thresholdDate);

        return instanceRepository.findAllOlderThan(timestamp).stream()
                .map(instanceMappingService::toInstanceObjectDto)
                .toList();
    }

    public void deleteAllOlderThan(int days) {
        this.getAllOlderThan(days).forEach(instance -> {
                    instanceFlowHeadersForRegisteredInstanceRequestProducerService
                            .get(instance.getId())
                            .ifPresentOrElse(
                                    instanceDeletedEventProducerService::publish,
                                    () -> log.warn("No instance flow headers found for instance with id={}", instance.getId())
                            );
                    try {
                        instanceRepository.deleteById(instance.getId());
                        log.info("Instance with id={} deleted", instance.getId());
                    } catch (EmptyResultDataAccessException e) {
                        log.warn("Instance with id={} was already deleted", instance.getId());
                        slackAlertService.sendMessage(
                                "Warning: Attempted to delete instance with id=" + instance.getId() + ", but it was already deleted."
                        );
                    } catch (Exception e) {
                        log.error("Failed to delete instance with id={}", instance.getId(), e);
                        slackAlertService.sendMessage(
                                "Error: Failed to delete instance with id=" + instance.getId() + ". Exception: " + e.getMessage()
                        );
                    }
                }
        );
    }

    public void deleteInstanceByInstanceFlowHeaders(InstanceFlowHeaders instanceFlowHeaders) {
        instanceRepository.deleteById(instanceFlowHeaders.getInstanceId());
        instanceDeletedEventProducerService.publish(instanceFlowHeaders);
    }

}
