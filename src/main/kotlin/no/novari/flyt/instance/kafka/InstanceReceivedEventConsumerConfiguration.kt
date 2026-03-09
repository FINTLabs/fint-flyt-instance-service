package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.InstanceService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowListenerFactoryService
import no.novari.kafka.consuming.ErrorHandlerConfiguration
import no.novari.kafka.consuming.ErrorHandlerFactory
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.topic.EventTopicService
import no.novari.kafka.topic.configuration.EventCleanupFrequency
import no.novari.kafka.topic.configuration.EventTopicConfiguration
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.util.backoff.FixedBackOff
import java.time.Duration

@Configuration
class InstanceReceivedEventConsumerConfiguration(
    eventTopicService: EventTopicService,
    @Value("\${novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time}") retentionTime:
        Duration,
) {
    private val eventTopicNameParameters: EventTopicNameParameters =
        EventTopicNameParameters
            .builder()
            .topicNamePrefixParameters(
                TopicNamePrefixParameters
                    .stepBuilder()
                    .orgIdApplicationDefault()
                    .domainContextApplicationDefault()
                    .build(),
            ).eventName("instance-received")
            .build()

    init {
        eventTopicService.createOrModifyTopic(
            eventTopicNameParameters,
            EventTopicConfiguration
                .stepBuilder()
                .partitions(PARTITIONS)
                .retentionTime(retentionTime)
                .cleanupFrequency(EventCleanupFrequency.NORMAL)
                .build(),
        )
    }

    @Bean
    fun instanceReceivedEventConsumer(
        instanceFlowListenerFactoryService: InstanceFlowListenerFactoryService,
        instanceService: InstanceService,
        instanceRegisteredEventProducerService: InstanceRegisteredEventProducerService,
        instanceRegistrationErrorEventProducerService: InstanceRegistrationErrorEventProducerService,
        errorHandlerFactory: ErrorHandlerFactory,
    ): ConcurrentMessageListenerContainer<String, InstanceObjectDto> {
        return instanceFlowListenerFactoryService
            .createRecordListenerContainerFactory<InstanceObjectDto>(
                InstanceObjectDto::class.java,
                { instanceFlowConsumerRecord ->
                    try {
                        val persistedInstance = instanceService.save(instanceFlowConsumerRecord.consumerRecord.value())
                        val persistedInstanceId = persistedInstance.id
                        if (persistedInstanceId == null) {
                            instanceRegistrationErrorEventProducerService.publishGeneralSystemErrorEvent(
                                instanceFlowConsumerRecord.instanceFlowHeaders,
                            )
                        } else {
                            instanceRegisteredEventProducerService.publish(
                                instanceFlowConsumerRecord.instanceFlowHeaders
                                    .toBuilder()
                                    .instanceId(persistedInstanceId)
                                    .build(),
                                persistedInstance,
                            )
                        }
                    } catch (_: Exception) {
                        instanceRegistrationErrorEventProducerService.publishGeneralSystemErrorEvent(
                            instanceFlowConsumerRecord.instanceFlowHeaders,
                        )
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
                        .stepBuilder<InstanceObjectDto>()
                        .retryWithFixedInterval(Duration.ofMillis(FixedBackOff.DEFAULT_INTERVAL), 0)
                        .useDefaultRetryClassification()
                        .restartRetryOnExceptionChange()
                        .skipFailedRecords()
                        .build(),
                ),
            ).createContainer(eventTopicNameParameters)
    }

    private companion object {
        private const val PARTITIONS = 1
    }
}
