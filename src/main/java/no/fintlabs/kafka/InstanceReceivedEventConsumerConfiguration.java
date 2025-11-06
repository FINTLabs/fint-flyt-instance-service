package no.fintlabs.kafka;

import no.fintlabs.InstanceService;
import no.fintlabs.flyt.kafka.instanceflow.consuming.InstanceFlowListenerFactoryService;
import no.fintlabs.kafka.consuming.ErrorHandlerConfiguration;
import no.fintlabs.kafka.consuming.ErrorHandlerFactory;
import no.fintlabs.kafka.consuming.ListenerConfiguration;
import no.fintlabs.kafka.topic.EventTopicService;
import no.fintlabs.kafka.topic.configuration.EventCleanupFrequency;
import no.fintlabs.kafka.topic.configuration.EventTopicConfiguration;
import no.fintlabs.kafka.topic.name.EventTopicNameParameters;
import no.fintlabs.kafka.topic.name.TopicNamePrefixParameters;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
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
            @Value("${fint.flyt.instance-service.kafka.topic.instance-processing-events-retention-time}") Duration retentionTime
    ) {
        this.eventTopicNameParameters = EventTopicNameParameters
                .builder()
                .topicNamePrefixParameters(TopicNamePrefixParameters
                        .builder()
                        .orgIdApplicationDefault()
                        .domainContextApplicationDefault()
                        .build()
                )
                .eventName("instance-received")
                .build();
        eventTopicService.createOrModifyTopic(eventTopicNameParameters, EventTopicConfiguration
                .builder()
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
                                .skipFailedRecords()
                                .build()
                )
        ).createContainer(
                this.eventTopicNameParameters
        );

    }

}
