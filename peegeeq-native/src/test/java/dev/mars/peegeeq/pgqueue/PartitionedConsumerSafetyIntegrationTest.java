package dev.mars.peegeeq.pgqueue;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Safety and edge-case integration tests for partitioned consumption.
 *
 * <p>These tests cover the concurrency and error-path gaps identified in the
 * code review that happy-path integration tests did not exercise:</p>
 * <ul>
 *   <li>Item 1: close() during async partitioned startup — CAS guard prevents state corruption</li>
 *   <li>Item 2: stop() failure logging — stopGracefully() completes cleanly in partitioned mode</li>
 *   <li>Item 3: overlapping fetch guard — slow handler does not cause duplicate message processing</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartitionedConsumerSafetyIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedConsumerSafetyIntegrationTest.class);
    private static final String SERVICE_ID = "safety-test";

    @Container
    static final PostgreSQLContainer postgres =
            PeeGeeQTestContainerFactory.createContainer(BASIC);

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private VertxPoolAdapter adapter;
    private ObjectMapper mapper;
    private PgConnectionManager connectionManager;

    @BeforeAll
    static void beforeAll() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, ALL);
    }

    @BeforeEach
    void setUp() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        databaseService = new PgDatabaseService(manager);
        adapter = new VertxPoolAdapter(
                databaseService.getVertx(),
                databaseService.getPool(),
                databaseService
        );

        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        connectionManager = new PgConnectionManager(databaseService.getVertx(), null);
        PgConnectionConfig connConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool(SERVICE_ID, connConfig, poolConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            try {
                cleanupTestData().await();
            } catch (Exception ignore) {}
            connectionManager.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
    }

    // ========================================================================
    // Item 1: close() during async partitioned startup
    //
    // Covers the CAS guard in startPartitioned(): if close() sets state to
    // CLOSED between start() and the async engine.start() callback, the CAS
    // compareAndSet(STARTING, ACTIVE) fails and the engine is stopped cleanly.
    // Without the fix, state.set(ACTIVE) would overwrite CLOSED.
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("close() during partitioned startup leaves state CLOSED, not ACTIVE")
    void testCloseDuringPartitionedStart_stateIsClosed(VertxTestContext testContext) throws Exception {
        logger.info("=== SAFETY 1: testCloseDuringPartitionedStart_stateIsClosed STARTED ===");

        String topic = "test-safety-close-start-" + System.nanoTime();
        String groupName = "safety-1";

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    // start() is fire-and-forget; transitions to STARTING then begins async work
                    group.start();

                    // Immediately close — should set state to CLOSED before async callback
                    group.close();

                    // State must be CLOSED immediately after close()
                    assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                            "State should be CLOSED immediately after close(), not overwritten by async start");

                    // Wait to ensure the async start callback doesn't resurrect state to ACTIVE
                    return databaseService.getVertx().timer(3000).mapEmpty()
                            .map(delayed -> {
                                assertEquals(PgNativeConsumerGroup.State.CLOSED, group.getState(),
                                        "State should still be CLOSED after async start callback completes");
                                assertFalse(group.isActive(),
                                        "Group should not be active after close()");
                                logger.info("SAFETY 1 PASSED: state remained CLOSED throughout async startup");
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // Item 2: stopGracefully() completes cleanly in partitioned mode
    //
    // Covers the .onFailure() logging added to stop(): verifies that
    // stopGracefully() returns a completed future and transitions state to NEW
    // when the partitioned engine is active. This path was previously untested
    // because CORE tests only exercise reference-counting mode.
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("stopGracefully() in partitioned mode returns to NEW state")
    void testStopGracefully_partitionedMode_returnsToNew(VertxTestContext testContext) throws Exception {
        logger.info("=== SAFETY 2: testStopGracefully_partitionedMode_returnsToNew STARTED ===");

        String topic = "test-safety-stop-" + System.nanoTime();
        String groupName = "safety-2";

        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> {
                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );
                    group.setMessageHandler(msg -> Future.succeededFuture());
                    group.start();

                    // Wait for the group to become ACTIVE (async join + engine start)
                    // Partitioned startup involves: topic mode query → engine.start() → joinGroup
                    // → initializeOffsets → startFetchLoop — can take 5+ seconds on cold Testcontainers
                    return databaseService.getVertx().timer(6000).mapEmpty()
                            .compose(delayed -> {
                                assertEquals(PgNativeConsumerGroup.State.ACTIVE, group.getState(),
                                        "Group should be ACTIVE after startup completes");

                                // stopGracefully returns a future — verify it completes and state is NEW
                                return group.stopGracefully();
                            })
                            .map(v2 -> {
                                assertEquals(PgNativeConsumerGroup.State.NEW, group.getState(),
                                        "State should be NEW after stopGracefully()");
                                assertFalse(group.isActive(),
                                        "Group should not be active after stopGracefully()");
                                logger.info("SAFETY 2 PASSED: stopGracefully() completed, state=NEW");
                                return (Void) null;
                            })
                            .eventually(() -> {
                                group.close();
                                return Future.succeededFuture();
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // Item 3: overlapping fetch guard prevents duplicate message processing
    //
    // Covers the fetchInProgress CAS guard in PartitionedConsumerEngine:
    // with a slow message handler, multiple fetch timer ticks fire while a
    // previous fetch+process+commit cycle is still running. The guard skips
    // overlapping fetches, preventing the same messages from being delivered
    // twice. Without the guard, concurrent fetches starting from the same
    // uncommitted offset would process duplicates.
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("slow handler with overlapping fetch ticks does not produce duplicate messages")
    void testSlowHandler_noDuplicateMessages(VertxTestContext testContext) throws Exception {
        logger.info("=== SAFETY 3: testSlowHandler_noDuplicateMessages STARTED ===");

        String topic = "test-safety-overlap-" + System.nanoTime();
        String groupName = "safety-3";

        // Track received message IDs — use synchronized set to detect duplicates
        Set<String> receivedIds = Collections.synchronizedSet(new HashSet<>());
        AtomicBoolean duplicateDetected = new AtomicBoolean(false);
        AtomicInteger totalInvocations = new AtomicInteger(0);

        // Insert several messages in one partition
        createTopic(topic, "OFFSET_WATERMARK")
                .compose(v -> createSubscription(topic, groupName))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-1"))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-2"))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-3"))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-4"))
                .compose(v -> insertOutboxMessage(topic, "slow-part", "msg-5"))
                .compose(v -> {
                    Vertx vtx = databaseService.getVertx();

                    PgNativeConsumerGroup<String> group = new PgNativeConsumerGroup<>(
                            groupName, topic, String.class,
                            adapter, mapper, null, null, databaseService,
                            connectionManager, SERVICE_ID
                    );

                    // Slow handler: 300ms per message via promise + timer
                    // With 5 messages, one fetch cycle takes ~1500ms
                    // Fetch interval is 1000ms, so the second tick fires while processing
                    group.setMessageHandler(msg -> {
                        totalInvocations.incrementAndGet();
                        if (!receivedIds.add(msg.getId())) {
                            duplicateDetected.set(true);
                            logger.error("DUPLICATE detected: id={}", msg.getId());
                        }
                        Promise<Void> p = Promise.promise();
                        vtx.setTimer(300, id -> p.complete());
                        return p.future();
                    });

                    group.start();

                    // Wait for startup (5s on cold Testcontainers) + multiple fetch cycles
                    // (1st fetch at ~0ms, 2nd at ~1000ms while 1st still processing,
                    //  guard should skip the 2nd, 3rd fetch at ~2000ms picks up from committed offset)
                    return vtx.timer(15000).mapEmpty()
                            .map(delayed -> {
                                group.close();

                                int total = totalInvocations.get();
                                logger.info("SAFETY 3: {} handler invocations, {} unique IDs, duplicate={}",
                                        total, receivedIds.size(), duplicateDetected.get());

                                assertFalse(duplicateDetected.get(),
                                        "No duplicate message IDs should be delivered; " +
                                        "the fetch overlap guard should prevent concurrent fetches on the same partition");

                                assertTrue(receivedIds.size() > 0,
                                        "Should have received at least some messages");

                                // Each message should have been delivered exactly once
                                assertEquals(total, receivedIds.size(),
                                        "Total invocations should equal unique IDs (no duplicates)");

                                logger.info("SAFETY 3 PASSED: {} messages processed, zero duplicates", receivedIds.size());
                                return (Void) null;
                            });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(45, TimeUnit.SECONDS), "Test timed out");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Future<Void> createTopic(String topic, String completionTrackingMode) {
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) " +
                        "VALUES ($1, 'PUB_SUB', $2) ON CONFLICT (topic) DO NOTHING"
                ).execute(Tuple.of(topic, completionTrackingMode))
                .map(rows -> (Void) null)
        );
    }

    private Future<Void> createSubscription(String topic, String groupName) {
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status) " +
                        "VALUES ($1, $2, 'ACTIVE') ON CONFLICT (topic, group_name) DO NOTHING"
                ).execute(Tuple.of(topic, groupName))
                .map(rows -> (Void) null)
        );
    }

    private Future<Long> insertOutboxMessage(String topic, String messageGroup, String payload) {
        JsonObject payloadJson = new JsonObject().put("data", payload);
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.preparedQuery(
                        "INSERT INTO outbox (topic, payload, status, message_group, created_at) " +
                        "VALUES ($1, $2, 'PENDING', $3, NOW()) RETURNING id"
                ).execute(Tuple.of(topic, payloadJson, messageGroup))
                .map(rows -> rows.iterator().next().getLong("id"))
        );
    }

    private Future<Void> cleanupTestData() {
        return connectionManager.withConnection(SERVICE_ID, conn ->
                conn.query("DELETE FROM outbox_partition_assignments WHERE topic LIKE 'test-safety-%'").execute()
                        .compose(v -> conn.query("DELETE FROM outbox_partition_offsets WHERE topic LIKE 'test-safety-%'").execute())
                        .compose(v -> conn.query("DELETE FROM outbox_topic_watermarks WHERE topic LIKE 'test-safety-%'").execute())
                        .compose(v -> conn.query("DELETE FROM outbox_topic_subscriptions WHERE topic LIKE 'test-safety-%'").execute())
                        .compose(v -> conn.query("DELETE FROM outbox WHERE topic LIKE 'test-safety-%'").execute())
                        .compose(v -> conn.query("DELETE FROM outbox_topics WHERE topic LIKE 'test-safety-%'").execute())
                        .map(rows -> (Void) null)
        );
    }
}
