package no.fintlabs;

import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducer;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerFactory;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerRecord;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.kafka.event.topic.EventTopicService;
import no.fintlabs.model.instance.dtos.InstanceElementDto;
import org.springframework.stereotype.Service;

@Service
public class InstanceRequestedForRetryEventProducerService {

    private final InstanceFlowEventProducer<InstanceElementDto> newInstanceEventProducer;
    private final EventTopicNameParameters topicNameParameters;

    public InstanceRequestedForRetryEventProducerService(
            InstanceFlowEventProducerFactory instanceFlowEventProducerFactory,
            EventTopicService eventTopicService) {
        this.newInstanceEventProducer = instanceFlowEventProducerFactory.createProducer(InstanceElementDto.class);
        this.topicNameParameters = EventTopicNameParameters.builder()
                .eventName("instance-requested-for-retry")
                .build();
        eventTopicService.ensureTopic(topicNameParameters, 0);
    }

    public void publish(InstanceFlowHeaders instanceFlowHeaders, InstanceElementDto instance) {
        newInstanceEventProducer.send(
                InstanceFlowEventProducerRecord.<InstanceElementDto>builder()
                        .topicNameParameters(topicNameParameters)
                        .instanceFlowHeaders(instanceFlowHeaders)
                        .value(instance)
                        .build()
        );
    }

}
