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
import io.vertx.junit5.Checkpoint;
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
import java.util.concurrent.TimeUnit;

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
    private final List<AutoCloseable> resources = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
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
        managerTenantA.start();

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
        managerTenantB.start();

        // Create factories for both tenants
        factoryTenantA = new OutboxFactory(new PgDatabaseService(managerTenantA), configTenantA);
        factoryTenantB = new OutboxFactory(new PgDatabaseService(managerTenantB), configTenantB);
    }

    @AfterEach
    void tearDown(VertxTestContext tearDownContext) throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
        resources.clear();
        if (factoryTenantA != null) factoryTenantA.close();
        if (factoryTenantB != null) factoryTenantB.close();
        Future<Void> closeChain = Future.succeededFuture();
        if (managerTenantA != null) {
            closeChain = closeChain.compose(v -> managerTenantA.closeReactive());
        }
        if (managerTenantB != null) {
            closeChain = closeChain.compose(v -> managerTenantB.closeReactive());
        }
        closeChain
                .onSuccess(v -> tearDownContext.completeNow())
                .onFailure(tearDownContext::failNow);
        assertTrue(tearDownContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testMessageIsolationBetweenTenants(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Tenant A sends a message
        MessageProducer<String> producerA = factoryTenantA.createProducer("test-topic", String.class);
        resources.add(producerA);
        producerA.send("tenant-a-message").onFailure(testContext::failNow);

        // Tenant B creates a consumer - should NOT receive tenant A's message
        MessageConsumer<String> consumerB = factoryTenantB.createConsumer("test-topic", String.class);
        resources.add(consumerB);
        List<String> receivedMessagesB = new CopyOnWriteArrayList<>();

        consumerB.subscribe(message -> {
            receivedMessagesB.add(message.getPayload());
            return Future.succeededFuture();
        });

        List<String> receivedMessagesA = new CopyOnWriteArrayList<>();
        Checkpoint messageReceivedA = testContext.checkpoint();

        // Wait 3 seconds to ensure Tenant B has not received Tenant A's message,
        // then create Tenant A's consumer
        vertx.timer(3000)
            .compose(timerId -> {
                testContext.verify(() -> assertTrue(receivedMessagesB.isEmpty(), "Tenant B should have no messages"));

                MessageConsumer<String> consumerA = factoryTenantA.createConsumer("test-topic", String.class);
                resources.add(consumerA);

                return consumerA.subscribe(message -> {
                    receivedMessagesA.add(message.getPayload());
                    if (receivedMessagesA.size() == 1) {
                        testContext.verify(() -> {
                            assertEquals("tenant-a-message", receivedMessagesA.get(0), "Tenant A should receive correct message");
                            messageReceivedA.flag();
                        });
                    }
                    return Future.succeededFuture();
                });
            })
            .onFailure(testContext::failNow);

        // Tenant A should receive its own message
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Tenant A should receive its own message");
        assertEquals(1, receivedMessagesA.size(), "Tenant A should have exactly 1 message");
    }

    @Test
    void testStatsIsolationBetweenTenants(VertxTestContext testContext) {
        // Tenant A sends 5 messages, sequencing sends via compose to ensure persistence ordering
        MessageProducer<String> producerA = factoryTenantA.createProducer("stats-topic", String.class);
        resources.add(producerA);
        Future<Void> chainA = Future.succeededFuture();
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            chainA = chainA.compose(v -> producerA.send("tenant-a-message-" + idx));
        }

        // Tenant B sends 3 messages
        MessageProducer<String> producerB = factoryTenantB.createProducer("stats-topic", String.class);
        resources.add(producerB);
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
    void testSameTopicNameAcrossTenants(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Both tenants use the same topic name but should be isolated
        String sharedTopicName = "shared-topic-name";

        MessageProducer<String> producerA = factoryTenantA.createProducer(sharedTopicName, String.class);
        resources.add(producerA);
        MessageProducer<String> producerB = factoryTenantB.createProducer(sharedTopicName, String.class);
        resources.add(producerB);

        producerA.send("message-from-tenant-a").onFailure(testContext::failNow);
        producerB.send("message-from-tenant-b").onFailure(testContext::failNow);

        // Use a shared context with 2 checkpoints (one per tenant)
        Checkpoint tenantAReceived = testContext.checkpoint();
        Checkpoint tenantBReceived = testContext.checkpoint();

        // Tenant A consumer should only receive tenant A's message
        MessageConsumer<String> consumerA = factoryTenantA.createConsumer(sharedTopicName, String.class);
        resources.add(consumerA);
        List<String> receivedA = new CopyOnWriteArrayList<>();

        consumerA.subscribe(message -> {
            receivedA.add(message.getPayload());
            if (receivedA.size() == 1) {
                tenantAReceived.flag();
            }
            return Future.succeededFuture();
        });

        // Tenant B consumer should only receive tenant B's message
        MessageConsumer<String> consumerB = factoryTenantB.createConsumer(sharedTopicName, String.class);
        resources.add(consumerB);
        List<String> receivedB = new CopyOnWriteArrayList<>();

        consumerB.subscribe(message -> {
            receivedB.add(message.getPayload());
            if (receivedB.size() == 1) {
                tenantBReceived.flag();
            }
            return Future.succeededFuture();
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Both tenants should receive messages");
        assertEquals(1, receivedA.size(), "Tenant A should receive exactly 1 message");
        assertEquals("message-from-tenant-a", receivedA.get(0), "Tenant A should receive its own message");
        assertEquals(1, receivedB.size(), "Tenant B should receive exactly 1 message");
        assertEquals("message-from-tenant-b", receivedB.get(0), "Tenant B should receive its own message");
    }
}



