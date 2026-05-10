package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages partition assignment to consumer instances within a group using
 * consistent hashing with generation-based fencing.
 *
 * <p>Key invariants:</p>
 * <ul>
 *   <li>Rebalance is serialized via FOR UPDATE on the subscription row</li>
 *   <li>Each rebalance increments the generation monotonically</li>
 *   <li>All offset rows are bumped to the new generation on rebalance</li>
 *   <li>Consistent hashing minimizes partition movement on rebalance</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
public class PartitionAssignmentService {

    private static final Logger logger = LoggerFactory.getLogger(PartitionAssignmentService.class);
    private static final int VIRTUAL_NODES = 150;

    private final PgConnectionManager connectionManager;
    private final String serviceId;

    public PartitionAssignmentService(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
    }

    /**
     * Joins a consumer instance to a group, triggering a rebalance that assigns
     * partitions using consistent hashing.
     *
     * <p>Within a single transaction:</p>
     * <ol>
     *   <li>Locks the subscription row (serialization point)</li>
     *   <li>Increments rebalance_generation</li>
     *   <li>Discovers partitions from outbox messages</li>
     *   <li>Gathers current instances + the joining instance</li>
     *   <li>Computes consistent-hash assignment</li>
     *   <li>Deletes old assignments, inserts new ones</li>
     *   <li>Bumps generation on all offset rows (fences stale consumers)</li>
     * </ol>
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param instanceId The consumer instance identifier
     * @return Future containing the list of partitions assigned to this instance
     */
    public Future<List<PartitionAssignment>> joinGroup(String topic, String groupName, String instanceId) {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("joinGroup: topic={}, group={}, instance={}", topic, groupName, instanceId);
        }

