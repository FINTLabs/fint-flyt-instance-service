package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.kafka.listener.MessageListenerContainer

class InstanceReceivedOffsetInitializerTest {
    private val repository = mock<InstanceReceivedOffsetRepository>()
    private val errors = mock<InstanceReceivedErrorHandler>()
    private val container = mock<MessageListenerContainer>()
    private val consumer = mock<Consumer<String, Any>>()
    private val initializer = InstanceReceivedOffsetInitializer(repository, errors, container)
    private val partition = TopicPartition("afk-no.flyt.instance-received", 0)

    @BeforeEach
    fun setUp() {
        whenever(repository.findNextOffset(partition)).thenReturn(null)
    }

    @Test
    fun `initializes idle partition from committed next offset without increment or seek`() {
        whenever(consumer.committed(setOf(partition))).thenReturn(mapOf(partition to OffsetAndMetadata(42)))

        initializer.onPartitionsAssigned(consumer, listOf(partition))

        verify(repository).initialize(partition, 42)
        verify(consumer, never()).seek(any<TopicPartition>(), any<Long>())
        verifyNoInteractions(errors)
    }

    @Test
    fun `existing position is never replaced by group position`() {
        whenever(repository.findNextOffset(partition)).thenReturn(42L)

        initializer.onPartitionsAssigned(consumer, listOf(partition))

        verify(repository, never()).initialize(any(), any())
        verifyNoInteractions(consumer, errors)
    }

    @Test
    fun `missing committed offset stops without choosing earliest or latest`() {
        whenever(consumer.committed(setOf(partition))).thenReturn(emptyMap())

        initializer.onPartitionsAssigned(consumer, listOf(partition))

        verify(
            errors,
        ).handleOtherException(any<InstanceReceivedOffsetException>(), eq(consumer), eq(container), eq(false))
        verify(repository, never()).initialize(any(), any())
    }

    @Test
    fun `database write failure stops the consumer`() {
        whenever(consumer.committed(setOf(partition))).thenReturn(mapOf(partition to OffsetAndMetadata(42)))
        val failure = DataAccessResourceFailureException("database unavailable")
        whenever(repository.initialize(partition, 42)).thenThrow(failure)

        initializer.onPartitionsAssigned(consumer, listOf(partition))

        verify(errors).handleOtherException(failure, consumer, container, false)
    }

    @Test
    fun `identity assigned by history-service stops before contacting Kafka`() {
        val failure = InstanceReceivedOffsetException("taken over")
        whenever(repository.findNextOffset(partition)).thenThrow(failure)

        initializer.onPartitionsAssigned(consumer, listOf(partition))

        verify(errors).handleOtherException(failure, consumer, container, false)
        verifyNoInteractions(consumer)
    }

    @Test
    fun `empty assignment performs no work`() {
        initializer.onPartitionsAssigned(consumer, emptyList())

        verifyNoInteractions(repository, errors, consumer)
    }
}
