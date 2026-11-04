package no.novari.flyt.instance.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import no.novari.flyt.instance.InstanceService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowConsumerRecord
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeadersMapper
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
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
    private val headersMapper: InstanceFlowHeadersMapper,
) {
    private val logger = KotlinLogging.logger {}

    fun handle(record: InstanceFlowConsumerRecord<InstanceObjectDto>) {
        val source = record.consumerRecord
        val partition = TopicPartition(source.topic(), source.partition())
        val nextOffset = Math.incrementExact(source.offset())
        if (alreadyProcessed(partition, source.offset())) return
        val saved =
            try {
                instanceService.save(requireNotNull(source.value()) { "Instance payload must not be null" })
            } catch (failure: Exception) {
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
    }

    fun reject(
        partition: TopicPartition,
        offset: Long,
        headers: Headers?,
    ) {
        val nextOffset = Math.incrementExact(offset)
        if (alreadyProcessed(partition, offset)) return
        val flowHeaders =
            try {
                headers?.let(headersMapper::getInstanceFlowHeaders)
            } catch (_: Exception) {
                null
            }
        if (flowHeaders != null) {
            errorProducer.publishGeneralSystemErrorEvent(flowHeaders).get()
        }
        offsetRepository.advance(partition, nextOffset)
    }

    private fun alreadyProcessed(
        partition: TopicPartition,
        offset: Long,
    ): Boolean {
        val storedNextOffset =
            offsetRepository.findNextOffset(partition)
                ?: throw InstanceReceivedOffsetException("No position initialized for $partition")
        return offset < storedNextOffset
    }
}
