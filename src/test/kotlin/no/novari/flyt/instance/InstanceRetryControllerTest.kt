package no.novari.flyt.instance

import jakarta.persistence.EntityNotFoundException
import no.novari.flyt.instance.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService
import no.novari.flyt.instance.kafka.InstanceRequestedForRetryEventProducerService
import no.novari.flyt.instance.kafka.InstanceRetryRequestErrorProducerService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class InstanceRetryControllerTest {
    @Mock
    private lateinit var instanceService: InstanceService

    @Mock
    private lateinit var instanceFlowHeadersForRegisteredInstanceRequestProducerService:
        InstanceFlowHeadersForRegisteredInstanceRequestProducerService

    @Mock
    private lateinit var instanceRequestedForRetryEventProducerService: InstanceRequestedForRetryEventProducerService

    @Mock
    private lateinit var instanceRetryRequestErrorProducerService: InstanceRetryRequestErrorProducerService

    @Mock
    private lateinit var instanceFlowHeaders: InstanceFlowHeaders

    private lateinit var controller: InstanceRetryController

    private val instanceId = 123L

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        controller =
            InstanceRetryController(
                instanceService,
                instanceFlowHeadersForRegisteredInstanceRequestProducerService,
                instanceRequestedForRetryEventProducerService,
                instanceRetryRequestErrorProducerService,
            )
    }

    @Test
    fun retry_instanceFound_headersFound_returnsOk() {
        val instance = InstanceObjectDto()

        whenever(instanceService.getById(instanceId)).thenReturn(instance)
        whenever(
            instanceFlowHeadersForRegisteredInstanceRequestProducerService.get(instanceId),
        ).thenReturn(instanceFlowHeaders)
        whenever(instanceFlowHeaders.toBuilder()).thenReturn(
            InstanceFlowHeaders.builder().sourceApplicationId(1L).correlationId(UUID.randomUUID()),
        )

        val response = controller.retry(instanceId)

        assertEquals(HttpStatus.OK, response.statusCode)
        verify(instanceRequestedForRetryEventProducerService).publish(any(), eq(instance))
    }

    @Test
    fun retry_instanceNotFound_throwsResponseStatusException() {
        whenever(instanceService.getById(instanceId)).thenThrow(EntityNotFoundException())

        val exception = assertThrows(ResponseStatusException::class.java) { controller.retry(instanceId) }

        assertTrue(exception.reason.orEmpty().contains("Could not find instance with id='$instanceId'"))
    }

    @Test
    fun retry_noInstanceFlowHeaders_throwsResponseStatusException() {
        val instance = InstanceObjectDto()

        whenever(instanceService.getById(instanceId)).thenReturn(instance)
        whenever(instanceFlowHeadersForRegisteredInstanceRequestProducerService.get(instanceId)).thenReturn(null)

        val exception = assertThrows(ResponseStatusException::class.java) { controller.retry(instanceId) }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.statusCode)
        assertTrue(
            exception.reason.orEmpty().contains(
                "Could not find instance flow headers for registered instance with id=$instanceId",
            ),
        )
    }

    @Test
    fun retry_genericException_throwsResponseStatusException() {
        whenever(instanceService.getById(instanceId)).thenThrow(RuntimeException("Some error"))

        val exception = assertThrows(ResponseStatusException::class.java) { controller.retry(instanceId) }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.statusCode)
        assertNull(exception.reason)
        verify(instanceRetryRequestErrorProducerService, never()).publishGeneralSystemErrorEvent(any())
    }

    @Test
    fun retry_genericExceptionWithInstanceFlowHeaders_publishesErrorAndThrowsResponseStatusException() {
        val instance = InstanceObjectDto()
        val headers =
            InstanceFlowHeaders
                .builder()
                .sourceApplicationId(1L)
                .correlationId(UUID.randomUUID())
                .build()

        whenever(instanceService.getById(instanceId)).thenReturn(instance)
        whenever(instanceFlowHeadersForRegisteredInstanceRequestProducerService.get(instanceId)).thenReturn(headers)
        doThrow(RuntimeException("Unexpected error"))
            .whenever(instanceRequestedForRetryEventProducerService)
            .publish(any(), any())

        val exception = assertThrows(ResponseStatusException::class.java) { controller.retry(instanceId) }

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.statusCode)
        verify(instanceRetryRequestErrorProducerService).publishGeneralSystemErrorEvent(any())
    }
}
