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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

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
@ExtendWith(VertxExtension.class)
@Testcontainers
class MultiTenantSchemaIsolationTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiTenantSchemaIsolationTest.class);

    @Container
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("multitenant_test")
                .withUsername("test_user")
                .withPassword("test_pass");
        return container;
    }

    private Vertx vertx;
    private PeeGeeQManager managerTenantA;
    private PeeGeeQManager managerTenantB;
    private BiTemporalEventStoreFactory factoryTenantA;
    private BiTemporalEventStoreFactory factoryTenantB;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
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

        // Create factories for both tenants
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        managerTenantA.start()
                .compose(v -> managerTenantB.start())
                .map(v -> {
                    factoryTenantA = new BiTemporalEventStoreFactory(vertx, managerTenantA, objectMapper);
                    factoryTenantB = new BiTemporalEventStoreFactory(vertx, managerTenantB, objectMapper);
                    logger.info("========== SETUP COMPLETED ==========");
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("========== TEARDOWN STARTING ==========");

        Future<Void> closeTenantAFuture = managerTenantA != null ? managerTenantA.closeReactive() : Future.succeededFuture();
        Future<Void> closeTenantBFuture = managerTenantB != null ? managerTenantB.closeReactive() : Future.succeededFuture();

        // Clean up test data to ensure isolation between tests
        closeTenantAFuture
                .recover(error -> Future.<Void>succeededFuture())
                .compose(v -> closeTenantBFuture.recover(error -> Future.<Void>succeededFuture()))
                .onSuccess(v -> {
                    PeeGeeQTestSchemaInitializer.cleanupTestData(postgres, "tenant_a", SchemaComponent.BITEMPORAL);
                    PeeGeeQTestSchemaInitializer.cleanupTestData(postgres, "tenant_b", SchemaComponent.BITEMPORAL);
                    logger.info("========== TEARDOWN COMPLETED ==========");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void testEventIsolationBetweenTenants(VertxTestContext testContext) {
        logger.info("========== TEST: testEventIsolationBetweenTenants ==========");

        // Tenant A appends an event
        EventStore<TestEvent> eventStoreTenantA = factoryTenantA.createEventStore(TestEvent.class, "bitemporal_event_log");
        TestEvent eventA = new TestEvent("tenant-a-id", "tenant-a-data", 100);
        EventStore<TestEvent> eventStoreTenantB = factoryTenantB.createEventStore(TestEvent.class, "bitemporal_event_log");
        eventStoreTenantA.appendBuilder()
                .eventType("TenantAEvent")
                .payload(eventA)
                .validTime(Instant.now())
                .execute()
                .compose(v -> eventStoreTenantB.query(EventQuery.all()))
                .compose(eventsB -> {
                    logger.info("Tenant B query returned {} events", eventsB.size());
                    for (BiTemporalEvent<TestEvent> event : eventsB) {
                        logger.info("  Event: type={}, data={}", event.getEventType(), event.getPayload().getData());
                    }
                    testContext.verify(() -> assertTrue(eventsB.isEmpty(), "Tenant B should NOT see tenant A's events"));
                    return eventStoreTenantA.query(EventQuery.all()).map(eventsA -> List.of(eventsB, eventsA));
                })
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TestEvent>> eventsA = (List<BiTemporalEvent<TestEvent>>) result.get(1);
                    assertEquals(1, eventsA.size(), "Tenant A should see exactly 1 event");
                    assertEquals("tenant-a-data", eventsA.get(0).getPayload().getData(), "Tenant A should see correct event data");
                    logger.info("Event isolation verified");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testQueryIsolationBetweenTenants(VertxTestContext testContext) {
        logger.info("========== TEST: testQueryIsolationBetweenTenants ==========");

        // Both tenants append events with the same event type
        EventStore<TestEvent> eventStoreTenantA = factoryTenantA.createEventStore(TestEvent.class, "bitemporal_event_log");
        EventStore<TestEvent> eventStoreTenantB = factoryTenantB.createEventStore(TestEvent.class, "bitemporal_event_log");

        TestEvent eventA = new TestEvent("tenant-a-id", "tenant-a-data", 100);
        TestEvent eventB = new TestEvent("tenant-b-id", "tenant-b-data", 200);

        eventStoreTenantA.appendBuilder()
                .eventType("SharedEventType")
                .payload(eventA)
                .validTime(Instant.now())
                .execute()
                .compose(v -> eventStoreTenantB.appendBuilder()
                        .eventType("SharedEventType")
                        .payload(eventB)
                        .validTime(Instant.now())
                        .execute())
                .compose(v -> eventStoreTenantA.query(EventQuery.forEventType("SharedEventType")))
                .compose(eventsA -> eventStoreTenantB.query(EventQuery.forEventType("SharedEventType"))
                        .map(eventsB -> List.of(eventsA, eventsB)))
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TestEvent>> eventsA = (List<BiTemporalEvent<TestEvent>>) result.get(0);
                    List<BiTemporalEvent<TestEvent>> eventsB = (List<BiTemporalEvent<TestEvent>>) result.get(1);
                    assertEquals(1, eventsA.size(), "Tenant A should see exactly 1 event");
                    assertEquals(1, eventsB.size(), "Tenant B should see exactly 1 event");
                    assertEquals("tenant-a-data", eventsA.get(0).getPayload().getData(), "Tenant A should see its own data");
                    assertEquals("tenant-b-data", eventsB.get(0).getPayload().getData(), "Tenant B should see its own data");
                    logger.info("Query isolation verified");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testAggregateIdIsolationBetweenTenants(VertxTestContext testContext) {
        logger.info("========== TEST: testAggregateIdIsolationBetweenTenants ==========");

        // Both tenants use the same aggregate ID
        String sharedAggregateId = "shared-aggregate-123";

        EventStore<TestEvent> eventStoreTenantA = factoryTenantA.createEventStore(TestEvent.class, "bitemporal_event_log");
        EventStore<TestEvent> eventStoreTenantB = factoryTenantB.createEventStore(TestEvent.class, "bitemporal_event_log");

        TestEvent eventA = new TestEvent("tenant-a-id", "tenant-a-data", 100);
        TestEvent eventB = new TestEvent("tenant-b-id", "tenant-b-data", 200);

        eventStoreTenantA.appendBuilder()
                .eventType("AggregateEvent")
                .payload(eventA)
                .validTime(Instant.now())
                .aggregateId(sharedAggregateId)
                .execute()
                .compose(v -> eventStoreTenantB.appendBuilder()
                        .eventType("AggregateEvent")
                        .payload(eventB)
                        .validTime(Instant.now())
                        .aggregateId(sharedAggregateId)
                        .execute())
                .compose(v -> eventStoreTenantA.query(EventQuery.forAggregate(sharedAggregateId)))
                .compose(eventsA -> eventStoreTenantB.query(EventQuery.forAggregate(sharedAggregateId))
                        .map(eventsB -> List.of(eventsA, eventsB)))
                .onSuccess(result -> testContext.verify(() -> {
                    List<BiTemporalEvent<TestEvent>> eventsA = (List<BiTemporalEvent<TestEvent>>) result.get(0);
                    List<BiTemporalEvent<TestEvent>> eventsB = (List<BiTemporalEvent<TestEvent>>) result.get(1);
                    assertEquals(1, eventsA.size(), "Tenant A should see exactly 1 event for shared aggregate ID");
                    assertEquals(1, eventsB.size(), "Tenant B should see exactly 1 event for shared aggregate ID");
                    assertEquals("tenant-a-data", eventsA.get(0).getPayload().getData(), "Tenant A should see its own data");
                    assertEquals("tenant-b-data", eventsB.get(0).getPayload().getData(), "Tenant B should see its own data");
                    logger.info("Aggregate ID isolation verified");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}




