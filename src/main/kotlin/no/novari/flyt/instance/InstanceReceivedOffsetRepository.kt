package no.novari.flyt.instance

import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class InstanceReceivedOffsetRepository(
    private val jdbcTemplate: JdbcTemplate,
    @param:Value($$"${novari.flyt.instance-service.database.history-schema}") private val historySchema: String,
) {
    private val table: String

    init {
        require(historySchema.matches(Regex("[a-z_][a-z0-9_]{0,62}"))) { "Invalid history database schema" }
        table = "\"$historySchema\".instance_received_offset"
    }

    @Transactional(readOnly = true)
    fun findNextOffset(partition: TopicPartition): Long? =
        jdbcTemplate
            .query(
                "SELECT next_offset, cluster_id, topic_id FROM $table WHERE topic_name = ? AND partition_id = ?",
                { row, _ ->
                    if (row.getString("cluster_id") != null || row.getObject("topic_id") != null) {
                        throw InstanceReceivedOffsetException(
                            "Position has been taken over by history-service for $partition",
                        )
                    }
                    row.getLong("next_offset")
                },
                partition.topic(),
                partition.partition(),
            ).singleOrNull()

    @Transactional
    fun initialize(
        partition: TopicPartition,
        nextOffset: Long,
    ): Long {
        require(nextOffset >= 0) { "Next offset must not be negative" }
        jdbcTemplate.update(
            "INSERT INTO $table (topic_name, partition_id, next_offset) VALUES (?, ?, ?) " +
                "ON CONFLICT (topic_name, partition_id) DO NOTHING",
            partition.topic(),
            partition.partition(),
            nextOffset,
        )
        return findNextOffset(partition)
            ?: throw InstanceReceivedOffsetException("No position initialized for $partition")
    }

    @Transactional
    fun advance(
        partition: TopicPartition,
        nextOffset: Long,
    ) {
        require(nextOffset >= 0) { "Next offset must not be negative" }
        val updated =
            jdbcTemplate.update(
                """
                UPDATE $table
                SET next_offset = GREATEST(next_offset, ?), updated_at = CURRENT_TIMESTAMP
                WHERE topic_name = ? AND partition_id = ? AND cluster_id IS NULL AND topic_id IS NULL
                """.trimIndent(),
                nextOffset,
                partition.topic(),
                partition.partition(),
            )
        if (updated != 1) {
            throw InstanceReceivedOffsetException(
                "Position is missing or has been taken over by history-service for $partition",
            )
        }
    }
}

class InstanceReceivedOffsetException(
    message: String,
) : RuntimeException(message)
