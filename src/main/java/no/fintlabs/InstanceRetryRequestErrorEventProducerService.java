package no.fintlabs;

import no.fintlabs.flyt.kafka.event.error.InstanceFlowErrorEventProducer;
import no.fintlabs.flyt.kafka.event.error.InstanceFlowErrorEventProducerRecord;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.event.error.Error;
import no.fintlabs.kafka.event.error.ErrorCollection;
import no.fintlabs.kafka.event.error.topic.ErrorEventTopicNameParameters;
import no.fintlabs.kafka.event.error.topic.ErrorEventTopicService;
import org.springframework.stereotype.Service;

@Service
public class InstanceRetryRequestErrorEventProducerService {

    private final InstanceFlowErrorEventProducer instanceFlowErrorEventProducer;
    private final ErrorEventTopicNameParameters topicNameParameters;

    public InstanceRetryRequestErrorEventProducerService(
            ErrorEventTopicService errorEventTopicService,
            InstanceFlowErrorEventProducer instanceFlowErrorEventProducer
    ) {
        this.instanceFlowErrorEventProducer = instanceFlowErrorEventProducer;

        this.topicNameParameters = ErrorEventTopicNameParameters.builder()
                .errorEventName("instance-retry-request-error")
                .build();

        errorEventTopicService.ensureTopic(topicNameParameters, 0);
    }

    public void publishGeneralSystemErrorEvent(InstanceFlowHeaders instanceFlowHeaders) {
        instanceFlowErrorEventProducer.send(
                InstanceFlowErrorEventProducerRecord
                        .builder()
                        .topicNameParameters(topicNameParameters)
                        .instanceFlowHeaders(instanceFlowHeaders)
                        .errorCollection(new ErrorCollection(Error
                                .builder()
                                .errorCode(ErrorCode.GENERAL_SYSTEM_ERROR.getCode())
                                .build()))
                        .build()
        );
    }


}
