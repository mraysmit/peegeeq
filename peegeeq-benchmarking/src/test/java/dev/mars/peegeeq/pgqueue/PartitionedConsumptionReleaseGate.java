package dev.mars.peegeeq.pgqueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.consumer.PartitionAssignmentService;
import dev.mars.peegeeq.db.consumer.PartitionedConsumerEngine;
import dev.mars.peegeeq.db.consumer.WatermarkCalculator;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.STANDARD;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit Task 6 release gate for sustained OFFSET_WATERMARK consumption.
 *
 * <p>The class deliberately does not use a Surefire default test suffix. It runs only when an
 * owner invokes it explicitly with {@code -Dtest=PartitionedConsumptionReleaseGate}; the normal
 * core, integration, performance, and all-tests profiles therefore do not gain a one-hour test.
 * The default workload is one hour. Shorter values are for harness qualification only and must
 * not be recorded as long-duration release evidence.</p>
 *
 * <p>The workload exercises two tenant schemas concurrently. Each tenant has two independent
 * consumer groups, a deliberately small connection pool, sustained transactional publishing,
 * concurrent OLTP probes, a new partition and rebalance during load, offset/watermark cleanup,
 * and assignment cleanup. Every database boundary uses a real PostgreSQL Testcontainer.</p>
 */
