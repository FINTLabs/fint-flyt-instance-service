package no.novari.flyt.instance.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.novari.flyt.instance.InstanceReceivedOffsetRepository
import no.novari.flyt.instance.InstanceService
import no.novari.flyt.instance.model.dtos.InstanceObjectDto
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowConsumerRecord
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowConsumerRecordMapper
import no.novari.flyt.kafka.instanceflow.consuming.InstanceFlowListenerFactoryService
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeadersMapper
import no.novari.flyt.kafka.instanceflow.producing.InstanceFlowTemplateFactory
import no.novari.kafka.consuming.ConsumerFactoryService
import no.novari.kafka.consuming.ListenerContainerFactoryService
import no.novari.kafka.consuming.ParameterizedListenerContainerFactoryService
import no.novari.kafka.producing.ParameterizedTemplateFactory
import no.novari.kafka.producing.ProducerFactory
import no.novari.kafka.producing.TemplateFactory
import no.novari.kafka.topic.ErrorEventTopicService
import no.novari.kafka.topic.EventTopicService
import no.novari.kafka.topic.name.ErrorEventTopicNameParameters
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePatternService
import no.novari.kafka.topic.name.TopicNameService
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

@Testcontainers
class InstanceReceivedKafkaIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withInitScript("db/instance-received-offset.sql")

        @Container
        @JvmStatic
        val kafka = KafkaContainer("apache/kafka:3.9.2")
    }

    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val headersMapper = InstanceFlowHeadersMapper(mapper)
    private val headers =
        InstanceFlowHeaders
            .builder()
            .sourceApplicationId(1L)
            .correlationId(UUID.randomUUID())
            .build()
    private val service = mock<InstanceService>()
    private val suffix = UUID.randomUUID().toString()
    private val group = "fint-flyt-instance-service-$suffix"
    private val sourceTopic = "afk-no.flyt.instance-received-$suffix"
    private val registeredTopic = "afk-no.flyt.instance-registered-$suffix"
    private val errorTopic = "afk-no.flyt.instance-error-$suffix"
    private val partition = TopicPartition(sourceTopic, 0)
    private val adminJdbc by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun setUp() {
        adminJdbc.execute("TRUNCATE history_test.instance_received_offset")
        adminJdbc.execute("GRANT UPDATE ON history_test.instance_received_offset TO instance_writer")
        whenever(service.save(any())).thenAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            InstanceObjectDto(id = 123L)
        }
    }

    @Test
    fun `initializes an idle partition and checkpoints after actual broker publication`() {
        withConsumer { context ->
            val repository = context.getBean(InstanceReceivedOffsetRepository::class.java)
            awaitPosition(repository, 0)
            val transaction = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
            assertThrows(IllegalTransactionStateException::class.java) {
                transaction.executeWithoutResult {
                    context.getBean(InstanceReceivedHandler::class.java).handle(
                        InstanceFlowConsumerRecord(
                            headers,
                            ConsumerRecord(sourceTopic, 0, 0, null, InstanceObjectDto()),
                        ),
                    )
                }
            }

            send("{}")
            awaitPosition(repository, 1)

            KafkaConsumer(
                mapOf(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers),
                StringDeserializer(),
                StringDeserializer(),
            ).use { observer ->
                val output = TopicPartition(registeredTopic, 0)
                observer.assign(listOf(output))
                observer.seekToBeginning(listOf(output))
                val records = mutableListOf<ConsumerRecord<String, String>>()
                await().atMost(Duration.ofSeconds(15)).untilAsserted {
                    observer.poll(Duration.ofMillis(200)).forEach(records::add)
                    assertEquals(1, records.size)
                }
                assertEquals(123L, headersMapper.getInstanceFlowHeaders(records.single().headers()).instanceId)
                assertEquals(123L, mapper.readTree(records.single().value()).get("id").asLong())
                assertNull(records.single().key())
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted { assertEquals(1L, committedOffset()) }
            verify(service).save(any())
        }
    }

    @Test
    fun `existing registration error is confirmed by the broker before checkpointing`() {
        whenever(service.save(any())).thenThrow(IllegalArgumentException("invalid source data"))

        withConsumer { context ->
            val repository = context.getBean(InstanceReceivedOffsetRepository::class.java)
            awaitPosition(repository, 0)

            send("{}")
            awaitPosition(repository, 1)

            KafkaConsumer(
                mapOf(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers),
                StringDeserializer(),
                StringDeserializer(),
            ).use { observer ->
                val output = TopicPartition(errorTopic, 0)
                observer.assign(listOf(output))
                observer.seekToBeginning(listOf(output))
                val records = mutableListOf<ConsumerRecord<String, String>>()
                await().atMost(Duration.ofSeconds(15)).untilAsserted {
                    observer.poll(Duration.ofMillis(200)).forEach(records::add)
                    assertEquals(1, records.size)
                }
                assertEquals(headers, headersMapper.getInstanceFlowHeaders(records.single().headers()))
                assertNull(records.single().key())
            }
            verify(service).save(any())
        }
    }

    @Test
    fun `broker rejection stops before later records and does not commit failed progress`() {
        withConsumer(failPublication = true) { context ->
            val repository = context.getBean(InstanceReceivedOffsetRepository::class.java)
            awaitPosition(repository, 0)

            send("{}")
            send("{}")
            awaitStopped(context)

            assertEquals(0L, repository.findNextOffset(partition))
            assertEquals(0L, committedOffset())
            verify(service, times(1)).save(any())
        }
    }

    @Test
    fun `database failure after publication stops without skipping to the next record`() {
        withConsumer { context ->
            val repository = context.getBean(InstanceReceivedOffsetRepository::class.java)
            awaitPosition(repository, 0)
            adminJdbc.execute("REVOKE UPDATE ON history_test.instance_received_offset FROM instance_writer")

            send("{}")
            send("{}")
            awaitStopped(context)

            assertEquals(0L, repository.findNextOffset(partition))
            assertEquals(0L, committedOffset())
            verify(service, times(1)).save(any())
        }
    }

    @Test
    fun `unreadable payload stops without advancing or processing later records`() {
        withConsumer { context ->
            val repository = context.getBean(InstanceReceivedOffsetRepository::class.java)
            awaitPosition(repository, 0)

            send("not-json-private-source-value")
            send("{}")
            awaitStopped(context)

            assertEquals(0L, repository.findNextOffset(partition))
            assertEquals(0L, committedOffset())
            verifyNoInteractions(service)
        }
    }

    @Test
    fun `missing group position stops without initializing from latest`() {
        withConsumer(committed = null) { context ->
            awaitStopped(context)

            assertNull(context.getBean(InstanceReceivedOffsetRepository::class.java).findNextOffset(partition))
            assertNull(committedOffset())
            verifyNoInteractions(service)
        }
    }

    @Test
    fun `redelivery preserves database progress and skips records already confirmed`() {
        withConsumer(initialPosition = 1) { context ->
            val repository = context.getBean(InstanceReceivedOffsetRepository::class.java)
            awaitPosition(repository, 1)

            send("{}")
            send("{}")
            awaitPosition(repository, 2)

            verify(service, times(1)).save(any())
        }
    }

    private fun withConsumer(
        committed: Long? = 0,
        initialPosition: Long? = null,
        failPublication: Boolean = false,
        action: (AssertableApplicationContext) -> Unit,
    ) {
        admin().use { admin ->
            val registered = NewTopic(registeredTopic, 1, 1.toShort())
            if (failPublication) registered.configs(mapOf("max.message.bytes" to "64"))
            admin
                .createTopics(
                    listOf(NewTopic(sourceTopic, 1, 1.toShort()), registered, NewTopic(errorTopic, 1, 1.toShort())),
                ).all()
                .get(15, TimeUnit.SECONDS)
            if (committed != null) {
                admin
                    .alterConsumerGroupOffsets(group, mapOf(partition to OffsetAndMetadata(committed)))
                    .all()
                    .get(15, TimeUnit.SECONDS)
            }
        }
        if (initialPosition != null) {
            adminJdbc.update(
                "INSERT INTO history_test.instance_received_offset (topic_name, partition_id, next_offset) VALUES (?, 0, ?)",
                sourceTopic,
                initialPosition,
            )
        }
        val topicNames = mock<TopicNameService>()
        whenever(topicNames.validateAndMapToTopicName(any())).thenAnswer {
            when (val parameters = it.arguments[0]) {
                is EventTopicNameParameters -> "afk-no.flyt.${parameters.eventName}-$suffix"
                is ErrorEventTopicNameParameters -> "afk-no.flyt.${parameters.errorEventName}-$suffix"
                else -> error("Unexpected topic parameters")
            }
        }
        val datasource = DriverManagerDataSource(postgres.jdbcUrl, "instance_writer", "writer")
        val producerConfig =
            ProducerConfig(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                    ProducerConfig.ACKS_CONFIG to "all",
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 1000,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 2000,
                    ProducerConfig.MAX_BLOCK_MS_CONFIG to 2000,
                ),
            )
        val producerFactory = TrackingProducerFactory(producerConfig)
        try {
            ApplicationContextRunner()
                .withUserConfiguration(KafkaTestConfiguration::class.java)
                .withInitializer { it.beanFactory.conversionService = ApplicationConversionService.getSharedInstance() }
                .withPropertyValues(
                    "novari.flyt.instance-service.database.history-schema=history_test",
                    "novari.flyt.instance-service.kafka.topic.instance-processing-events-retention-time=4d",
                ).withBean(com.fasterxml.jackson.databind.ObjectMapper::class.java, { mapper })
                .withBean(ConsumerConfig::class.java, {
                    ConsumerConfig(
                        mapOf(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                            ConsumerConfig.GROUP_ID_CONFIG to group,
                            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to true,
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest",
                        ),
                    )
                })
                .withBean(ProducerFactory::class.java, { producerFactory })
                .withBean(javax.sql.DataSource::class.java, { datasource })
                .withBean(JdbcTemplate::class.java, { JdbcTemplate(datasource) })
                .withBean(PlatformTransactionManager::class.java, { JdbcTransactionManager(datasource) })
                .withBean(InstanceService::class.java, { service })
                .withBean(TopicNameService::class.java, { topicNames })
                .withBean(TopicNamePatternService::class.java, { mock<TopicNamePatternService>() })
                .withBean(EventTopicService::class.java, { mock<EventTopicService>() })
                .withBean(ErrorEventTopicService::class.java, { mock<ErrorEventTopicService>() })
                .run { context ->
                    assertNull(context.startupFailure)
                    action(context)
                }
        } finally {
            producerFactory.factories.forEach(DefaultKafkaProducerFactory<*, *>::destroy)
        }
    }

    private fun send(payload: String) {
        KafkaProducer(
            mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers),
            StringSerializer(),
            StringSerializer(),
        ).use { producer ->
            producer
                .send(
                    ProducerRecord(sourceTopic, 0, null, null, payload, listOf(headersMapper.toHeader(headers))),
                ).get(15, TimeUnit.SECONDS)
        }
    }

    private fun awaitPosition(
        repository: InstanceReceivedOffsetRepository,
        expected: Long,
    ) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertEquals(expected, repository.findNextOffset(partition))
        }
    }

    private fun awaitStopped(context: AssertableApplicationContext) {
        val container = context.getBean("instanceReceivedEventConsumer", ConcurrentMessageListenerContainer::class.java)
        await().atMost(Duration.ofSeconds(20)).until { !container.isRunning }
    }

    private fun committedOffset(): Long? =
        admin().use {
            it
                .listConsumerGroupOffsets(
                    group,
                ).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS)[partition]
                ?.offset()
        }

    private fun admin(): Admin =
        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers))

    private inner class TrackingProducerFactory(
        config: ProducerConfig,
    ) : ProducerFactory(config, mapper) {
        val factories = mutableListOf<DefaultKafkaProducerFactory<*, *>>()

        override fun <V : Any?> createFactory(
            valueClass: Class<V>,
        ): org.springframework.kafka.core.ProducerFactory<String, V> =
            super.createFactory(valueClass).also { factories.add(it as DefaultKafkaProducerFactory<String, V>) }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(
        ConsumerFactoryService::class,
        ListenerContainerFactoryService::class,
        ParameterizedListenerContainerFactoryService::class,
        InstanceFlowListenerFactoryService::class,
        InstanceFlowConsumerRecordMapper::class,
        InstanceFlowHeadersMapper::class,
        TemplateFactory::class,
        ParameterizedTemplateFactory::class,
        InstanceFlowTemplateFactory::class,
        InstanceRegisteredEventProducerService::class,
        InstanceRegistrationErrorProducerService::class,
        InstanceReceivedOffsetRepository::class,
        InstanceReceivedHandler::class,
        InstanceReceivedEventConsumerConfiguration::class,
    )
    class KafkaTestConfiguration
}
