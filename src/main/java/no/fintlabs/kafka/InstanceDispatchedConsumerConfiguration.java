package no.fintlabs.kafka;

import lombok.extern.slf4j.Slf4j;
import no.fintlabs.InstanceService;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventConsumerFactoryService;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.kafka.event.topic.EventTopicService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

@Configuration
@Slf4j
public class InstanceDispatchedConsumerConfiguration {

    private final EventTopicService eventTopicService;

    public InstanceDispatchedConsumerConfiguration(
            EventTopicService eventTopicService
    ) {
        this.eventTopicService = eventTopicService;
    }

    @Bean
    public ConcurrentMessageListenerContainer<String, InstanceObjectDto>
    prepareInstanceToDispatchEventConsumer(
            InstanceFlowEventConsumerFactoryService instanceFlowEventConsumerFactoryService,
            InstanceService instanceService) {
        EventTopicNameParameters topic = EventTopicNameParameters.builder()
                .eventName("instance-dispatched")
                .build();

        eventTopicService.ensureTopic(topic, 0);

        return instanceFlowEventConsumerFactoryService.createRecordFactory(
                InstanceObjectDto.class,
                instanceFlowConsumerRecord -> instanceService.deleteInstanceByInstanceFlowHeaders(
                        instanceFlowConsumerRecord.getInstanceFlowHeaders()
                )
        ).createContainer(topic);
    }

}