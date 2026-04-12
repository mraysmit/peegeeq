package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fetches messages per-partition in offset order for OFFSET_WATERMARK
 * completion tracking mode.
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Fetches messages with id > committed_offset for a specific partition</li>
 *   <li>Respects batch size limits</li>
 *   <li>Uses FOR UPDATE SKIP LOCKED to prevent double delivery</li>
 *   <li>Sets pending_offset after fetch for crash recovery tracking</li>
 *   <li>Rejects stale-generation fetches (generation fencing)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
public class PartitionedFetcher {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedFetcher.class);

    private final PgConnectionManager connectionManager;
    private final String serviceId;

    public PartitionedFetcher(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
    }

    /**
     * Fetches a batch of messages for a specific partition, starting after the
     * committed offset. Uses FOR UPDATE SKIP LOCKED for concurrent safety.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param partitionKey The partition key (message_group or '__default__')
     * @param batchSize Maximum number of messages to return
     * @param generation The current rebalance generation for fencing
     * @return Future containing the fetched messages in id order
     */
    public Future<List<OutboxMessage>> fetch(String topic, String groupName,
                                              String partitionKey, int batchSize,
                                              int generation) {
        return connectionManager.withTransaction(serviceId, conn ->
                getCommittedOffset(conn, topic, groupName, partitionKey, generation)
                        .compose(committedOffset -> {
                            if (committedOffset == null) {
                                // Generation mismatch or no offset row — fenced
                                logger.debug("Fetch fenced: topic={}, group={}, partition={}, gen={}",
                                        topic, groupName, partitionKey, generation);
                                return Future.succeededFuture(List.<OutboxMessage>of());
                            }
                            return fetchMessages(conn, topic, partitionKey, committedOffset, batchSize)
                                    .compose(messages -> {
                                        if (messages.isEmpty()) {
                                            return Future.succeededFuture(messages);
                                        }
                                        long lastId = messages.get(messages.size() - 1).getId();
                                        return setPendingOffset(conn, topic, groupName, partitionKey, lastId, generation)
                                                .map(v -> messages);
                                    });
                        })
        );
    }

    /**
     * Reads the committed_offset for a partition, verifying generation match.
     * Returns null if the generation doesn't match (fenced).
     */
    private Future<Long> getCommittedOffset(SqlConnection conn, String topic, String groupName,
                                             String partitionKey, int generation) {
        String sql = """
            SELECT committed_offset FROM outbox_partition_offsets
            WHERE topic = $1 AND group_name = $2 AND partition_key = $3 AND generation = $4
            """;
        return conn.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, partitionKey, generation))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return null;
                    }
                    return rows.iterator().next().getLong("committed_offset");
                });
    }

    /**
     * Fetches messages from the outbox for a specific partition, starting after
     * the committed offset. Uses FOR UPDATE SKIP LOCKED for concurrent safety.
     */
    private Future<List<OutboxMessage>> fetchMessages(SqlConnection conn, String topic,
                                                       String partitionKey, long committedOffset,
                                                       int batchSize) {
        boolean isDefault = "__default__".equals(partitionKey);

        String sql;
        Tuple params;
        if (isDefault) {
            sql = """
                SELECT o.id, o.topic, o.payload, o.headers, o.correlation_id,
                       o.message_group, o.created_at, o.required_consumer_groups,
                       o.completed_consumer_groups
                FROM outbox o
                WHERE o.topic = $1
                  AND o.id > $2
                  AND o.status IN ('PENDING', 'PROCESSING')
                  AND o.message_group IS NULL
                ORDER BY o.id ASC
                LIMIT $3
                FOR UPDATE OF o SKIP LOCKED
                """;
            params = Tuple.of(topic, committedOffset, batchSize);
        } else {
            sql = """
                SELECT o.id, o.topic, o.payload, o.headers, o.correlation_id,
                       o.message_group, o.created_at, o.required_consumer_groups,
                       o.completed_consumer_groups
                FROM outbox o
                WHERE o.topic = $1
                  AND o.id > $2
                  AND o.status IN ('PENDING', 'PROCESSING')
                  AND o.message_group = $3
                ORDER BY o.id ASC
                LIMIT $4
                FOR UPDATE OF o SKIP LOCKED
                """;
            params = Tuple.of(topic, committedOffset, partitionKey, batchSize);
        }

        return conn.preparedQuery(sql)
                .execute(params)
                .map(rows -> {
                    List<OutboxMessage> messages = new ArrayList<>();
                    for (Row row : rows) {
                        messages.add(mapRow(row));
                    }
                    return messages;
                });
    }

    /**
     * Sets the pending_offset for a partition after a successful fetch.
     */
    private Future<Void> setPendingOffset(SqlConnection conn, String topic, String groupName,
                                           String partitionKey, long lastFetchedId, int generation) {
        String sql = """
            UPDATE outbox_partition_offsets
            SET pending_offset = $4, pending_since = $5
            WHERE topic = $1 AND group_name = $2 AND partition_key = $3 AND generation = $6
            """;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return conn.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, partitionKey, lastFetchedId, now, generation))
                .map(rows -> (Void) null);
    }

    private OutboxMessage mapRow(Row row) {
        return OutboxMessage.builder()
                .id(row.getLong("id"))
                .topic(row.getString("topic"))
                .payload(row.get(JsonObject.class, row.getColumnIndex("payload")))
                .headers(row.get(JsonObject.class, row.getColumnIndex("headers")))
                .correlationId(row.getString("correlation_id"))
                .messageGroup(row.getString("message_group"))
                .createdAt(row.getOffsetDateTime("created_at") != null
                        ? row.getOffsetDateTime("created_at").toInstant() : null)
                .requiredConsumerGroups(row.getInteger("required_consumer_groups"))
                .completedConsumerGroups(row.getInteger("completed_consumer_groups"))
                .build();
    }
}
