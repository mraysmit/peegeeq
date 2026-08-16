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
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQDefaults;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for H1: clientId propagation through the consumer group path.
 *
 * <p>These tests verify that when an {@link OutboxFactory} is constructed with a
 * non-null clientId, that clientId is propagated through to the {@link OutboxConsumerGroup}
 * and ultimately to the {@link OutboxConsumer} created inside
 * {@link OutboxConsumerGroup#start()}.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("H1: clientId propagation through consumer group path")
class OutboxConsumerGroupClientIdPropagationTest {

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private DatabaseService databaseService;
    private PeeGeeQConfiguration config;
    private OutboxConsumerGroup<String> startedConsumerGroup;
    private OutboxProducer<String> startedProducer;
    private OutboxFactory startedFactory;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.05S")
                .build();
        this.config = new PeeGeeQConfiguration("default", testProps);
        this.manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        this.manager.start().onSuccess(v -> {
            this.databaseService = new PgDatabaseService(manager);
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        List<Throwable> cleanupFailures = new ArrayList<>();

        if (startedProducer != null) {
            try {
                startedProducer.close();
            } catch (Exception error) {
                cleanupFailures.add(error);
            }
        }

        Future.<Void>succeededFuture()
                .compose(v -> captureCleanupFailure(
                        startedConsumerGroup != null ? startedConsumerGroup.close() : Future.succeededFuture(),
                        cleanupFailures))
                .compose(v -> captureCleanupFailure(
                        startedFactory != null ? startedFactory.close() : Future.succeededFuture(),
                        cleanupFailures))
                .compose(v -> captureCleanupFailure(
                        manager != null ? manager.closeReactive() : Future.succeededFuture(),
                        cleanupFailures))
                .compose(v -> failIfCleanupFailed(cleanupFailures))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Positive tests: clientId SHOULD be propagated
    // ========================================================================

    @Test
    @DisplayName("OutboxFactory consumer group should deliver through its named client")
    void factoryWithClientIdShouldPropagateToConsumerGroup(
            Vertx vertx, VertxTestContext testContext) throws Exception {
        String expectedPayload = "factory-named-client-message";

        exerciseFactoryGroupFlow(
                vertx, "tenant-pool-42", "factory-named-group",
                "factory-named-topic", expectedPayload)
                .onSuccess(receivedPayloads -> testContext.verify(() -> {
                    assertEquals(List.of(expectedPayload), receivedPayloads,
                            "Factory-created consumer group should deliver through the named client pool");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Consumer group start() should create underlying consumer with correct clientId")
    void consumerGroupStartShouldPassClientIdToUnderlyingConsumer(
            Vertx vertx, VertxTestContext testContext) throws Exception {
        // Given: only a specifically named client can access the outbox during this test
        String expectedClientId = "tenant-pool-99";
        String topic = "client-id-propagation-topic";
        String expectedPayload = "named-client-message";
        List<String> receivedPayloads = new CopyOnWriteArrayList<>();

        manager.getClientFactory().createClient(
                expectedClientId, config.getDatabaseConfig(), config.getPoolConfig());
        Pool namedPool = manager.getClientFactory().getPool(expectedClientId)
                .orElseThrow(() -> new IllegalStateException("Named client pool was not registered"));

        startedConsumerGroup = new OutboxConsumerGroup<>(
                "test-group", topic, String.class,
                databaseService, null, null, config, expectedClientId);
        startedProducer = new OutboxProducer<>(
                databaseService, null, topic, String.class, null, expectedClientId);

        startedConsumerGroup.addConsumer("member-1", message -> {
            receivedPayloads.add(message.getPayload());
            return Future.succeededFuture();
        });

        // Removing the default pool makes the observable message flow fail unless both
        // the producer and the consumer group use the registered named client.
        manager.getClientFactory().removeClient(PeeGeeQDefaults.DEFAULT_POOL_ID)
                .compose(v -> startedConsumerGroup.start())
                .compose(v -> startedProducer.send(expectedPayload))
                .compose(v -> awaitMessageCompleted(vertx, namedPool, topic, 10_000))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(List.of(expectedPayload), receivedPayloads,
                            "Consumer group should receive the message through the named client pool");
                    assertTrue(startedConsumerGroup.isActive(),
                            "Consumer group should remain active after processing through the named client pool");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Negative tests: null clientId should remain null (default pool)
    // ========================================================================

    @Test
    @DisplayName("OutboxFactory consumer group without clientId should use the default pool")
    void factoryWithNullClientIdShouldPropagateNullToConsumerGroup(
            Vertx vertx, VertxTestContext testContext) throws Exception {
        String expectedPayload = "factory-default-client-message";

        exerciseFactoryGroupFlow(
                vertx, null, "factory-default-group",
                "factory-default-topic", expectedPayload)
                .onSuccess(receivedPayloads -> testContext.verify(() -> {
                    assertEquals(List.of(expectedPayload), receivedPayloads,
                            "Factory-created consumer group should deliver through the default pool");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Factory-created direct consumer and group should both use the named client")
    void directConsumerAndGroupConsumerShouldHaveSameClientId(
            Vertx vertx, VertxTestContext testContext) throws Exception {
        String expectedClientId = "shared-pool";
        String directTopic = "shared-pool-direct-topic";
        String groupTopic = "shared-pool-group-topic";
        String directPayload = "direct-consumer-message";
        String groupPayload = "group-consumer-message";
        List<String> directPayloads = new CopyOnWriteArrayList<>();
        List<String> groupPayloads = new CopyOnWriteArrayList<>();

        Pool namedPool = registerNamedClient(expectedClientId);
        startedFactory = new OutboxFactory(databaseService, null, config, expectedClientId);

        MessageProducer<String> directProducer = startedFactory.createProducer(directTopic, String.class);
        MessageConsumer<String> directConsumer = startedFactory.createConsumer(directTopic, String.class);
        MessageProducer<String> groupProducer = startedFactory.createProducer(groupTopic, String.class);
        ConsumerGroup<String> group = startedFactory.createConsumerGroup(
                "shared-pool-group", groupTopic, String.class);

        group.addConsumer("group-member", message -> {
            groupPayloads.add(message.getPayload());
            return Future.succeededFuture();
        });

        manager.getClientFactory().removeClient(PeeGeeQDefaults.DEFAULT_POOL_ID)
                .compose(v -> directConsumer.subscribe(message -> {
                    directPayloads.add(message.getPayload());
                    return Future.succeededFuture();
                }))
                .compose(v -> group.start())
                .compose(v -> Future.all(
                        directProducer.send(directPayload),
                        groupProducer.send(groupPayload)).mapEmpty())
                .compose(v -> awaitMessageCompleted(vertx, namedPool, directTopic, 10_000))
                .compose(v -> awaitMessageCompleted(vertx, namedPool, groupTopic, 10_000))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(List.of(directPayload), directPayloads,
                            "Direct consumer should deliver through the factory's named client pool");
                    assertEquals(List.of(groupPayload), groupPayloads,
                            "Consumer group should deliver through the same named client pool");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private Pool registerNamedClient(String clientId) {
        manager.getClientFactory().createClient(
                clientId, config.getDatabaseConfig(), config.getPoolConfig());
        return manager.getClientFactory().getPool(clientId)
                .orElseThrow(() -> new IllegalStateException("Named client pool was not registered: " + clientId));
    }

    private Future<List<String>> exerciseFactoryGroupFlow(
            Vertx vertx, String clientId, String groupName, String topic, String payload) {
        Pool pool = clientId == null ? manager.getPool() : registerNamedClient(clientId);
        startedFactory = new OutboxFactory(databaseService, null, config, clientId);
        MessageProducer<String> producer = startedFactory.createProducer(topic, String.class);
        ConsumerGroup<String> group = startedFactory.createConsumerGroup(groupName, topic, String.class);
        List<String> receivedPayloads = new CopyOnWriteArrayList<>();

        group.addConsumer("member-1", message -> {
            receivedPayloads.add(message.getPayload());
            return Future.succeededFuture();
        });

        Future<Void> isolateNamedClient = clientId == null
                ? Future.succeededFuture()
                : manager.getClientFactory().removeClient(PeeGeeQDefaults.DEFAULT_POOL_ID);

        return isolateNamedClient
                .compose(v -> group.start())
                .compose(v -> producer.send(payload))
                .compose(v -> awaitMessageCompleted(vertx, pool, topic, 10_000))
                .map(receivedPayloads);
    }

    private Future<Void> awaitMessageCompleted(
            Vertx vertx, Pool pool, String topic, long timeoutMillis) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        return checkMessageCompleted(vertx, pool, topic, deadlineNanos);
    }

    private Future<Void> checkMessageCompleted(
            Vertx vertx, Pool pool, String topic, long deadlineNanos) {
        String sql = "SELECT status FROM \"" + PostgreSQLTestConstants.TEST_SCHEMA
                + "\".outbox WHERE topic = $1 ORDER BY created_at DESC LIMIT 1";

        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .compose(rows -> {
                    var iterator = rows.iterator();
                    String status = iterator.hasNext() ? iterator.next().getString("status") : null;
                    if ("COMPLETED".equals(status)) {
                        return Future.succeededFuture();
                    }
                    if (System.nanoTime() >= deadlineNanos) {
                        return Future.failedFuture(
                                "Timed out waiting for message to complete; last status was " + status);
                    }
                    return vertx.timer(50)
                            .compose(timerId -> checkMessageCompleted(vertx, pool, topic, deadlineNanos));
                });
    }

    private Future<Void> captureCleanupFailure(
            Future<Void> cleanup, List<Throwable> cleanupFailures) {
        return cleanup.transform(result -> {
            if (result.failed()) {
                cleanupFailures.add(result.cause());
            }
            return Future.succeededFuture();
        });
    }

    private Future<Void> failIfCleanupFailed(List<Throwable> cleanupFailures) {
        if (cleanupFailures.isEmpty()) {
            return Future.succeededFuture();
        }

        Throwable primaryFailure = cleanupFailures.get(0);
        cleanupFailures.stream().skip(1).forEach(primaryFailure::addSuppressed);
        return Future.failedFuture(primaryFailure);
    }

}
