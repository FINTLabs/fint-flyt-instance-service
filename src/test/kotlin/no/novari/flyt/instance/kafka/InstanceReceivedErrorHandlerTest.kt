package no.novari.flyt.instance.kafka

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
import org.springframework.kafka.KafkaException
import org.springframework.kafka.listener.MessageListenerContainer
import java.sql.SQLException
import java.util.UUID

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
    fun `stops abnormally and preserves safe diagnostics without exposing record or exception contents`() {
        val sql = SQLException("private-source-value", "08006")
        val failure = IllegalStateException("private-source-value", sql)
        failure.addSuppressed(IllegalArgumentException("private-source-value"))

        val stopped =
            assertThrows(KafkaException::class.java) {
                errors.handleRemaining(
                    failure,
                    listOf(ConsumerRecord("afk-no.flyt.instance-received", 0, 42, null, "private-source-value")),
                    consumer,
                    container,
                )
            }

        verify(container, timeout(2000)).stopAbnormally(any())
        assertFalse(errors.isAckAfterHandle)
        val diagnostic = stopped.stackTraceToString()
        assertFalse(diagnostic.contains("private-source-value"))
        assertEquals(
            sql.stackTrace.toList(),
            stopped.cause!!
                .cause!!
                .stackTrace
                .toList(),
        )
        assertEquals("java.sql.SQLException, SQLSTATE=08006", stopped.cause!!.cause!!.message)
        assertEquals(
            "java.lang.IllegalArgumentException",
            stopped.cause!!
                .suppressed
                .single()
                .message,
        )
    }

    @Test
    fun `retains position error messages which contain only technical coordinates`() {
        val failure = InstanceReceivedOffsetException("Position taken over for afk-no.flyt.instance-received-0")

        assertEquals(failure.message, safeDiagnosticCause(failure).message)
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

        errors.handleRemaining(IllegalArgumentException("private header"), listOf(record), consumer, container)

        verify(handler).reject(partition, 42, record.headers())
        verify(consumer).seek(partition, 43L)
        verify(consumer).commitSync(mapOf(partition to OffsetAndMetadata(43)))
        verifyNoInteractions(container)
    }

    @Test
    fun `failed rejection stops without skipping or committing the record`() {
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
    fun `retains Kafka timeout diagnostics`() {
        val failure = TimeoutException("Topic afk-no.flyt.instance-registered not present in metadata after 60000 ms")

        assertEquals("${failure.javaClass.name}: ${failure.message}", safeDiagnosticCause(failure).message)
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
