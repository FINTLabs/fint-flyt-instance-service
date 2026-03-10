package no.novari.flyt.instance

import no.novari.flyt.instance.kafka.InstanceDeletedEventProducerService
import no.novari.flyt.instance.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService
import no.novari.flyt.instance.model.InstanceMappingService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.instance.model.entities.InstanceObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.EmptyResultDataAccessException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Date

class InstanceServiceTest {
    @Mock
    private lateinit var instanceRepository: InstanceRepository

    @Mock
    private lateinit var instanceMappingService: InstanceMappingService

    @Mock
    private lateinit var instanceDeletedEventProducerService: InstanceDeletedEventProducerService

    @Mock
    private lateinit var instanceFlowHeadersForRegisteredInstanceRequestProducerService:
        InstanceFlowHeadersForRegisteredInstanceRequestProducerService

    private lateinit var instanceService: InstanceService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        instanceService =
            InstanceService(
                instanceRepository,
                instanceMappingService,
                instanceDeletedEventProducerService,
                instanceFlowHeadersForRegisteredInstanceRequestProducerService,
            )
    }

    @Test
    fun testSave() {
        val valuePerKey =
            mutableMapOf(
                "key1" to "value1",
                "key2" to "value2",
            )

        val objectCollectionPerKey = mutableMapOf<String, Collection<InstanceObjectDto>>()

        val dto = InstanceObjectDto(id = 1L, valuePerKey = valuePerKey, objectCollectionPerKey = objectCollectionPerKey)
        val instanceObject =
            InstanceObject(
                id = 1L,
                valuePerKey = valuePerKey,
                objectCollectionPerKey = mutableMapOf(),
                createdAt = Date(),
            )

        whenever(instanceMappingService.toInstanceObject(any())).thenReturn(instanceObject)
        whenever(instanceRepository.save(any<InstanceObject>())).thenReturn(instanceObject)
        whenever(instanceMappingService.toInstanceObjectDto(any())).thenReturn(dto)

        val result = instanceService.save(dto)

        assertEquals(dto, result)
        verify(instanceMappingService, times(1)).toInstanceObject(dto)
        verify(instanceRepository, times(1)).save(instanceObject)
        verify(instanceMappingService, times(1)).toInstanceObjectDto(instanceObject)
    }

    @Test
    fun testGetById() {
        val valuePerKey =
            mutableMapOf(
                "key1" to "value1",
                "key2" to "value2",
            )

        val objectCollectionPerKey = mutableMapOf<String, Collection<InstanceObjectDto>>()

        val id = 1L
        val dto = InstanceObjectDto(id = id, valuePerKey = valuePerKey, objectCollectionPerKey = objectCollectionPerKey)
        val instanceObject =
            InstanceObject(
                id = id,
                valuePerKey = valuePerKey,
                objectCollectionPerKey = mutableMapOf(),
                createdAt = Date(),
            )

        whenever(instanceRepository.getReferenceById(any<Long>())).thenReturn(instanceObject)
        whenever(instanceMappingService.toInstanceObjectDto(any())).thenReturn(dto)

        val result = instanceService.getById(id)

        assertEquals(dto, result)
        verify(instanceRepository, times(1)).getReferenceById(id)
        verify(instanceMappingService, times(1)).toInstanceObjectDto(instanceObject)
    }

    @Test
    fun testDeleteAllOlderThan_throwsEmptyResultDataAccessException() {
        val days = 30
        val oldTimestamp = Timestamp.valueOf(LocalDateTime.now().minusDays((days + 1).toLong()))

        val instance1 = InstanceObject(id = 1L, createdAt = oldTimestamp)
        val instance2 = InstanceObject(id = 2L, createdAt = oldTimestamp)

        val instanceObjects = listOf(instance1, instance2)

        val instanceDto1 = InstanceObjectDto(id = 1L, createdAt = oldTimestamp)
        val instanceDto2 = InstanceObjectDto(id = 2L, createdAt = oldTimestamp)

        doReturn(instanceObjects).whenever(instanceRepository).findAllOlderThan(any<Timestamp>())
        doReturn(instanceDto1).whenever(instanceMappingService).toInstanceObjectDto(instance1)
        doReturn(instanceDto2).whenever(instanceMappingService).toInstanceObjectDto(instance2)
        doReturn(null).whenever(instanceFlowHeadersForRegisteredInstanceRequestProducerService).get(any<Long>())
        doThrow(EmptyResultDataAccessException(1)).whenever(instanceRepository).deleteById(any<Long>())

        instanceService.deleteAllOlderThan(days)
    }

    @Test
    fun testDeleteAllOlderThan_throwsRuntimeException() {
        val days = 30
        val oldTimestamp = Timestamp.valueOf(LocalDateTime.now().minusDays((days + 1).toLong()))

        val instance1 = InstanceObject(id = 1L, createdAt = oldTimestamp)
        val instance2 = InstanceObject(id = 2L, createdAt = oldTimestamp)

        val instanceObjects = listOf(instance1, instance2)

        val instanceDto1 = InstanceObjectDto(id = 1L, createdAt = oldTimestamp)
        val instanceDto2 = InstanceObjectDto(id = 2L, createdAt = oldTimestamp)

        doReturn(instanceObjects).whenever(instanceRepository).findAllOlderThan(any<Timestamp>())
        doReturn(instanceDto1).whenever(instanceMappingService).toInstanceObjectDto(instance1)
        doReturn(instanceDto2).whenever(instanceMappingService).toInstanceObjectDto(instance2)
        doReturn(null).whenever(instanceFlowHeadersForRegisteredInstanceRequestProducerService).get(any<Long>())
        doThrow(RuntimeException("Simulated deletion failure")).whenever(instanceRepository).deleteById(any<Long>())

        instanceService.deleteAllOlderThan(days)
    }
}
