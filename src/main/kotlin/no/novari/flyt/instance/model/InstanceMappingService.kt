package no.novari.flyt.instance.model

import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.instance.model.entities.InstanceObject
import no.novari.flyt.instance.model.entities.InstanceObjectCollection
import org.springframework.stereotype.Service

@Service
class InstanceMappingService {
    fun toInstanceObject(instanceObjectDto: InstanceObjectDto): InstanceObject {
        return InstanceObject(
            valuePerKey = instanceObjectDto.valuePerKey.toMutableMap(),
            objectCollectionPerKey =
                instanceObjectDto.objectCollectionPerKey
                    .mapValues { (_, values) ->
                        InstanceObjectCollection(
                            objects =
                                values
                                    .map(::toInstanceObject)
                                    .toMutableList(),
                        )
                    }.toMutableMap(),
            createdAt = instanceObjectDto.createdAt,
        )
    }

    fun toInstanceObjectDto(instanceObject: InstanceObject): InstanceObjectDto {
        return InstanceObjectDto(
            id = instanceObject.id,
            valuePerKey = instanceObject.valuePerKey.toMutableMap(),
            objectCollectionPerKey =
                instanceObject.objectCollectionPerKey
                    .mapValues { (_, value) ->
                        value.objects
                            .map(::toInstanceObjectDto)
                    }.toMutableMap(),
            createdAt = instanceObject.createdAt,
        )
    }
}
