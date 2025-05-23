package no.fintlabs.kafka;

import no.fintlabs.InstanceService;
import no.fintlabs.flyt.kafka.event.InstanceFlowEventConsumerFactoryService;
import no.fintlabs.kafka.event.EventConsumerConfiguration;
import no.fintlabs.kafka.event.topic.EventTopicNameParameters;
import no.fintlabs.kafka.event.topic.EventTopicService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class InstanceReceivedEventConsumerConfiguration {

    private final EventTopicNameParameters formDefinitionEventTopicNameParameters;

    public InstanceReceivedEventConsumerConfiguration(
            EventTopicService eventTopicService,
            @Value("${fint.flyt.instance-service.kafka.topic.instance-processing-events-retention-time-ms}") long retentionMs
    ) {
        this.formDefinitionEventTopicNameParameters = EventTopicNameParameters.builder()
                .eventName("instance-received")
                .build();
        eventTopicService.ensureTopic(formDefinitionEventTopicNameParameters, retentionMs);
    }

    @Bean
    public ConcurrentMessageListenerContainer<String, InstanceObjectDto> instanceReceivedEventConsumer(
            InstanceFlowEventConsumerFactoryService instanceFlowEventConsumerFactoryService,
            InstanceService instanceService,
            InstanceRegisteredEventProducerService instanceRegisteredEventProducerService,
            InstanceRegistrationErrorEventProducerService instanceRegistrationErrorEventProducerService
    ) {

        return instanceFlowEventConsumerFactoryService.createRecordFactory(
                InstanceObjectDto.class,
                instanceFlowConsumerRecord -> {
                    try {
                        InstanceObjectDto persistedInstance = instanceService.save(
                                instanceFlowConsumerRecord.getConsumerRecord().value()
                        );
                        instanceRegisteredEventProducerService.publish(
                                instanceFlowConsumerRecord
                                        .getInstanceFlowHeaders()
                                        .toBuilder()
                                        .instanceId(persistedInstance.getId())
                                        .build(),
                                persistedInstance
                        );
                    } catch (Exception e) {
                        instanceRegistrationErrorEventProducerService.publishGeneralSystemErrorEvent(
                                instanceFlowConsumerRecord.getInstanceFlowHeaders()
                        );
                    }
                },
                EventConsumerConfiguration
                        .builder()
                        .errorHandler(new DefaultErrorHandler(
                                new FixedBackOff(FixedBackOff.DEFAULT_INTERVAL, 0)
                        ))
                        .build()
        ).createContainer(
                this.formDefinitionEventTopicNameParameters
        );

    }

}
