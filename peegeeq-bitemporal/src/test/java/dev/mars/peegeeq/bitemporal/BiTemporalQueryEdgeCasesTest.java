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
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

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
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class BiTemporalQueryEdgeCasesTest {
    private static final Logger logger = LoggerFactory.getLogger(BiTemporalQueryEdgeCasesTest.class);
    
    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_integration_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }
    
    private Vertx vertx;
    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory eventStoreFactory;
    private EventStore<OrderEvent> eventStore;
    
    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
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
        manager.start()
                .map(v -> {
                    logger.info("PeeGeeQ Manager started");
                    eventStoreFactory = new BiTemporalEventStoreFactory(vertx, manager);
                    eventStore = eventStoreFactory.createEventStore(OrderEvent.class, "bitemporal_event_log");
                    logger.info("Bi-temporal event store created");
                    logger.info("Bi-temporal query edge cases test setup completed");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down bi-temporal query edge cases test...");

        Future<Void> closeStoreFuture = eventStore != null ? eventStore.close() : Future.succeededFuture();
        Future<Void> closeManagerFuture = manager != null ? manager.closeReactive() : Future.succeededFuture();

        closeStoreFuture
                .recover(error -> {
                    logger.warn("Error closing event store: {}", error.getMessage());
                    return Future.succeededFuture();
                })
                .compose(v -> closeManagerFuture.recover(error -> {
                    logger.warn("Error closing manager: {}", error.getMessage());
                    return Future.succeededFuture();
                }))
                .onSuccess(v -> {
                    System.clearProperty("peegeeq.database.host");
                    System.clearProperty("peegeeq.database.port");
                    System.clearProperty("peegeeq.database.name");
                    System.clearProperty("peegeeq.database.username");
                    System.clearProperty("peegeeq.database.password");
                    System.clearProperty("peegeeq.database.ssl.enabled");
                    System.clearProperty("peegeeq.database.pool.max.size");
                    System.clearProperty("peegeeq.database.pool.min.size");
                    logger.info("Bi-temporal query edge cases test teardown completed");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testMultipleEventStorage(VertxTestContext testContext) {
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
        eventStore.appendBuilder().eventType("OrderEvent").payload(event1).validTime(validTime1)
            .headers(Map.of("test", "boundary")).correlationId("test-corr-1").causationId(null)
            .aggregateId("ORDER-001").execute()
            .compose(storedEvent1 -> eventStore.appendBuilder().eventType("OrderEvent").payload(event2).validTime(validTime2)
                .headers(Map.of("test", "boundary")).correlationId("test-corr-2").causationId(null)
                .aggregateId("ORDER-002").execute()
                .map(storedEvent2 -> List.of(storedEvent1, storedEvent2)))
            .compose(storedEvents -> eventStore.appendBuilder().eventType("OrderEvent").payload(event3).validTime(validTime3)
                .headers(Map.of("test", "boundary")).correlationId("test-corr-3").causationId(null)
                .aggregateId("ORDER-003").execute()
                .map(storedEvent3 -> List.of(storedEvents.get(0), storedEvents.get(1), storedEvent3)))
            .compose(storedEvents -> eventStore.query(EventQuery.all()).map(allEvents -> Map.entry(storedEvents, allEvents)))
            .onSuccess(result -> testContext.verify(() -> {
                List<BiTemporalEvent<OrderEvent>> storedEvents = result.getKey();
                List<BiTemporalEvent<OrderEvent>> allEvents = result.getValue();
                assertNotNull(storedEvents.get(0));
                assertNotNull(storedEvents.get(1));
                assertNotNull(storedEvents.get(2));
                assertEquals("ORDER-001", storedEvents.get(0).getPayload().getOrderId());
                assertEquals("ORDER-002", storedEvents.get(1).getPayload().getOrderId());
                assertEquals("ORDER-003", storedEvents.get(2).getPayload().getOrderId());
                assertTrue(allEvents.size() >= 3, "Should have at least 3 events stored");
                assertNotNull(IntegrationTestUtils.findEventByCorrelationId(allEvents, "test-corr-1"), "Should find event with correlation ID test-corr-1");
                assertNotNull(IntegrationTestUtils.findEventByCorrelationId(allEvents, "test-corr-2"), "Should find event with correlation ID test-corr-2");
                assertNotNull(IntegrationTestUtils.findEventByCorrelationId(allEvents, "test-corr-3"), "Should find event with correlation ID test-corr-3");
                logger.info("Multiple event storage test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
        void testEventQueryAndRetrieval(VertxTestContext testContext) {
        logger.info("Starting event query and retrieval test...");

        // Step 1: Create a single event - using established utility
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        OrderEvent event = IntegrationTestUtils.createOrderEvent("ORDER-100", "CUST-100", "CREATED", "US", baseTime);

        eventStore.appendBuilder().eventType("OrderEvent").payload(event).validTime(baseTime)
            .headers(Map.of("test", "query-retrieval")).correlationId("test-corr-100")
            .causationId(null).aggregateId("ORDER-100").execute()
            .compose(storedEvent -> eventStore.query(EventQuery.all()).map(allEvents -> Map.entry(storedEvent, allEvents)))
            .onSuccess(result -> testContext.verify(() -> {
                BiTemporalEvent<OrderEvent> storedEvent = result.getKey();
                List<BiTemporalEvent<OrderEvent>> allEvents = result.getValue();
                assertNotNull(storedEvent);
                assertTrue(allEvents.size() >= 1, "Should have at least 1 event stored");
                BiTemporalEvent<OrderEvent> foundEvent = IntegrationTestUtils.findEventByCorrelationId(allEvents, "test-corr-100");
                assertNotNull(foundEvent, "Should find event with correlation ID test-corr-100");
                assertEquals("ORDER-100", foundEvent.getPayload().getOrderId());
                assertEquals("test-corr-100", foundEvent.getCorrelationId());
                assertEquals("ORDER-100", foundEvent.getAggregateId());
                assertEquals("OrderEvent", foundEvent.getEventType());
                assertNotNull(foundEvent.getValidTime(), "Valid time should be set");
                assertNotNull(foundEvent.getTransactionTime(), "Transaction time should be set");
                assertTrue(foundEvent.getTransactionTime().isAfter(foundEvent.getValidTime()) ||
                        foundEvent.getTransactionTime().equals(foundEvent.getValidTime()),
                    "Transaction time should be >= valid time");
                logger.info("Event query and retrieval test completed successfully");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}



