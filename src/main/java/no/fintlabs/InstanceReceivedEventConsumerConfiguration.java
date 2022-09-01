package no.fintlabs;

import no.fintlabs.flyt.kafka.event.InstanceFlowEventConsumerFactoryService;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.model.instance.Instance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

@Configuration
public class InstanceReceivedEventConsumerConfiguration {

    @Bean
    public ConcurrentMessageListenerContainer<String, Instance> instanceReceivedEventConsumer(
            InstanceFlowEventConsumerFactoryService instanceFlowEventConsumerFactoryService,
            InstanceRepository instanceRepository,
            InstanceRegisteredEventProducerService instanceRegisteredEventProducerService,
            InstanceRegistrationErrorHandlerService instanceRegistrationErrorHandlerService
    ) {
        return instanceFlowEventConsumerFactoryService.createFactory(
                Instance.class,
                consumerRecord -> {
                    Instance persistedInstance = instanceRepository.save(consumerRecord.getConsumerRecord().value());
                    instanceRepository.flush();
                    instanceRegisteredEventProducerService.publish(
                            consumerRecord
                                    .getInstanceFlowHeaders()
                                    .toBuilder()
                                    .instanceId(String.valueOf(persistedInstance.getId()))
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
