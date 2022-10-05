package no.fintlabs;

import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.kafka.common.topic.TopicCleanupPolicyParameters;
import no.fintlabs.kafka.requestreply.RequestProducer;
import no.fintlabs.kafka.requestreply.RequestProducerConfiguration;
import no.fintlabs.kafka.requestreply.RequestProducerFactory;
import no.fintlabs.kafka.requestreply.RequestProducerRecord;
import no.fintlabs.kafka.requestreply.topic.ReplyTopicNameParameters;
import no.fintlabs.kafka.requestreply.topic.ReplyTopicService;
import no.fintlabs.kafka.requestreply.topic.RequestTopicNameParameters;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class InstanceFlowHeadersForRegisteredInstanceRequestProducerService {

    private final RequestTopicNameParameters requestTopicNameParameters;
    private final RequestProducer<Long, InstanceFlowHeaders> requestProducer;

    public InstanceFlowHeadersForRegisteredInstanceRequestProducerService(
            @Value("${fint.kafka.application-id}") String applicationId,
            RequestProducerFactory requestProducerFactory,
            ReplyTopicService replyTopicService
    ) {
        ReplyTopicNameParameters replyTopicNameParameters = ReplyTopicNameParameters.builder()
                .applicationId(applicationId)
                .resource("instance-flow-headers-for-registered-instance")
                .build();

        replyTopicService.ensureTopic(replyTopicNameParameters, 0, TopicCleanupPolicyParameters.builder().build());

        this.requestTopicNameParameters = RequestTopicNameParameters.builder()
                .resource("instance-flow-headers-for-registered-instance")
                .parameterName("instance-id")
                .build();

        this.requestProducer = requestProducerFactory.createProducer(
                replyTopicNameParameters,
                Long.class,
                InstanceFlowHeaders.class,
                RequestProducerConfiguration
                        .builder()
                        .defaultReplyTimeout(Duration.ofSeconds(15))
                        .build()
        );
    }

    public Optional<InstanceFlowHeaders> get(Long instanceId) {
        return requestProducer.requestAndReceive(
                RequestProducerRecord.<Long>builder()
                        .topicNameParameters(requestTopicNameParameters)
                        .value(instanceId)
                        .build()
        ).map(ConsumerRecord::value);
    }

}