        return connectionManager.withTransaction(serviceId, conn ->
                lockAndBumpGeneration(conn, topic, groupName)
                        .compose(newGen -> discoverPartitionsInternal(conn, topic)
                                .compose(partitions -> {
                                    if (partitions.isEmpty()) {
                                        return Future.succeededFuture(List.<PartitionAssignment>of());
                                    }
                                    return getCurrentInstances(conn, topic, groupName)
                                            .compose(currentInstances -> {
                                                Set<String> allInstances = new LinkedHashSet<>(currentInstances);
                                                allInstances.add(instanceId);
                                                List<String> instanceList = new ArrayList<>(allInstances);

                                                Map<String, String> assignments = computeAssignments(partitions, instanceList);

                                                return deleteOldAssignments(conn, topic, groupName)
                                                        .compose(v -> insertAssignments(conn, topic, groupName, assignments, newGen))
                                                        .compose(v -> bumpOffsetGenerations(conn, topic, groupName, newGen))
                                                        .compose(v -> queryAssignmentsForInstance(conn, topic, groupName, instanceId));
                                            });
                                })
                        )
        );
    }

    /**
     * Removes a consumer instance from a group, triggering a rebalance that
     * redistributes its partitions to remaining instances.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param instanceId The consumer instance identifier
     * @return Future completing when rebalance is done
     */
    public Future<Void> leaveGroup(String topic, String groupName, String instanceId) {
        TraceCtx trace = TraceCtx.createNew();
        try (var scope = TraceContextUtil.mdcScope(trace)) {
            logger.debug("leaveGroup: topic={}, group={}, instance={}", topic, groupName, instanceId);
        }

        return connectionManager.withTransaction(serviceId, conn ->
                lockAndBumpGeneration(conn, topic, groupName)
                        .compose(newGen -> discoverPartitionsInternal(conn, topic)
                                .compose(partitions -> {
                                    // Get remaining instances (excluding the leaving one)
                                    return getCurrentInstances(conn, topic, groupName)
                                            .compose(currentInstances -> {
                                                List<String> remainingInstances = currentInstances.stream()
                                                        .filter(id -> !id.equals(instanceId))
                                                        .collect(Collectors.toList());

                                                return deleteOldAssignments(conn, topic, groupName)
                                                        .compose(v -> {
                                                            if (remainingInstances.isEmpty() || partitions.isEmpty()) {
                                                                // No remaining instances or no partitions just clean up
                                                                return Future.succeededFuture();
                                                            }
                                                            Map<String, String> assignments = computeAssignments(partitions, remainingInstances);
                                                            return insertAssignments(conn, topic, groupName, assignments, newGen);
                                                        })
                                                        .compose(v -> bumpOffsetGenerations(conn, topic, groupName, newGen));
                                            });
                                })
                        )
        );
    }

    /**
     * Returns the current partition assignments for a consumer instance.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param instanceId The consumer instance identifier
     * @return Future containing the list of partitions assigned to this instance
     */
    public Future<List<PartitionAssignment>> getAssignments(String topic, String groupName, String instanceId) {
        return connectionManager.withConnection(serviceId, conn ->
                queryAssignmentsForInstance(conn, topic, groupName, instanceId)
        );
    }

    /**
     * Updates the heartbeat timestamp for all partitions assigned to an instance.
     *
     * @param topic The topic name
     * @param groupName The consumer group name
     * @param instanceId The consumer instance identifier
     * @return Future completing when heartbeat is updated
     */
    public Future<Void> heartbeat(String topic, String groupName, String instanceId) {
        return connectionManager.withConnection(serviceId, conn ->
                conn.preparedQuery(
                        "UPDATE outbox_partition_assignments " +
                        "SET last_heartbeat_at = clock_timestamp() " +
                        "WHERE topic = $1 AND group_name = $2 AND assigned_instance_id = $3"
                ).execute(Tuple.of(topic, groupName, instanceId))
                 .map(rows -> (Void) null)
        );
    }

    /**
     * Discovers partition keys from outbox messages for a topic.
     *
     * @param topic The topic name
     * @return Future containing the list of discovered partition keys
     */
    public Future<List<String>> discoverPartitions(String topic) {
        return connectionManager.withConnection(serviceId, conn -> discoverPartitionsInternal(conn, topic));
    }

    // ========================================================================
    // Internal transaction operations
    // ========================================================================

    private Future<Integer> lockAndBumpGeneration(SqlConnection conn, String topic, String groupName) {
        String sql = """
            UPDATE outbox_topic_subscriptions
            SET rebalance_generation = rebalance_generation + 1
            WHERE topic = $1 AND group_name = $2
            RETURNING rebalance_generation
            """;
        return conn.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> {
                    if (rows.size() == 0) {
                        throw new IllegalStateException(
                                "No subscription found for topic=" + topic + " group=" + groupName);
                    }
                    return rows.iterator().next().getInteger("rebalance_generation");
                });
    }

    private Future<List<String>> discoverPartitionsInternal(SqlConnection conn, String topic) {
        String sql = """
            SELECT DISTINCT COALESCE(message_group, '__default__') AS partition_key
            FROM outbox
            WHERE topic = $1
              AND status IN ('PENDING', 'PROCESSING')
            ORDER BY partition_key
            """;
        return conn.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(rows -> {
                    List<String> partitions = new ArrayList<>();
                    for (Row row : rows) {
                        partitions.add(row.getString("partition_key"));
                    }
                    return partitions;
                });
    }

    private Future<List<String>> getCurrentInstances(SqlConnection conn, String topic, String groupName) {
        String sql = """
            SELECT DISTINCT assigned_instance_id
            FROM outbox_partition_assignments
            WHERE topic = $1 AND group_name = $2
            ORDER BY assigned_instance_id
            """;
        return conn.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> {
                    List<String> instances = new ArrayList<>();
                    for (Row row : rows) {
                        instances.add(row.getString("assigned_instance_id"));
                    }
                    return instances;
                });
    }

    private Future<Void> deleteOldAssignments(SqlConnection conn, String topic, String groupName) {
        String sql = "DELETE FROM outbox_partition_assignments WHERE topic = $1 AND group_name = $2";
        return conn.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> (Void) null);
    }

    private Future<Void> insertAssignments(SqlConnection conn, String topic, String groupName,
                                            Map<String, String> assignments, int generation) {
        String sql = """
            INSERT INTO outbox_partition_assignments
                (topic, group_name, partition_key, assigned_instance_id, assigned_at, last_heartbeat_at, generation)
            VALUES ($1, $2, $3, $4, NOW(), NOW(), $5)
            """;

        Future<Void> chain = Future.succeededFuture();
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            String partitionKey = entry.getKey();
            String instanceId = entry.getValue();
            chain = chain.compose(v ->
                    conn.preparedQuery(sql)
                            .execute(Tuple.of(topic, groupName, partitionKey, instanceId, generation))
                            .map(rows -> (Void) null)
            );
        }
        return chain;
    }

    private Future<Void> bumpOffsetGenerations(SqlConnection conn, String topic, String groupName, int newGeneration) {
        String sql = """
            UPDATE outbox_partition_offsets
            SET generation = $3,
                pending_offset = NULL,
                pending_since = NULL
            WHERE topic = $1 AND group_name = $2
            """;
        return conn.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, newGeneration))
                .map(rows -> (Void) null);
    }

    private Future<List<PartitionAssignment>> queryAssignmentsForInstance(SqlConnection conn,
                                                                          String topic, String groupName,
                                                                          String instanceId) {
        String sql = """
            SELECT topic, group_name, partition_key, assigned_instance_id,
                   assigned_at, last_heartbeat_at, generation
            FROM outbox_partition_assignments
            WHERE topic = $1 AND group_name = $2 AND assigned_instance_id = $3
            ORDER BY partition_key
            """;
        return conn.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName, instanceId))
                .map(rows -> {
                    List<PartitionAssignment> assignments = new ArrayList<>();
                    for (Row row : rows) {
                        assignments.add(mapRow(row));
                    }
                    return assignments;
                });
    }

    // ========================================================================
    // Consistent hash ring assignment
    // ========================================================================

    /**
     * Assigns partitions to instances using a consistent hash ring with virtual nodes.
     * This minimizes partition movement when instances are added/removed.
     */
    Map<String, String> computeAssignments(List<String> partitions, List<String> instances) {
        if (instances.isEmpty()) {
            return Map.of();
        }
        if (instances.size() == 1) {
            String instance = instances.get(0);
            return partitions.stream().collect(Collectors.toMap(p -> p, p -> instance));
        }

        TreeMap<Long, String> ring = new TreeMap<>();
        for (String instance : instances) {
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                long hash = consistentHash(instance + "#" + i);
                ring.put(hash, instance);
            }
        }

        Map<String, String> assignments = new LinkedHashMap<>();
        for (String partition : partitions) {
            long hash = consistentHash(partition);
            Map.Entry<Long, String> entry = ring.ceilingEntry(hash);
            if (entry == null) {
                entry = ring.firstEntry();
            }
            assignments.put(partition, entry.getValue());
        }
        return assignments;
    }

    /**
     * Murmur3-style 64-bit hash for deterministic consistent hashing.
     * Uses proper avalanche mixing to ensure similar keys (e.g. "part-1", "part-2")
     * distribute evenly across the hash ring.
     */
    static long consistentHash(String key) {
        long h = 0;
        for (int i = 0; i < key.length(); i++) {
            h = h * 31 + key.charAt(i);
        }
        // MurmurHash3 64-bit finalization mix
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    private PartitionAssignment mapRow(Row row) {
        return new PartitionAssignment(
                row.getString("topic"),
                row.getString("group_name"),
                row.getString("partition_key"),
                row.getString("assigned_instance_id"),
                row.getOffsetDateTime("assigned_at"),
                row.getOffsetDateTime("last_heartbeat_at"),
                row.getInteger("generation")
        );
    }
}
