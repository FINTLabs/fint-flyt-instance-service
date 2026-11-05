package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowListenerFactoryService
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeadersMapper
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.topic.EventTopicService
import no.novari.kafka.topic.configuration.EventCleanupFrequency
import no.novari.kafka.topic.configuration.EventTopicConfiguration
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
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
        instanceReceivedHandler: InstanceReceivedHandler,
        offsetRepository: InstanceReceivedOffsetRepository,
        headersMapper: InstanceFlowHeadersMapper,
    ): ConcurrentMessageListenerContainer<String, InstanceObjectDto> {
        val errorHandler = InstanceReceivedErrorHandler(instanceReceivedHandler, headersMapper)
        val container =
            instanceFlowListenerFactoryService
                .createRecordListenerContainerFactory<InstanceObjectDto>(
                    InstanceObjectDto::class.java,
                    { record ->
                        instanceReceivedHandler.handle(record)
                        errorHandler.processingSucceeded()
                    },
                    ListenerConfiguration
                        .stepBuilder()
                        .groupIdApplicationDefault()
                        .maxPollRecords(1)
                        .maxPollIntervalKafkaDefault()
                        .continueFromPreviousOffsetOnAssignment()
                        .build(),
                    errorHandler,
                ).createContainer(eventTopicNameParameters)
        container.containerProperties.apply {
            ackMode = ContainerProperties.AckMode.RECORD
            assignmentCommitOption = ContainerProperties.AssignmentCommitOption.NEVER
            isStopImmediate = true
            isMissingTopicsFatal = true
            kafkaConsumerProperties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            kafkaConsumerProperties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none")
            consumerRebalanceListener = InstanceReceivedOffsetInitializer(offsetRepository)
            authExceptionRetryInterval = Duration.ofSeconds(5)
        }
        return container
    }

    private companion object {
        private const val PARTITIONS = 1
    }
}
