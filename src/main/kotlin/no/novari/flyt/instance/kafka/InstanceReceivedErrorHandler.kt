package no.novari.flyt.instance.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeadersMapper
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RecordDeserializationException
import org.apache.kafka.common.header.Headers
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ListenerUtils
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.util.backoff.FixedBackOff

class InstanceReceivedErrorHandler(
    private val handler: InstanceReceivedHandler,
    private val headersMapper: InstanceFlowHeadersMapper,
) : DefaultErrorHandler(FixedBackOff(5_000, FixedBackOff.UNLIMITED_ATTEMPTS)) {
    private val consumerLogger = KotlinLogging.logger {}
    private val stopping = CommonContainerStoppingErrorHandler()

    init {
        isAckAfterHandle = false
        setSeekAfterError(false)
        setClassifications(emptyMap(), true)
    }

    override fun handleOne(
        thrownException: Exception,
        record: ConsumerRecord<*, *>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
    ): Boolean {
        if (fatalPositionFailure(thrownException)) {
            stop(thrownException, consumer, container)
            return false
        }
        if (unreadableHeaders(record.headers())) {
            return skip(
                TopicPartition(record.topic(), record.partition()),
                record.offset(),
                record.headers(),
                consumer,
                container,
            )
        }
        consumerLogger.error(thrownException) {
            "Retrying instance-received processing: topic=${record.topic()}, " +
                "partition=${record.partition()}, offset=${record.offset()}"
        }
        return super.handleOne(thrownException, record, consumer, container)
    }

    override fun handleOtherException(
        thrownException: Exception,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        batchListener: Boolean,
    ) {
        if (fatalPositionFailure(thrownException)) {
            stop(thrownException, consumer, container)
            return
        }
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
        consumerLogger.error(thrownException) { "Retrying instance-received assignment or polling after failure" }
        if (container.isRunning &&
            generateSequence(thrownException as Throwable) { it.cause }.any {
                it is InstanceReceivedAssignmentException
            }
        ) {
            consumer.enforceRebalance()
        }
        ListenerUtils.stoppableSleep(container, 5_000)
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
    ): Boolean {
        try {
            val nextOffset = Math.incrementExact(offset)
            handler.reject(partition, offset, headers)
            consumer.seek(partition, nextOffset)
            consumer.commitSync(mapOf(partition to OffsetAndMetadata(nextOffset)))
        } catch (failure: Exception) {
            if (fatalPositionFailure(failure)) {
                stop(failure, consumer, container)
                return false
            }
            consumerLogger.error(
                failure,
            ) { "Retrying rejection of instance-received message for $partition, offset=$offset" }
            ListenerUtils.stoppableSleep(container, 5_000)
            return false
        }
        consumerLogger.warn { "Skipped invalid instance-received message for $partition, offset=$offset" }
        return true
    }

    private fun fatalPositionFailure(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any {
            it is InstanceReceivedOffsetException || it is NoOffsetForPartitionException ||
                it is OffsetOutOfRangeException
        }

    private fun stop(
        failure: Exception,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
    ) {
        consumerLogger.error(failure) {
            "Stopping instance-received consumer because its starting position is invalid or taken over"
        }
        stopping.handleOtherException(failure, consumer, container, false)
    }
}
