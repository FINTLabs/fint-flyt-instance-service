package no.fintlabs;

import no.fintlabs.model.instance.InstanceMappingService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import no.fintlabs.model.instance.entities.InstanceObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class InstanceServiceTest {

    @Mock
    private InstanceRepository instanceRepository;

    @Mock
    private InstanceMappingService instanceMappingService;

    private InstanceService instanceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        instanceService = new InstanceService(instanceRepository, instanceMappingService);
    }

    @Test
    void testSave() {
        Map<String, String> valuePerKey = new HashMap<>();
        valuePerKey.put("key1", "value1");
        valuePerKey.put("key2", "value2");

        Map<String, Collection<InstanceObjectDto>> objectCollectionPerKey = new HashMap<>();

        InstanceObjectDto dto = InstanceObjectDto.builder().id(1L).valuePerKey(valuePerKey).objectCollectionPerKey(objectCollectionPerKey).build();
        InstanceObject object = new InstanceObject(1L, valuePerKey, new HashMap<>());

        when(instanceMappingService.toInstanceObject(any())).thenReturn(object);
        when(instanceRepository.save(any())).thenReturn(object);
        when(instanceMappingService.toInstanceObjectDto(any())).thenReturn(dto);

        InstanceObjectDto result = instanceService.save(dto);

        assertEquals(dto, result);

        verify(instanceMappingService, times(1)).toInstanceObject(dto);
        verify(instanceRepository, times(1)).save(object);
        verify(instanceMappingService, times(1)).toInstanceObjectDto(object);
    }

    @Test
    void testGetById() {
        Map<String, String> valuePerKey = new HashMap<>();
        valuePerKey.put("key1", "value1");
        valuePerKey.put("key2", "value2");

        Map<String, Collection<InstanceObjectDto>> objectCollectionPerKey = new HashMap<>();

        Long id = 1L;

        InstanceObjectDto dto = InstanceObjectDto.builder().id(id).valuePerKey(valuePerKey).objectCollectionPerKey(objectCollectionPerKey).build();
        InstanceObject object = new InstanceObject(id, valuePerKey, new HashMap<>());

        when(instanceRepository.getById(any())).thenReturn(object);
        when(instanceMappingService.toInstanceObjectDto(any())).thenReturn(dto);

        InstanceObjectDto result = instanceService.getById(id);

        assertEquals(dto, result);

        verify(instanceRepository, times(1)).getById(id);
        verify(instanceMappingService, times(1)).toInstanceObjectDto(object);
    }
}
