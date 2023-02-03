package no.fintlabs;

import no.fintlabs.flyt.kafka.event.InstanceFlowEventConsumerFactoryService;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.model.instance.dtos.InstanceElementDto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

@Configuration
public class InstanceReceivedEventConsumerConfiguration {

    @Bean
    public ConcurrentMessageListenerContainer<String, InstanceElementDto> instanceReceivedEventConsumer(
            InstanceFlowEventConsumerFactoryService instanceFlowEventConsumerFactoryService,
            InstanceService instanceService,
            InstanceRegisteredEventProducerService instanceRegisteredEventProducerService,
            InstanceRegistrationErrorHandlerService instanceRegistrationErrorHandlerService
    ) {
        return instanceFlowEventConsumerFactoryService.createFactory(
                InstanceElementDto.class,
                consumerRecord -> {
                    InstanceElementDto persistedInstance = instanceService.save(consumerRecord.getConsumerRecord().value());
                    instanceRegisteredEventProducerService.publish(
                            consumerRecord
                                    .getInstanceFlowHeaders()
                                    .toBuilder()
                                    .instanceId(persistedInstance.getId())
                                    .build(),
                            persistedInstance
                    );
                },
                instanceRegistrationErrorHandlerService,
                false
        ).createContainer(
                EventTopicNameParameters.builder()
                        .eventName("instance-received")
                        .build()
        );

    }

}
