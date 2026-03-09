package no.novari.flyt.instance.kafka

import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.requestreply.RequestProducerRecord
import no.novari.kafka.requestreply.RequestTemplate
import no.novari.kafka.requestreply.RequestTemplateFactory
import no.novari.kafka.requestreply.topic.ReplyTopicService
import no.novari.kafka.requestreply.topic.configuration.ReplyTopicConfiguration
import no.novari.kafka.requestreply.topic.name.ReplyTopicNameParameters
import no.novari.kafka.requestreply.topic.name.RequestTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class InstanceFlowHeadersForRegisteredInstanceRequestProducerService(
    @Value("\${novari.kafka.application-id}") applicationId: String,
    requestTemplateFactory: RequestTemplateFactory,
    replyTopicService: ReplyTopicService,
) {
    private val requestTopicNameParameters: RequestTopicNameParameters
    private val requestTemplate: RequestTemplate<Long, InstanceFlowHeaders>

    init {
        val replyTopicNameParameters =
            ReplyTopicNameParameters
                .builder()
                .applicationId(applicationId)
                .topicNamePrefixParameters(
                    TopicNamePrefixParameters
                        .stepBuilder()
                        .orgIdApplicationDefault()
                        .domainContextApplicationDefault()
                        .build(),
                ).resourceName("instance-flow-headers-for-registered-instance")
                .build()

        replyTopicService.createOrModifyTopic(
            replyTopicNameParameters,
            ReplyTopicConfiguration
                .builder()
                .retentionTime(RETENTION_TIME)
                .build(),
        )

        requestTopicNameParameters =
            RequestTopicNameParameters
                .builder()
                .topicNamePrefixParameters(
                    TopicNamePrefixParameters
                        .stepBuilder()
                        .orgIdApplicationDefault()
                        .domainContextApplicationDefault()
                        .build(),
                ).resourceName("instance-flow-headers-for-registered-instance")
                .parameterName("instance-id")
                .build()

        requestTemplate =
            requestTemplateFactory.createTemplate(
                replyTopicNameParameters,
                Long::class.java,
                InstanceFlowHeaders::class.java,
                REPLY_TIMEOUT,
                ListenerConfiguration
                    .stepBuilder()
                    .groupIdApplicationDefault()
                    .maxPollRecordsKafkaDefault()
                    .maxPollIntervalKafkaDefault()
                    .continueFromPreviousOffsetOnAssignment()
                    .build(),
            )
    }

    fun get(instanceId: Long): InstanceFlowHeaders? {
        return requestTemplate
            .requestAndReceive(
                RequestProducerRecord
                    .builder<Long>()
                    .topicNameParameters(requestTopicNameParameters)
                    .value(instanceId)
                    .build(),
            ).value()
    }

    private companion object {
        private val RETENTION_TIME = Duration.ofDays(5)
        private val REPLY_TIMEOUT = Duration.ofSeconds(15)
    }
}
