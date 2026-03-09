package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowProducerRecord
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplate
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplateFactory
import no.novari.kafka.topic.EventTopicService
import no.novari.kafka.topic.configuration.EventCleanupFrequency
import no.novari.kafka.topic.configuration.EventTopicConfiguration
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class InstanceRegisteredEventProducerService(
    instanceFlowTemplateFactory: InstanceFlowTemplateFactory,
    eventTopicService: EventTopicService,
    @Value("\${novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time}") retentionTime:
        Duration,
) {
    private val instanceFlowTemplate: InstanceFlowTemplate<InstanceObjectDto> =
        instanceFlowTemplateFactory.createTemplate(InstanceObjectDto::class.java)
    private val topicNameParameters: EventTopicNameParameters =
        EventTopicNameParameters
            .builder()
            .topicNamePrefixParameters(
                TopicNamePrefixParameters
                    .stepBuilder()
                    .orgIdApplicationDefault()
                    .domainContextApplicationDefault()
                    .build(),
            ).eventName("instance-registered")
            .build()

    init {
        eventTopicService.createOrModifyTopic(
            topicNameParameters,
            EventTopicConfiguration
                .stepBuilder()
                .partitions(PARTITIONS)
                .retentionTime(retentionTime)
                .cleanupFrequency(EventCleanupFrequency.NORMAL)
                .build(),
        )
    }

    fun publish(
        instanceFlowHeaders: InstanceFlowHeaders,
        instance: InstanceObjectDto,
    ) {
        instanceFlowTemplate.send(
            InstanceFlowProducerRecord
                .builder<InstanceObjectDto>()
                .instanceFlowHeaders(instanceFlowHeaders)
                .topicNameParameters(topicNameParameters)
                .value(instance)
                .build(),
        )
    }

    private companion object {
        private const val PARTITIONS = 1
    }
}
