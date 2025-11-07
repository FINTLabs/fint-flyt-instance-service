package no.novari.flyt.instance;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.flyt.kafka.instanceflow.headers.InstanceFlowHeaders;
import no.novari.flyt.instance.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService;
import no.novari.flyt.instance.kafka.InstanceRequestedForRetryEventProducerService;
import no.novari.flyt.instance.kafka.InstanceRetryRequestErrorEventProducerService;
import no.novari.flyt.instance.model.dtos.InstanceObjectDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static no.fintlabs.resourceserver.UrlPaths.INTERNAL_API;

@RestController
@RequestMapping(INTERNAL_API)
@Slf4j
public class InstanceRetryController {

    private final InstanceService instanceService;
    private final InstanceFlowHeadersForRegisteredInstanceRequestProducerService instanceFlowHeadersForRegisteredInstanceRequestProducerService;
    private final InstanceRequestedForRetryEventProducerService instanceRequestedForRetryEventProducerService;
    private final InstanceRetryRequestErrorEventProducerService instanceRetryRequestErrorEventProducerService;

    public InstanceRetryController(
            InstanceService instanceService,
            InstanceFlowHeadersForRegisteredInstanceRequestProducerService instanceFlowHeadersForRegisteredInstanceRequestProducerService,
            InstanceRequestedForRetryEventProducerService instanceRequestedForRetryEventProducerService,
            InstanceRetryRequestErrorEventProducerService instanceRetryRequestErrorEventProducerService
    ) {
        this.instanceService = instanceService;
        this.instanceFlowHeadersForRegisteredInstanceRequestProducerService = instanceFlowHeadersForRegisteredInstanceRequestProducerService;
        this.instanceRequestedForRetryEventProducerService = instanceRequestedForRetryEventProducerService;
        this.instanceRetryRequestErrorEventProducerService = instanceRetryRequestErrorEventProducerService;
    }

    @PostMapping("handlinger/instanser/{instanceId}/prov-igjen")
    public ResponseEntity<?> retry(@PathVariable Long instanceId) {
        InstanceFlowHeaders instanceFlowHeaders = null;
        try {
            InstanceObjectDto instance = instanceService.getById(instanceId);

            instanceFlowHeaders = instanceFlowHeadersForRegisteredInstanceRequestProducerService
                    .get(instanceId)
                    .map(ifh -> ifh.toBuilder()
                            .correlationId(UUID.randomUUID())
                            .build()
                    )
                    .orElseThrow(() -> new NoInstanceFlowHeadersException(instanceId));

            instanceRequestedForRetryEventProducerService.publish(instanceFlowHeaders, instance);

            return ResponseEntity.ok().build();

        } catch (EntityNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Could not find instance with id='" + instanceId + "'");
        } catch (NoInstanceFlowHeadersException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        } catch (Exception e) {
            if (instanceFlowHeaders != null) {
                instanceRetryRequestErrorEventProducerService.publishGeneralSystemErrorEvent(instanceFlowHeaders);
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, null, e);
        }

    }

    @PostMapping("handlinger/instanser/prov-igjen/batch")
    public ResponseEntity<?> retry(@RequestBody List<Long> instanceIds) {

        for (Long instanceId : instanceIds) {

            InstanceFlowHeaders instanceFlowHeaders = null;
            try {
                InstanceObjectDto instance = instanceService.getById(instanceId);

                instanceFlowHeaders = instanceFlowHeadersForRegisteredInstanceRequestProducerService
                        .get(instanceId)
                        .map(ifh -> ifh.toBuilder()
                                .correlationId(UUID.randomUUID())
                                .build()
                        )
                        .orElseThrow(() -> new NoInstanceFlowHeadersException(instanceId));

                instanceRequestedForRetryEventProducerService.publish(instanceFlowHeaders, instance);

            } catch (EntityNotFoundException e) {
                log.error("Could not find instance with id='{}'", instanceId);
            } catch (NoInstanceFlowHeadersException e) {
                log.error(e.getMessage());
            } catch (Exception e) {
                if (instanceFlowHeaders != null) {
                    instanceRetryRequestErrorEventProducerService.publishGeneralSystemErrorEvent(instanceFlowHeaders);
                }
                log.error(e.getMessage());
            }
        }

        return ResponseEntity.ok().build();
    }
}
