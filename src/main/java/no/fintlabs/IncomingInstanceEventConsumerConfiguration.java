package no.fintlabs;

import no.fintlabs.flyt.kafka.event.InstanceFlowEventConsumerFactoryService;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.model.instance.Instance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonLoggingErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

@Configuration
public class IncomingInstanceEventConsumerConfiguration {

    @Bean
    public ConcurrentMessageListenerContainer<String, Instance> incomingInstanceConsumer(
            InstanceFlowEventConsumerFactoryService instanceFlowEventConsumerFactoryService,
            InstanceRepository instanceRepository,
            NewInstanceEventProducerService newInstanceEventProducerService
    ) {
        return instanceFlowEventConsumerFactoryService.createFactory(
                Instance.class,
                consumerRecord -> {
                    Instance persistedInstance = instanceRepository.save(consumerRecord.getConsumerRecord().value());
                    instanceRepository.flush();
                    newInstanceEventProducerService.sendNewInstance(
                            consumerRecord
                                    .getInstanceFlowHeaders()
                                    .toBuilder()
                                    .instanceId(String.valueOf(persistedInstance.getId()))
                                    .build(),
                            persistedInstance
                    );
                },
                new CommonLoggingErrorHandler(),
                false
        ).createContainer(
                EventTopicNameParameters.builder()
                        .eventName("incoming-instance")
                        .build()
        );

    }

}
