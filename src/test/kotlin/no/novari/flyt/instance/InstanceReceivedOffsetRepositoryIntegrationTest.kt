package no.novari.flyt.instance

import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@JdbcTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.datasource.hikari.schema=instance_test",
        "novari.flyt.instance-service.database.history-schema=history_test",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InstanceReceivedOffsetRepository::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class InstanceReceivedOffsetRepositoryIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres =
            PostgreSQLContainer("postgres:17-alpine")
                .withInitScript("db/instance-received-offset.sql")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username") { "instance_writer" }
            registry.add("spring.datasource.password") { "writer" }
        }
    }

    @Autowired
    private lateinit var repository: InstanceReceivedOffsetRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val partition = TopicPartition("afk-no.flyt.instance-received", 0)
    private val admin by lazy {
        JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun cleanUp() {
        admin.execute("TRUNCATE history_test.instance_received_offset")
    }

    @Test
    fun `writes to history schema using only the granted privileges`() {
        assertEquals("instance_writer", jdbc.queryForObject("SELECT current_user", String::class.java))
        assertEquals("instance_test", jdbc.queryForObject("SELECT current_schema()", String::class.java))
        assertFalse(
            jdbc.queryForObject(
                "SELECT has_schema_privilege(current_user, 'history_test', 'CREATE')",
                Boolean::class.java,
            )!!,
        )
        assertFalse(
            jdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'history_test.instance_received_offset', 'DELETE')",
                Boolean::class.java,
            )!!,
        )

        assertNull(repository.findNextOffset(partition))
        assertEquals(42L, repository.initialize(partition, 42))
        repository.advance(partition, 43)

        assertEquals(43L, repository.findNextOffset(partition))
        val row = jdbc.queryForMap("SELECT * FROM history_test.instance_received_offset")
        assertNull(row["cluster_id"])
        assertNull(row["topic_id"])
        assertTrue(row["updated_at"] != null)
    }

    @Test
    fun `initialization preserves existing progress and repeated updates never move backwards`() {
        repository.initialize(partition, 42)
        repository.advance(partition, 100)

        assertEquals(100L, repository.initialize(partition, 5))
        repository.advance(partition, 43)
        repository.advance(partition, 100)

        assertEquals(100L, repository.findNextOffset(partition))
    }

    @Test
    fun `keeps independent positions per topic and partition`() {
        val otherPartition = TopicPartition(partition.topic(), 1)
        val otherTopic = TopicPartition("vlfk-no.flyt.instance-received", 0)
        repository.initialize(partition, 5)
        repository.initialize(otherPartition, 10)
        repository.initialize(otherTopic, 15)

        repository.advance(partition, 9)

        assertEquals(9L, repository.findNextOffset(partition))
        assertEquals(10L, repository.findNextOffset(otherPartition))
        assertEquals(15L, repository.findNextOffset(otherTopic))
    }

    @Test
    fun `does not recreate missing rows while advancing`() {
        assertThrows(InstanceReceivedOffsetException::class.java) { repository.advance(partition, 42) }
        assertNull(repository.findNextOffset(partition))
    }

    @Test
    fun `refuses every operation after history-service has assigned identity`() {
        repository.initialize(partition, 42)
        admin.update(
            "UPDATE history_test.instance_received_offset SET cluster_id = ?, topic_id = ? WHERE topic_name = ? AND partition_id = ?",
            "cluster",
            UUID.randomUUID(),
            partition.topic(),
            partition.partition(),
        )

        assertThrows(InstanceReceivedOffsetException::class.java) { repository.findNextOffset(partition) }
        assertThrows(InstanceReceivedOffsetException::class.java) { repository.initialize(partition, 0) }
        assertThrows(InstanceReceivedOffsetException::class.java) { repository.advance(partition, 100) }
        assertEquals(
            42L,
            admin.queryForObject("SELECT next_offset FROM history_test.instance_received_offset", Long::class.java)!!,
        )
    }

    @Test
    fun `position rolls back when its database transaction fails`() {
        repository.initialize(partition, 42)

        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                repository.advance(partition, 43)
                throw IllegalStateException("rollback")
            }
        }

        assertEquals(42L, repository.findNextOffset(partition))
    }

    @Test
    fun `concurrent initialization creates a single row without overwriting the winner`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val attempts =
                listOf(42L, 100L).map { offset ->
                    executor.submit<Long> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        repository.initialize(partition, offset)
                    }
                }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = attempts.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results.first(), results.last())
            assertEquals(results.first(), repository.findNextOffset(partition))
        }
        assertEquals(
            1L,
            jdbc.queryForObject("SELECT count(*) FROM history_test.instance_received_offset", Long::class.java)!!,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "history; DROP SCHEMA public", "history.test", "History", "public\"", " public"])
    fun `rejects invalid schema identifiers`(schema: String) {
        assertThrows(IllegalArgumentException::class.java) { InstanceReceivedOffsetRepository(jdbc, schema) }
    }
}
