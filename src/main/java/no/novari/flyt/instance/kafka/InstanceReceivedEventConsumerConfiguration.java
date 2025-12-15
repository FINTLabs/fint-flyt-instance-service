package no.novari.flyt.instance.kafka;

import no.novari.flyt.instance.InstanceService;
import no.novari.flyt.instance.model.dtos.InstanceObjectDto;
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowListenerFactoryService;
import no.novari.kafka.consuming.ErrorHandlerConfiguration;
import no.novari.kafka.consuming.ErrorHandlerFactory;
import no.novari.kafka.consuming.ListenerConfiguration;
import no.novari.kafka.topic.EventTopicService;
import no.novari.kafka.topic.configuration.EventCleanupFrequency;
import no.novari.kafka.topic.configuration.EventTopicConfiguration;
import no.novari.kafka.topic.name.EventTopicNameParameters;
import no.novari.kafka.topic.name.TopicNamePrefixParameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration
public class InstanceReceivedEventConsumerConfiguration {

    private final EventTopicNameParameters eventTopicNameParameters;

    private static final int PARTITIONS = 1;

    public InstanceReceivedEventConsumerConfiguration(
            EventTopicService eventTopicService,
            @Value("${novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time}") Duration retentionTime
    ) {
        this.eventTopicNameParameters = EventTopicNameParameters
                .builder()
                .topicNamePrefixParameters(TopicNamePrefixParameters
                        .stepBuilder()
                        .orgIdApplicationDefault()
                        .domainContextApplicationDefault()
                        .build()
                )
                .eventName("instance-received")
                .build();
        eventTopicService.createOrModifyTopic(eventTopicNameParameters, EventTopicConfiguration
                .stepBuilder()
                .partitions(PARTITIONS)
                .retentionTime(retentionTime)
                .cleanupFrequency(EventCleanupFrequency.NORMAL)
                .build());
    }

    @Bean
    public ConcurrentMessageListenerContainer<String, InstanceObjectDto> instanceReceivedEventConsumer(
            InstanceFlowListenerFactoryService instanceFlowListenerFactoryService,
            InstanceService instanceService,
            InstanceRegisteredEventProducerService instanceRegisteredEventProducerService,
            InstanceRegistrationErrorEventProducerService instanceRegistrationErrorEventProducerService,
            ErrorHandlerFactory errorHandlerFactory
    ) {

        return instanceFlowListenerFactoryService.createRecordListenerContainerFactory(
                InstanceObjectDto.class,
                instanceFlowConsumerRecord -> {
                    try {
                        InstanceObjectDto persistedInstance = instanceService.save(
                                instanceFlowConsumerRecord.getConsumerRecord().value()
                        );
                        instanceRegisteredEventProducerService.publish(
                                instanceFlowConsumerRecord
                                        .getInstanceFlowHeaders()
                                        .toBuilder()
                                        .instanceId(persistedInstance.getId())
                                        .build(),
                                persistedInstance
                        );
                    } catch (Exception e) {
                        instanceRegistrationErrorEventProducerService.publishGeneralSystemErrorEvent(
                                instanceFlowConsumerRecord.getInstanceFlowHeaders()
                        );
                    }
                },
                ListenerConfiguration
                        .stepBuilder()
                        .groupIdApplicationDefault()
                        .maxPollRecordsKafkaDefault()
                        .maxPollIntervalKafkaDefault()
                        .continueFromPreviousOffsetOnAssignment()
                        .build(),
                errorHandlerFactory.createErrorHandler(
                        ErrorHandlerConfiguration
                                .stepBuilder()
                                .retryWithFixedInterval(Duration.ofMillis(FixedBackOff.DEFAULT_INTERVAL), 0)
                                .useDefaultRetryClassification()
                                .restartRetryOnExceptionChange()
                                .skipFailedRecords()
                                .build()
                )
        ).createContainer(
                this.eventTopicNameParameters
        );

    }

}
