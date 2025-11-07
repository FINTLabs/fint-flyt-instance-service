package no.novari.flyt.instance.kafka;

import lombok.extern.slf4j.Slf4j;
import no.novari.flyt.instance.InstanceService;
import no.fintlabs.flyt.kafka.instanceflow.consuming.InstanceFlowListenerFactoryService;
import no.fintlabs.kafka.consuming.ErrorHandlerConfiguration;
import no.fintlabs.kafka.consuming.ErrorHandlerFactory;
import no.fintlabs.kafka.consuming.ListenerConfiguration;
import no.fintlabs.kafka.topic.name.EventTopicNameParameters;
import no.fintlabs.kafka.topic.name.TopicNamePrefixParameters;
import no.novari.flyt.instance.model.dtos.InstanceObjectDto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

@Configuration
@Slf4j
public class InstanceDispatchedConsumerConfiguration {

    @Bean
    public ConcurrentMessageListenerContainer<String, InstanceObjectDto>
    instanceRegisteredConsumer(
            InstanceFlowListenerFactoryService instanceFlowListenerFactoryService,
            InstanceService instanceService,
            ErrorHandlerFactory errorHandlerFactory) {
        EventTopicNameParameters topic = EventTopicNameParameters
                .builder()
                .topicNamePrefixParameters(TopicNamePrefixParameters
                        .builder()
                        .orgIdApplicationDefault()
                        .domainContextApplicationDefault()
                        .build()
                )
                .eventName("instance-dispatched")
                .build();

        return instanceFlowListenerFactoryService.createRecordListenerContainerFactory(
                InstanceObjectDto.class,
                instanceFlowConsumerRecord -> instanceService.deleteInstanceByInstanceFlowHeaders(
                        instanceFlowConsumerRecord.getInstanceFlowHeaders()
                ),
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
                                .noRetries()
                                .skipFailedRecords()
                                .build()
                )
        ).createContainer(topic);
    }

}
