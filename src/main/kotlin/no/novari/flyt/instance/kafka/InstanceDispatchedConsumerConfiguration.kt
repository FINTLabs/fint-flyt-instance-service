package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.InstanceService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowListenerFactoryService
import no.novari.kafka.consuming.ErrorHandlerConfiguration
import no.novari.kafka.consuming.ErrorHandlerFactory
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer

@Configuration
class InstanceDispatchedConsumerConfiguration {
    @Bean
    fun instanceRegisteredConsumer(
        instanceFlowListenerFactoryService: InstanceFlowListenerFactoryService,
        instanceService: InstanceService,
        errorHandlerFactory: ErrorHandlerFactory,
    ): ConcurrentMessageListenerContainer<String, InstanceObjectDto> {
        val topic =
            EventTopicNameParameters
                .builder()
                .topicNamePrefixParameters(
                    TopicNamePrefixParameters
                        .stepBuilder()
                        .orgIdApplicationDefault()
                        .domainContextApplicationDefault()
                        .build(),
                ).eventName("instance-dispatched")
                .build()

        return instanceFlowListenerFactoryService
            .createRecordListenerContainerFactory<InstanceObjectDto>(
                InstanceObjectDto::class.java,
                { instanceFlowConsumerRecord ->
                    instanceService.deleteInstanceByInstanceFlowHeaders(instanceFlowConsumerRecord.instanceFlowHeaders)
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
                        .noRetries()
                        .skipFailedRecords()
                        .build(),
                ),
            ).createContainer(topic)
    }
}
