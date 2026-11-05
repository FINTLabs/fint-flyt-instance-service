package no.novari.flyt.instance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@JdbcTest(properties = ["spring.flyway.enabled=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(InstanceReceivedOffsetRepository::class)
class InstanceReceivedOffsetRepositoryIntegrationTest {
    companion object {
        private const val SCHEMA = "test_no_fint_flyt_history_service_db"
        private const val TOPIC = "test-no.flyt.event.instance-received"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine").withInitScript("history-offset-table.sql")

        // Connects as the restricted instance-service role, so missing grants fail the tests.
        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username") { "test_no_fint_flyt_instance_service_db" }
            registry.add("spring.datasource.password") { "instance" }
        }
    }

    @Autowired
    private lateinit var repository: InstanceReceivedOffsetRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `derives the history schema from the database user`() {
        assertEquals(SCHEMA, repository.historySchema)
    }

    @Test
    fun `first write creates an unbound row with the next offset`() {
        repository.markProcessed(TOPIC, 0, 41)

        val row = jdbcTemplate.queryForMap("SELECT * FROM $SCHEMA.instance_received_offset WHERE topic_name = ?", TOPIC)
        assertEquals(42L, row["next_offset"])
        assertNull(row["cluster_id"])
        assertNull(row["topic_id"])
    }

    @Test
    fun `position never moves backwards and a skipped write is covered by the next one`() {
        repository.markProcessed(TOPIC, 0, 100)
        repository.markProcessed(TOPIC, 0, 102)
        repository.markProcessed(TOPIC, 0, 101)

        assertEquals(103L, nextOffset())
    }

    @Test
    fun `does not move a position history-service has bound`() {
        repository.markProcessed(TOPIC, 0, 9)
        jdbcTemplate.update(
            "UPDATE $SCHEMA.instance_received_offset SET cluster_id = 'cluster', topic_id = gen_random_uuid()",
        )

        repository.markProcessed(TOPIC, 0, 20)

        assertEquals(10L, nextOffset())
    }

    @Test
    fun `fails on a database user it cannot derive from unless the schema is set`() {
        assertThrows<IllegalArgumentException> { InstanceReceivedOffsetRepository(JdbcTemplate(), "postgres", "") }
        assertEquals("history", InstanceReceivedOffsetRepository(JdbcTemplate(), "postgres", "history").historySchema)
    }

    @Test
    fun `rejects a schema name that is unsafe in SQL`() {
        assertThrows<IllegalArgumentException> {
            InstanceReceivedOffsetRepository(JdbcTemplate(), "postgres", "history; DROP TABLE x")
        }
    }

    private fun nextOffset(): Long? =
        jdbcTemplate.queryForObject(
            "SELECT next_offset FROM $SCHEMA.instance_received_offset WHERE topic_name = ? AND partition_id = 0",
            Long::class.java,
            TOPIC,
        )
}
