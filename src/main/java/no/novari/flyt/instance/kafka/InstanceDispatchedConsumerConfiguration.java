package no.novari.flyt.instance.kafka;

import lombok.extern.slf4j.Slf4j;
import no.novari.flyt.instance.InstanceService;
import no.novari.flyt.instance.model.dtos.InstanceObjectDto;
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowListenerFactoryService;
import no.novari.kafka.consuming.ErrorHandlerConfiguration;
import no.novari.kafka.consuming.ErrorHandlerFactory;
import no.novari.kafka.consuming.ListenerConfiguration;
import no.novari.kafka.topic.name.EventTopicNameParameters;
import no.novari.kafka.topic.name.TopicNamePrefixParameters;
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
                        .stepBuilder()
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
