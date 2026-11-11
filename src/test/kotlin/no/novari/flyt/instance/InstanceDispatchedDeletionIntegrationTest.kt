package no.novari.flyt.instance

import no.novari.flyt.instance.kafka.InstanceDeletedEventProducerService
import no.novari.flyt.instance.kafka.InstanceFlowHeadersForRegisteredInstanceRequestProducerService
import no.novari.flyt.instance.model.InstanceMappingService
import no.novari.flyt.instance.model.entities.InstanceObject
import no.novari.flyt.instance.model.entities.InstanceObjectCollection
import no.novari.flyt.kafka.instanceflow.headers.InstanceFlowHeaders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JpaAuditingTestConfig::class, InstanceService::class, InstanceMappingService::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InstanceDispatchedDeletionIntegrationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    @Autowired
    private lateinit var instanceService: InstanceService

    @Autowired
    private lateinit var instanceRepository: InstanceRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @MockitoBean
    private lateinit var instanceDeletedEventProducerService: InstanceDeletedEventProducerService

    @MockitoBean
    private lateinit var instanceFlowHeadersForRegisteredInstanceRequestProducerService:
        InstanceFlowHeadersForRegisteredInstanceRequestProducerService

    @Test
    fun `concurrent deletions of a dispatched instance delete the graph and publish once`() {
        val headers = headers(instanceRepository.saveAndFlush(instanceGraph()).id!!)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val second =
                TransactionTemplate(transactionManager).execute {
                    // The first deletion joins this transaction, so its row lock is held until the block ends.
                    instanceService.deleteInstanceByInstanceFlowHeaders(headers)
                    val second = executor.submit { instanceService.deleteInstanceByInstanceFlowHeaders(headers) }
                    awaitWaitingLock()
                    assertFalse(second.isDone)
                    second
                }!!
            second.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        verify(instanceDeletedEventProducerService, times(1)).publish(headers)
        for (table in listOf("instance_object", "instance_object_collection", "instance_object_value_per_key")) {
            assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Long::class.java))
        }
    }

    private fun awaitWaitingLock() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long::class.java) == 0L) {
            check(System.nanoTime() < deadline) { "The second deletion never waited for the row lock" }
            Thread.sleep(50)
        }
    }

    private fun instanceGraph(): InstanceObject {
        val child = InstanceObject(valuePerKey = mutableMapOf("level" to "child"))
        return InstanceObject(
            valuePerKey = mutableMapOf("level" to "root"),
            objectCollectionPerKey =
                mutableMapOf("children" to InstanceObjectCollection(objects = mutableListOf(child))),
        )
    }

    private fun headers(instanceId: Long) =
        InstanceFlowHeaders
            .builder()
            .sourceApplicationId(1L)
            .correlationId(UUID.randomUUID())
            .instanceId(instanceId)
            .build()
}
