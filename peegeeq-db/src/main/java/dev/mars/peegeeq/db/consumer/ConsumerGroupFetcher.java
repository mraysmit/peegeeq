package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches messages for a specific consumer group using FOR UPDATE SKIP LOCKED
 * for concurrent consumer safety.
 * 
 * <p>This service implements the consumer group message fetching logic for the
 * Reference Counting completion tracking mode. It queries messages where the
 * consumer group has not yet processed the message (no row in outbox_consumer_groups
 * or status = PENDING).</p>
 * 
 * <p><strong>Key Behaviors:</strong></p>
 * <ul>
 *   <li>Fetches only messages where this group has PENDING status or no tracking row</li>
 *   <li>Uses FOR UPDATE SKIP LOCKED for concurrent consumer safety</li>
 *   <li>Returns messages in created_at ASC order (FIFO)</li>
 *   <li>Supports configurable batch size</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
public class ConsumerGroupFetcher {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupFetcher.class);

    private final PgConnectionManager connectionManager;
    private final String serviceId;

    /**
     * Creates a new ConsumerGroupFetcher.
     *
     * @param connectionManager The connection manager for database operations
     * @param serviceId The service ID for connection tracking
     */
    public ConsumerGroupFetcher(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = connectionManager;
        this.serviceId = serviceId;
    }

    /**
     * Fetches a batch of messages for the specified consumer group.
     * 
     * <p>This method queries the outbox table for messages that:
     * <ul>
     *   <li>Match the specified topic</li>
     *   <li>Have status = 'PENDING'</li>
     *   <li>Either have no tracking row for this group, or have status = 'PENDING' in tracking row</li>
     * </ul>
     * </p>
     * 
     * <p>The query uses FOR UPDATE SKIP LOCKED to ensure that concurrent consumers
     * within the same group don't fetch the same messages.</p>
     *
     * @param topic The topic to fetch messages from
     * @param groupName The consumer group name
     * @param batchSize Maximum number of messages to fetch
     * @return Future containing list of fetched messages
     */
    public Future<List<OutboxMessage>> fetchMessages(String topic, String groupName, int batchSize) {
        logger.debug("Fetching messages for topic='{}', group='{}', batchSize={}", topic, groupName, batchSize);

        return connectionManager.withConnection(serviceId, connection -> {
            // Query messages where this group hasn't processed them yet
            // Uses LEFT JOIN to find messages with no tracking row OR status = 'PENDING'
            String sql = """
                SELECT o.id, o.topic, o.payload, o.headers, o.correlation_id, o.message_group,
                       o.created_at, o.required_consumer_groups, o.completed_consumer_groups
                FROM outbox o
                LEFT JOIN outbox_consumer_groups cg
                    ON cg.message_id = o.id AND cg.group_name = $2
                WHERE o.topic = $1
                  AND o.status = 'PENDING'
                  AND (cg.id IS NULL OR cg.status = 'PENDING')
                ORDER BY o.created_at ASC
                LIMIT $3
                FOR UPDATE OF o SKIP LOCKED
                """;

            Tuple params = Tuple.of(topic, groupName, batchSize);

            return connection.preparedQuery(sql)
                    .execute(params)
                    .map(rows -> {
                        List<OutboxMessage> messages = new ArrayList<>();
                        for (Row row : rows) {
                            messages.add(mapRowToOutboxMessage(row));
                        }
                        logger.debug("Fetched {} messages for topic='{}', group='{}'",
                                messages.size(), topic, groupName);
                        return messages;
                    });
        });
    }

    /**
     * Maps a database row to an OutboxMessage object.
     *
     * @param row The database row
     * @return The mapped OutboxMessage
     */
    private OutboxMessage mapRowToOutboxMessage(Row row) {
        // Read OffsetDateTime from database and convert to Instant
        OffsetDateTime createdAtOdt = row.getOffsetDateTime("created_at");

        // Parse JSON fields
        JsonObject payload = row.get(JsonObject.class, row.getColumnIndex("payload"));
        JsonObject headers = row.get(JsonObject.class, row.getColumnIndex("headers"));

        return OutboxMessage.builder()
                .id(row.getLong("id"))
                .topic(row.getString("topic"))
                .payload(payload)
                .headers(headers)
                .correlationId(row.getString("correlation_id"))
                .messageGroup(row.getString("message_group"))
                .createdAt(createdAtOdt != null ? createdAtOdt.toInstant() : null)
                .requiredConsumerGroups(row.getInteger("required_consumer_groups"))
                .completedConsumerGroups(row.getInteger("completed_consumer_groups"))
                .build();
    }
}

