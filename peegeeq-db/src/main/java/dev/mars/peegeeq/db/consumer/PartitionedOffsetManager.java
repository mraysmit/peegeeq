package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages per-(topic, group, partition) offset tracking for OFFSET_WATERMARK
 * completion tracking mode.
 *
 * <p>Provides compare-and-swap (CAS) offset commits with generation-based
 * fencing to prevent stale consumer instances from committing after a
 * rebalance.</p>
 *
 * <p>Key invariants:</p>
 * <ul>
 *   <li>Offsets only move forward (committed_offset monotonically increases)</li>
 *   <li>Commits with a stale generation are rejected (returns false)</li>
 *   <li>Pending offsets track in-flight batches and are cleared on commit</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
public class PartitionedOffsetManager {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedOffsetManager.class);

    private final PgConnectionManager connectionManager;
    private final String serviceId;

    /**
     * Creates a new PartitionedOffsetManager.
     *
     * @param connectionManager The connection manager for database operations
     * @param serviceId The service ID for connection tracking
     */
    public PartitionedOffsetManager(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
    }

    /**
     * Initializes an offset row for the given (topic, group, partition) with
     * committed_offset=0. If the row already exists, returns the existing state.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param partitionKey The partition key
     * @param generation The rebalance generation
     * @return Future containing the offset state
     */
    public Future<PartitionOffset> initializeOffset(String topic, String groupName,
                                                     String partitionKey, int generation) {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("Initializing offset: topic={}, group={}, partition={}, generation={}",
                    topic, groupName, partitionKey, generation);
        }

        String sql = """
            INSERT INTO outbox_partition_offsets (topic, group_name, partition_key, committed_offset,
                                                  committed_at, generation)
            VALUES ($1, $2, $3, 0, NOW(), $4)
            ON CONFLICT (topic, group_name, partition_key)
            DO UPDATE SET topic = outbox_partition_offsets.topic
            RETURNING topic, group_name, partition_key, committed_offset, committed_at,
                      pending_offset, pending_since, generation
            """;

        return connectionManager.withConnection(serviceId, connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, partitionKey, generation))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    PartitionOffset offset = mapRow(row);
                    try (var innerScope = TraceContextUtil.mdcScope(trace)) {
                        logger.debug("Offset initialized: topic={}, group={}, partition={}, offset={}, gen={}",
                                offset.topic(), offset.groupName(), offset.partitionKey(),
                                offset.committedOffset(), offset.generation());
                    }
                    return offset;
                })
        );
    }

    /**
     * Commits an offset forward using compare-and-swap semantics with generation fencing.
     *
     * <p>The commit succeeds only if:</p>
     * <ul>
     *   <li>The new offset is strictly greater than the current committed_offset</li>
     *   <li>The provided generation matches the current generation</li>
     * </ul>
     *
     * <p>On success, pending_offset and pending_since are cleared.</p>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param partitionKey The partition key
     * @param newOffset The offset to commit (must be > current committed_offset)
     * @param generation The generation for fencing
     * @return Future containing true if commit succeeded, false if rejected
     */
    public Future<Boolean> commitOffset(String topic, String groupName,
                                         String partitionKey, long newOffset, int generation) {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("Committing offset: topic={}, group={}, partition={}, offset={}, gen={}",
                    topic, groupName, partitionKey, newOffset, generation);
        }

        String sql = """
            UPDATE outbox_partition_offsets
            SET committed_offset = $4,
                committed_at = NOW(),
                pending_offset = NULL,
                pending_since = NULL
            WHERE topic = $1
              AND group_name = $2
              AND partition_key = $3
              AND generation = $5
              AND committed_offset < $4
            """;

        return connectionManager.withConnection(serviceId, connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, partitionKey, newOffset, generation))
                .map(rows -> {
                    boolean committed = rows.rowCount() > 0;
                    try (var innerScope = TraceContextUtil.mdcScope(trace)) {
                        if (committed) {
                            logger.debug("Offset committed: topic={}, group={}, partition={}, offset={}, gen={}",
                                    topic, groupName, partitionKey, newOffset, generation);
                        } else {
                            logger.debug("Offset commit rejected: topic={}, group={}, partition={}, offset={}, gen={}",
                                    topic, groupName, partitionKey, newOffset, generation);
                        }
                    }
                    return committed;
                })
        );
    }

    /**
     * Retrieves the current offset state for a (topic, group, partition) triple.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param partitionKey The partition key
     * @return Future containing Optional with offset state, or empty if not found
     */
    public Future<Optional<PartitionOffset>> getOffset(String topic, String groupName,
                                                        String partitionKey) {
        String sql = """
            SELECT topic, group_name, partition_key, committed_offset, committed_at,
                   pending_offset, pending_since, generation
            FROM outbox_partition_offsets
            WHERE topic = $1 AND group_name = $2 AND partition_key = $3
            """;

        return connectionManager.withConnection(serviceId, connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, partitionKey))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return Optional.empty();
                    }
                    return Optional.of(mapRow(rows.iterator().next()));
                })
        );
    }

    /**
     * Sets the pending offset for an in-flight batch with generation fencing.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param partitionKey The partition key
     * @param pendingOffset The offset of the in-flight batch
     * @param generation The generation for fencing
     * @return Future containing true if set succeeded, false if stale generation
     */
    public Future<Boolean> setPendingOffset(String topic, String groupName,
                                             String partitionKey, long pendingOffset,
                                             int generation) {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("Setting pending offset: topic={}, group={}, partition={}, pending={}, gen={}",
                    topic, groupName, partitionKey, pendingOffset, generation);
        }

        String sql = """
            UPDATE outbox_partition_offsets
            SET pending_offset = $4,
                pending_since = NOW()
            WHERE topic = $1
              AND group_name = $2
              AND partition_key = $3
              AND generation = $5
            """;

        return connectionManager.withConnection(serviceId, connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, partitionKey, pendingOffset, generation))
                .map(rows -> {
                    boolean set = rows.rowCount() > 0;
                    try (var innerScope = TraceContextUtil.mdcScope(trace)) {
                        if (set) {
                            logger.debug("Pending offset set: topic={}, group={}, partition={}, pending={}, gen={}",
                                    topic, groupName, partitionKey, pendingOffset, generation);
                        } else {
                            logger.debug("Pending offset rejected (stale gen): topic={}, group={}, partition={}, gen={}",
                                    topic, groupName, partitionKey, generation);
                        }
                    }
                    return set;
                })
        );
    }

    /**
     * Bumps the generation for a (topic, group, partition) and clears any pending offset.
     * Used during rebalance to invalidate stale consumer instances.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param partitionKey The partition key
     * @return Future containing Optional with the new generation, or empty if row not found
     */
    public Future<Optional<Integer>> bumpGeneration(String topic, String groupName, String partitionKey) {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("Bumping generation: topic={}, group={}, partition={}",
                    topic, groupName, partitionKey);
        }

        String sql = """
            UPDATE outbox_partition_offsets
            SET generation = generation + 1,
                pending_offset = NULL,
                pending_since = NULL
            WHERE topic = $1 AND group_name = $2 AND partition_key = $3
            RETURNING generation
            """;

        return connectionManager.withConnection(serviceId, connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, partitionKey))
                .map(rows -> {
                    if (rows.size() == 0) {
                        try (var innerScope = TraceContextUtil.mdcScope(trace)) {
                            logger.debug("Generation bump skipped (row not found): topic={}, group={}, partition={}",
                                    topic, groupName, partitionKey);
                        }
                        return Optional.<Integer>empty();
                    }
                    int newGen = rows.iterator().next().getInteger("generation");
                    try (var innerScope = TraceContextUtil.mdcScope(trace)) {
                        logger.debug("Generation bumped: topic={}, group={}, partition={}, newGen={}",
                                topic, groupName, partitionKey, newGen);
                    }
                    return Optional.of(newGen);
                })
        );
    }

    private PartitionOffset mapRow(Row row) {
        return new PartitionOffset(
                row.getString("topic"),
                row.getString("group_name"),
                row.getString("partition_key"),
                row.getLong("committed_offset"),
                row.getOffsetDateTime("committed_at"),
                row.getLong("pending_offset"),
                row.getOffsetDateTime("pending_since"),
                row.getInteger("generation")
        );
    }
}
