package no.fintlabs;

import no.fintlabs.kafka.InstanceDeletedEventProducerService;
import no.fintlabs.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService;
import no.fintlabs.model.instance.InstanceMappingService;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import no.fintlabs.model.instance.entities.InstanceObject;
import no.fintlabs.slack.SlackAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.EmptyResultDataAccessException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class InstanceServiceTest {

    @Mock
    private InstanceRepository instanceRepository;

    @Mock
    private InstanceMappingService instanceMappingService;

    @Mock
    private InstanceDeletedEventProducerService instanceDeletedEventProducerService;

    @Mock
    private InstanceFlowHeadersForRegisteredInstanceRequestProducerService instanceFlowHeadersForRegisteredInstanceRequestProducerService;

    @Mock
    private SlackAlertService slackAlertService;

    private InstanceService instanceService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        instanceService = new InstanceService(
                instanceRepository,
                instanceMappingService,
                instanceDeletedEventProducerService,
                instanceFlowHeadersForRegisteredInstanceRequestProducerService,
                slackAlertService
        );
    }

    @Test
    void testSave() {
        Map<String, String> valuePerKey = new HashMap<>();
        valuePerKey.put("key1", "value1");
        valuePerKey.put("key2", "value2");

        Map<String, Collection<InstanceObjectDto>> objectCollectionPerKey = new HashMap<>();

        InstanceObjectDto dto = InstanceObjectDto.builder().id(1L).valuePerKey(valuePerKey).objectCollectionPerKey(objectCollectionPerKey).build();
        InstanceObject object = new InstanceObject(1L, valuePerKey, new HashMap<>(), new Date());

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
        InstanceObject object = new InstanceObject(id, valuePerKey, new HashMap<>(), new Date());

        when(instanceRepository.getReferenceById(any())).thenReturn(object);
        when(instanceMappingService.toInstanceObjectDto(any())).thenReturn(dto);

        InstanceObjectDto result = instanceService.getById(id);

        assertEquals(dto, result);

        verify(instanceRepository, times(1)).getReferenceById(id);
        verify(instanceMappingService, times(1)).toInstanceObjectDto(object);
    }

    @Test
    void testDeleteAllOlderThan_throwsEmptyResultDataAccessException() {
        int days = 30;
        Timestamp oldTimestamp = Timestamp.valueOf(LocalDateTime.now().minusDays(days + 1));

        InstanceObject instance1 = InstanceObject.builder().id(1L).createdAt(oldTimestamp).build();
        InstanceObject instance2 = InstanceObject.builder().id(2L).createdAt(oldTimestamp).build();

        List<InstanceObject> instanceObjects = List.of(instance1, instance2);

        InstanceObjectDto instanceDto1 = InstanceObjectDto.builder().id(1L).createdAt(oldTimestamp).build();
        InstanceObjectDto instanceDto2 = InstanceObjectDto.builder().id(2L).createdAt(oldTimestamp).build();

        doReturn(instanceObjects).when(instanceRepository).findAllOlderThan(any(Timestamp.class));

        doReturn(instanceDto1).when(instanceMappingService).toInstanceObjectDto(instance1);
        doReturn(instanceDto2).when(instanceMappingService).toInstanceObjectDto(instance2);

        doReturn(Optional.empty()).when(instanceFlowHeadersForRegisteredInstanceRequestProducerService).get(anyLong());

        doThrow(new EmptyResultDataAccessException(1)).when(instanceRepository).deleteById(anyLong());

        instanceService.deleteAllOlderThan(days);

        verify(slackAlertService, atLeastOnce()).sendMessage(contains("was already deleted"));
    }

    @Test
    void testDeleteAllOlderThan_throwsRuntimeException() {
        int days = 30;
        Timestamp oldTimestamp = Timestamp.valueOf(LocalDateTime.now().minusDays(days + 1));

        InstanceObject instance1 = InstanceObject.builder().id(1L).createdAt(oldTimestamp).build();
        InstanceObject instance2 = InstanceObject.builder().id(2L).createdAt(oldTimestamp).build();

        List<InstanceObject> instanceObjects = List.of(instance1, instance2);

        InstanceObjectDto instanceDto1 = InstanceObjectDto.builder().id(1L).createdAt(oldTimestamp).build();
        InstanceObjectDto instanceDto2 = InstanceObjectDto.builder().id(2L).createdAt(oldTimestamp).build();

        doReturn(instanceObjects).when(instanceRepository).findAllOlderThan(any(Timestamp.class));

        doReturn(instanceDto1).when(instanceMappingService).toInstanceObjectDto(instance1);
        doReturn(instanceDto2).when(instanceMappingService).toInstanceObjectDto(instance2);

        doReturn(Optional.empty()).when(instanceFlowHeadersForRegisteredInstanceRequestProducerService).get(anyLong());

        doThrow(new RuntimeException("Simulated deletion failure")).when(instanceRepository).deleteById(anyLong());

        instanceService.deleteAllOlderThan(days);

        verify(slackAlertService, atLeastOnce()).sendMessage(contains("Failed to delete instance"));
    }
}
