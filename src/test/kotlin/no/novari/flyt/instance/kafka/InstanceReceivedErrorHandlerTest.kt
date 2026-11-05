package no.novari.flyt.instance.kafka

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeadersMapper
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RecordDeserializationException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.kafka.KafkaException
import org.springframework.kafka.listener.MessageListenerContainer
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class InstanceReceivedErrorHandlerTest {
    private val handler = mock<InstanceReceivedHandler>()
    private val headersMapper = mock<InstanceFlowHeadersMapper>()
    private val errors = InstanceReceivedErrorHandler(handler, headersMapper)
    private val container = mock<MessageListenerContainer>()
    private val consumer = mock<Consumer<String, String>>()
    private val partition = TopicPartition("afk-no.flyt.instance-received", 0)

    @BeforeEach
    fun setUp() {
        val headers =
            InstanceFlowHeaders
                .builder()
                .sourceApplicationId(1L)
                .correlationId(UUID.randomUUID())
                .build()
        whenever(headersMapper.getInstanceFlowHeaders(any())).thenReturn(headers)
    }

    @Test
    fun `invalid position stops with its original cause and without logging record contents`() {
        val failure = InstanceReceivedOffsetException("Position has been taken over by history-service")

        val stopped =
            assertThrows(KafkaException::class.java) {
                errors.handleOne(
                    failure,
                    ConsumerRecord<String, String>(partition.topic(), 0, 42, null, "private-source-value"),
                    consumer,
                    container,
                )
            }

        verify(container, timeout(2000)).stopAbnormally(any())
        assertFalse(errors.isAckAfterHandle)
        val diagnostic = stopped.stackTraceToString()
        assertFalse(diagnostic.contains("private-source-value"))
        assertSame(failure, stopped.cause)
    }

    @Test
    fun `technical record failures are retried without stopping the consumer`() {
        val failure = TimeoutException("Broker unavailable")

        assertFalse(
            errors.handleOne(
                failure,
                ConsumerRecord<String, String>(partition.topic(), 0, 42, null, "private-source-value"),
                consumer,
                container,
            ),
        )
        verify(container, never()).stopAbnormally(any())
    }

    @Test
    fun `rejects unreadable content before seeking and committing without stopping`() {
        errors.handleOtherException(
            unreadableRecord(),
            consumer,
            container,
            false,
        )

        inOrder(handler, consumer) {
            verify(handler).reject(partition, 42, null)
            verify(consumer).seek(partition, 43L)
            verify(consumer).commitSync(mapOf(partition to OffsetAndMetadata(43)))
        }
        verifyNoInteractions(container)
    }

    @Test
    fun `rejects malformed headers before continuing with the next record`() {
        val record = ConsumerRecord<String, String>(partition.topic(), 0, 42, null, "private-source-value")
        whenever(
            headersMapper.getInstanceFlowHeaders(record.headers()),
        ).thenThrow(IllegalArgumentException("private header"))

        errors.handleOne(IllegalArgumentException("private header"), record, consumer, container)

        verify(handler).reject(partition, 42, record.headers())
        verify(consumer).seek(partition, 43L)
        verify(consumer).commitSync(mapOf(partition to OffsetAndMetadata(43)))
        verifyNoInteractions(container)
    }

    @Test
    fun `position taken over while rejecting stops without skipping the record`() {
        doThrow(InstanceReceivedOffsetException("database position unchanged"))
            .whenever(handler)
            .reject(partition, 42, null)

        assertThrows(KafkaException::class.java) {
            errors.handleOtherException(
                unreadableRecord(),
                consumer,
                container,
                false,
            )
        }

        verify(container, timeout(2000)).stopAbnormally(any())
        verify(consumer, never()).seek(any<TopicPartition>(), any<Long>())
        verify(consumer, never()).commitSync(any<Map<TopicPartition, OffsetAndMetadata>>())
    }

    @Test
    fun `failed rejection publication is retried without skipping or stopping`() {
        doThrow(DataAccessResourceFailureException("database unavailable"))
            .whenever(handler)
            .reject(partition, 42, null)

        errors.handleOtherException(unreadableRecord(), consumer, container, false)

        verify(consumer, never()).seek(any<TopicPartition>(), any<Long>())
        verify(consumer, never()).commitSync(any<Map<TopicPartition, OffsetAndMetadata>>())
        verify(container, never()).stopAbnormally(any())
    }

    @Test
    fun `polling and assignment failures request reassignment instead of stopping`() {
        whenever(container.isRunning).thenReturn(true, false)

        errors.handleOtherException(
            InstanceReceivedAssignmentException(TimeoutException("Broker unavailable")),
            consumer,
            container,
            false,
        )

        verify(consumer).enforceRebalance()
        verify(container, never()).stopAbnormally(any())
    }

    @Test
    fun `polling failures do not force a rebalance`() {
        errors.handleOtherException(TimeoutException("Broker unavailable"), consumer, container, false)

        verify(consumer, never()).enforceRebalance()
        verify(container, never()).stopAbnormally(any())
    }

    @Test
    fun `repeated failures log once per minute and recovery resets the first failure log`() {
        val now = AtomicReference(Instant.parse("2026-11-04T10:00:00Z"))
        val clock = mock<Clock>()
        whenever(clock.instant()).thenAnswer { now.get() }
        val errors = InstanceReceivedErrorHandler(handler, headersMapper, clock)
        val logger = LoggerFactory.getLogger(InstanceReceivedErrorHandler::class.java) as Logger
        val captured = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(captured)
        try {
            errors.handleOtherException(TimeoutException("Broker unavailable"), consumer, container, false)
            repeat(11) {
                now.set(now.get().plusSeconds(5))
                errors.handleOtherException(
                    IllegalStateException("poll failed", TimeoutException("Broker unavailable")),
                    consumer,
                    container,
                    false,
                )
            }
            assertEquals(1, captured.list.size)

            now.set(now.get().plusSeconds(5))
            errors.handleOtherException(TimeoutException("Broker unavailable"), consumer, container, false)
            errors.handleOtherException(
                DataAccessResourceFailureException("Database unavailable"),
                consumer,
                container,
                false,
            )
            errors.processingSucceeded()
            errors.processingSucceeded()
            errors.handleOtherException(
                DataAccessResourceFailureException("Database unavailable"),
                consumer,
                container,
                false,
            )

            assertEquals(
                listOf(Level.ERROR, Level.WARN, Level.ERROR, Level.INFO, Level.ERROR),
                captured.list.map { it.level },
            )
            assertNotNull(captured.list[0].throwableProxy)
            assertNull(captured.list[1].throwableProxy)
            assertEquals("Instance-received processing resumed after retry", captured.list[3].formattedMessage)
        } finally {
            logger.detachAppender(captured)
            captured.stop()
        }
    }

    private fun unreadableRecord(): RecordDeserializationException =
        RecordDeserializationException(
            RecordDeserializationException.DeserializationExceptionOrigin.VALUE,
            partition,
            42,
            0,
            TimestampType.CREATE_TIME,
            null,
            null,
            null,
            "private-source-value",
            null,
        )
}