@Tag(TestCategories.PERFORMANCE)
@Testcontainers
@ExtendWith(VertxExtension.class)
@Isolated("Task 6 release gate requires exclusive database and host resources")
class PartitionedConsumptionReleaseGate {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedConsumptionReleaseGate.class);

    private static final String SCHEMA_A = "task6_tenant_a";
    private static final String SCHEMA_B = "task6_tenant_b";
    private static final String SERVICE_A = "task6-tenant-a";
    private static final String SERVICE_B = "task6-tenant-b";

    private static final long DURATION_SECONDS = positiveLongProperty(
            "peegeeq.task6.duration.seconds", 3_600L);
    private static final int TOTAL_MESSAGE_RATE = positiveIntProperty(
            "peegeeq.task6.message.rate", 200);
    private static final int BATCH_SIZE = positiveIntProperty(
            "peegeeq.task6.batch.size", 50);
    private static final int INITIAL_PARTITIONS = positiveIntProperty(
            "peegeeq.task6.partition.count", 16);
    private static final int GROUPS_PER_TENANT = positiveIntProperty(
            "peegeeq.task6.groups.per.tenant", 2);
    private static final int PAYLOAD_BYTES = positiveIntProperty(
            "peegeeq.task6.payload.bytes", 512);
    private static final int POOL_SIZE = positiveIntProperty(
            "peegeeq.task6.pool.size", 4);
    private static final long DRAIN_TIMEOUT_SECONDS = positiveLongProperty(
            "peegeeq.task6.drain.timeout.seconds", 600L);

    @Container
    static final PostgreSQLContainer postgres = PeeGeeQTestContainerFactory.createContainer(STANDARD);

    private PgConnectionManager connectionManager;
    private Workload tenantA;
    private Workload tenantB;

    @Test
    @Timeout(value = 90, timeUnit = TimeUnit.MINUTES)
    @org.junit.jupiter.api.Timeout(value = 90, unit = TimeUnit.MINUTES)
    void sustainedFanoutPartitionStabilityAcrossTenants(Vertx vertx, VertxTestContext testContext) {
        validateConfiguration();
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_A, ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA_B, ALL);

        connectionManager = new PgConnectionManager(vertx, null);
        configurePool(SERVICE_A, SCHEMA_A);
        configurePool(SERVICE_B, SCHEMA_B);

        String topic = "task6-release-" + System.nanoTime();
        tenantA = new Workload("tenant-a", SERVICE_A, topic, TOTAL_MESSAGE_RATE / 2, INITIAL_PARTITIONS);
        tenantB = new Workload(
                "tenant-b",
                SERVICE_B,
                topic,
                TOTAL_MESSAGE_RATE - tenantA.messageRate,
                INITIAL_PARTITIONS);

        emitTelemetry("TASK6_GATE_START durationSeconds=%d totalRate=%d batchSize=%d initialPartitions=%d "
                        + "groupsPerTenant=%d payloadBytes=%d poolSize=%d postgresImage=%s",
                DURATION_SECONDS, TOTAL_MESSAGE_RATE, BATCH_SIZE, INITIAL_PARTITIONS,
                GROUPS_PER_TENANT, PAYLOAD_BYTES, POOL_SIZE, postgres.getDockerImageName());

        Future.all(prepareWorkload(tenantA, vertx), prepareWorkload(tenantB, vertx))
                .compose(prepared -> Future.all(startEngines(tenantA, vertx), startEngines(tenantB, vertx)))
                .compose(started -> Future.all(
                        awaitDelivered(tenantA, tenantA.published.getAcquire(), 60_000L, vertx),
                        awaitDelivered(tenantB, tenantB.published.getAcquire(), 60_000L, vertx)))
                .compose(seedDelivered -> Future.all(captureWalStart(tenantA), captureWalStart(tenantB)))
                .compose(walCaptured -> runSustainedWindow(vertx))
                .compose(windowComplete -> Future.all(
                        awaitDelivered(tenantA, tenantA.published.getAcquire(),
                                TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS), vertx),
                        awaitDelivered(tenantB, tenantB.published.getAcquire(),
                                TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS), vertx)))
                .compose(drained -> Future.all(sweepAndSnapshot(tenantA), sweepAndSnapshot(tenantB)))
                .map(snapshots -> {
                    DatabaseSnapshot snapshotA = (DatabaseSnapshot) snapshots.resultAt(0);
                    DatabaseSnapshot snapshotB = (DatabaseSnapshot) snapshots.resultAt(1);
                    testContext.verify(() -> {
                        assertWorkload(tenantA, snapshotA);
                        assertWorkload(tenantB, snapshotB);
                    });
                    TenantStatistics statisticsA = captureStatistics(tenantA, snapshotA);
                    TenantStatistics statisticsB = captureStatistics(tenantB, snapshotB);
                    logSummary(statisticsA);
                    logSummary(statisticsB);
                    return new PerformanceSnapshots(statisticsA, statisticsB);
                })
                .compose(snapshots -> stopAllEngines().map(snapshots))
                .compose(snapshots -> Future.all(countAssignments(tenantA), countAssignments(tenantB))
                        .map(assignmentCounts -> new ReleaseResults(
                                snapshots.tenantA,
                                snapshots.tenantB,
                                (int) assignmentCounts.resultAt(0),
                                (int) assignmentCounts.resultAt(1))))
                .compose(results -> {
                    testContext.verify(() -> {
                        assertEquals(0, results.tenantAAssignments,
                                "Tenant A assignments must be removed after engine stop");
                        assertEquals(0, results.tenantBAssignments,
                                "Tenant B assignments must be removed after engine stop");
                    });
                    return writePerformanceArtifacts(vertx, results);
                })
                .onSuccess(ignored -> testContext.verify(() -> {
                    emitTelemetry("TASK6_GATE_PASS durationSeconds=%d tenantAPublished=%d tenantBPublished=%d",
                            DURATION_SECONDS, tenantA.published.getAcquire(), tenantB.published.getAcquire());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        stopAllEngines()
                .compose(stopped -> cleanupWorkloads())
                .eventually(() -> connectionManager == null
                        ? Future.succeededFuture()
                        : connectionManager.close())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(failure -> {
                    logger.error("Task 6 release-gate cleanup failed", failure);
                    testContext.failNow(failure);
                });
    }

    private void configurePool(String serviceId, String schema) {
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(schema)
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(POOL_SIZE)
                .shared(false)
                .connectionTimeout(Duration.ofSeconds(30))
                .idleTimeout(Duration.ofSeconds(30))
                .build();
        connectionManager.getOrCreateReactivePool(serviceId, connectionConfig, poolConfig);
    }

    private Future<Void> prepareWorkload(Workload workload, Vertx vertx) {
        return createTopic(workload)
                .compose(created -> createSubscriptions(workload))
                .compose(subscribed -> insertSeedMessages(workload))
                .map(seedIds -> {
                    workload.published.addAndGet(seedIds);
                    workload.vertx = vertx;
                    return null;
                });
    }

    private Future<Void> createTopic(Workload workload) {
        return connectionManager.withTransaction(workload.serviceId, connection ->
                connection.preparedQuery("""
                        INSERT INTO outbox_topics
                            (topic, semantics, completion_tracking_mode, message_retention_hours)
                        VALUES ($1, 'PUB_SUB', 'OFFSET_WATERMARK', 1)
                        """)
                        .execute(Tuple.of(workload.topic))
                        .mapEmpty());
    }

    private Future<Void> createSubscriptions(Workload workload) {
        Future<Void> chain = Future.succeededFuture();
        for (int groupIndex = 0; groupIndex < GROUPS_PER_TENANT; groupIndex++) {
            String groupName = groupName(groupIndex);
            chain = chain.compose(ignored -> connectionManager.withTransaction(
                    workload.serviceId,
                    connection -> connection.preparedQuery("""
                            INSERT INTO outbox_topic_subscriptions
                                (topic, group_name, subscription_status)
                            VALUES ($1, $2, 'ACTIVE')
                            """)
                            .execute(Tuple.of(workload.topic, groupName))
                            .mapEmpty()));
        }
        return chain;
    }

    private Future<Integer> insertSeedMessages(Workload workload) {
        List<Tuple> rows = new ArrayList<>();
        long sentAt = System.currentTimeMillis();
        for (int partition = 0; partition < workload.partitionCount.getAcquire(); partition++) {
            long sequence = workload.sequence.incrementAndGet();
            rows.add(messageTuple(workload, partitionKey(partition), sequence, sentAt));
        }
        return insertRows(workload, rows).map(ignored -> rows.size());
    }

    private Future<Void> startEngines(Workload workload, Vertx vertx) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Future<Void> chain = Future.succeededFuture();
        for (int groupIndex = 0; groupIndex < GROUPS_PER_TENANT; groupIndex++) {
            int index = groupIndex;
            String groupName = groupName(index);
            String instanceId = workload.name + "-instance-" + index;
            PartitionedConsumerEngine<String> engine = new PartitionedConsumerEngine<>(
                    vertx,
                    connectionManager,
                    workload.serviceId,
                    workload.topic,
                    groupName,
                    instanceId,
                    String.class,
                    mapper);
            workload.engines.add(engine);
            workload.instanceIds.add(instanceId);
            chain = chain.compose(ignored -> engine.start(message -> handleMessage(workload, index, message)));
        }
        return chain;
    }

    private Future<Void> handleMessage(Workload workload, int groupIndex, Message<String> message) {
        DeliveryState state = workload.deliveryStates.get(groupIndex);
        String[] fields = message.getPayload().split("\\|", 5);
        if (fields.length < 4) {
            return Future.failedFuture(new AssertionError("Invalid Task 6 payload: " + message.getPayload()));
        }
        if (!workload.name.equals(fields[0])) {
            state.crossTenantDeliveries.incrementAndGet();
        }

        String partitionKey = fields[1];
        long sentAt = Long.parseLong(fields[3]);
        long messageId = Long.parseLong(message.getId());
        state.lastIdByPartition.compute(partitionKey, (ignored, previous) -> {
            if (previous != null && messageId <= previous) {
                state.orderViolations.incrementAndGet();
            }
            return messageId;
        });
        state.latencies.record(Math.max(0L, System.currentTimeMillis() - sentAt));
        state.delivered.incrementAndGet();
        return Future.succeededFuture();
    }

    private Future<Void> captureWalStart(Workload workload) {
        return connectionManager.withConnection(workload.serviceId, connection ->
                connection.query("SELECT pg_current_wal_lsn()::text AS wal_lsn")
                        .execute()
                        .map(rows -> {
                            workload.walStart = rows.iterator().next().getString("wal_lsn");
                            return (Void) null;
                        }));
    }

    private Future<Void> runSustainedWindow(Vertx vertx) {
        startWindowClock(tenantA);
        startWindowClock(tenantB);
        long monitorId = vertx.setPeriodic(60_000L, ignored -> {
            logProgress(tenantA);
            logProgress(tenantB);
        });
        return Future.all(
                        publishUntilDeadline(tenantA),
                        publishUntilDeadline(tenantB),
                        probeOltpUntilDeadline(tenantA),
                        probeOltpUntilDeadline(tenantB))
                .eventually(() -> {
                    vertx.cancelTimer(monitorId);
                    return Future.succeededFuture();
                })
                .mapEmpty();
    }

    private void startWindowClock(Workload workload) {
        workload.testStartedAt = OffsetDateTime.now(ZoneOffset.UTC);
        workload.publisherStartedNanos = System.nanoTime();
        workload.publishedAtWindowStart = workload.published.getAcquire();
        workload.deadlineNanos = workload.publisherStartedNanos
                + TimeUnit.SECONDS.toNanos(DURATION_SECONDS);
        workload.expansionNanos = workload.publisherStartedNanos
                + TimeUnit.SECONDS.toNanos(Math.max(1L, DURATION_SECONDS / 2L));
    }

    private Future<Void> publishUntilDeadline(Workload workload) {
        Promise<Void> completion = Promise.promise();
        continuePublishing(workload, completion);
        return completion.future();
    }

    private void continuePublishing(Workload workload, Promise<Void> completion) {
        if (completion.future().isComplete()) {
            return;
        }
        long now = System.nanoTime();
        if (now >= workload.deadlineNanos) {
            completion.tryComplete();
            return;
        }
        if (now >= workload.expansionNanos && workload.expansionTriggered.compareAndSet(false, true)) {
            expandPartitionsAndRebalance(workload)
                    .onSuccess(ignored -> workload.vertx.runOnContext(
                            event -> continuePublishing(workload, completion)))
                    .onFailure(completion::tryFail);
            return;
        }

        List<Tuple> rows = new ArrayList<>(BATCH_SIZE);
        long sentAt = System.currentTimeMillis();
        int partitions = workload.partitionCount.getAcquire();
        for (int index = 0; index < BATCH_SIZE; index++) {
            long sequence = workload.sequence.incrementAndGet();
            int partition = (int) ((sequence - 1L) % partitions);
            rows.add(messageTuple(workload, partitionKey(partition), sequence, sentAt));
        }

        insertRows(workload, rows)
                .onSuccess(inserted -> {
                    workload.published.addAndGet(rows.size());
                    long windowPublished = workload.published.getAcquire() - workload.publishedAtWindowStart;
                    long targetElapsedNanos = (long) ((windowPublished
                            * 1_000_000_000.0) / workload.messageRate);
                    long actualElapsedNanos = System.nanoTime() - workload.publisherStartedNanos;
                    long delayNanos = Math.max(0L, targetElapsedNanos - actualElapsedNanos);
                    if (delayNanos == 0L) {
                        workload.vertx.runOnContext(event -> continuePublishing(workload, completion));
                        return;
                    }
                    long delayMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(delayNanos));
                    workload.vertx.setTimer(delayMillis,
                            ignored -> continuePublishing(workload, completion));
                })
                .onFailure(completion::tryFail);
    }

    private Future<Void> expandPartitionsAndRebalance(Workload workload) {
        int newPartition = workload.partitionCount.getAcquire();
        long sequence = workload.sequence.incrementAndGet();
        List<Tuple> expansionRows = new ArrayList<>(newPartition + 1);
        long sentAt = System.currentTimeMillis();
        for (int partition = 0; partition <= newPartition; partition++) {
            long rowSequence = partition == newPartition
                    ? sequence
                    : workload.sequence.incrementAndGet();
            expansionRows.add(messageTuple(workload, partitionKey(partition), rowSequence, sentAt));
        }

        return insertRows(workload, expansionRows)
                .compose(inserted -> {
                    workload.published.addAndGet(expansionRows.size());
                    workload.partitionCount.incrementAndGet();
                    PartitionAssignmentService assignmentService =
                            new PartitionAssignmentService(connectionManager, workload.serviceId);
                    Future<Void> rebalances = Future.succeededFuture();
                    for (int groupIndex = 0; groupIndex < GROUPS_PER_TENANT; groupIndex++) {
                        String group = groupName(groupIndex);
                        String instance = workload.instanceIds.get(groupIndex);
                        rebalances = rebalances.compose(ignored ->
                                assignmentService.joinGroup(workload.topic, group, instance).mapEmpty());
                    }
                    return rebalances;
                })
                .onSuccess(ignored -> emitTelemetry(
                        "TASK6_PARTITION_EXPANDED tenant=%s partitionCount=%d published=%d",
                        workload.name, workload.partitionCount.getAcquire(), workload.published.getAcquire()));
    }

    private Tuple messageTuple(
            Workload workload, String partitionKey, long sequence, long sentAtEpochMillis) {
        String fixed = workload.name + "|" + partitionKey + "|" + sequence + "|" + sentAtEpochMillis + "|";
        String payload = fixed + "x".repeat(Math.max(0, PAYLOAD_BYTES - fixed.length()));
        return Tuple.of(
                workload.topic,
                new JsonObject().put("value", payload),
                partitionKey,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private Future<Void> insertRows(Workload workload, List<Tuple> rows) {
        return connectionManager.withTransaction(workload.serviceId, connection ->
                connection.preparedQuery("""
                        INSERT INTO outbox (topic, payload, status, message_group, created_at)
                        VALUES ($1, $2, 'PENDING', $3, $4)
                        """)
                        .executeBatch(rows)
                        .mapEmpty());
    }

    private Future<Void> probeOltpUntilDeadline(Workload workload) {
        Promise<Void> completion = Promise.promise();
        continueProbingOltp(workload, completion);
        return completion.future();
    }

    private void continueProbingOltp(Workload workload, Promise<Void> completion) {
        if (completion.future().isComplete()) {
            return;
        }
        if (System.nanoTime() >= workload.deadlineNanos) {
            completion.tryComplete();
            return;
        }
        long started = System.nanoTime();
        connectionManager.withConnection(workload.serviceId, connection ->
                        connection.query("SELECT 1 AS healthy").execute())
                .compose(rows -> {
                    Row row = rows.iterator().next();
                    if (row.getInteger("healthy") != 1) {
                        return Future.failedFuture(new AssertionError("OLTP health query returned an invalid result"));
                    }
                    workload.oltpLatencies.record(TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - started));
                    workload.oltpProbeCount.incrementAndGet();
                    return Future.<Void>succeededFuture();
                })
                .onSuccess(ignored -> workload.vertx.setTimer(100L,
                        timerId -> continueProbingOltp(workload, completion)))
                .onFailure(completion::tryFail);
    }

    private Future<Void> awaitDelivered(Workload workload, long expected, long timeoutMillis, Vertx vertx) {
        Promise<Void> completion = Promise.promise();
        pollDelivered(workload, expected, System.currentTimeMillis() + timeoutMillis, vertx, completion);
        return completion.future();
    }

    private void pollDelivered(
            Workload workload,
            long expected,
            long deadline,
            Vertx vertx,
            Promise<Void> completion) {
        if (completion.future().isComplete()) {
            return;
        }
        boolean complete = workload.deliveryStates.stream()
                .allMatch(state -> state.delivered.getAcquire() >= expected);
        if (complete) {
            completion.tryComplete();
            return;
        }
        if (System.currentTimeMillis() >= deadline) {
            completion.tryFail(new AssertionError(
                    "Timed out draining " + workload.name + ": expected=" + expected
                            + ", delivered=" + deliveredCounts(workload)));
            return;
        }
        vertx.setTimer(250L,
                ignored -> pollDelivered(workload, expected, deadline, vertx, completion));
    }

    private Future<DatabaseSnapshot> sweepAndSnapshot(Workload workload) {
        WatermarkCalculator calculator = new WatermarkCalculator(connectionManager, workload.serviceId);
        return calculator.calculateAndSweep(workload.topic)
                .compose(swept -> connectionManager.withConnection(workload.serviceId, connection ->
                        connection.preparedQuery("""
                                SELECT
                                    COUNT(*) FILTER (WHERE status = 'PENDING')::bigint AS pending_count,
                                    COUNT(*) FILTER (WHERE status = 'COMPLETED')::bigint AS completed_count,
                                    COALESCE((
                                        SELECT watermark_id
                                        FROM outbox_topic_watermarks
                                        WHERE topic = $1
                                    ), 0)::bigint AS watermark_id,
                                    COUNT(*) FILTER (
                                        WHERE status = 'PENDING'
                                          AND id <= COALESCE((
                                              SELECT watermark_id
                                              FROM outbox_topic_watermarks
                                              WHERE topic = $1
                                          ), 0)
                                    )::bigint AS pending_at_or_below_watermark
                                FROM outbox
                                WHERE topic = $1
                                """)
                                .execute(Tuple.of(workload.topic))
                                .map(rows -> {
                                    Row row = rows.iterator().next();
                                    return new long[]{
                                            row.getLong("pending_count"),
                                            row.getLong("completed_count"),
                                            swept,
                                            row.getLong("watermark_id"),
                                            row.getLong("pending_at_or_below_watermark")
                                    };
                                })))
                .compose(messageCounts -> connectionManager.withConnection(workload.serviceId, connection ->
                        connection.preparedQuery("""
                                SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), $1::pg_lsn)::bigint AS wal_bytes
                                """)
                                .execute(Tuple.of(workload.walStart))
                                .map(rows -> new long[]{
                                        messageCounts[0],
                                        messageCounts[1],
                                        messageCounts[2],
                                        messageCounts[3],
                                        messageCounts[4],
                                        rows.iterator().next().getLong("wal_bytes")
                                })))
                .compose(countsAndWal -> connectionManager.withConnection(workload.serviceId, connection ->
                        connection.query("""
                                SELECT COALESCE(n_live_tup, 0)::bigint AS live_tuples,
                                       COALESCE(n_dead_tup, 0)::bigint AS dead_tuples
                                FROM pg_stat_user_tables
                                WHERE schemaname = current_schema() AND relname = 'outbox'
                                """)
                                .execute()
                                .map(rows -> {
                                    Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
                                    long live = row == null ? 0L : row.getLong("live_tuples");
                                    long dead = row == null ? 0L : row.getLong("dead_tuples");
                                    return new DatabaseSnapshot(
                                            countsAndWal[0], countsAndWal[1], countsAndWal[2],
                                            countsAndWal[3], countsAndWal[4], countsAndWal[5], live, dead);
                                })));
    }

    private void assertWorkload(Workload workload, DatabaseSnapshot snapshot) {
        long expected = workload.published.getAcquire();
        for (int groupIndex = 0; groupIndex < workload.deliveryStates.size(); groupIndex++) {
            DeliveryState state = workload.deliveryStates.get(groupIndex);
            assertEquals(expected, state.delivered.getAcquire(),
                    workload.name + " group " + groupIndex + " must receive every published message");
            assertEquals(0L, state.orderViolations.getAcquire(),
                    workload.name + " group " + groupIndex + " must preserve per-partition order");
            assertEquals(0L, state.crossTenantDeliveries.getAcquire(),
                    workload.name + " group " + groupIndex + " must not receive another tenant's payload");
        }
        assertEquals(expected, snapshot.completedCount + snapshot.pendingCount,
                workload.name + " must account for every published message after the final sweep");
        assertEquals(0L, snapshot.pendingAtOrBelowWatermark,
                workload.name + " must not retain pending messages at or below the safe watermark");
        assertTrue(snapshot.pendingCount <= workload.partitionCount.getAcquire() - 1L,
                workload.name + " may retain only the bounded cross-partition tail above the watermark");
        assertTrue(workload.oltpProbeCount.getAcquire() >= Math.max(1L, DURATION_SECONDS / 2L),
                workload.name + " must sustain OLTP probes throughout the load window");
        assertTrue(workload.oltpLatencies.percentile(0.95) < 5_000L,
                workload.name + " OLTP p95 must remain below the 5-second connection timeout envelope");
        assertEquals(INITIAL_PARTITIONS + 1, workload.partitionCount.getAcquire(),
                workload.name + " must create and consume the new partition introduced during load");
    }

    private void logProgress(Workload workload) {
        emitTelemetry("TASK6_PROGRESS tenant=%s elapsedSeconds=%d published=%d delivered=%s oltpProbes=%d",
                workload.name,
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - workload.publisherStartedNanos),
                workload.published.getAcquire(),
                deliveredCounts(workload),
                workload.oltpProbeCount.getAcquire());
    }

    private TenantStatistics captureStatistics(Workload workload, DatabaseSnapshot snapshot) {
        long elapsedMillis = Math.max(1L, Duration.between(
                workload.testStartedAt, OffsetDateTime.now(ZoneOffset.UTC)).toMillis());
        double publishRate = workload.published.getAcquire() * 1_000.0 / elapsedMillis;
        double bloatPercent = snapshot.liveTuples + snapshot.deadTuples == 0
                ? 0.0
                : snapshot.deadTuples * 100.0 / (snapshot.liveTuples + snapshot.deadTuples);
        long orderViolations = workload.deliveryStates.stream()
                .mapToLong(state -> state.orderViolations.getAcquire())
                .sum();
        long crossTenantDeliveries = workload.deliveryStates.stream()
                .mapToLong(state -> state.crossTenantDeliveries.getAcquire())
                .sum();
        return new TenantStatistics(
                workload.name,
                elapsedMillis / 1_000L,
                workload.published.getAcquire(),
                deliveredCountValues(workload),
                publishRate,
                combinedLatency(workload, 0.50),
                combinedLatency(workload, 0.95),
                combinedLatency(workload, 0.99),
                workload.oltpProbeCount.getAcquire(),
                workload.oltpLatencies.percentile(0.50),
                workload.oltpLatencies.percentile(0.95),
                workload.oltpLatencies.percentile(0.99),
                snapshot.pendingCount,
                snapshot.completedCount,
                snapshot.swept,
                snapshot.watermarkId,
                snapshot.pendingAtOrBelowWatermark,
                snapshot.walBytes,
                snapshot.liveTuples,
                snapshot.deadTuples,
                bloatPercent,
                workload.partitionCount.getAcquire(),
                GROUPS_PER_TENANT,
                POOL_SIZE,
                orderViolations,
                crossTenantDeliveries);
    }

    private void logSummary(TenantStatistics statistics) {
        emitTelemetry("TASK6_SUMMARY tenant=%s durationSeconds=%d published=%d delivered=%s publishRate=%s "
                        + "deliveryP50Ms=%d deliveryP95Ms=%d deliveryP99Ms=%d oltpCount=%d oltpP50Ms=%d "
                        + "oltpP95Ms=%d oltpP99Ms=%d pending=%d completed=%d swept=%d watermarkId=%d "
                        + "pendingAtOrBelowWatermark=%d walBytes=%d "
                        + "liveTuples=%d deadTuples=%d bloatPercent=%s partitions=%d groups=%d poolSize=%d",
                statistics.tenant,
                statistics.durationSeconds,
                statistics.published,
                statistics.delivered,
                String.format(Locale.ROOT, "%.2f", statistics.publishRate),
                statistics.deliveryP50Ms,
                statistics.deliveryP95Ms,
                statistics.deliveryP99Ms,
                statistics.oltpCount,
                statistics.oltpP50Ms,
                statistics.oltpP95Ms,
                statistics.oltpP99Ms,
                statistics.pending,
                statistics.completed,
                statistics.swept,
                statistics.watermarkId,
                statistics.pendingAtOrBelowWatermark,
                statistics.walBytes,
                statistics.liveTuples,
                statistics.deadTuples,
                String.format(Locale.ROOT, "%.2f", statistics.bloatPercent),
                statistics.partitions,
                statistics.groups,
                statistics.poolSize);
    }

    private Future<Void> writePerformanceArtifacts(Vertx vertx, ReleaseResults results) {
        String outputDirectory = System.getProperty(
                "peegeeq.performance.results.dir", "target/performance-results");
        String jsonPath = outputDirectory + "/task6-partitioned-consumption.json";
        String markdownPath = outputDirectory + "/task6-partitioned-consumption.md";
        JsonObject report = new JsonObject()
                .put("schemaVersion", 1)
                .put("test", "PartitionedConsumptionReleaseGate")
                .put("status", "PASS")
                .put("recordedAtUtc", OffsetDateTime.now(ZoneOffset.UTC).toString())
                .put("build", new JsonObject()
                        .put("url", environmentValue("BUILD_URL"))
                        .put("number", environmentValue("BUILD_NUMBER"))
                        .put("job", environmentValue("JOB_NAME"))
                        .put("node", environmentValue("NODE_NAME"))
                        .put("gitCommit", environmentValue("GIT_COMMIT")))
                .put("configuration", new JsonObject()
                        .put("durationSeconds", DURATION_SECONDS)
                        .put("totalMessageRate", TOTAL_MESSAGE_RATE)
                        .put("batchSize", BATCH_SIZE)
                        .put("initialPartitions", INITIAL_PARTITIONS)
                        .put("finalPartitions", INITIAL_PARTITIONS + 1)
                        .put("groupsPerTenant", GROUPS_PER_TENANT)
                        .put("payloadBytes", PAYLOAD_BYTES)
                        .put("poolSizePerTenant", POOL_SIZE)
                        .put("drainTimeoutSeconds", DRAIN_TIMEOUT_SECONDS)
                        .put("postgresImage", postgres.getDockerImageName()))
                .put("totals", new JsonObject()
                        .put("published", results.tenantA.published + results.tenantB.published)
                        .put("handlerDeliveries", totalDeliveries(results.tenantA)
                                + totalDeliveries(results.tenantB)))
                .put("tenants", new JsonArray()
                        .add(toJson(results.tenantA, results.tenantAAssignments))
                        .add(toJson(results.tenantB, results.tenantBAssignments)));
        Buffer json = Buffer.buffer(report.encodePrettily() + System.lineSeparator());
        Buffer markdown = Buffer.buffer(toMarkdown(report, results));
        return vertx.fileSystem().mkdirs(outputDirectory)
                .compose(created -> vertx.fileSystem().writeFile(jsonPath, json))
                .compose(written -> vertx.fileSystem().writeFile(markdownPath, markdown))
                .onSuccess(written -> emitTelemetry(
                        "TASK6_ARTIFACTS json=%s markdown=%s", jsonPath, markdownPath));
    }

    private JsonObject toJson(TenantStatistics statistics, int assignmentsAfterShutdown) {
        return new JsonObject()
                .put("tenant", statistics.tenant)
                .put("durationSeconds", statistics.durationSeconds)
                .put("published", statistics.published)
                .put("deliveredPerGroup", new JsonArray(statistics.delivered))
                .put("publishRatePerSecond", statistics.publishRate)
                .put("deliveryLatencyMs", new JsonObject()
                        .put("p50", statistics.deliveryP50Ms)
                        .put("p95", statistics.deliveryP95Ms)
                        .put("p99", statistics.deliveryP99Ms))
                .put("oltp", new JsonObject()
                        .put("count", statistics.oltpCount)
                        .put("p50Ms", statistics.oltpP50Ms)
                        .put("p95Ms", statistics.oltpP95Ms)
                        .put("p99Ms", statistics.oltpP99Ms))
                .put("database", new JsonObject()
                        .put("pending", statistics.pending)
                        .put("completed", statistics.completed)
                        .put("swept", statistics.swept)
                        .put("watermarkId", statistics.watermarkId)
                        .put("pendingAtOrBelowWatermark", statistics.pendingAtOrBelowWatermark)
                        .put("walBytes", statistics.walBytes)
                        .put("liveTuples", statistics.liveTuples)
                        .put("deadTuples", statistics.deadTuples)
                        .put("bloatPercent", statistics.bloatPercent))
                .put("partitions", statistics.partitions)
                .put("groups", statistics.groups)
                .put("poolSize", statistics.poolSize)
                .put("orderViolations", statistics.orderViolations)
                .put("crossTenantDeliveries", statistics.crossTenantDeliveries)
                .put("assignmentsAfterShutdown", assignmentsAfterShutdown);
    }

    private String toMarkdown(JsonObject report, ReleaseResults results) {
        return String.format(Locale.ROOT, """
                # Task 6 Partitioned Consumption Performance Result

                - Status: **PASS**
                - Recorded (UTC): `%s`
                - Jenkins build: `%s`
                - Git revision: `%s`
                - Duration: %,d seconds
                - Configured message rate: %,d/second total
                - PostgreSQL image: `%s`

                | Tenant | Published | Delivered per group | Rate/s | Delivery p95 | OLTP probes | OLTP p95 | Pending | Watermark | Order violations | Cross-tenant deliveries | Assignments after shutdown |
                |---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
                %s
                %s

                Total published: **%,d**  
                Total handler deliveries: **%,d**
                """,
                report.getString("recordedAtUtc"),
                report.getJsonObject("build").getString("url"),
                report.getJsonObject("build").getString("gitCommit"),
                DURATION_SECONDS,
                TOTAL_MESSAGE_RATE,
                postgres.getDockerImageName(),
                markdownRow(results.tenantA, results.tenantAAssignments),
                markdownRow(results.tenantB, results.tenantBAssignments),
                report.getJsonObject("totals").getLong("published"),
                report.getJsonObject("totals").getLong("handlerDeliveries"));
    }

    private String markdownRow(TenantStatistics statistics, int assignmentsAfterShutdown) {
        return String.format(Locale.ROOT,
                "| %s | %,d | %s | %.2f | %,d ms | %,d | %,d ms | %,d | %,d | %,d | %,d | %,d |",
                statistics.tenant,
                statistics.published,
                statistics.delivered,
                statistics.publishRate,
                statistics.deliveryP95Ms,
                statistics.oltpCount,
                statistics.oltpP95Ms,
                statistics.pending,
                statistics.watermarkId,
                statistics.orderViolations,
                statistics.crossTenantDeliveries,
                assignmentsAfterShutdown);
    }

    private static String environmentValue(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? "not-set" : value;
    }

    private long totalDeliveries(TenantStatistics statistics) {
        return statistics.delivered.stream().mapToLong(Long::longValue).sum();
    }

    private static void emitTelemetry(String format, Object... arguments) {
        System.out.println(String.format(Locale.ROOT, format, arguments));
    }

    private long combinedLatency(Workload workload, double percentile) {
        long maximum = 0L;
        for (DeliveryState state : workload.deliveryStates) {
            maximum = Math.max(maximum, state.latencies.percentile(percentile));
        }
        return maximum;
    }

    private String deliveredCounts(Workload workload) {
        return deliveredCountValues(workload).toString();
    }

    private List<Long> deliveredCountValues(Workload workload) {
        return workload.deliveryStates.stream()
                .map(state -> state.delivered.getAcquire())
                .toList();
    }

    private Future<Void> stopAllEngines() {
        Future<Void> chain = Future.succeededFuture();
        chain = addEngineStops(tenantA, chain);
        return addEngineStops(tenantB, chain);
    }

    private Future<Void> addEngineStops(Workload workload, Future<Void> chain) {
        if (workload == null) {
            return chain;
        }
        for (PartitionedConsumerEngine<String> engine : workload.engines) {
            chain = chain.compose(ignored -> engine.stop());
        }
        return chain;
    }

    private Future<Integer> countAssignments(Workload workload) {
        return connectionManager.withConnection(workload.serviceId, connection ->
                connection.preparedQuery("""
                        SELECT COUNT(*)::int AS assignment_count
                        FROM outbox_partition_assignments
                        WHERE topic = $1
                        """)
                        .execute(Tuple.of(workload.topic))
                        .map(rows -> rows.iterator().next().getInteger("assignment_count")));
    }

    private Future<Void> cleanupWorkloads() {
        List<Future<Void>> cleanups = new ArrayList<>();
        addCleanup(tenantA, cleanups);
        addCleanup(tenantB, cleanups);
        return cleanups.isEmpty() ? Future.succeededFuture() : Future.all(cleanups).mapEmpty();
    }

    private void addCleanup(Workload workload, List<Future<Void>> cleanups) {
        if (workload == null || connectionManager == null) {
            return;
        }
        cleanups.add(connectionManager.withTransaction(workload.serviceId, connection ->
                deleteTopicData(connection, workload.topic)));
    }

    private Future<Void> deleteTopicData(SqlConnection connection, String topic) {
        return connection.preparedQuery("DELETE FROM outbox_partition_assignments WHERE topic = $1")
                .execute(Tuple.of(topic))
                .compose(ignored -> connection.preparedQuery(
                                "DELETE FROM outbox_partition_offsets WHERE topic = $1")
                        .execute(Tuple.of(topic)))
                .compose(ignored -> connection.preparedQuery(
                                "DELETE FROM outbox_topic_watermarks WHERE topic = $1")
                        .execute(Tuple.of(topic)))
                .compose(ignored -> connection.preparedQuery(
                                "DELETE FROM outbox_topic_subscriptions WHERE topic = $1")
                        .execute(Tuple.of(topic)))
                .compose(ignored -> connection.preparedQuery("DELETE FROM outbox WHERE topic = $1")
                        .execute(Tuple.of(topic)))
                .compose(ignored -> connection.preparedQuery("DELETE FROM outbox_topics WHERE topic = $1")
                        .execute(Tuple.of(topic)))
                .mapEmpty();
    }

    private static String groupName(int index) {
        return "task6-group-" + index;
    }

    private static String partitionKey(int index) {
        return "partition-" + index;
    }

    private static void validateConfiguration() {
        if (TOTAL_MESSAGE_RATE < 2) {
            throw new IllegalArgumentException("peegeeq.task6.message.rate must be at least 2");
        }
        if (DURATION_SECONDS < 1) {
            throw new IllegalArgumentException("peegeeq.task6.duration.seconds must be positive");
        }
        if (GROUPS_PER_TENANT < 1) {
            throw new IllegalArgumentException("peegeeq.task6.groups.per.tenant must be positive");
        }
    }

    private static int positiveIntProperty(String key, int defaultValue) {
        int value = Integer.getInteger(key, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static long positiveLongProperty(String key, long defaultValue) {
        long value = Long.getLong(key, defaultValue);
        if (value <= 0L) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static final class Workload {
        private final String name;
        private final String serviceId;
        private final String topic;
        private final int messageRate;
        private final AtomicInteger partitionCount;
        private final AtomicLong published = new AtomicLong();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean expansionTriggered = new AtomicBoolean();
        private final AtomicLong oltpProbeCount = new AtomicLong();
        private final LatencyHistogram oltpLatencies = new LatencyHistogram();
        private final List<DeliveryState> deliveryStates = new ArrayList<>();
        private final List<PartitionedConsumerEngine<String>> engines = new ArrayList<>();
        private final List<String> instanceIds = new ArrayList<>();

        private Vertx vertx;
        private OffsetDateTime testStartedAt;
        private long publisherStartedNanos;
        private long publishedAtWindowStart;
        private long deadlineNanos;
        private long expansionNanos;
        private String walStart;

        private Workload(String name, String serviceId, String topic, int messageRate, int partitionCount) {
            this.name = name;
            this.serviceId = serviceId;
            this.topic = topic;
            this.messageRate = messageRate;
            this.partitionCount = new AtomicInteger(partitionCount);
            for (int index = 0; index < GROUPS_PER_TENANT; index++) {
                deliveryStates.add(new DeliveryState());
            }
        }
    }

    private static final class DeliveryState {
        private final AtomicLong delivered = new AtomicLong();
        private final AtomicLong orderViolations = new AtomicLong();
        private final AtomicLong crossTenantDeliveries = new AtomicLong();
        private final Map<String, Long> lastIdByPartition = new ConcurrentHashMap<>();
        private final LatencyHistogram latencies = new LatencyHistogram();
    }

    private static final class LatencyHistogram {
        private static final long[] UPPER_BOUNDS_MS = {
                10L, 25L, 50L, 100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, Long.MAX_VALUE
        };
        private final AtomicLongArray counts = new AtomicLongArray(UPPER_BOUNDS_MS.length);
        private final AtomicLong total = new AtomicLong();

        private void record(long latencyMillis) {
            for (int index = 0; index < UPPER_BOUNDS_MS.length; index++) {
                if (latencyMillis <= UPPER_BOUNDS_MS[index]) {
                    counts.incrementAndGet(index);
                    total.incrementAndGet();
                    return;
                }
            }
        }

        private long percentile(double percentile) {
            long observations = total.getAcquire();
            if (observations == 0L) {
                return 0L;
            }
            long target = Math.max(1L, (long) Math.ceil(observations * percentile));
            long cumulative = 0L;
            for (int index = 0; index < UPPER_BOUNDS_MS.length; index++) {
                cumulative += counts.getAcquire(index);
                if (cumulative >= target) {
                    return UPPER_BOUNDS_MS[index];
                }
            }
            return Long.MAX_VALUE;
        }
    }

    private record DatabaseSnapshot(
            long pendingCount,
            long completedCount,
            long swept,
            long watermarkId,
            long pendingAtOrBelowWatermark,
            long walBytes,
            long liveTuples,
            long deadTuples) {
    }

    private record PerformanceSnapshots(TenantStatistics tenantA, TenantStatistics tenantB) {
    }

    private record ReleaseResults(
            TenantStatistics tenantA,
            TenantStatistics tenantB,
            int tenantAAssignments,
            int tenantBAssignments) {
    }

    private record TenantStatistics(
            String tenant,
            long durationSeconds,
            long published,
            List<Long> delivered,
            double publishRate,
            long deliveryP50Ms,
            long deliveryP95Ms,
            long deliveryP99Ms,
            long oltpCount,
            long oltpP50Ms,
            long oltpP95Ms,
            long oltpP99Ms,
            long pending,
            long completed,
            long swept,
            long watermarkId,
            long pendingAtOrBelowWatermark,
            long walBytes,
            long liveTuples,
            long deadTuples,
            double bloatPercent,
            int partitions,
            int groups,
            int poolSize,
            long orderViolations,
            long crossTenantDeliveries) {
    }
}
