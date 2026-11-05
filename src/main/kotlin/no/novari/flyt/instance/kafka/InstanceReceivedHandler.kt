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
        if (alreadyProcessed(partition, source.offset())) return
        val saved =
            try {
                instanceService.save(requireNotNull(source.value()) { "Instance payload must not be null" })
            } catch (failure: Exception) {
                logger.error {
                    "Instance registration failed for $partition, offset=${source.offset()}: ${failure.javaClass.name}"
                }
                null
            }
        if (saved?.id != null) {
            registeredProducer.publish(
                record.instanceFlowHeaders
                    .toBuilder()
                    .instanceId(saved.id)
                    .build(),
                saved,
            )
        } else {
            errorProducer.publishGeneralSystemErrorEvent(record.instanceFlowHeaders)
        }
        offsetRepository.advance(partition, Math.incrementExact(source.offset()))
    }

    fun reject(
        partition: TopicPartition,
        offset: Long,
        headers: Headers?,
    ) {
        if (alreadyProcessed(partition, offset)) return
        val flowHeaders =
            try {
                headers?.let(headersMapper::getInstanceFlowHeaders)
            } catch (_: Exception) {
                null
            }
        if (flowHeaders != null) {
            errorProducer.publishGeneralSystemErrorEvent(flowHeaders)
        }
        offsetRepository.advance(partition, Math.incrementExact(offset))
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
