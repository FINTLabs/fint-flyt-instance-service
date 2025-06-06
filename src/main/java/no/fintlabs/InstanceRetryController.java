package no.fintlabs;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService;
import no.fintlabs.kafka.InstanceRequestedForRetryEventProducerService;
import no.fintlabs.kafka.InstanceRetryRequestErrorEventProducerService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.persistence.EntityNotFoundException;
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
