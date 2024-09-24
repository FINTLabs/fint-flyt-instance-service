package no.fintlabs.kafka;

import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducer;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerFactory;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerRecord;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.kafka.event.topic.EventTopicService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.stereotype.Service;

@Service
public class InstanceRequestedForRetryEventProducerService {

    private final InstanceFlowEventProducer<InstanceObjectDto> newInstanceEventProducer;
    private final EventTopicNameParameters topicNameParameters;

    public InstanceRequestedForRetryEventProducerService(
            InstanceFlowEventProducerFactory instanceFlowEventProducerFactory,
            EventTopicService eventTopicService) {
        this.newInstanceEventProducer = instanceFlowEventProducerFactory.createProducer(InstanceObjectDto.class);
        this.topicNameParameters = EventTopicNameParameters.builder()
                .eventName("instance-requested-for-retry")
                .build();
        eventTopicService.ensureTopic(topicNameParameters, 0);
    }

    public void publish(InstanceFlowHeaders instanceFlowHeaders, InstanceObjectDto instance) {
        newInstanceEventProducer.send(
                InstanceFlowEventProducerRecord.<InstanceObjectDto>builder()
                        .topicNameParameters(topicNameParameters)
                        .instanceFlowHeaders(instanceFlowHeaders)
                        .value(instance)
                        .build()
        );
    }

}
