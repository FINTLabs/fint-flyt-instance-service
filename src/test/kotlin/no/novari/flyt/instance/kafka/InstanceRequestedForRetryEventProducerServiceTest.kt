package no.novari.flyt.instance.kafka

import no.novari.flyt.audit.actor.Actor
import no.novari.flyt.audit.actor.ActorHeader
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowProducerRecord
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplate
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplateFactory
import no.novari.kafka.topic.EventTopicService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.AuditorAware
import java.time.Duration
import java.util.Optional
import java.util.UUID

class InstanceRequestedForRetryEventProducerServiceTest {
    @Mock
    private lateinit var instanceFlowTemplateFactory: InstanceFlowTemplateFactory

    @Mock
    private lateinit var eventTopicService: EventTopicService

    @Mock
    private lateinit var auditorAware: AuditorAware<Actor>

    @Mock
    private lateinit var instanceFlowTemplate: InstanceFlowTemplate<InstanceObjectDto>

    private lateinit var producerService: InstanceRequestedForRetryEventProducerService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(
            instanceFlowTemplateFactory.createTemplate(InstanceObjectDto::class.java),
        ).thenReturn(instanceFlowTemplate)
        producerService =
            InstanceRequestedForRetryEventProducerService(
                instanceFlowTemplateFactory,
                eventTopicService,
                auditorAware,
                Duration.ofDays(4),
            )
    }

    @Test
    fun `publish adds current actor as flyt actor header`() {
        val actor = Actor.User(UUID.fromString("2ee6f95e-44c3-11ed-b878-0242ac120002"))
        val instanceFlowHeaders = createInstanceFlowHeaders()
        val instance = InstanceObjectDto(id = 123L)
        whenever(auditorAware.currentAuditor).thenReturn(Optional.of(actor))

        producerService.publish(instanceFlowHeaders, instance)

        val recordCaptor = argumentCaptor<InstanceFlowProducerRecord<InstanceObjectDto>>()
        verify(instanceFlowTemplate).send(recordCaptor.capture())
        val record = recordCaptor.firstValue

        assertEquals(instanceFlowHeaders, record.instanceFlowHeaders)
        assertEquals(instance, record.value)
        assertEquals(
            actor,
            ActorHeader.fromHeaderValue(record.additionalHeaders.lastHeader(ActorHeader.HEADER_NAME).value()),
        )
    }

    @Test
    fun `publish adds system actor header when current auditor is empty`() {
        whenever(auditorAware.currentAuditor).thenReturn(Optional.empty())

        producerService.publish(createInstanceFlowHeaders(), InstanceObjectDto(id = 123L))

        val recordCaptor = argumentCaptor<InstanceFlowProducerRecord<InstanceObjectDto>>()
        verify(instanceFlowTemplate).send(recordCaptor.capture())
        val actorHeader = recordCaptor.firstValue.additionalHeaders.lastHeader(ActorHeader.HEADER_NAME)

        assertEquals(Actor.System, ActorHeader.fromHeaderValue(actorHeader.value()))
    }

    private fun createInstanceFlowHeaders(): InstanceFlowHeaders =
        InstanceFlowHeaders
            .builder()
            .sourceApplicationId(1L)
            .correlationId(UUID.fromString("2ee6f95e-44c3-11ed-b878-0242ac120002"))
            .build()
}
