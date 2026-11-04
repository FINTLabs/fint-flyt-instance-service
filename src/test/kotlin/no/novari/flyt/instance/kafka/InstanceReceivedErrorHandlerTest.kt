package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.InstanceReceivedOffsetException
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.springframework.kafka.KafkaException
import org.springframework.kafka.listener.MessageListenerContainer
import java.sql.SQLException

class InstanceReceivedErrorHandlerTest {
    @Test
    fun `stops abnormally and preserves safe diagnostics without exposing record or exception contents`() {
        val container = mock<MessageListenerContainer>()
        val consumer = mock<Consumer<String, String>>()
        val sql = SQLException("private-source-value", "08006")
        val failure = IllegalStateException("private-source-value", sql)
        failure.addSuppressed(IllegalArgumentException("private-source-value"))

        val stopped =
            assertThrows(KafkaException::class.java) {
                InstanceReceivedErrorHandler().handleRemaining(
                    failure,
                    listOf(ConsumerRecord("afk-no.flyt.instance-received", 0, 42, null, "private-source-value")),
                    consumer,
                    container,
                )
            }

        verify(container, timeout(2000)).stopAbnormally(any())
        assertFalse(InstanceReceivedErrorHandler().isAckAfterHandle)
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
}
