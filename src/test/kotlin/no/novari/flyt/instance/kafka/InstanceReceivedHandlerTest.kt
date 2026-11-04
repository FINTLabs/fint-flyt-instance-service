package no.novari.flyt.instance.kafka

import no.novari.flyt.instance.InstanceReceivedOffsetException
import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import no.novari.flyt.instance.InstanceService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowConsumerRecord
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeadersMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeaders
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.dao.DataAccessResourceFailureException
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
    private val headersMapper = mock<InstanceFlowHeadersMapper>()
    private val handler = InstanceReceivedHandler(service, registeredProducer, errorProducer, repository, headersMapper)
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
        whenever(errorProducer.publishGeneralSystemErrorEvent(any())).thenReturn(rejected)
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
    fun `synchronous publication failure publishes an error for the saved instance and advances`() {
        whenever(registeredProducer.publish(any(), eq(saved))).thenThrow(IllegalStateException("broker unavailable"))

        handler.handle(record())

        verify(errorProducer).publishGeneralSystemErrorEvent(headers.toBuilder().instanceId(saved.id).build())
        verify(repository).advance(partition, 43)
    }

    @Test
    fun `failed broker confirmation publishes a registration error before advancing`() {
        whenever(registeredProducer.publish(any(), eq(saved)))
            .thenReturn(CompletableFuture.failedFuture(IllegalStateException("broker unavailable")))

        handler.handle(record())

        inOrder(errorProducer, repository) {
            verify(errorProducer).publishGeneralSystemErrorEvent(headers.toBuilder().instanceId(saved.id).build())
            verify(repository).advance(partition, 43)
        }
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
    fun `checkpoint retry does not save or publish the instance again`() {
        doThrow(DataAccessResourceFailureException("write failed"))
            .doNothing()
            .whenever(repository)
            .advance(partition, 43)

        assertThrows(DataAccessResourceFailureException::class.java) { handler.handle(record()) }
        handler.handle(record())

        verify(service, times(1)).save(input)
        verify(registeredProducer, times(1)).publish(any(), eq(saved))
        verify(repository, times(2)).advance(partition, 43)
        verifyNoInteractions(errorProducer)
    }

    @Test
    fun `retrying a failed error publication does not repeat saving or registered publication`() {
        whenever(registeredProducer.publish(any(), eq(saved)))
            .thenReturn(CompletableFuture.failedFuture(IllegalStateException("registration publication failed")))
        whenever(errorProducer.publishGeneralSystemErrorEvent(any()))
            .thenReturn(CompletableFuture.failedFuture(IllegalStateException("broker unavailable")), rejected)

        assertThrows(ExecutionException::class.java) { handler.handle(record()) }
        verify(repository, never()).advance(any(), any())
        handler.handle(record())

        verify(service, times(1)).save(input)
        verify(registeredProducer, times(1)).publish(any(), eq(saved))
        verify(errorProducer, times(2)).publishGeneralSystemErrorEvent(headers.toBuilder().instanceId(saved.id).build())
        verify(repository).advance(partition, 43)
    }

    @Test
    fun `checkpoint retry after error publication does not publish the error again`() {
        whenever(service.save(input)).thenThrow(IllegalArgumentException("invalid source data"))
        doThrow(DataAccessResourceFailureException("write failed"))
            .doNothing()
            .whenever(repository)
            .advance(partition, 43)

        assertThrows(DataAccessResourceFailureException::class.java) { handler.handle(record()) }
        handler.handle(record())

        verify(service, times(1)).save(input)
        verify(errorProducer, times(1)).publishGeneralSystemErrorEvent(headers)
        verify(repository, times(2)).advance(partition, 43)
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
    fun `null payload publishes registration error and advances without saving an instance`() {
        handler.handle(
            InstanceFlowConsumerRecord(
                headers,
                ConsumerRecord<String, InstanceObjectDto>(partition.topic(), 0, 42, null, null),
            ),
        )

        verifyNoInteractions(service, registeredProducer)
        verify(errorProducer).publishGeneralSystemErrorEvent(headers)
        verify(repository).advance(partition, 43)
    }

    @Test
    fun `interrupted broker wait propagates without setting the interrupt flag or advancing`() {
        val interrupted =
            object : CompletableFuture<SendResult<String, InstanceObjectDto>>() {
                override fun get(): SendResult<String, InstanceObjectDto> = throw InterruptedException("stopping")
            }
        whenever(registeredProducer.publish(any(), eq(saved))).thenReturn(interrupted)
        try {
            assertThrows(InterruptedException::class.java) { handler.handle(record()) }
            assertFalse(Thread.currentThread().isInterrupted)
            verify(repository, never()).advance(any(), any())
            verifyNoInteractions(errorProducer)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `unreadable payload with readable headers publishes error before advancing`() {
        val rawHeaders = RecordHeaders()
        whenever(headersMapper.getInstanceFlowHeaders(rawHeaders)).thenReturn(headers)

        handler.reject(partition, 42, rawHeaders)

        inOrder(errorProducer, repository) {
            verify(errorProducer).publishGeneralSystemErrorEvent(headers)
            verify(repository).advance(partition, 43)
        }
        verifyNoInteractions(service, registeredProducer)
    }

    @Test
    fun `unreadable headers advance without fabricating error headers`() {
        val rawHeaders = RecordHeaders()
        whenever(headersMapper.getInstanceFlowHeaders(rawHeaders)).thenThrow(IllegalArgumentException("invalid header"))

        handler.reject(partition, 42, rawHeaders)

        verify(repository).advance(partition, 43)
        verifyNoInteractions(service, registeredProducer, errorProducer)
    }

    @Test
    fun `failed rejection publication leaves the position unchanged`() {
        val rawHeaders = RecordHeaders()
        whenever(headersMapper.getInstanceFlowHeaders(rawHeaders)).thenReturn(headers)
        whenever(errorProducer.publishGeneralSystemErrorEvent(headers))
            .thenReturn(CompletableFuture.failedFuture(IllegalStateException("broker unavailable")))

        assertThrows(ExecutionException::class.java) { handler.reject(partition, 42, rawHeaders) }

        verify(repository, never()).advance(any(), any())
    }

    private fun record(): InstanceFlowConsumerRecord<InstanceObjectDto> =
        InstanceFlowConsumerRecord(headers, ConsumerRecord(partition.topic(), partition.partition(), 42, null, input))
}
