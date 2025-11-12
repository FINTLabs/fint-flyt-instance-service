package no.novari.flyt.instance.kafka;

import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
public class InstanceDeletedEventProducerService {

    private final InstanceFlowTemplate<InstanceObjectDto> instanceFlowTemplate;
    private final EventTopicNameParameters topicNameParameters;

    private static final int PARTITIONS = 1;

    public InstanceDeletedEventProducerService(
            InstanceFlowTemplateFactory instanceFlowTemplateFactory,
            EventTopicService eventTopicService,
            @Value("${novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time}") Duration retentionTime
    ) {
        this.instanceFlowTemplate = instanceFlowTemplateFactory.createTemplate(InstanceObjectDto.class);

        this.topicNameParameters = EventTopicNameParameters
                .builder()
                .topicNamePrefixParameters(TopicNamePrefixParameters
                        .builder()
                        .orgIdApplicationDefault()
                        .domainContextApplicationDefault()
                        .build()
                )
                .eventName("instance-deleted")
                .build();
        eventTopicService.createOrModifyTopic(topicNameParameters, EventTopicConfiguration
                .builder()
                .partitions(PARTITIONS)
                .retentionTime(retentionTime)
                .cleanupFrequency(EventCleanupFrequency.NORMAL)
                .build());
    }

    public void publish(InstanceFlowHeaders instanceFlowHeaders) {
        instanceFlowTemplate.send(
                InstanceFlowProducerRecord
                        .<InstanceObjectDto>builder()
                        .instanceFlowHeaders(instanceFlowHeaders)
                        .topicNameParameters(topicNameParameters)
                        .value(null)
                        .build()
        );
    }

}
