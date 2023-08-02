package no.fintlabs.model.instance;

import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import no.fintlabs.model.instance.entities.InstanceObject;
import no.fintlabs.model.instance.entities.InstanceObjectCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class InstanceMappingServiceTest {

    private InstanceMappingService instanceMappingService;

    @BeforeEach
    void setUp() {
        instanceMappingService = new InstanceMappingService();
    }

    @Test
    void testToInstanceObject() {
        Map<String, String> valuePerKey = new HashMap<>();
        valuePerKey.put("key1", "value1");
        valuePerKey.put("key2", "value2");

        Map<String, Collection<InstanceObjectDto>> objectCollectionPerKey = new HashMap<>();
        objectCollectionPerKey.put("key1", List.of(InstanceObjectDto.builder().id(1L).build()));
        objectCollectionPerKey.put("key2", List.of(InstanceObjectDto.builder().id(2L).build()));

        InstanceObjectDto dto = InstanceObjectDto.builder().id(1L).valuePerKey(valuePerKey).objectCollectionPerKey(objectCollectionPerKey).build();

        InstanceObject result = instanceMappingService.toInstanceObject(dto);

        assertNotNull(result);
        assertEquals(valuePerKey, result.getValuePerKey());
        assertEquals(2, result.getObjectCollectionPerKey().size());
    }

    @Test
    void testToInstanceObjectDto() {
        Map<String, String> valuePerKey = new HashMap<>();
        valuePerKey.put("key1", "value1");
        valuePerKey.put("key2", "value2");

        Map<String, InstanceObjectCollection> objectCollectionPerKey = new HashMap<>();
        objectCollectionPerKey.put("key1", new InstanceObjectCollection(1L, List.of(new InstanceObject(1L, valuePerKey, new HashMap<>()))));
        objectCollectionPerKey.put("key2", new InstanceObjectCollection(2L, List.of(new InstanceObject(2L, valuePerKey, new HashMap<>()))));

        InstanceObject object = new InstanceObject(1L, valuePerKey, objectCollectionPerKey);

        InstanceObjectDto result = instanceMappingService.toInstanceObjectDto(object);

        assertNotNull(result);
        assertEquals(object.getId(), result.getId());
        assertEquals(valuePerKey, result.getValuePerKey());
        assertEquals(2, result.getObjectCollectionPerKey().size());
    }
}
