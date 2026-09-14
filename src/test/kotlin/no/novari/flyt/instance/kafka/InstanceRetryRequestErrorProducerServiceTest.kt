package no.novari.flyt.instance.kafka

import no.novari.flyt.audit.actor.Actor
import no.novari.flyt.audit.actor.ActorHeader
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowProducerRecord
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplate
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplateFactory
import no.novari.flyt.kafka.model.InstanceErrorEvent
import no.novari.kafka.topic.ErrorEventTopicService
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

class InstanceRetryRequestErrorProducerServiceTest {
    @Mock
    private lateinit var errorEventTopicService: ErrorEventTopicService

    @Mock
    private lateinit var instanceFlowTemplateFactory: InstanceFlowTemplateFactory

    @Mock
    private lateinit var auditorAware: AuditorAware<Actor>

    @Mock
    private lateinit var instanceFlowTemplate: InstanceFlowTemplate<InstanceErrorEvent>

    private lateinit var producerService: InstanceRetryRequestErrorProducerService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(
            instanceFlowTemplateFactory.createTemplate(InstanceErrorEvent::class.java),
        ).thenReturn(instanceFlowTemplate)
        producerService =
            InstanceRetryRequestErrorProducerService(
                errorEventTopicService,
                instanceFlowTemplateFactory,
                auditorAware,
                Duration.ofDays(4),
            )
    }

    @Test
    fun `publish general system error event adds current actor as flyt actor header`() {
        val actor = Actor.User(UUID.fromString("2ee6f95e-44c3-11ed-b878-0242ac120002"))
        val instanceFlowHeaders = createInstanceFlowHeaders()
        whenever(auditorAware.currentAuditor).thenReturn(Optional.of(actor))

        producerService.publishGeneralSystemErrorEvent(instanceFlowHeaders)

        val recordCaptor = argumentCaptor<InstanceFlowProducerRecord<InstanceErrorEvent>>()
        verify(instanceFlowTemplate).send(recordCaptor.capture())
        val record = recordCaptor.firstValue

        assertEquals(instanceFlowHeaders, record.instanceFlowHeaders)
        assertEquals(
            actor,
            ActorHeader.fromHeaderValue(record.additionalHeaders.lastHeader(ActorHeader.HEADER_NAME).value()),
        )
    }

    @Test
    fun `publish general system error event adds system actor header when current auditor is empty`() {
        whenever(auditorAware.currentAuditor).thenReturn(Optional.empty())

        producerService.publishGeneralSystemErrorEvent(createInstanceFlowHeaders())

        val recordCaptor = argumentCaptor<InstanceFlowProducerRecord<InstanceErrorEvent>>()
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
