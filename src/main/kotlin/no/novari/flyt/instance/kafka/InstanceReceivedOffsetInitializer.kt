package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.TopicPartition
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener
import org.springframework.kafka.listener.MessageListenerContainer

class InstanceReceivedOffsetInitializer(
    private val offsetRepository: InstanceReceivedOffsetRepository,
    private val errorHandler: InstanceReceivedErrorHandler,
    private val container: MessageListenerContainer,
) : ConsumerAwareRebalanceListener {
    override fun onPartitionsAssigned(
        consumer: Consumer<*, *>,
        partitions: Collection<TopicPartition>,
    ) {
        try {
            val missing = partitions.filter { offsetRepository.findNextOffset(it) == null }.toSet()
            if (missing.isEmpty()) return
            val committed = consumer.committed(missing)
            missing.forEach { partition ->
                val position =
                    committed[partition]
                        ?: throw InstanceReceivedOffsetException("No committed Kafka position exists for $partition")
                offsetRepository.initialize(partition, position.offset())
            }
        } catch (failure: Exception) {
            errorHandler.handleOtherException(failure, consumer, container, false)
        }
    }
}
