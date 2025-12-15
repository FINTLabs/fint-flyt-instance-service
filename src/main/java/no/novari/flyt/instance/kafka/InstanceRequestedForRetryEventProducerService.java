package no.novari.flyt.instance.kafka;

import no.novari.flyt.instance.model.dtos.InstanceObjectDto;
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders;
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowProducerRecord;
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplate;
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplateFactory;
import no.novari.kafka.topic.EventTopicService;
import no.novari.kafka.topic.configuration.EventCleanupFrequency;
import no.novari.kafka.topic.configuration.EventTopicConfiguration;
import no.novari.kafka.topic.name.EventTopicNameParameters;
import no.novari.kafka.topic.name.TopicNamePrefixParameters;
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
            @Value("${novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time}") Duration retentionMs
    ) {
        this.instanceFlowTemplate = instanceFlowTemplateFactory.createTemplate(InstanceObjectDto.class);
        this.topicNameParameters = EventTopicNameParameters.builder()
                .topicNamePrefixParameters(TopicNamePrefixParameters
                        .stepBuilder()
                        .orgIdApplicationDefault()
                        .domainContextApplicationDefault()
                        .build()
                )
                .eventName("instance-requested-for-retry")
                .build();
        eventTopicService.createOrModifyTopic(topicNameParameters, EventTopicConfiguration
                .stepBuilder()
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
