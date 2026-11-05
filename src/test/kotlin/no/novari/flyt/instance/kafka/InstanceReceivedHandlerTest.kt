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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

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

    @BeforeEach
    fun setUp() {
        whenever(repository.findNextOffset(partition)).thenReturn(42L)
        whenever(service.save(input)).thenReturn(saved)
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
    fun `synchronous registered send failure leaves the position unchanged`() {
        doThrow(IllegalStateException("broker unavailable"))
            .whenever(registeredProducer)
            .publish(any(), eq(saved))

        assertThrows(IllegalStateException::class.java) { handler.handle(record()) }

        verify(repository, never()).advance(any(), any())
        verifyNoInteractions(errorProducer)
    }

    @Test
    fun `failed error publication does not advance`() {
        whenever(service.save(input)).thenThrow(IllegalArgumentException("invalid source data"))
        doThrow(IllegalStateException("broker unavailable"))
            .whenever(errorProducer)
            .publishGeneralSystemErrorEvent(headers)

        assertThrows(IllegalStateException::class.java) { handler.handle(record()) }

        verify(repository, never()).advance(any(), any())
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
        doThrow(IllegalStateException("broker unavailable"))
            .whenever(errorProducer)
            .publishGeneralSystemErrorEvent(headers)

        assertThrows(IllegalStateException::class.java) { handler.reject(partition, 42, rawHeaders) }

        verify(repository, never()).advance(any(), any())
    }

    private fun record(): InstanceFlowConsumerRecord<InstanceObjectDto> =
        InstanceFlowConsumerRecord(headers, ConsumerRecord(partition.topic(), partition.partition(), 42, null, input))
}
