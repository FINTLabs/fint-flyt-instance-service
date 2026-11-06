package no.novari.flyt.instance

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Temporary: records progress on instance-received in history-service's table, so history-service
 * can continue where this service stopped. Remove when history-service has taken over.
 */
@Repository
class InstanceReceivedOffsetRepository(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${spring.datasource.username}") databaseUsername: String,
    @Value("\${novari.flyt.history.database.schema:}") configuredHistorySchema: String,
) {
    val historySchema: String =
        configuredHistorySchema.ifEmpty {
            require(databaseUsername.endsWith(OWN_SCHEMA)) {
                "Cannot derive the history schema: database user '$databaseUsername' does not end with " +
                    "'$OWN_SCHEMA'. Set novari.flyt.history.database.schema explicitly."
            }
            databaseUsername.removeSuffix(OWN_SCHEMA) + HISTORY_SCHEMA
        }

    init {
        require(historySchema.matches(SCHEMA_NAME)) { "Invalid history schema name: '$historySchema'" }
    }

    // Qualified table name: search_path is session state and is not reliable behind PgBouncer.
    // GREATEST keeps the position monotonic, so a failed or out-of-order write never moves it backwards.
    private val upsertSql =
        """
        INSERT INTO $historySchema.instance_received_offset AS o (topic_name, partition_id, next_offset)
        VALUES (?, ?, ?)
        ON CONFLICT (topic_name, partition_id) DO UPDATE
        SET next_offset = GREATEST(o.next_offset, EXCLUDED.next_offset), updated_at = CURRENT_TIMESTAMP
        WHERE o.cluster_id IS NULL
        """.trimIndent()

    fun markProcessed(
        topic: String,
        partition: Int,
        offset: Long,
    ) {
        jdbcTemplate.update(upsertSql, topic, partition, offset + 1)
    }

    private companion object {
        const val OWN_SCHEMA = "fint_flyt_instance_service_db"
        const val HISTORY_SCHEMA = "fint_flyt_history_service_db"
        val SCHEMA_NAME = Regex("[a-z_][a-z0-9_]*")
    }
}
