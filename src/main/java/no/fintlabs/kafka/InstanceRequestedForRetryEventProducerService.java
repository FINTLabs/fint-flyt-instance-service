package no.fintlabs.kafka;

import no.fintlabs.flyt.kafka.instanceflow.headers.InstanceFlowHeaders;
import no.fintlabs.flyt.kafka.instanceflow.producing.InstanceFlowProducerRecord;
import no.fintlabs.flyt.kafka.instanceflow.producing.InstanceFlowTemplate;
import no.fintlabs.flyt.kafka.instanceflow.producing.InstanceFlowTemplateFactory;
import no.fintlabs.kafka.topic.EventTopicService;
import no.fintlabs.kafka.topic.configuration.EventCleanupFrequency;
import no.fintlabs.kafka.topic.configuration.EventTopicConfiguration;
import no.fintlabs.kafka.topic.name.EventTopicNameParameters;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class InstanceRequestedForRetryEventProducerService {

    private final InstanceFlowTemplate<InstanceObjectDto> instanceFlowTemplate;
    private final EventTopicNameParameters topicNameParameters;

    private static final int PARTITIONS = 1;

    public InstanceRequestedForRetryEventProducerService(
            InstanceFlowTemplateFactory instanceFlowTemplateFactory,
            EventTopicService eventTopicService,
            @Value("${fint.flyt.instance-service.kafka.topic.instance-processing-events-retention-time}") Duration retentionMs
    ) {
        this.instanceFlowTemplate = instanceFlowTemplateFactory.createTemplate(InstanceObjectDto.class);
        this.topicNameParameters = EventTopicNameParameters.builder()
                .eventName("instance-requested-for-retry")
                .build();
        eventTopicService.createOrModifyTopic(topicNameParameters, EventTopicConfiguration
                .builder()
                .partitions(PARTITIONS)
                .retentionTime(retentionMs)
                .cleanupFrequency(EventCleanupFrequency.NORMAL)
                .build()
        );
    }

    public void publish(InstanceFlowHeaders instanceFlowHeaders, InstanceObjectDto instance) {
        instanceFlowTemplate.send(
                InstanceFlowProducerRecord
                        .<InstanceObjectDto>builder()
                        .instanceFlowHeaders(instanceFlowHeaders)
                        .topicNameParameters(topicNameParameters)
                        .value(instance)
                        .build()
        );
    }

}
