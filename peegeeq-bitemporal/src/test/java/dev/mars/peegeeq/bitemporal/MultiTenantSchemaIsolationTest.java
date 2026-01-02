package dev.mars.peegeeq.bitemporal;

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
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
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

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-tenant schema isolation tests for the bitemporal event store implementation.
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
    private BiTemporalEventStoreFactory factoryTenantA;
    private BiTemporalEventStoreFactory factoryTenantB;

    @BeforeEach
    void setUp() {
        logger.info("========== SETUP STARTING ==========");

        // Initialize two separate tenant schemas
        String schemaTenantA = "tenant_a";
        String schemaTenantB = "tenant_b";

        logger.info("About to initialize schema for Tenant A: {}", schemaTenantA);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaTenantA,
                SchemaComponent.BITEMPORAL);
        logger.info("Finished initializing schema for Tenant A");

        logger.info("About to initialize schema for Tenant B: {}", schemaTenantB);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, schemaTenantB,
                SchemaComponent.BITEMPORAL);
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
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        factoryTenantA = new BiTemporalEventStoreFactory(managerTenantA, objectMapper);
        factoryTenantB = new BiTemporalEventStoreFactory(managerTenantB, objectMapper);

        logger.info("========== SETUP COMPLETED ==========");
    }

    @AfterEach
    void tearDown() {
        logger.info("========== TEARDOWN STARTING ==========");
        if (managerTenantA != null) {
            managerTenantA.close();
        }
        if (managerTenantB != null) {
            managerTenantB.close();
        }

        // Clean up test data to ensure isolation between tests
        PeeGeeQTestSchemaInitializer.cleanupTestData(postgres, "tenant_a", SchemaComponent.BITEMPORAL);
        PeeGeeQTestSchemaInitializer.cleanupTestData(postgres, "tenant_b", SchemaComponent.BITEMPORAL);

        logger.info("========== TEARDOWN COMPLETED ==========");
    }

    @Test
    void testEventIsolationBetweenTenants() throws Exception {
        logger.info("========== TEST: testEventIsolationBetweenTenants ==========");

        // Tenant A appends an event
        EventStore<TestEvent> eventStoreTenantA = factoryTenantA.createEventStore(TestEvent.class, "bitemporal_event_log");
        TestEvent eventA = new TestEvent("tenant-a-id", "tenant-a-data", 100);
        eventStoreTenantA.append("TenantAEvent", eventA, Instant.now()).join();

        // Tenant B creates an event store - should NOT see tenant A's event
        EventStore<TestEvent> eventStoreTenantB = factoryTenantB.createEventStore(TestEvent.class, "bitemporal_event_log");
        List<BiTemporalEvent<TestEvent>> eventsB = eventStoreTenantB.query(EventQuery.all()).join();

        logger.info("Tenant B query returned {} events", eventsB.size());
        for (BiTemporalEvent<TestEvent> event : eventsB) {
            logger.info("  Event: type={}, data={}", event.getEventType(), event.getPayload().getData());
        }

        assertTrue(eventsB.isEmpty(), "Tenant B should NOT see tenant A's events");

        // Tenant A should see its own event
        List<BiTemporalEvent<TestEvent>> eventsA = eventStoreTenantA.query(EventQuery.all()).join();
        assertEquals(1, eventsA.size(), "Tenant A should see exactly 1 event");
        assertEquals("tenant-a-data", eventsA.get(0).getPayload().getData(), "Tenant A should see correct event data");

        logger.info("✅ Event isolation verified");
    }

    @Test
    void testQueryIsolationBetweenTenants() throws Exception {
        logger.info("========== TEST: testQueryIsolationBetweenTenants ==========");

        // Both tenants append events with the same event type
        EventStore<TestEvent> eventStoreTenantA = factoryTenantA.createEventStore(TestEvent.class, "bitemporal_event_log");
        EventStore<TestEvent> eventStoreTenantB = factoryTenantB.createEventStore(TestEvent.class, "bitemporal_event_log");

        TestEvent eventA = new TestEvent("tenant-a-id", "tenant-a-data", 100);
        TestEvent eventB = new TestEvent("tenant-b-id", "tenant-b-data", 200);

        eventStoreTenantA.append("SharedEventType", eventA, Instant.now()).join();
        eventStoreTenantB.append("SharedEventType", eventB, Instant.now()).join();

        // Query by event type - each tenant should only see their own events
        List<BiTemporalEvent<TestEvent>> eventsA = eventStoreTenantA.query(
                EventQuery.forEventType("SharedEventType")
        ).join();
        List<BiTemporalEvent<TestEvent>> eventsB = eventStoreTenantB.query(
                EventQuery.forEventType("SharedEventType")
        ).join();

        assertEquals(1, eventsA.size(), "Tenant A should see exactly 1 event");
        assertEquals(1, eventsB.size(), "Tenant B should see exactly 1 event");
        assertEquals("tenant-a-data", eventsA.get(0).getPayload().getData(), "Tenant A should see its own data");
        assertEquals("tenant-b-data", eventsB.get(0).getPayload().getData(), "Tenant B should see its own data");

        logger.info("✅ Query isolation verified");
    }

    @Test
    void testAggregateIdIsolationBetweenTenants() throws Exception {
        logger.info("========== TEST: testAggregateIdIsolationBetweenTenants ==========");

        // Both tenants use the same aggregate ID
        String sharedAggregateId = "shared-aggregate-123";

        EventStore<TestEvent> eventStoreTenantA = factoryTenantA.createEventStore(TestEvent.class, "bitemporal_event_log");
        EventStore<TestEvent> eventStoreTenantB = factoryTenantB.createEventStore(TestEvent.class, "bitemporal_event_log");

        TestEvent eventA = new TestEvent("tenant-a-id", "tenant-a-data", 100);
        TestEvent eventB = new TestEvent("tenant-b-id", "tenant-b-data", 200);

        eventStoreTenantA.append("AggregateEvent", eventA, Instant.now(), null, null, null, sharedAggregateId).join();
        eventStoreTenantB.append("AggregateEvent", eventB, Instant.now(), null, null, null, sharedAggregateId).join();

        // Query by aggregate ID - each tenant should only see their own events
        List<BiTemporalEvent<TestEvent>> eventsA = eventStoreTenantA.query(
                EventQuery.forAggregate(sharedAggregateId)
        ).join();
        List<BiTemporalEvent<TestEvent>> eventsB = eventStoreTenantB.query(
                EventQuery.forAggregate(sharedAggregateId)
        ).join();

        assertEquals(1, eventsA.size(), "Tenant A should see exactly 1 event for shared aggregate ID");
        assertEquals(1, eventsB.size(), "Tenant B should see exactly 1 event for shared aggregate ID");
        assertEquals("tenant-a-data", eventsA.get(0).getPayload().getData(), "Tenant A should see its own data");
        assertEquals("tenant-b-data", eventsB.get(0).getPayload().getData(), "Tenant B should see its own data");

        logger.info("✅ Aggregate ID isolation verified");
    }
}

