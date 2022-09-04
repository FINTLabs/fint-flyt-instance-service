package no.fintlabs;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducer;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerFactory;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerRecord;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.kafka.event.topic.EventTopicService;
import no.fintlabs.model.instance.Instance;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class InstanceRegisteredEventProducerService {

    private final InstanceFlowEventProducer<Instance> newInstanceEventProducer;
    private final EventTopicNameParameters topicNameParameters;

    public InstanceRegisteredEventProducerService(
            InstanceFlowEventProducerFactory instanceFlowEventProducerFactory,
            EventTopicService eventTopicService) {
        this.newInstanceEventProducer = instanceFlowEventProducerFactory.createProducer(Instance.class);
        this.topicNameParameters = EventTopicNameParameters.builder()
                .eventName("instance-registered")
                .build();
        eventTopicService.ensureTopic(topicNameParameters, 0);
    }

    public void publish(InstanceFlowHeaders instanceFlowHeaders, Instance instance) {
        newInstanceEventProducer.send(
                InstanceFlowEventProducerRecord.<Instance>builder()
                        .topicNameParameters(topicNameParameters)
                        .instanceFlowHeaders(instanceFlowHeaders)
                        .value(instance)
                        .build()
        );
    }

}
