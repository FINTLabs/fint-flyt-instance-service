package no.novari.flyt.instance.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import no.novari.flyt.instance.InstanceService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowConsumerRecord
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeadersMapper
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentHashMap

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
    private val pending = ConcurrentHashMap<TopicPartition, PendingRegistration>()

    fun handle(record: InstanceFlowConsumerRecord<InstanceObjectDto>) {
        val source = record.consumerRecord
        val partition = TopicPartition(source.topic(), source.partition())
        if (alreadyProcessed(partition, source.offset())) {
            pending.remove(partition)
            return
        }
        val registration =
            pending.computeIfAbsent(partition) {
                val saved =
                    try {
                        instanceService.save(requireNotNull(source.value()) { "Instance payload must not be null" })
                    } catch (failure: Exception) {
                        logger.error {
                            "Instance registration failed for $partition, offset=${source.offset()}: ${failure.javaClass.name}"
                        }
                        null
                    }
                val headers =
                    if (saved?.id != null) {
                        record.instanceFlowHeaders
                            .toBuilder()
                            .instanceId(saved.id)
                            .build()
                    } else {
                        record.instanceFlowHeaders
                    }
                PendingRegistration(source.offset(), headers, saved)
            }
        complete(partition, source.offset(), registration)
    }

    fun reject(
        partition: TopicPartition,
        offset: Long,
        headers: Headers?,
    ) {
        if (alreadyProcessed(partition, offset)) {
            pending.remove(partition)
            return
        }
        val registration =
            pending.computeIfAbsent(partition) {
                val flowHeaders =
                    try {
                        headers?.let(headersMapper::getInstanceFlowHeaders)
                    } catch (_: Exception) {
                        null
                    }
                PendingRegistration(offset, flowHeaders, null)
            }
        complete(partition, offset, registration)
    }

    fun clearPending(partitions: Collection<TopicPartition>) {
        partitions.forEach(pending::remove)
    }

    private fun complete(
        partition: TopicPartition,
        offset: Long,
        registration: PendingRegistration,
    ) {
        if (registration.offset != offset) {
            throw InstanceReceivedOffsetException(
                "Unfinished registration exists for $partition, offset=${registration.offset}",
            )
        }
        val nextOffset = Math.incrementExact(offset)
        if (!registration.publicationConfirmed) {
            if (registration.instance?.id != null) {
                registeredProducer
                    .publish(
                        checkNotNull(registration.headers),
                        registration.instance,
                    ).get()
            } else if (registration.headers != null) {
                errorProducer.publishGeneralSystemErrorEvent(registration.headers).get()
            }
            registration.publicationConfirmed = true
        }
        offsetRepository.advance(partition, nextOffset)
        pending.remove(partition, registration)
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

    private data class PendingRegistration(
        val offset: Long,
        val headers: InstanceFlowHeaders?,
        val instance: InstanceObjectDto?,
        var publicationConfirmed: Boolean = false,
    )
}
