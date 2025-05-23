package no.fintlabs.kafka;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducer;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerFactory;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerRecord;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.kafka.event.topic.EventTopicService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class InstanceRegisteredEventProducerService {

    private final InstanceFlowEventProducer<InstanceObjectDto> newInstanceEventProducer;
    private final EventTopicNameParameters topicNameParameters;

    public InstanceRegisteredEventProducerService(
            InstanceFlowEventProducerFactory instanceFlowEventProducerFactory,
            EventTopicService eventTopicService,
            @Value("${fint.flyt.instance-service.kafka.topic.instance-processing-events-retention-time-ms}") long retentionMs
    ) {
        this.newInstanceEventProducer = instanceFlowEventProducerFactory.createProducer(InstanceObjectDto.class);
        this.topicNameParameters = EventTopicNameParameters.builder()
                .eventName("instance-registered")
                .build();

        eventTopicService.ensureTopic(topicNameParameters, retentionMs);
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
