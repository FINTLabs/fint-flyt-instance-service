package no.novari.flyt.instance.model

import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.instance.model.entities.InstanceObject
import no.novari.flyt.instance.model.entities.InstanceObjectCollection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Date

class InstanceMappingServiceTest {
    private lateinit var instanceMappingService: InstanceMappingService

    @BeforeEach
    fun setUp() {
        instanceMappingService = InstanceMappingService()
    }

    @Test
    fun testToInstanceObject() {
        val valuePerKey =
            mutableMapOf(
                "key1" to "value1",
                "key2" to "value2",
            )

        val objectCollectionPerKey = mutableMapOf<String, Collection<InstanceObjectDto>>()
        objectCollectionPerKey["key1"] = listOf(InstanceObjectDto(id = 1L))
        objectCollectionPerKey["key2"] = listOf(InstanceObjectDto(id = 2L))

        val dto =
            InstanceObjectDto(
                id = 1L,
                valuePerKey = valuePerKey,
                objectCollectionPerKey = objectCollectionPerKey,
            )

        val result = instanceMappingService.toInstanceObject(dto)

        assertNotNull(result)
        assertEquals(valuePerKey, result.valuePerKey)
        assertEquals(2, result.objectCollectionPerKey.size)
    }

    @Test
    fun testToInstanceObjectDto() {
        val valuePerKey =
            mutableMapOf(
                "key1" to "value1",
                "key2" to "value2",
            )

        val objectCollectionPerKey = mutableMapOf<String, InstanceObjectCollection>()
        objectCollectionPerKey["key1"] =
            InstanceObjectCollection(
                id = 1L,
                objects =
                    mutableListOf(
                        InstanceObject(
                            id = 1L,
                            valuePerKey = valuePerKey,
                            objectCollectionPerKey = mutableMapOf(),
                            createdAt = Date(),
                        ),
                    ),
            )
        objectCollectionPerKey["key2"] =
            InstanceObjectCollection(
                id = 2L,
                objects =
                    mutableListOf(
                        InstanceObject(
                            id = 2L,
                            valuePerKey = valuePerKey,
                            objectCollectionPerKey = mutableMapOf(),
                            createdAt = Date(),
                        ),
                    ),
            )

        val instanceObject =
            InstanceObject(
                id = 1L,
                valuePerKey = valuePerKey,
                objectCollectionPerKey = objectCollectionPerKey,
                createdAt = Date(),
            )

        val result = instanceMappingService.toInstanceObjectDto(instanceObject)

        assertNotNull(result)
        assertEquals(instanceObject.id, result.id)
        assertEquals(valuePerKey, result.valuePerKey)
        assertEquals(2, result.objectCollectionPerKey.size)
    }
}
