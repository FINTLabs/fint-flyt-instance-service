package no.fintlabs.model.instance;

import no.fintlabs.model.instance.dtos.InstanceElementDto;
import no.fintlabs.model.instance.entities.InstanceElement;
import no.fintlabs.model.instance.entities.InstanceElementCollection;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class InstanceMapperService {

    public InstanceElement toInstanceElement(InstanceElementDto instanceElementDto) {
        return InstanceElement
                .builder()
                .valuePerKey(new HashMap<>(instanceElementDto.getValuePerKey()))
                .elementCollectionPerKey(
                        instanceElementDto.getElementCollectionPerKey()
                                .keySet()
                                .stream()
                                .collect(Collectors.toMap(
                                        Function.identity(),
                                        key -> InstanceElementCollection
                                                .builder()
                                                .elements(
                                                        instanceElementDto.getElementCollectionPerKey().get(key)
                                                                .stream()
                                                                .map(this::toInstanceElement)
                                                                .toList()
                                                )
                                                .build()
                                ))
                )
                .build();
    }

    public InstanceElementDto toInstanceElementDto(InstanceElement instanceElement) {
        return new InstanceElementDto(
                instanceElement.getId(),
                new HashMap<>(instanceElement.getValuePerKey()),
                instanceElement.getElementCollectionPerKey()
                        .keySet()
                        .stream()
                        .collect(Collectors.toMap(
                                Function.identity(),
                                key -> instanceElement.getElementCollectionPerKey().get(key)
                                        .getElements()
                                        .stream()
                                        .map(this::toInstanceElementDto)
                                        .toList()
                        ))
        );
    }
}
