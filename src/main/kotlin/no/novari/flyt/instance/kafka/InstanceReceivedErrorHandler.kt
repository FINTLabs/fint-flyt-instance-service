package no.novari.flyt.instance.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeadersMapper
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RecordDeserializationException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.header.Headers
import org.hibernate.exception.ConstraintViolationException
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer
import java.sql.SQLException

class InstanceReceivedErrorHandler(
    private val handler: InstanceReceivedHandler,
    private val headersMapper: InstanceFlowHeadersMapper,
) : CommonContainerStoppingErrorHandler() {
    private val logger = KotlinLogging.logger {}

    override fun isAckAfterHandle(): Boolean = false

    override fun handleRemaining(
        thrownException: Exception,
        records: List<ConsumerRecord<*, *>>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
    ) {
        val record = records.firstOrNull()
        if (record != null && unreadableHeaders(record.headers())) {
            skip(
                TopicPartition(record.topic(), record.partition()),
                record.offset(),
                record.headers(),
                consumer,
                container,
            )
            return
        }
        val safeFailure = safeDiagnosticCause(thrownException)
        logger.error(safeFailure) {
            "Stopping instance-received consumer: topic=${record?.topic()}, " +
                "partition=${record?.partition()}, offset=${record?.offset()}"
        }
        super.handleRemaining(safeFailure, records, consumer, container)
    }

    override fun handleOtherException(
        thrownException: Exception,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        batchListener: Boolean,
    ) {
        if (thrownException is RecordDeserializationException) {
            skip(
                thrownException.topicPartition(),
                thrownException.offset(),
                thrownException.headers(),
                consumer,
                container,
            )
            return
        }
        val safeFailure = safeDiagnosticCause(thrownException)
        logger.error(safeFailure) { "Stopping instance-received consumer after assignment or consumer failure" }
        super.handleOtherException(safeFailure, consumer, container, batchListener)
    }

    private fun unreadableHeaders(headers: Headers): Boolean =
        try {
            headersMapper.getInstanceFlowHeaders(headers)
            false
        } catch (_: Exception) {
            true
        }

    private fun skip(
        partition: TopicPartition,
        offset: Long,
        headers: Headers?,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
    ) {
        try {
            val nextOffset = Math.incrementExact(offset)
            handler.reject(partition, offset, headers)
            consumer.seek(partition, nextOffset)
            consumer.commitSync(mapOf(partition to OffsetAndMetadata(nextOffset)))
        } catch (failure: Exception) {
            val safeFailure = safeDiagnosticCause(failure)
            logger.error(safeFailure) { "Cannot skip instance-received message for $partition, offset=$offset" }
            super.handleOtherException(safeFailure, consumer, container, false)
            return
        }
        logger.warn { "Skipped invalid instance-received message for $partition, offset=$offset" }
    }
}

internal fun safeDiagnosticCause(failure: Throwable): RuntimeException {
    val diagnostic =
        when (failure) {
            is InstanceReceivedOffsetException -> failure.message
            is SQLException -> "${failure.javaClass.name}, SQLSTATE=${failure.sqlState}"
            is ConstraintViolationException -> "${failure.javaClass.name}, constraint=${failure.constraintName}"
            is TimeoutException -> "${failure.javaClass.name}: ${failure.message}"
            else -> failure.javaClass.name
        }
    return RuntimeException(diagnostic, failure.cause?.let(::safeDiagnosticCause)).also { safe ->
        safe.stackTrace = failure.stackTrace
        failure.suppressed.forEach { safe.addSuppressed(safeDiagnosticCause(it)) }
    }
}
