package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.TopicPartition
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener

class InstanceReceivedOffsetInitializer(
    private val offsetRepository: InstanceReceivedOffsetRepository,
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
        } catch (failure: InstanceReceivedOffsetException) {
            throw failure
        } catch (failure: Exception) {
            throw InstanceReceivedAssignmentException(failure)
        }
    }
}

class InstanceReceivedAssignmentException(
    cause: Exception,
) : RuntimeException("Instance-received assignment failed", cause)
