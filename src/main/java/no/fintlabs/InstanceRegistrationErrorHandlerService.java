package no.fintlabs;

import no.fintlabs.flyt.kafka.InstanceFlowErrorHandler;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeadersMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Service;

@Service
public class InstanceRegistrationErrorHandlerService extends InstanceFlowErrorHandler {

    private final InstanceRegistrationErrorEventProducerService instanceRegistrationErrorEventProducerService;

    protected InstanceRegistrationErrorHandlerService(
            InstanceFlowHeadersMapper instanceFlowHeadersMapper,
            InstanceRegistrationErrorEventProducerService instanceRegistrationErrorEventProducerService
    ) {
        super(instanceFlowHeadersMapper);
        this.instanceRegistrationErrorEventProducerService = instanceRegistrationErrorEventProducerService;
    }

    @Override
    public void handleInstanceFlowRecord(Throwable cause, InstanceFlowHeaders instanceFlowHeaders, ConsumerRecord<?, ?> consumerRecord) {
        instanceRegistrationErrorEventProducerService.publishGeneralSystemErrorEvent(instanceFlowHeaders);
    }

}
