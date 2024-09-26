package no.fintlabs.kafka;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducer;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerFactory;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventProducerRecord;
import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.kafka.event.topic.EventTopicService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class InstanceDeletedEventProducerService {

    private final InstanceFlowEventProducer<InstanceObjectDto> newInstanceEventProducer;
    private final EventTopicNameParameters topicNameParameters;

    public InstanceDeletedEventProducerService(
            InstanceFlowEventProducerFactory instanceFlowEventProducerFactory,
            EventTopicService eventTopicService
    ) {
        this.newInstanceEventProducer = instanceFlowEventProducerFactory.createProducer(InstanceObjectDto.class);
        this.topicNameParameters = EventTopicNameParameters.builder()
                .eventName("instance-deleted")
                .build();
        eventTopicService.ensureTopic(topicNameParameters, 0);
    }

    public void publish(InstanceFlowHeaders instanceFlowHeaders) {

        log.info("File UUIDs published to intance-deleted: {}", instanceFlowHeaders.getFileIds().toString());

        newInstanceEventProducer.send(
                InstanceFlowEventProducerRecord.<InstanceObjectDto>builder()
                        .topicNameParameters(topicNameParameters)
                        .instanceFlowHeaders(instanceFlowHeaders)
                        .value(null)
                        .build()
        );
    }

}
