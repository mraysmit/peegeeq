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

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.QueueStats;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-tenant schema isolation tests for the native PostgreSQL queue implementation.
 * 
 * These tests verify that schema-based multi-tenancy works correctly and that
 * tenants are completely isolated from each other.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-22
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class MultiTenantSchemaIsolationTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenantSchemaIsolationTest.class);

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("multitenant_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PeeGeeQManager managerTenantA;
    private PeeGeeQManager managerTenantB;
    private QueueFactory factoryTenantA;
    private QueueFactory factoryTenantB;

    @BeforeEach
    void setUp() {
        System.out.println("========== SETUP STARTING ==========");
        logger.info("🧪 Setting up multi-tenant schema isolation test");

        // Initialize two separate tenant schemas
        String schemaTenantA = "tenant_a";
        String schemaTenantB = "tenant_b";

        System.out.println("About to initialize schema for Tenant A: " + schemaTenantA);
        logger.info("Initializing schema for Tenant A: {}", schemaTenantA);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaTenantA,
            SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);
        System.out.println("Finished initializing schema for Tenant A");

        System.out.println("About to initialize schema for Tenant B: " + schemaTenantB);
        logger.info("Initializing schema for Tenant B: {}", schemaTenantB);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaTenantB,
            SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);
        System.out.println("Finished initializing schema for Tenant B");

        // Verify tables were created in the correct schemas (removed - will verify after factory creation)

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

        // Create factories for each tenant
        PgDatabaseService dbServiceTenantA = new PgDatabaseService(managerTenantA);
        PgDatabaseService dbServiceTenantB = new PgDatabaseService(managerTenantB);

        PgQueueFactoryProvider providerTenantA = new PgQueueFactoryProvider();
        PgQueueFactoryProvider providerTenantB = new PgQueueFactoryProvider();

        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) providerTenantA);
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) providerTenantB);

        factoryTenantA = providerTenantA.createFactory("native", dbServiceTenantA);
        factoryTenantB = providerTenantB.createFactory("native", dbServiceTenantB);

        logger.info("✅ Multi-tenant setup complete");
        System.out.println("========== SETUP COMPLETE ==========");
        System.out.flush();
    }

    @AfterEach
    void tearDown() {
        logger.info("Tearing down multi-tenant test");
        if (managerTenantA != null) {
            managerTenantA.stop();
        }
        if (managerTenantB != null) {
            managerTenantB.stop();
        }
    }

    /**
     * Test 1: Verify that messages sent by one tenant are not visible to another tenant.
     */
    @Test
    void testMessageIsolationBetweenTenants() throws Exception {
        logger.info("🧪 Test 1: Testing message isolation between tenants");

        // Tenant A sends a message
        MessageProducer<String> producerA = factoryTenantA.createProducer("test-queue", String.class);
        producerA.send("tenant-a-message").get(5, TimeUnit.SECONDS);
        logger.info("✅ Tenant A sent message: tenant-a-message");

        // Give a moment for the message to be persisted
        Thread.sleep(500);

        // Tenant B creates a consumer
        MessageConsumer<String> consumerB = factoryTenantB.createConsumer("test-queue", String.class);
        List<String> receivedB = new ArrayList<>();
        CountDownLatch latchB = new CountDownLatch(1);

        consumerB.subscribe(msg -> {
            receivedB.add(msg.getPayload());
            latchB.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for any cross-tenant leakage
        boolean receivedMessage = latchB.await(3, TimeUnit.SECONDS);

        // Verify tenant B did NOT receive tenant A's message
        assertFalse(receivedMessage, "Tenant B should not receive Tenant A's messages");
        assertEquals(0, receivedB.size(), "Tenant B should have 0 messages");

        // Verify tenant A can receive its own message
        MessageConsumer<String> consumerA = factoryTenantA.createConsumer("test-queue", String.class);
        List<String> receivedA = new ArrayList<>();
        CountDownLatch latchA = new CountDownLatch(1);

        consumerA.subscribe(msg -> {
            logger.info("🔔 Tenant A consumer received message: {}", msg.getPayload());
            receivedA.add(msg.getPayload());
            latchA.countDown();
            return CompletableFuture.completedFuture(null);
        });

        logger.info("⏳ Waiting for Tenant A to receive message...");
        boolean receivedByA = latchA.await(10, TimeUnit.SECONDS);
        logger.info("⏱️ Tenant A wait result: {}, received count: {}", receivedByA, receivedA.size());

        assertTrue(receivedByA, "Tenant A should receive its own message");
        assertEquals(1, receivedA.size(), "Tenant A should have 1 message");
        assertEquals("tenant-a-message", receivedA.get(0), "Message content should match");

        logger.info("✅ Test 1: Message isolation verified successfully");
    }

    /**
     * Test 2: Verify that queue statistics are isolated between tenants.
     */
    @Test
    void testStatsIsolationBetweenTenants() throws Exception {
        logger.info("🧪 Test 2: Testing stats isolation between tenants");

        // Tenant A sends 5 messages
        MessageProducer<String> producerA = factoryTenantA.createProducer("stats-queue", String.class);
        for (int i = 0; i < 5; i++) {
            producerA.send("tenant-a-message-" + i).get(5, TimeUnit.SECONDS);
        }
        logger.info("Tenant A sent 5 messages");

        // Tenant B sends 3 messages
        MessageProducer<String> producerB = factoryTenantB.createProducer("stats-queue", String.class);
        for (int i = 0; i < 3; i++) {
            producerB.send("tenant-b-message-" + i).get(5, TimeUnit.SECONDS);
        }
        logger.info("Tenant B sent 3 messages");

        // Get stats for tenant A
        QueueStats statsA = factoryTenantA.getStatsAsync("stats-queue")
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);

        // Get stats for tenant B
        QueueStats statsB = factoryTenantB.getStatsAsync("stats-queue")
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);

        // Verify stats are isolated
        assertEquals(5, statsA.getPendingMessages(), "Tenant A should have 5 pending messages");
        assertEquals(3, statsB.getPendingMessages(), "Tenant B should have 3 pending messages");

        logger.info("✅ Test 2: Stats isolation verified successfully");
    }

    /**
     * Test 3: Verify that queue names can be the same across tenants without collision.
     */
    @Test
    void testSameQueueNameAcrossTenants() throws Exception {
        logger.info("🧪 Test 3: Testing same queue name across tenants");

        String queueName = "shared-queue-name";

        // Both tenants send messages to queues with the same name
        MessageProducer<String> producerA = factoryTenantA.createProducer(queueName, String.class);
        MessageProducer<String> producerB = factoryTenantB.createProducer(queueName, String.class);

        producerA.send("tenant-a-data").get(5, TimeUnit.SECONDS);
        producerB.send("tenant-b-data").get(5, TimeUnit.SECONDS);

        logger.info("Both tenants sent messages to queue: {}", queueName);

        // Tenant A consumes from its queue
        MessageConsumer<String> consumerA = factoryTenantA.createConsumer(queueName, String.class);
        List<String> receivedA = new ArrayList<>();
        CountDownLatch latchA = new CountDownLatch(1);

        consumerA.subscribe(msg -> {
            receivedA.add(msg.getPayload());
            latchA.countDown();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(latchA.await(10, TimeUnit.SECONDS), "Tenant A should receive message");
        assertEquals(1, receivedA.size(), "Tenant A should have 1 message");
        assertEquals("tenant-a-data", receivedA.get(0), "Tenant A should receive its own message");

        // Tenant B consumes from its queue
        MessageConsumer<String> consumerB = factoryTenantB.createConsumer(queueName, String.class);
        List<String> receivedB = new ArrayList<>();
        CountDownLatch latchB = new CountDownLatch(1);

        consumerB.subscribe(msg -> {
            receivedB.add(msg.getPayload());
            latchB.countDown();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(latchB.await(10, TimeUnit.SECONDS), "Tenant B should receive message");
        assertEquals(1, receivedB.size(), "Tenant B should have 1 message");
        assertEquals("tenant-b-data", receivedB.get(0), "Tenant B should receive its own message");

        logger.info("✅ Test 3: Same queue name isolation verified successfully");
    }
}
