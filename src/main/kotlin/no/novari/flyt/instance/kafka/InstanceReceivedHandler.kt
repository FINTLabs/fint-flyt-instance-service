package no.novari.flyt.instance.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import no.novari.flyt.instance.InstanceService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NEVER)
class InstanceReceivedHandler(
    private val instanceService: InstanceService,
    private val registeredProducer: InstanceRegisteredEventProducerService,
    private val errorProducer: InstanceRegistrationErrorProducerService,
    private val offsetRepository: InstanceReceivedOffsetRepository,
) {
    private val logger = KotlinLogging.logger {}

    fun handle(record: InstanceFlowConsumerRecord<InstanceObjectDto>) {
        val source = record.consumerRecord
        val partition = TopicPartition(source.topic(), source.partition())
        val nextOffset = Math.incrementExact(source.offset())
        val storedNextOffset =
            offsetRepository.findNextOffset(partition)
                ?: throw InstanceReceivedOffsetException("No position initialized for $partition")
        if (source.offset() < storedNextOffset) return
        val payload = requireNotNull(source.value()) { "Instance payload must not be null" }

        try {
            val saved =
                try {
                    instanceService.save(payload)
                } catch (failure: Exception) {
                    if (failure is InterruptedException || Thread.currentThread().isInterrupted) throw failure
                    logger.error(safeDiagnosticCause(failure)) {
                        "Instance registration failed for topic=${source.topic()}, " +
                            "partition=${source.partition()}, offset=${source.offset()}"
                    }
                    null
                }
            if (saved?.id == null) {
                errorProducer.publishGeneralSystemErrorEvent(record.instanceFlowHeaders).get()
            } else {
                registeredProducer
                    .publish(
                        record.instanceFlowHeaders
                            .toBuilder()
                            .instanceId(saved.id)
                            .build(),
                        saved,
                    ).get()
            }
            offsetRepository.advance(partition, nextOffset)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
    }
}
