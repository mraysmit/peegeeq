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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-tenant schema isolation tests for Outbox.
 * Verifies that messages sent by one tenant are not visible to another tenant.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class MultiTenantSchemaIsolationTest {

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager managerTenantA;
    private PeeGeeQManager managerTenantB;
    private OutboxFactory factoryTenantA;
    private OutboxFactory factoryTenantB;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        // Initialize two separate tenant schemas
        String schemaTenantA = "tenant_a";
        String schemaTenantB = "tenant_b";

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaTenantA,
                SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaTenantB,
                SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        // Create configuration for Tenant A using programmatic constructor
        PeeGeeQConfiguration configTenantA = new PeeGeeQConfiguration(
                "tenant-a",
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getDatabaseName(),
                postgres.getUsername(),
                postgres.getPassword(),
                schemaTenantA
        );
        managerTenantA = new PeeGeeQManager(configTenantA, new SimpleMeterRegistry());

        // Create configuration for Tenant B using programmatic constructor
        PeeGeeQConfiguration configTenantB = new PeeGeeQConfiguration(
                "tenant-b",
                postgres.getHost(),
                postgres.getFirstMappedPort(),
                postgres.getDatabaseName(),
                postgres.getUsername(),
                postgres.getPassword(),
                schemaTenantB
        );
        managerTenantB = new PeeGeeQManager(configTenantB, new SimpleMeterRegistry());

        managerTenantA.start()
                .compose(v -> managerTenantB.start())
                .map(v -> {
                    factoryTenantA = new OutboxFactory(new PgDatabaseService(managerTenantA), configTenantA);
                    factoryTenantB = new OutboxFactory(new PgDatabaseService(managerTenantB), configTenantB);
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext tearDownContext) {
        Future<Void> closeTenantAFactory = factoryTenantA != null
                ? factoryTenantA.close()
                : Future.succeededFuture();
        closeTenantAFactory
                .eventually(() -> factoryTenantB != null
                        ? factoryTenantB.close()
                        : Future.succeededFuture())
                .eventually(() -> managerTenantA != null
                        ? managerTenantA.closeReactive()
                        : Future.succeededFuture())
                .eventually(() -> managerTenantB != null
                        ? managerTenantB.closeReactive()
                        : Future.succeededFuture())
                .onSuccess(v -> tearDownContext.completeNow())
                .onFailure(tearDownContext::failNow);
    }

    @Test
    void testMessageIsolationBetweenTenants(Vertx vertx, VertxTestContext testContext) {
        // Tenant A sends a message
        MessageProducer<String> producerA = factoryTenantA.createProducer("test-topic", String.class);

        // Tenant B creates a consumer - should NOT receive tenant A's message
        MessageConsumer<String> consumerB = factoryTenantB.createConsumer("test-topic", String.class);
        List<String> receivedMessagesB = new CopyOnWriteArrayList<>();
        List<String> receivedMessagesA = new CopyOnWriteArrayList<>();

        // Wait 3 seconds to ensure Tenant B has not received Tenant A's message,
        // then create Tenant A's consumer
        consumerB.subscribe(message -> {
                receivedMessagesB.add(message.getPayload());
                return Future.succeededFuture();
            })
            .compose(v -> producerA.send("tenant-a-message"))
            .compose(v -> vertx.timer(3000))
            .compose(timerId -> {
                testContext.verify(() -> assertTrue(receivedMessagesB.isEmpty(), "Tenant B should have no messages"));

                MessageConsumer<String> consumerA = factoryTenantA.createConsumer("test-topic", String.class);
                return consumerA.subscribe(message -> {
                    receivedMessagesA.add(message.getPayload());
                    return Future.succeededFuture();
                });
            })
            .compose(v -> awaitCondition(
                    vertx,
                    () -> !receivedMessagesA.isEmpty(),
                    "Tenant A should receive its own message",
                    150))
            .onSuccess(v -> testContext.verify(() -> {
                assertTrue(receivedMessagesB.isEmpty(), "Tenant B should have no messages");
                assertEquals(List.of("tenant-a-message"), receivedMessagesA,
                        "Tenant A should receive exactly its own message");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testStatsIsolationBetweenTenants(VertxTestContext testContext) {
        // Tenant A sends 5 messages, sequencing sends via compose to ensure persistence ordering
        MessageProducer<String> producerA = factoryTenantA.createProducer("stats-topic", String.class);
        Future<Void> chainA = Future.succeededFuture();
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            chainA = chainA.compose(v -> producerA.send("tenant-a-message-" + idx));
        }

        // Tenant B sends 3 messages
        MessageProducer<String> producerB = factoryTenantB.createProducer("stats-topic", String.class);
        Future<Void> chainB = Future.succeededFuture();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            chainB = chainB.compose(v -> producerB.send("tenant-b-message-" + idx));
        }

        Future.all(chainA, chainB)
                .compose(v -> Future.all(
                        factoryTenantA.getStats("stats-topic"),
                        factoryTenantB.getStats("stats-topic")))
                .onSuccess(cf -> testContext.verify(() -> {
                    var statsA = cf.<dev.mars.peegeeq.api.messaging.QueueStats>resultAt(0);
                    var statsB = cf.<dev.mars.peegeeq.api.messaging.QueueStats>resultAt(1);
                    assertEquals(5, statsA.getPendingMessages(), "Tenant A should have 5 pending messages");
                    assertEquals(3, statsB.getPendingMessages(), "Tenant B should have 3 pending messages");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testSameTopicNameAcrossTenants(Vertx vertx, VertxTestContext testContext) {
        // Both tenants use the same topic name but should be isolated
        String sharedTopicName = "shared-topic-name";

        MessageProducer<String> producerA = factoryTenantA.createProducer(sharedTopicName, String.class);
        MessageProducer<String> producerB = factoryTenantB.createProducer(sharedTopicName, String.class);

        // Tenant A consumer should only receive tenant A's message
        MessageConsumer<String> consumerA = factoryTenantA.createConsumer(sharedTopicName, String.class);
        List<String> receivedA = new CopyOnWriteArrayList<>();

        // Tenant B consumer should only receive tenant B's message
        MessageConsumer<String> consumerB = factoryTenantB.createConsumer(sharedTopicName, String.class);
        List<String> receivedB = new CopyOnWriteArrayList<>();

        Future<Void> subscribeA = consumerA.subscribe(message -> {
            receivedA.add(message.getPayload());
            return Future.succeededFuture();
        });
        Future<Void> subscribeB = consumerB.subscribe(message -> {
            receivedB.add(message.getPayload());
            return Future.succeededFuture();
        });

        Future.all(subscribeA, subscribeB)
                .compose(v -> Future.all(
                        producerA.send("message-from-tenant-a"),
                        producerB.send("message-from-tenant-b")))
                .compose(v -> awaitCondition(
                        vertx,
                        () -> !receivedA.isEmpty() && !receivedB.isEmpty(),
                        "Both tenants should receive messages",
                        100))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(List.of("message-from-tenant-a"), receivedA,
                            "Tenant A should receive exactly its own message");
                    assertEquals(List.of("message-from-tenant-b"), receivedB,
                            "Tenant B should receive exactly its own message");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    private Future<Void> awaitCondition(
            Vertx vertx,
            BooleanSupplier condition,
            String timeoutMessage,
            int remainingAttempts) {
        if (condition.getAsBoolean()) {
            return Future.succeededFuture();
        }
        if (remainingAttempts == 0) {
            return Future.failedFuture(timeoutMessage);
        }
        return vertx.timer(100)
                .compose(v -> awaitCondition(
                        vertx,
                        condition,
                        timeoutMessage,
                        remainingAttempts - 1));
    }
}


