package no.fintlabs;

import no.fintlabs.flyt.kafka.event.InstanceFlowEventConsumerFactoryService;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

@Configuration
public class InstanceReceivedEventConsumerConfiguration {

    @Bean
    public ConcurrentMessageListenerContainer<String, InstanceObjectDto> instanceReceivedEventConsumer(
            InstanceFlowEventConsumerFactoryService instanceFlowEventConsumerFactoryService,
            InstanceService instanceService,
            InstanceRegisteredEventProducerService instanceRegisteredEventProducerService
    ) {
        return instanceFlowEventConsumerFactoryService.createRecordFactory(
                InstanceObjectDto.class,
                consumerRecord -> {
                    InstanceObjectDto persistedInstance = instanceService.save(consumerRecord.getConsumerRecord().value());
                    instanceRegisteredEventProducerService.publish(
                            consumerRecord
                                    .getInstanceFlowHeaders()
                                    .toBuilder()
                                    .instanceId(persistedInstance.getId())
                                    .build(),
                            persistedInstance
                    );
                }
        ).createContainer(
                EventTopicNameParameters.builder()
                        .eventName("instance-received")
                        .build()
        );

    }

}
