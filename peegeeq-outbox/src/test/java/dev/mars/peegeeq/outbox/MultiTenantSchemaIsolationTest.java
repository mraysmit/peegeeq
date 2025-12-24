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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * Multi-tenant schema isolation tests for Outbox.
 * Verifies that messages sent by one tenant are not visible to another tenant.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class MultiTenantSchemaIsolationTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenantSchemaIsolationTest.class);

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("multitenant_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PeeGeeQManager managerTenantA;
    private PeeGeeQManager managerTenantB;
    private OutboxFactory factoryTenantA;
    private OutboxFactory factoryTenantB;

    @BeforeEach
    void setUp() {
        logger.info("========== SETUP STARTING ==========");
        
        // Initialize two separate tenant schemas
        String schemaTenantA = "tenant_a";
        String schemaTenantB = "tenant_b";

        logger.info("About to initialize schema for Tenant A: {}", schemaTenantA);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaTenantA,
                SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);
        logger.info("Finished initializing schema for Tenant A");

        logger.info("About to initialize schema for Tenant B: {}", schemaTenantB);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaTenantB,
                SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);
        logger.info("Finished initializing schema for Tenant B");

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

        logger.info("========== SETUP COMPLETE ==========");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factoryTenantA != null) factoryTenantA.close();
        if (factoryTenantB != null) factoryTenantB.close();
        if (managerTenantA != null) managerTenantA.close();
        if (managerTenantB != null) managerTenantB.close();
    }

    @Test
    void testMessageIsolationBetweenTenants() throws Exception {
        // Tenant A sends a message
        MessageProducer<String> producerA = factoryTenantA.createProducer("test-topic", String.class);
        producerA.send("tenant-a-message").get(5, TimeUnit.SECONDS);

        // Tenant B creates a consumer - should NOT receive tenant A's message
        MessageConsumer<String> consumerB = factoryTenantB.createConsumer("test-topic", String.class);
        CountDownLatch latchB = new CountDownLatch(1);
        List<String> receivedMessagesB = new ArrayList<>();

        consumerB.subscribe(message -> {
            receivedMessagesB.add(message.getPayload());
            latchB.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait a bit to ensure no messages are received
        boolean receivedB = latchB.await(3, TimeUnit.SECONDS);
        assertFalse(receivedB, "Tenant B should NOT receive tenant A's message");
        assertTrue(receivedMessagesB.isEmpty(), "Tenant B should have no messages");

        // Tenant A creates a consumer - should receive its own message
        MessageConsumer<String> consumerA = factoryTenantA.createConsumer("test-topic", String.class);
        CountDownLatch latchA = new CountDownLatch(1);
        List<String> receivedMessagesA = new ArrayList<>();

        consumerA.subscribe(message -> {
            receivedMessagesA.add(message.getPayload());
            latchA.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Tenant A should receive its own message
        assertTrue(latchA.await(10, TimeUnit.SECONDS), "Tenant A should receive its own message");
        assertEquals(1, receivedMessagesA.size(), "Tenant A should have exactly 1 message");
        assertEquals("tenant-a-message", receivedMessagesA.get(0), "Tenant A should receive correct message");

        consumerA.close();
        consumerB.close();
        producerA.close();
    }

    @Test
    void testStatsIsolationBetweenTenants() throws Exception {
        // Tenant A sends 5 messages
        MessageProducer<String> producerA = factoryTenantA.createProducer("stats-topic", String.class);
        for (int i = 0; i < 5; i++) {
            producerA.send("tenant-a-message-" + i).get(5, TimeUnit.SECONDS);
        }

        // Tenant B sends 3 messages
        MessageProducer<String> producerB = factoryTenantB.createProducer("stats-topic", String.class);
        for (int i = 0; i < 3; i++) {
            producerB.send("tenant-b-message-" + i).get(5, TimeUnit.SECONDS);
        }

        // Verify stats are isolated
        var statsA = factoryTenantA.getStats("stats-topic");
        var statsB = factoryTenantB.getStats("stats-topic");

        assertEquals(5, statsA.getPendingMessages(), "Tenant A should have 5 pending messages");
        assertEquals(3, statsB.getPendingMessages(), "Tenant B should have 3 pending messages");

        producerA.close();
        producerB.close();
    }

    @Test
    void testSameTopicNameAcrossTenants() throws Exception {
        // Both tenants use the same topic name but should be isolated
        String sharedTopicName = "shared-topic-name";

        MessageProducer<String> producerA = factoryTenantA.createProducer(sharedTopicName, String.class);
        MessageProducer<String> producerB = factoryTenantB.createProducer(sharedTopicName, String.class);

        producerA.send("message-from-tenant-a").get(5, TimeUnit.SECONDS);
        producerB.send("message-from-tenant-b").get(5, TimeUnit.SECONDS);

        // Tenant A consumer should only receive tenant A's message
        MessageConsumer<String> consumerA = factoryTenantA.createConsumer(sharedTopicName, String.class);
        CountDownLatch latchA = new CountDownLatch(1);
        List<String> receivedA = new ArrayList<>();

        consumerA.subscribe(message -> {
            receivedA.add(message.getPayload());
            latchA.countDown();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(latchA.await(10, TimeUnit.SECONDS), "Tenant A should receive message");
        assertEquals(1, receivedA.size(), "Tenant A should receive exactly 1 message");
        assertEquals("message-from-tenant-a", receivedA.get(0), "Tenant A should receive its own message");

        // Tenant B consumer should only receive tenant B's message
        MessageConsumer<String> consumerB = factoryTenantB.createConsumer(sharedTopicName, String.class);
        CountDownLatch latchB = new CountDownLatch(1);
        List<String> receivedB = new ArrayList<>();

        consumerB.subscribe(message -> {
            receivedB.add(message.getPayload());
            latchB.countDown();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(latchB.await(10, TimeUnit.SECONDS), "Tenant B should receive message");
        assertEquals(1, receivedB.size(), "Tenant B should receive exactly 1 message");
        assertEquals("message-from-tenant-b", receivedB.get(0), "Tenant B should receive its own message");

        consumerA.close();
        consumerB.close();
        producerA.close();
        producerB.close();
    }
}

