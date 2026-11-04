package no.novari.flyt.instance.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.flyt.instance.InstanceReceivedOffsetException
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.errors.RecordDeserializationException
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer
import java.sql.SQLException

class InstanceReceivedErrorHandler : CommonContainerStoppingErrorHandler() {
    private val logger = KotlinLogging.logger {}

    override fun isAckAfterHandle(): Boolean = false

    override fun handleRemaining(
        thrownException: Exception,
        records: List<ConsumerRecord<*, *>>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
    ) {
        val record = records.firstOrNull()
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
        val safeFailure = safeDiagnosticCause(thrownException)
        val location =
            if (thrownException is RecordDeserializationException) {
                "topic=${thrownException.topicPartition().topic()}, " +
                    "partition=${thrownException.topicPartition().partition()}, offset=${thrownException.offset()}"
            } else {
                "assignment or consumer failure"
            }
        logger.error(safeFailure) { "Stopping instance-received consumer: $location" }
        super.handleOtherException(safeFailure, consumer, container, batchListener)
    }
}

internal fun safeDiagnosticCause(failure: Throwable): RuntimeException {
    val diagnostic =
        when (failure) {
            is InstanceReceivedOffsetException -> failure.message
            is SQLException -> "${failure.javaClass.name}, SQLSTATE=${failure.sqlState}"
            else -> failure.javaClass.name
        }
    return RuntimeException(diagnostic, failure.cause?.let(::safeDiagnosticCause)).also { safe ->
        safe.stackTrace = failure.stackTrace
        failure.suppressed.forEach { safe.addSuppressed(safeDiagnosticCause(it)) }
    }
}
