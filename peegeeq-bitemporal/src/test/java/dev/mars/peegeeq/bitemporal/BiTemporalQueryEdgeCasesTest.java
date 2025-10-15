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

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for bi-temporal query edge cases and temporal boundary conditions.
 * Validates complex temporal scenarios and query consistency.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-07
 * @version 1.0
 */
@Testcontainers
class BiTemporalQueryEdgeCasesTest {
    private static final Logger logger = LoggerFactory.getLogger(BiTemporalQueryEdgeCasesTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_integration_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory eventStoreFactory;
    private EventStore<OrderEvent> eventStore;
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up bi-temporal query edge cases test...");
        
        // Set system properties for PeeGeeQ configuration - following exact pattern
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.database.pool.max.size", "10");
        System.setProperty("peegeeq.database.pool.min.size", "2");

        // Initialize database schema using centralized schema initializer
        logger.info("Creating bitemporal_event_log table using PeeGeeQTestSchemaInitializer...");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);
        logger.info("bitemporal_event_log table created successfully");

        // Configure PeeGeeQ - following exact pattern
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();

        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
        logger.info("PeeGeeQ Manager started");

        // Create bi-temporal event store - following exact pattern
        eventStoreFactory = new BiTemporalEventStoreFactory(manager);
        eventStore = eventStoreFactory.createEventStore(OrderEvent.class);
        logger.info("Bi-temporal event store created");
        
        logger.info("Bi-temporal query edge cases test setup completed");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down bi-temporal query edge cases test...");
        
        try {
            if (eventStore != null) {
                eventStore.close();
            }
            if (manager != null) {
                manager.close();
            }
        } catch (Exception e) {
            logger.warn("Error during teardown: {}", e.getMessage());
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.database.pool.max.size");
        System.clearProperty("peegeeq.database.pool.min.size");
        
        logger.info("Bi-temporal query edge cases test teardown completed");
    }
    
    @Test
    void testMultipleEventStorage() throws Exception {
        logger.info("Starting multiple event storage test...");

        // Step 1: Create multiple events with different times
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant validTime1 = baseTime.minus(1, ChronoUnit.HOURS);
        Instant validTime2 = baseTime;
        Instant validTime3 = baseTime.plus(1, ChronoUnit.HOURS);

        // Create events with different valid times - using established utility
        OrderEvent event1 = IntegrationTestUtils.createOrderEvent("ORDER-001", "CUST-001", "CREATED", "US", validTime1);
        OrderEvent event2 = IntegrationTestUtils.createOrderEvent("ORDER-002", "CUST-002", "PENDING", "EU", validTime2);
        OrderEvent event3 = IntegrationTestUtils.createOrderEvent("ORDER-003", "CUST-003", "CONFIRMED", "CA", validTime3);

        // Step 2: Append events to store - following exact API pattern
        BiTemporalEvent<OrderEvent> storedEvent1 = eventStore.append(
            "OrderEvent", event1, validTime1, Map.of("test", "boundary"), "test-corr-1", "ORDER-001"
        ).join();
        BiTemporalEvent<OrderEvent> storedEvent2 = eventStore.append(
            "OrderEvent", event2, validTime2, Map.of("test", "boundary"), "test-corr-2", "ORDER-002"
        ).join();
        BiTemporalEvent<OrderEvent> storedEvent3 = eventStore.append(
            "OrderEvent", event3, validTime3, Map.of("test", "boundary"), "test-corr-3", "ORDER-003"
        ).join();

        // Step 3: Validate events were stored
        assertNotNull(storedEvent1);
        assertNotNull(storedEvent2);
        assertNotNull(storedEvent3);

        assertEquals("ORDER-001", storedEvent1.getPayload().getOrderId());
        assertEquals("ORDER-002", storedEvent2.getPayload().getOrderId());
        assertEquals("ORDER-003", storedEvent3.getPayload().getOrderId());

        // Step 4: Query all events to verify storage
        List<BiTemporalEvent<OrderEvent>> allEvents = eventStore.query(EventQuery.all()).join();

        assertTrue(allEvents.size() >= 3, "Should have at least 3 events stored");

        // Step 5: Verify we can find our specific events by correlation ID
        BiTemporalEvent<OrderEvent> foundEvent1 = IntegrationTestUtils.findEventByCorrelationId(allEvents, "test-corr-1");
        BiTemporalEvent<OrderEvent> foundEvent2 = IntegrationTestUtils.findEventByCorrelationId(allEvents, "test-corr-2");
        BiTemporalEvent<OrderEvent> foundEvent3 = IntegrationTestUtils.findEventByCorrelationId(allEvents, "test-corr-3");

        assertNotNull(foundEvent1, "Should find event with correlation ID test-corr-1");
        assertNotNull(foundEvent2, "Should find event with correlation ID test-corr-2");
        assertNotNull(foundEvent3, "Should find event with correlation ID test-corr-3");

        logger.info("✅ Multiple event storage test completed successfully");
    }

    @Test
    void testEventQueryAndRetrieval() throws Exception {
        logger.info("Starting event query and retrieval test...");

        // Step 1: Create a single event - using established utility
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        OrderEvent event = IntegrationTestUtils.createOrderEvent("ORDER-100", "CUST-100", "CREATED", "US", baseTime);

        BiTemporalEvent<OrderEvent> storedEvent = eventStore.append(
            "OrderEvent", event, baseTime, Map.of("test", "query-retrieval"), "test-corr-100", "ORDER-100"
        ).join();
        assertNotNull(storedEvent);

        // Step 2: Query all events to verify storage
        List<BiTemporalEvent<OrderEvent>> allEvents = eventStore.query(EventQuery.all()).join();

        assertTrue(allEvents.size() >= 1, "Should have at least 1 event stored");

        // Step 3: Find our specific event by correlation ID
        BiTemporalEvent<OrderEvent> foundEvent = IntegrationTestUtils.findEventByCorrelationId(allEvents, "test-corr-100");

        assertNotNull(foundEvent, "Should find event with correlation ID test-corr-100");
        assertEquals("ORDER-100", foundEvent.getPayload().getOrderId());
        assertEquals("test-corr-100", foundEvent.getCorrelationId());
        assertEquals("ORDER-100", foundEvent.getAggregateId());
        assertEquals("OrderEvent", foundEvent.getEventType());

        // Step 4: Verify temporal properties
        assertNotNull(foundEvent.getValidTime(), "Valid time should be set");
        assertNotNull(foundEvent.getTransactionTime(), "Transaction time should be set");
        assertTrue(foundEvent.getTransactionTime().isAfter(foundEvent.getValidTime()) ||
                  foundEvent.getTransactionTime().equals(foundEvent.getValidTime()),
                  "Transaction time should be >= valid time");

        logger.info("✅ Event query and retrieval test completed successfully");
    }
}
