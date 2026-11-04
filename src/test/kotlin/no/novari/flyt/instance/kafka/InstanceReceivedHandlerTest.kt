package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import no.novari.flyt.instance.InstanceService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowConsumerRecord
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.kafka.support.SendResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InstanceReceivedHandlerTest {
    private val service = mock<InstanceService>()
    private val registeredProducer = mock<InstanceRegisteredEventProducerService>()
    private val errorProducer = mock<InstanceRegistrationErrorProducerService>()
    private val repository = mock<InstanceReceivedOffsetRepository>()
    private val handler = InstanceReceivedHandler(service, registeredProducer, errorProducer, repository)
    private val partition = TopicPartition("afk-no.flyt.instance-received", 0)
    private val headers =
        InstanceFlowHeaders
            .builder()
            .sourceApplicationId(1L)
            .correlationId(UUID.randomUUID())
            .build()
    private val input = InstanceObjectDto(valuePerKey = mutableMapOf("key" to "value"))
    private val saved = InstanceObjectDto(id = 123L)
    private val registered = CompletableFuture.completedFuture(mock<SendResult<String, InstanceObjectDto>>())
    private val rejected =
        CompletableFuture.completedFuture(mock<SendResult<String, no.novari.flyt.kafka.model.InstanceErrorEvent>>())

    @BeforeEach
    fun setUp() {
        whenever(repository.findNextOffset(partition)).thenReturn(42L)
        whenever(service.save(input)).thenReturn(saved)
        whenever(registeredProducer.publish(any(), eq(saved))).thenReturn(registered)
        whenever(errorProducer.publishGeneralSystemErrorEvent(headers)).thenReturn(rejected)
    }

    @Test
    fun `saves and publishes generated id before advancing the position`() {
        handler.handle(record())

        inOrder(service, registeredProducer, repository) {
            verify(repository).findNextOffset(partition)
            verify(service).save(input)
            verify(registeredProducer).publish(headers.toBuilder().instanceId(123L).build(), saved)
            verify(repository).advance(partition, 43)
        }
        verifyNoInteractions(errorProducer)
    }

    @Test
    fun `waits for broker confirmation before advancing`() {
        val waiting = CountDownLatch(1)
        val pending =
            object : CompletableFuture<SendResult<String, InstanceObjectDto>>() {
                override fun get(): SendResult<String, InstanceObjectDto> {
                    waiting.countDown()
                    return super.get()
                }
            }
        whenever(registeredProducer.publish(any(), eq(saved))).thenReturn(pending)
        Executors.newSingleThreadExecutor().use { executor ->
            val processing = executor.submit { handler.handle(record()) }
            try {
                assertTrue(waiting.await(5, TimeUnit.SECONDS))
                assertFalse(processing.isDone)
                verify(repository, never()).advance(any(), any())
            } finally {
                pending.complete(mock())
            }
            processing.get(5, TimeUnit.SECONDS)
        }
        verify(repository).advance(partition, 43)
    }

    @Test
    fun `existing registration failure advances only after error publication`() {
        whenever(service.save(input)).thenThrow(IllegalArgumentException("invalid source data"))

        handler.handle(record())

        inOrder(errorProducer, repository) {
            verify(errorProducer).publishGeneralSystemErrorEvent(headers)
            verify(repository).advance(partition, 43)
        }
        verifyNoInteractions(registeredProducer)
    }

    @Test
    fun `missing generated id follows existing error flow`() {
        whenever(service.save(input)).thenReturn(InstanceObjectDto())

        handler.handle(record())

        verify(errorProducer).publishGeneralSystemErrorEvent(headers)
        verify(repository).advance(partition, 43)
        verifyNoInteractions(registeredProducer)
    }

    @Test
    fun `synchronous publication failure is not converted to a registration error`() {
        whenever(registeredProducer.publish(any(), eq(saved))).thenThrow(IllegalStateException("broker unavailable"))

        assertThrows(IllegalStateException::class.java) { handler.handle(record()) }

        verify(repository, never()).advance(any(), any())
        verifyNoInteractions(errorProducer)
    }

    @Test
    fun `failed broker confirmation neither advances nor publishes a second event`() {
        whenever(registeredProducer.publish(any(), eq(saved)))
            .thenReturn(CompletableFuture.failedFuture(IllegalStateException("broker unavailable")))

        assertThrows(ExecutionException::class.java) { handler.handle(record()) }

        verify(repository, never()).advance(any(), any())
        verifyNoInteractions(errorProducer)
    }

    @Test
    fun `failed error publication does not advance`() {
        whenever(service.save(input)).thenThrow(IllegalArgumentException("invalid source data"))
        whenever(errorProducer.publishGeneralSystemErrorEvent(headers))
            .thenReturn(CompletableFuture.failedFuture(IllegalStateException("broker unavailable")))

        assertThrows(ExecutionException::class.java) { handler.handle(record()) }

        verify(repository, never()).advance(any(), any())
    }

    @Test
    fun `checkpoint failure after broker confirmation is propagated without error publication`() {
        whenever(repository.advance(partition, 43)).thenThrow(InstanceReceivedOffsetException("write failed"))

        assertThrows(InstanceReceivedOffsetException::class.java) { handler.handle(record()) }

        verifyNoInteractions(errorProducer)
    }

    @Test
    fun `already completed records are not registered again`() {
        whenever(repository.findNextOffset(partition)).thenReturn(43L)

        handler.handle(record())

        verifyNoInteractions(service, registeredProducer, errorProducer)
        verify(repository, never()).advance(any(), any())
    }

    @Test
    fun `missing position prevents processing`() {
        whenever(repository.findNextOffset(partition)).thenReturn(null)

        assertThrows(InstanceReceivedOffsetException::class.java) { handler.handle(record()) }

        verifyNoInteractions(service, registeredProducer, errorProducer)
    }

    @Test
    fun `position taken over by history-service prevents processing`() {
        whenever(repository.findNextOffset(partition)).thenThrow(InstanceReceivedOffsetException("taken over"))

        assertThrows(InstanceReceivedOffsetException::class.java) { handler.handle(record()) }

        verifyNoInteractions(service, registeredProducer, errorProducer)
    }

    @Test
    fun `null payload stops without creating registration error or progress`() {
        assertThrows(IllegalArgumentException::class.java) {
            handler.handle(
                InstanceFlowConsumerRecord(
                    headers,
                    ConsumerRecord<String, InstanceObjectDto>(partition.topic(), 0, 42, null, null),
                ),
            )
        }

        verifyNoInteractions(service, registeredProducer, errorProducer)
        verify(repository, never()).advance(any(), any())
    }

    @Test
    fun `interrupted broker wait restores the interrupt flag and does not advance`() {
        val interrupted =
            object : CompletableFuture<SendResult<String, InstanceObjectDto>>() {
                override fun get(): SendResult<String, InstanceObjectDto> = throw InterruptedException("stopping")
            }
        whenever(registeredProducer.publish(any(), eq(saved))).thenReturn(interrupted)
        try {
            assertThrows(InterruptedException::class.java) { handler.handle(record()) }
            assertTrue(Thread.currentThread().isInterrupted)
            verify(repository, never()).advance(any(), any())
            verifyNoInteractions(errorProducer)
        } finally {
            Thread.interrupted()
        }
    }

    private fun record(): InstanceFlowConsumerRecord<InstanceObjectDto> =
        InstanceFlowConsumerRecord(headers, ConsumerRecord(partition.topic(), partition.partition(), 42, null, input))
}
