package no.fintlabs;

import no.fintlabs.flyt.kafka.headers.InstanceFlowHeaders;
import no.fintlabs.model.instance.dtos.InstanceObjectDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import javax.persistence.EntityNotFoundException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class InstanceRetryControllerTest {

    @Mock
    private InstanceService instanceService;

    @Mock
    private InstanceFlowHeadersForRegisteredInstanceRequestProducerService instanceFlowHeadersForRegisteredInstanceRequestProducerService;

    @Mock
    private InstanceRequestedForRetryEventProducerService instanceRequestedForRetryEventProducerService;

    @Mock
    private InstanceRetryRequestErrorEventProducerService instanceRetryRequestErrorEventProducerService;

    @Mock
    private InstanceFlowHeaders instanceFlowHeaders;

    @InjectMocks
    private InstanceRetryController controller;

    private final Long instanceId = 123L;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }


    @Test
    public void retry_instanceFound_headersFound_returnsOk() {
        InstanceObjectDto instance = InstanceObjectDto.builder().build();

        when(instanceService.getById(instanceId)).thenReturn(instance);
        when(instanceFlowHeadersForRegisteredInstanceRequestProducerService.get(instanceId)).thenReturn(Optional.of(instanceFlowHeaders));
        when(instanceFlowHeaders.toBuilder()).thenReturn(InstanceFlowHeaders.builder().sourceApplicationId(1L).correlationId(UUID.randomUUID()));

        ResponseEntity<?> response = controller.retry(instanceId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(instanceRequestedForRetryEventProducerService).publish(any(), eq(instance));
    }

    @Test
    public void retry_instanceNotFound_throwsResponseStatusException() {
        when(instanceService.getById(instanceId)).thenThrow(new EntityNotFoundException());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> controller.retry(instanceId));

        assertThrows(ResponseStatusException.class, () -> controller.retry(instanceId));
        assertTrue(Objects.requireNonNull(exception.getReason()).contains("Could not find instance with id='" + instanceId + "'"));
    }

    @Test
    public void retry_noInstanceFlowHeaders_throwsResponseStatusException() {
        InstanceObjectDto instance = InstanceObjectDto.builder().build();

        when(instanceService.getById(instanceId)).thenReturn(instance);
        when(instanceFlowHeadersForRegisteredInstanceRequestProducerService.get(instanceId)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> controller.retry(instanceId));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        assertTrue(Objects.requireNonNull(exception.getReason()).contains("Could not find instance flow headers for registered instance with id=" + instanceId));
    }

    @Test
    public void retry_genericException_throwsResponseStatusException() {
        when(instanceService.getById(instanceId)).thenThrow(new RuntimeException("Some error"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> controller.retry(instanceId));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
        assertNull(exception.getReason());
        verify(instanceRetryRequestErrorEventProducerService, never()).publishGeneralSystemErrorEvent(any());
    }

    @Test
    public void retry_genericExceptionWithInstanceFlowHeaders_publishesErrorAndThrowsResponseStatusException() {
        InstanceObjectDto instance = InstanceObjectDto.builder().build();
        InstanceFlowHeaders instanceFlowHeaders = InstanceFlowHeaders.builder().sourceApplicationId(1L).correlationId(UUID.randomUUID()).build();

        when(instanceService.getById(instanceId)).thenReturn(instance);
        when(instanceFlowHeadersForRegisteredInstanceRequestProducerService.get(instanceId)).thenReturn(Optional.of(instanceFlowHeaders));

        doThrow(new RuntimeException("Unexpected error"))
                .when(instanceRequestedForRetryEventProducerService)
                .publish(any(InstanceFlowHeaders.class), any(InstanceObjectDto.class));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.retry(instanceId));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        verify(instanceRetryRequestErrorEventProducerService).publishGeneralSystemErrorEvent(any(InstanceFlowHeaders.class));
    }

}
