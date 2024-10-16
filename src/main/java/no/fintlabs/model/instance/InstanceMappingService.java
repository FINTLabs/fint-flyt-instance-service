package no.fintlabs.model.instance;

import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import no.fintlabs.model.instance.entities.InstanceObject;
import no.fintlabs.model.instance.entities.InstanceObjectCollection;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InstanceMappingService {

    public InstanceObject toInstanceObject(InstanceObjectDto instanceObjectDto) {
        return InstanceObject
                .builder()
                .valuePerKey(new HashMap<>(instanceObjectDto.getValuePerKey()))
                .objectCollectionPerKey(
                        instanceObjectDto.getObjectCollectionPerKey()
                                .keySet()
                                .stream()
                                .collect(Collectors.toMap(
                                        Function.identity(),
                                        key -> InstanceObjectCollection
                                                .builder()
                                                .objects(
                                                        instanceObjectDto.getObjectCollectionPerKey().get(key)
                                                                .stream()
                                                                .map(this::toInstanceObject)
                                                                .toList()
                                                )
                                                .build()
                                ))
                )
                .createdAt(instanceObjectDto.getCreatedAt())
                .build();
    }

    public InstanceObjectDto toInstanceObjectDto(InstanceObject instanceObject) {
        return InstanceObjectDto
                .builder()
                .id(instanceObject.getId())
                .valuePerKey(new HashMap<>(instanceObject.getValuePerKey()))
                .objectCollectionPerKey(
                        instanceObject.getObjectCollectionPerKey()
                                .keySet()
                                .stream()
                                .collect(Collectors.toMap(
                                        Function.identity(),
                                        key -> instanceObject.getObjectCollectionPerKey().get(key)
                                                .getObjects()
                                                .stream()
                                                .map(this::toInstanceObjectDto)
                                                .toList()
                                ))
                )
                .createdAt(instanceObject.getCreatedAt())
                .build();
    }
}
