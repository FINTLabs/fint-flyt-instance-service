package no.novari.flyt.instance.kafka;

import no.novari.flyt.instance.ErrorCode;
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders;
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowProducerRecord;
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplate;
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplateFactory;
import no.novari.kafka.model.Error;
import no.novari.kafka.model.ErrorCollection;
import no.novari.kafka.topic.ErrorEventTopicService;
import no.novari.kafka.topic.configuration.EventCleanupFrequency;
import no.novari.kafka.topic.configuration.EventTopicConfiguration;
import no.novari.kafka.topic.name.ErrorEventTopicNameParameters;
import no.novari.kafka.topic.name.TopicNamePrefixParameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class InstanceRetryRequestErrorEventProducerService {

    private final InstanceFlowTemplate<ErrorCollection> instanceFlowTemplate;
    private final ErrorEventTopicNameParameters topicNameParameters;

    private static final int PARTITIONS = 1;

    public InstanceRetryRequestErrorEventProducerService(
            ErrorEventTopicService errorEventTopicService,
            InstanceFlowTemplateFactory instanceFlowTemplateFactory,
            @Value("${novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time}") Duration retentionTime) {
        this.instanceFlowTemplate = instanceFlowTemplateFactory.createTemplate(ErrorCollection.class);

        this.topicNameParameters = ErrorEventTopicNameParameters
                .builder()
                .topicNamePrefixParameters(TopicNamePrefixParameters
                        .builder()
                        .orgIdApplicationDefault()
                        .domainContextApplicationDefault()
                        .build()
                )
                .errorEventName("instance-retry-request-error")
                .build();

        errorEventTopicService.createOrModifyTopic(topicNameParameters, EventTopicConfiguration
                .builder()
                .partitions(PARTITIONS)
                .retentionTime(retentionTime)
                .cleanupFrequency(EventCleanupFrequency.NORMAL)
                .build()
        );
    }

    public void publishGeneralSystemErrorEvent(InstanceFlowHeaders instanceFlowHeaders) {
        instanceFlowTemplate.send(
                InstanceFlowProducerRecord
                        .<ErrorCollection>builder()
                        .instanceFlowHeaders(instanceFlowHeaders)
                        .topicNameParameters(topicNameParameters)
                        .value(new ErrorCollection(Error
                                .builder()
                                .errorCode(ErrorCode.GENERAL_SYSTEM_ERROR.getCode())
                                .build()))
                        .build()
        );
    }


}
