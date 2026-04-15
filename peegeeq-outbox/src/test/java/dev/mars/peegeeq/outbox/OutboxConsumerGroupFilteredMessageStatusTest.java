package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TDD tests for H2: filtered messages silently acknowledged as completed.
 *
 * <p>Current defect: When {@link OutboxConsumerGroup}'s {@code distributeMessage()}
 * method filters a message (via group filter or because no eligible consumer exists),
 * it returns {@code CompletableFuture.completedFuture(null)}. The underlying
 * {@link OutboxConsumer} interprets handler success as message-completed and calls
 * {@code markMessageCompleted()}, permanently marking the filtered message as COMPLETED
 * in the database.</p>
 *
 * <p>These tests verify the expected behaviour: filtered messages should NOT be marked
 * as COMPLETED. They should remain in a state that allows reprocessing (e.g., reset to
 * PENDING or marked with a distinct FILTERED status).</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("H2: Filtered messages must not be acknowledged as completed")
class OutboxConsumerGroupFilteredMessageStatusTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupFilteredMessageStatusTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private ConsumerGroup<String> consumerGroup;
    private String testTopic;

    @BeforeEach
    void setUp() {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "filter-status-test-" + UUID.randomUUID().toString().substring(0, 8);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("filter-status-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumerGroup = outboxFactory.createConsumerGroup("filter-group", testTopic, String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and manager");
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");

        if (consumerGroup != null) {
            consumerGroup.stop();
            consumerGroup.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
    }

    // ========================================================================
    // Positive test: accepted messages should be COMPLETED
    // ========================================================================

    @Test
    @DisplayName("Messages accepted by group filter should be marked COMPLETED")
    void acceptedMessagesShouldBeCompleted(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: accepted messages should be completed");
        AtomicInteger processedCount = new AtomicInteger(0);

        // Group filter accepts messages starting with "Accept"
        consumerGroup.setGroupFilter(msg -> msg.getPayload().startsWith("Accept"));

        consumerGroup.addConsumer("member-1", message -> {
            processedCount.incrementAndGet();
            return Future.succeededFuture();
        });

        consumerGroup.start();

        // Send a message that passes the filter
        producer.send("Accept-this-message")
            .compose(v -> awaitCondition(vertx, () -> processedCount.get() >= 1, 10_000,
                    "Accepted message should have been processed"))
            // Allow time for markMessageCompleted to execute
            .compose(v -> vertx.timer(1000))
            .compose(timerId -> queryMessageStatusCountsAsync(vertx, testTopic))
            .onSuccess(statusCounts -> testContext.verify(() -> {
                int completedCount = statusCounts.getOrDefault("COMPLETED", 0);
                assertTrue(completedCount >= 1,
                        "Accepted message should be marked COMPLETED, but statuses were: " + statusCounts);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Negative tests: filtered messages should NOT be COMPLETED
    // ========================================================================

    @Test
    @DisplayName("Messages rejected by group filter must NOT be marked COMPLETED")
    void filteredByGroupFilterShouldNotBeCompleted(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: filtered by group filter should not be completed");
        AtomicInteger processedCount = new AtomicInteger(0);

        // Group filter only accepts messages starting with "Accept"
        consumerGroup.setGroupFilter(msg -> msg.getPayload().startsWith("Accept"));

        consumerGroup.addConsumer("member-1", message -> {
            processedCount.incrementAndGet();
            return Future.succeededFuture();
        });

        consumerGroup.start();

        // Send a message that will be REJECTED by the group filter
        producer.send("Reject-this-message")
            .compose(v -> awaitCondition(vertx,
                    () -> consumerGroup.getStats().getTotalMessagesFiltered() >= 1,
                    10_000, "Message should have been filtered by group filter"))
            // Allow time for any status update to execute
            .compose(v -> vertx.timer(1000))
            .compose(timerId -> queryMessageStatusCountsAsync(vertx, testTopic))
            .onSuccess(statusCounts -> testContext.verify(() -> {
                // Critical assertion: the filtered message must NOT be COMPLETED
                assertEquals(0, processedCount.get(),
                        "Filtered message should not have reached the handler");

                int completedCount = statusCounts.getOrDefault("COMPLETED", 0);

                // THIS IS THE CORE ASSERTION — currently fails due to H2 bug
                assertEquals(0, completedCount,
                        "Group-filtered message must NOT be marked COMPLETED. " +
                        "It should remain PENDING (or a distinct FILTERED status) for reprocessing. " +
                        "Actual statuses: " + statusCounts);

                // Filtered messages should still be available for reprocessing
                int pendingOrFilteredCount = statusCounts.getOrDefault("PENDING", 0)
                        + statusCounts.getOrDefault("FILTERED", 0);
                assertTrue(pendingOrFilteredCount >= 1,
                        "Filtered message should be in PENDING or FILTERED state, but statuses were: " + statusCounts);

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Messages with no eligible consumer must NOT be marked COMPLETED")
    void noEligibleConsumerShouldNotBeCompleted(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: no eligible consumer should not be completed");
        AtomicInteger processedCount = new AtomicInteger(0);

        // Member only accepts messages with payload "A"
        consumerGroup.addConsumer("member-A", message -> {
            processedCount.incrementAndGet();
            return Future.succeededFuture();
        }, msg -> msg.getPayload().equals("A"));

        consumerGroup.start();

        // Send a message that no consumer will accept ("B" doesn't match "A" filter)
        producer.send("B")
            .compose(v -> awaitCondition(vertx,
                    () -> consumerGroup.getStats().getTotalMessagesFiltered() >= 1,
                    10_000, "Message should have been filtered (no eligible consumer)"))
            // Allow time for any status update to execute
            .compose(v -> vertx.timer(1000))
            .compose(timerId -> queryMessageStatusCountsAsync(vertx, testTopic))
            .onSuccess(statusCounts -> testContext.verify(() -> {
                assertEquals(0, processedCount.get(),
                        "No-eligible-consumer message should not reach any handler");

                int completedCount = statusCounts.getOrDefault("COMPLETED", 0);

                // THIS IS THE CORE ASSERTION — currently fails due to H2 bug
                assertEquals(0, completedCount,
                        "Message with no eligible consumer must NOT be marked COMPLETED. " +
                        "Actual statuses: " + statusCounts);

                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Filtered message should be available for a second consumer group to process")
    void filteredMessageShouldBeAvailableForSecondGroup(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: filtered message should be available for second group");
        AtomicInteger group1Processed = new AtomicInteger(0);
        AtomicInteger group2Processed = new AtomicInteger(0);

        // Group 1: only accepts "TypeA" messages — will filter out "TypeB"
        consumerGroup.setGroupFilter(msg -> msg.getPayload().startsWith("TypeA"));
        consumerGroup.addConsumer("member-1", message -> {
            group1Processed.incrementAndGet();
            return Future.succeededFuture();
        });

        // Group 2: accepts "TypeB" messages — the ones group 1 rejected
        ConsumerGroup<String> consumerGroup2 = outboxFactory.createConsumerGroup(
                "group-2", testTopic, String.class);
        consumerGroup2.setGroupFilter(msg -> msg.getPayload().startsWith("TypeB"));
        consumerGroup2.addConsumer("member-2", message -> {
            group2Processed.incrementAndGet();
            return Future.succeededFuture();
        });

        consumerGroup.start();
        consumerGroup2.start();

        // Send a TypeB message — group 1 should filter it, group 2 should process it
        producer.send("TypeB-important-event")
            .compose(v -> awaitCondition(vertx, () -> group2Processed.get() >= 1, 10_000,
                    "Group 2 should have processed the TypeB message"))
            .onSuccess(v -> testContext.verify(() -> {
                assertEquals(0, group1Processed.get(),
                        "Group 1 should not have processed TypeB message");
                assertTrue(group2Processed.get() >= 1,
                        "Group 2 should have processed the TypeB message that group 1 filtered");

                consumerGroup2.stop();
                consumerGroup2.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Queries the outbox table directly via JDBC to check message statuses.
     * This bypasses the application layer to verify actual database state.
     */
    private Map<String, Integer> queryMessageStatusCounts(String topic) throws Exception {
        Map<String, Integer> counts = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT status, COUNT(*) as cnt FROM public.outbox WHERE topic = ? GROUP BY status")) {
            stmt.setString(1, topic);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("status"), rs.getInt("cnt"));
                }
            }
        }
        return counts;
    }

    private Future<Map<String, Integer>> queryMessageStatusCountsAsync(Vertx vertx, String topic) {
        return vertx.executeBlocking(() -> queryMessageStatusCounts(topic));
    }

    /**
     * Asynchronously awaits a condition using Vert.x timers instead of Thread.sleep polling.
     */
    private Future<Void> awaitCondition(Vertx vertx, java.util.function.BooleanSupplier condition,
                                         long timeoutMillis, String failureMessage) {
        Promise<Void> promise = Promise.promise();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        checkCondition(vertx, condition, deadline, failureMessage, promise);
        return promise.future();
    }

    private void checkCondition(Vertx vertx, java.util.function.BooleanSupplier condition,
                                long deadline, String failureMessage, Promise<Void> promise) {
        if (condition.getAsBoolean()) {
            promise.complete();
        } else if (System.currentTimeMillis() > deadline) {
            promise.fail(new AssertionError(failureMessage + " (timed out)"));
        } else {
            vertx.timer(100).onSuccess(id -> checkCondition(vertx, condition, deadline, failureMessage, promise));
        }
    }
}
