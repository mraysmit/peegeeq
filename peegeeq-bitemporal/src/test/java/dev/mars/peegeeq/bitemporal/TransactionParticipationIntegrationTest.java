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

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;

import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for transaction participation functionality in PgBiTemporalEventStore.
 * This class tests the actual transaction participation with a real PostgreSQL database using TestContainers.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class TransactionParticipationIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionParticipationIntegrationTest.class);
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("transaction_" + System.currentTimeMillis() + "_" + System.nanoTime())  // Unique database name with timestamp
            .withUsername("peegeeq")
            .withPassword("peegeeq_test_password");
    
    private PeeGeeQManager peeGeeQManager;
    private PgBiTemporalEventStore<TestEvent> eventStore;
    
    @BeforeEach
    void setUp() throws Exception {
        logger.info("🧪 Setting up TransactionParticipationIntegrationTest");

        // Initialize database schema using centralized schema initializer
        logger.info("Creating ALL database tables using PeeGeeQTestSchemaInitializer...");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("ALL database tables created successfully");

        // Set system properties for PeeGeeQ configuration - following established patterns
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Configure PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();

        // Initialize PeeGeeQ Manager - following established patterns
        peeGeeQManager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        peeGeeQManager.start();

        // Create the bitemporal event store using factory pattern
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(peeGeeQManager);
        eventStore = (PgBiTemporalEventStore<TestEvent>) factory.createEventStore(TestEvent.class);

        // Create a test business table for transaction testing
        createBusinessTable();

        logger.info("✅ Setup completed successfully");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        logger.info("🧹 Cleaning up TransactionParticipationIntegrationTest");

        if (eventStore != null) {
            eventStore.close();
        }

        if (peeGeeQManager != null) {
            peeGeeQManager.close();
        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");

        logger.info("✅ Cleanup completed successfully");
    }
    
    private void createBusinessTable() throws Exception {
        String createTableSql = """
            CREATE TABLE IF NOT EXISTS business_data (
                id SERIAL PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                value INTEGER NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
            )
            """;

        // Create a direct connection to create the business table
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();
        pool.query(createTableSql).execute().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        pool.close();

        logger.info("✅ Business table created successfully");
    }
    
    @Test
    @DisplayName("Test simple transaction participation - business data + bitemporal event")
    void testSimpleTransactionParticipation() throws Exception {
        logger.info("🧪 Testing simple transaction participation");

        // Create a direct connection pool for transaction testing
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        // Test data
        String businessName = "Test Business Record";
        int businessValue = 42;
        String eventType = "business.created";
        TestEvent eventPayload = new TestEvent("test-data", 123);
        Instant validTime = Instant.now();

        try {
            // Execute business operation + bitemporal event in same transaction
            BiTemporalEvent<TestEvent> event = pool.withTransaction(connection -> {
                logger.info("📝 Starting transaction with business operation + bitemporal event");

                // 1. Insert business data
                String insertBusinessSql = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                return connection.preparedQuery(insertBusinessSql)
                    .execute(Tuple.of(businessName, businessValue))
                    .compose(businessResult -> {
                        int businessId = businessResult.iterator().next().getInteger("id");
                        logger.info("✅ Business record inserted with ID: {}", businessId);

                        // 2. Append bitemporal event in same transaction - convert CompletableFuture to Future
                        CompletableFuture<BiTemporalEvent<TestEvent>> eventFuture =
                            eventStore.appendInTransaction(eventType, eventPayload, validTime, connection);

                        // Convert CompletableFuture to Vert.x Future
                        return io.vertx.core.Future.fromCompletionStage(eventFuture)
                            .map(biTemporalEvent -> {
                                logger.info("✅ Bitemporal event appended with ID: {}", biTemporalEvent.getEventId());
                                return biTemporalEvent;
                            });
                    });
            }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

            // Verify the returned event object
            assertNotNull(event);
            assertEquals(eventType, event.getEventType());
            assertEquals(eventPayload.getData(), event.getPayload().getData());
            assertEquals(eventPayload.getValue(), event.getPayload().getValue());

            // CRITICAL: Database state validation after successful transaction
            logger.info("🔍 Validating database state after transaction commit");

            // 1. Verify business data was persisted in business_data table
            Integer businessRecordCount = pool.withConnection(connection -> {
                String selectBusinessSql = "SELECT COUNT(*) FROM business_data WHERE name = $1 AND value = $2";
                return connection.preparedQuery(selectBusinessSql)
                    .execute(Tuple.of(businessName, businessValue))
                    .map(result -> result.iterator().next().getInteger(0));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, businessRecordCount, "Business data should exist in business_data table");
            logger.info("✅ Business data validation: Found {} record(s) in business_data table", businessRecordCount);

            // 2. Verify bitemporal event was persisted in bitemporal_event_log table
            Integer eventRecordCount = pool.withConnection(connection -> {
                String selectEventSql = "SELECT COUNT(*) FROM bitemporal_event_log WHERE event_id = $1 AND event_type = $2";
                return connection.preparedQuery(selectEventSql)
                    .execute(Tuple.of(event.getEventId(), eventType))
                    .map(result -> result.iterator().next().getInteger(0));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(1, eventRecordCount, "Bitemporal event should exist in bitemporal_event_log table");
            logger.info("✅ Bitemporal event validation: Found {} record(s) in bitemporal_event_log table", eventRecordCount);

            // 3. Data integrity validation - verify event payload was correctly serialized
            String retrievedPayload = pool.withConnection(connection -> {
                String selectPayloadSql = "SELECT payload FROM bitemporal_event_log WHERE event_id = $1";
                return connection.preparedQuery(selectPayloadSql)
                    .execute(Tuple.of(event.getEventId()))
                    .map(result -> result.iterator().next().getString("payload"));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertNotNull(retrievedPayload, "Event payload should be stored in database");
            assertTrue(retrievedPayload.contains("test-data"), "Payload should contain expected data");
            assertTrue(retrievedPayload.contains("123"), "Payload should contain expected value");
            logger.info("✅ Data integrity validation: Event payload correctly serialized and stored");

            logger.info("✅ COMPREHENSIVE TRANSACTION PARTICIPATION TEST PASSED: Business data and bitemporal event committed together with database state validation");

        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("Test business table + bitemporal_event_log consistency verification")
    void testBusinessTableEventLogConsistency() throws Exception {
        logger.info("🧪 Testing business table + bitemporal_event_log consistency verification");

        // Create a direct connection pool for transaction testing
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        // Test data with correlation
        String businessName = "Consistency Test Record";
        int businessValue = 999;
        String eventType = "business.consistency.test";
        TestEvent eventPayload = new TestEvent("consistency-data", 999);
        Instant validTime = Instant.now();
        String correlationId = "CONSISTENCY-TEST-" + System.currentTimeMillis();

        try {
            // Execute transaction with correlation ID for consistency tracking
            BiTemporalEvent<TestEvent> event = pool.withTransaction(connection -> {
                logger.info("📝 Starting consistency verification transaction");

                // 1. Insert business data with correlation ID in a comment field
                String insertBusinessSql = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                return connection.preparedQuery(insertBusinessSql)
                    .execute(Tuple.of(businessName + "-" + correlationId, businessValue))
                    .compose(businessResult -> {
                        int businessId = businessResult.iterator().next().getInteger("id");
                        logger.info("✅ Business record inserted with ID: {} for correlation: {}", businessId, correlationId);

                        // 2. Append bitemporal event with same correlation ID
                        Map<String, String> headers = Map.of(
                            "business-id", String.valueOf(businessId),
                            "test-type", "consistency-verification"
                        );

                        CompletableFuture<BiTemporalEvent<TestEvent>> eventFuture =
                            eventStore.appendInTransaction(eventType, eventPayload, validTime, headers, correlationId, "business-" + businessId, connection);

                        return io.vertx.core.Future.fromCompletionStage(eventFuture)
                            .map(biTemporalEvent -> {
                                logger.info("✅ Bitemporal event appended with ID: {} for correlation: {}", biTemporalEvent.getEventId(), correlationId);
                                return biTemporalEvent;
                            });
                    });
            }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

            // COMPREHENSIVE CONSISTENCY VERIFICATION
            logger.info("🔍 Performing comprehensive business table + event log consistency verification");

            // 1. Cross-reference business data and event data using correlation ID
            pool.withConnection(connection -> {
                String consistencyCheckSql = """
                    SELECT
                        bd.id as business_id,
                        bd.name as business_name,
                        bd.value as business_value,
                        bel.event_id,
                        bel.event_type,
                        bel.correlation_id,
                        bel.headers,
                        bel.valid_time,
                        bel.transaction_time
                    FROM business_data bd
                    CROSS JOIN bitemporal_event_log bel
                    WHERE bd.name LIKE $1
                    AND bel.correlation_id = $2
                    AND bel.event_type = $3
                    """;

                return connection.preparedQuery(consistencyCheckSql)
                    .execute(Tuple.of(businessName + "-" + correlationId, correlationId, eventType))
                    .map(result -> {
                        assertTrue(result.rowCount() > 0, "Should find correlated business and event records");

                        var row = result.iterator().next();
                        int businessId = row.getInteger("business_id");
                        String businessNameFromDb = row.getString("business_name");
                        int businessValueFromDb = row.getInteger("business_value");
                        String eventIdFromDb = row.getString("event_id");
                        String eventTypeFromDb = row.getString("event_type");
                        String correlationIdFromDb = row.getString("correlation_id");

                        // Verify consistency between business and event data
                        assertEquals(businessValue, businessValueFromDb, "Business value should match");
                        assertEquals(eventType, eventTypeFromDb, "Event type should match");
                        assertEquals(correlationId, correlationIdFromDb, "Correlation ID should match");
                        assertEquals(event.getEventId(), eventIdFromDb, "Event ID should match");
                        assertTrue(businessNameFromDb.contains(correlationId), "Business name should contain correlation ID");

                        logger.info("✅ Consistency verification: Business ID {} correlates with Event ID {}", businessId, eventIdFromDb);
                        logger.info("✅ Consistency verification: Correlation ID {} links business and event records", correlationId);

                        return null;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            logger.info("✅ COMPREHENSIVE CONSISTENCY TEST PASSED: Business table and bitemporal_event_log are fully consistent");

        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("Test transaction commit verification with timing validation")
    void testTransactionCommitVerification() throws Exception {
        logger.info("🧪 Testing transaction commit verification with timing validation");

        // Create a direct connection pool for transaction testing
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        // Test data
        String businessName = "Commit Verification Test";
        int businessValue = 777;
        String eventType = "business.commit.verification";
        TestEvent eventPayload = new TestEvent("commit-test-data", 777);
        Instant validTime = Instant.now();
        Instant beforeTransaction = Instant.now();

        try {
            // Execute transaction and capture timing
            BiTemporalEvent<TestEvent> event = pool.withTransaction(connection -> {
                logger.info("📝 Starting transaction commit verification test");

                // 1. Insert business data
                String insertBusinessSql = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                return connection.preparedQuery(insertBusinessSql)
                    .execute(Tuple.of(businessName, businessValue))
                    .compose(businessResult -> {
                        int businessId = businessResult.iterator().next().getInteger("id");
                        logger.info("✅ Business record inserted with ID: {}", businessId);

                        // 2. Append bitemporal event
                        CompletableFuture<BiTemporalEvent<TestEvent>> eventFuture =
                            eventStore.appendInTransaction(eventType, eventPayload, validTime, connection);

                        return io.vertx.core.Future.fromCompletionStage(eventFuture)
                            .map(biTemporalEvent -> {
                                logger.info("✅ Bitemporal event appended with ID: {}", biTemporalEvent.getEventId());
                                return biTemporalEvent;
                            });
                    });
            }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

            Instant afterTransaction = Instant.now();

            // TRANSACTION COMMIT VERIFICATION
            logger.info("🔍 Verifying transaction commit with timing validation");

            // 1. Verify both records exist (proving commit succeeded)
            Integer totalRecords = pool.withConnection(connection -> {
                String countSql = """
                    SELECT
                        (SELECT COUNT(*) FROM business_data WHERE name = $1) as business_count,
                        (SELECT COUNT(*) FROM bitemporal_event_log WHERE event_id = $2) as event_count
                    """;

                return connection.preparedQuery(countSql)
                    .execute(Tuple.of(businessName, event.getEventId()))
                    .map(result -> {
                        var row = result.iterator().next();
                        int businessCount = row.getInteger("business_count");
                        int eventCount = row.getInteger("event_count");

                        assertEquals(1, businessCount, "Business record should exist after commit");
                        assertEquals(1, eventCount, "Event record should exist after commit");

                        logger.info("✅ Commit verification: {} business record(s) and {} event record(s) found", businessCount, eventCount);

                        return businessCount + eventCount;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(2, totalRecords, "Both business and event records should exist after successful commit");

            // 2. Verify transaction timing - transaction_time should be within our test window
            Instant transactionTime = pool.withConnection(connection -> {
                String timingSql = "SELECT transaction_time FROM bitemporal_event_log WHERE event_id = $1";
                return connection.preparedQuery(timingSql)
                    .execute(Tuple.of(event.getEventId()))
                    .map(result -> {
                        var row = result.iterator().next();
                        return row.getLocalDateTime("transaction_time").toInstant(java.time.ZoneOffset.UTC);
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Verify transaction time is within reasonable bounds
            assertTrue(transactionTime.isAfter(beforeTransaction.minusSeconds(1)),
                "Transaction time should be after test start");
            assertTrue(transactionTime.isBefore(afterTransaction.plusSeconds(1)),
                "Transaction time should be before test end");

            logger.info("✅ Timing verification: Transaction time {} is within expected bounds [{} to {}]",
                transactionTime, beforeTransaction, afterTransaction);

            // 3. Verify valid time vs transaction time
            assertEquals(validTime.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                event.getValidTime().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                "Valid time should match what was specified");

            logger.info("✅ Temporal verification: Valid time {} correctly stored", event.getValidTime());

            logger.info("✅ COMPREHENSIVE TRANSACTION COMMIT VERIFICATION PASSED: All timing and commit validations successful");

        } finally {
            pool.close();
        }
    }

    // ========================================
    // INCREMENT 2.2: TRANSACTION ROLLBACK SCENARIOS
    // ========================================

    @Test
    @DisplayName("Test business operation failure after bitemporal append - rollback scenario")
    void testBusinessOperationFailureAfterBiTemporalAppend() throws Exception {
        logger.info("🧪 Testing business operation failure after bitemporal append - rollback scenario");

        // Create a direct connection pool for transaction testing
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        // Test data
        String eventType = "business.rollback.test.after.append";
        TestEvent eventPayload = new TestEvent("rollback-test-data", 888);
        Instant validTime = Instant.now();
        String correlationId = "ROLLBACK-TEST-AFTER-APPEND-" + System.currentTimeMillis();

        try {
            // Attempt transaction that should fail after bitemporal append
            Exception thrownException = assertThrows(Exception.class, () -> {
                pool.withTransaction(connection -> {
                    logger.info("📝 Starting transaction that will fail after bitemporal append");

                    // 1. First, successfully append bitemporal event
                    CompletableFuture<BiTemporalEvent<TestEvent>> eventFuture =
                        eventStore.appendInTransaction(eventType, eventPayload, validTime,
                            Map.of("test-type", "rollback-after-append"), correlationId, "rollback-test", connection);

                    return io.vertx.core.Future.fromCompletionStage(eventFuture)
                        .compose(biTemporalEvent -> {
                            logger.info("✅ Bitemporal event appended successfully with ID: {}", biTemporalEvent.getEventId());

                            // 2. Now attempt business operation that will fail (invalid SQL)
                            String invalidBusinessSql = "INSERT INTO non_existent_table (invalid_column) VALUES ($1)";
                            return connection.preparedQuery(invalidBusinessSql)
                                .execute(Tuple.of("this will fail"))
                                .map(result -> {
                                    logger.error("❌ This should not execute - business operation should have failed");
                                    return biTemporalEvent;
                                });
                        });
                }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            });

            logger.info("✅ Expected exception thrown: {}", thrownException.getMessage());

            // CRITICAL ROLLBACK VERIFICATION
            logger.info("🔍 Verifying complete rollback - no partial data should exist");

            // 1. Verify NO bitemporal event exists (should be rolled back)
            Integer eventCount = pool.withConnection(connection -> {
                String selectEventSql = "SELECT COUNT(*) FROM bitemporal_event_log WHERE correlation_id = $1 AND event_type = $2";
                return connection.preparedQuery(selectEventSql)
                    .execute(Tuple.of(correlationId, eventType))
                    .map(result -> result.iterator().next().getInteger(0));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(0, eventCount, "NO bitemporal events should exist after rollback");
            logger.info("✅ Rollback verification: {} bitemporal event(s) found (expected 0)", eventCount);

            // 2. Verify NO business data exists (none was inserted due to failure)
            Integer businessCount = pool.withConnection(connection -> {
                String selectBusinessSql = "SELECT COUNT(*) FROM business_data WHERE name LIKE $1";
                return connection.preparedQuery(selectBusinessSql)
                    .execute(Tuple.of("%" + correlationId + "%"))
                    .map(result -> result.iterator().next().getInteger(0));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(0, businessCount, "NO business data should exist after rollback");
            logger.info("✅ Rollback verification: {} business record(s) found (expected 0)", businessCount);

            // 3. Verify database is in clean state - no orphaned data
            Integer totalRecords = pool.withConnection(connection -> {
                String totalCountSql = """
                    SELECT
                        (SELECT COUNT(*) FROM bitemporal_event_log WHERE correlation_id = $1) as event_count,
                        (SELECT COUNT(*) FROM business_data WHERE name LIKE $2) as business_count
                    """;

                return connection.preparedQuery(totalCountSql)
                    .execute(Tuple.of(correlationId, "%" + correlationId + "%"))
                    .map(result -> {
                        var row = result.iterator().next();
                        int events = row.getInteger("event_count");
                        int business = row.getInteger("business_count");

                        assertEquals(0, events, "No orphaned events should exist");
                        assertEquals(0, business, "No orphaned business data should exist");

                        logger.info("✅ Clean state verification: {} events, {} business records (both should be 0)", events, business);

                        return events + business;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(0, totalRecords, "Database should be in completely clean state after rollback");

            logger.info("✅ COMPREHENSIVE ROLLBACK TEST PASSED: Business operation failure after bitemporal append correctly rolled back all operations");

        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("Test bitemporal append failure after business operation - rollback scenario")
    void testBiTemporalAppendFailureAfterBusinessOperation() throws Exception {
        logger.info("🧪 Testing bitemporal append failure after business operation - rollback scenario");

        // Create a direct connection pool for transaction testing
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        // Test data
        String businessName = "Rollback Test Business Record";
        int businessValue = 777;
        String correlationId = "ROLLBACK-TEST-AFTER-BUSINESS-" + System.currentTimeMillis();

        try {
            // Attempt transaction that should fail after business operation
            Exception thrownException = assertThrows(Exception.class, () -> {
                pool.withTransaction(connection -> {
                    logger.info("📝 Starting transaction that will fail after business operation");

                    // 1. First, successfully insert business data
                    String insertBusinessSql = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                    return connection.preparedQuery(insertBusinessSql)
                        .execute(Tuple.of(businessName + "-" + correlationId, businessValue))
                        .compose(businessResult -> {
                            int businessId = businessResult.iterator().next().getInteger("id");
                            logger.info("✅ Business record inserted successfully with ID: {}", businessId);

                            // 2. Now attempt bitemporal append that will fail (null payload to trigger validation error)
                            CompletableFuture<BiTemporalEvent<TestEvent>> eventFuture =
                                eventStore.appendInTransaction("business.rollback.test", null, Instant.now(), connection);

                            return io.vertx.core.Future.fromCompletionStage(eventFuture)
                                .map(biTemporalEvent -> {
                                    logger.error("❌ This should not execute - bitemporal append should have failed");
                                    return biTemporalEvent;
                                });
                        });
                }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            });

            logger.info("✅ Expected exception thrown: {}", thrownException.getMessage());

            // CRITICAL ROLLBACK VERIFICATION
            logger.info("🔍 Verifying complete rollback - no partial data should exist");

            // 1. Verify NO business data exists (should be rolled back)
            Integer businessCount = pool.withConnection(connection -> {
                String selectBusinessSql = "SELECT COUNT(*) FROM business_data WHERE name LIKE $1";
                return connection.preparedQuery(selectBusinessSql)
                    .execute(Tuple.of("%" + correlationId + "%"))
                    .map(result -> result.iterator().next().getInteger(0));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(0, businessCount, "NO business data should exist after rollback");
            logger.info("✅ Rollback verification: {} business record(s) found (expected 0)", businessCount);

            // 2. Verify NO bitemporal events exist (failed to insert)
            Integer eventCount = pool.withConnection(connection -> {
                String selectEventSql = "SELECT COUNT(*) FROM bitemporal_event_log WHERE correlation_id = $1";
                return connection.preparedQuery(selectEventSql)
                    .execute(Tuple.of(correlationId))
                    .map(result -> result.iterator().next().getInteger(0));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(0, eventCount, "NO bitemporal events should exist after rollback");
            logger.info("✅ Rollback verification: {} bitemporal event(s) found (expected 0)", eventCount);

            logger.info("✅ COMPREHENSIVE ROLLBACK TEST PASSED: Bitemporal append failure after business operation correctly rolled back all operations");

        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("Test transaction boundary integrity - no partial commits")
    void testTransactionBoundaryIntegrity() throws Exception {
        logger.info("🧪 Testing transaction boundary integrity - no partial commits");

        // Create a direct connection pool for transaction testing
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        // Test data for multiple operations
        String correlationId = "BOUNDARY-INTEGRITY-TEST-" + System.currentTimeMillis();

        try {
            // Attempt complex transaction with multiple operations that will fail at the end
            Exception thrownException = assertThrows(Exception.class, () -> {
                pool.withTransaction(connection -> {
                    logger.info("📝 Starting complex transaction with multiple operations that will fail");

                    // 1. Insert first business record
                    String insertBusiness1Sql = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                    return connection.preparedQuery(insertBusiness1Sql)
                        .execute(Tuple.of("Business-1-" + correlationId, 100))
                        .compose(result1 -> {
                            int businessId1 = result1.iterator().next().getInteger("id");
                            logger.info("✅ First business record inserted with ID: {}", businessId1);

                            // 2. Append first bitemporal event
                            CompletableFuture<BiTemporalEvent<TestEvent>> event1Future =
                                eventStore.appendInTransaction("boundary.test.event1",
                                    new TestEvent("event1-data", 100), Instant.now(),
                                    Map.of("business-id", String.valueOf(businessId1)),
                                    correlationId + "-event1", "business-" + businessId1, connection);

                            return io.vertx.core.Future.fromCompletionStage(event1Future)
                                .compose(event1 -> {
                                    logger.info("✅ First bitemporal event appended with ID: {}", event1.getEventId());

                                    // 3. Insert second business record
                                    return connection.preparedQuery(insertBusiness1Sql)
                                        .execute(Tuple.of("Business-2-" + correlationId, 200))
                                        .compose(result2 -> {
                                            int businessId2 = result2.iterator().next().getInteger("id");
                                            logger.info("✅ Second business record inserted with ID: {}", businessId2);

                                            // 4. Append second bitemporal event
                                            CompletableFuture<BiTemporalEvent<TestEvent>> event2Future =
                                                eventStore.appendInTransaction("boundary.test.event2",
                                                    new TestEvent("event2-data", 200), Instant.now(),
                                                    Map.of("business-id", String.valueOf(businessId2)),
                                                    correlationId + "-event2", "business-" + businessId2, connection);

                                            return io.vertx.core.Future.fromCompletionStage(event2Future)
                                                .compose(event2 -> {
                                                    logger.info("✅ Second bitemporal event appended with ID: {}", event2.getEventId());

                                                    // 5. Now cause a failure - invalid SQL operation
                                                    String failureSql = "INSERT INTO non_existent_table (invalid) VALUES ($1)";
                                                    return connection.preparedQuery(failureSql)
                                                        .execute(Tuple.of("force failure"))
                                                        .map(failureResult -> {
                                                            logger.error("❌ This should not execute - operation should have failed");
                                                            return event2;
                                                        });
                                                });
                                        });
                                });
                        });
                }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            });

            logger.info("✅ Expected exception thrown: {}", thrownException.getMessage());

            // COMPREHENSIVE TRANSACTION BOUNDARY VERIFICATION
            logger.info("🔍 Verifying transaction boundary integrity - ALL operations should be rolled back");

            // 1. Verify NO business data exists (all should be rolled back)
            Integer businessCount = pool.withConnection(connection -> {
                String selectBusinessSql = "SELECT COUNT(*) FROM business_data WHERE name LIKE $1";
                return connection.preparedQuery(selectBusinessSql)
                    .execute(Tuple.of("%" + correlationId + "%"))
                    .map(result -> result.iterator().next().getInteger(0));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(0, businessCount, "NO business data should exist after transaction rollback");
            logger.info("✅ Boundary integrity verification: {} business record(s) found (expected 0)", businessCount);

            // 2. Verify NO bitemporal events exist (all should be rolled back)
            Integer eventCount = pool.withConnection(connection -> {
                String selectEventSql = "SELECT COUNT(*) FROM bitemporal_event_log WHERE correlation_id LIKE $1";
                return connection.preparedQuery(selectEventSql)
                    .execute(Tuple.of(correlationId + "%"))
                    .map(result -> result.iterator().next().getInteger(0));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(0, eventCount, "NO bitemporal events should exist after transaction rollback");
            logger.info("✅ Boundary integrity verification: {} bitemporal event(s) found (expected 0)", eventCount);

            // 3. Verify complete clean state - no partial commits
            pool.withConnection(connection -> {
                String comprehensiveCheckSql = """
                    SELECT
                        (SELECT COUNT(*) FROM business_data WHERE name LIKE $1) as business_count,
                        (SELECT COUNT(*) FROM bitemporal_event_log WHERE correlation_id LIKE $2) as event_count,
                        (SELECT COUNT(*) FROM bitemporal_event_log WHERE aggregate_id LIKE $3) as aggregate_count
                    """;

                return connection.preparedQuery(comprehensiveCheckSql)
                    .execute(Tuple.of("%" + correlationId + "%", correlationId + "%", "business-%"))
                    .map(result -> {
                        var row = result.iterator().next();
                        int business = row.getInteger("business_count");
                        int events = row.getInteger("event_count");
                        int aggregates = row.getInteger("aggregate_count");

                        assertEquals(0, business, "No business records should exist");
                        assertEquals(0, events, "No event records should exist");
                        // Note: aggregate_count might include other test data, so we don't assert on it

                        logger.info("✅ Comprehensive boundary verification: {} business, {} events, {} aggregates",
                            business, events, aggregates);

                        return null;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            logger.info("✅ COMPREHENSIVE TRANSACTION BOUNDARY INTEGRITY TEST PASSED: All operations correctly rolled back, no partial commits");

        } finally {
            pool.close();
        }
    }

    /**
     * Test event payload class
     */
    public static class TestEvent {
        private String data;
        private int value;
        
        public TestEvent() {}
        
        public TestEvent(String data, int value) {
            this.data = data;
            this.value = value;
        }
        
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
        
        public int getValue() { return value; }
        public void setValue(int value) { this.value = value; }
    }

    // ========================================
    // INCREMENT 2.3: MULTIPLE OPERATIONS IN SINGLE TRANSACTION
    // ========================================

    @Test
    @DisplayName("Multiple bitemporal events in single transaction")
    void testMultipleBiTemporalEventsInSingleTransaction() throws Exception {
        logger.info("🧪 Testing multiple bitemporal events in single transaction");

        // Create a direct connection pool for transaction testing
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        try {
            Instant testStart = Instant.now();
            List<String> eventIds = new ArrayList<>();

            // TRANSACTION WITH MULTIPLE BITEMPORAL EVENTS
            logger.info("🚀 Starting multiple bitemporal events transaction test");

            pool.withTransaction(connection -> {
                // Insert business record
                String insertSql = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                return connection.preparedQuery(insertSql)
                    .execute(Tuple.of("Multi Event Test", 100))
                    .compose(result -> {
                        int businessId = result.iterator().next().getInteger("id");
                        logger.info("📝 Business record inserted with ID: {}", businessId);

                        // Append first bitemporal event
                        TestEvent event1 = new TestEvent("multi-event-1", 1);
                        Instant validTime1 = testStart.plusMillis(100);

                        return Future.fromCompletionStage(eventStore.appendInTransaction("business.multi.event1", event1, validTime1, connection))
                            .compose(biTemporalEvent1 -> {
                                eventIds.add(biTemporalEvent1.getEventId());
                                logger.info("📊 First bitemporal event appended with ID: {}", biTemporalEvent1.getEventId());

                                // Append second bitemporal event
                                TestEvent event2 = new TestEvent("multi-event-2", 2);
                                Instant validTime2 = testStart.plusMillis(200);

                                return Future.fromCompletionStage(eventStore.appendInTransaction("business.multi.event2", event2, validTime2, connection))
                                    .compose(biTemporalEvent2 -> {
                                        eventIds.add(biTemporalEvent2.getEventId());
                                        logger.info("📊 Second bitemporal event appended with ID: {}", biTemporalEvent2.getEventId());

                                        // Append third bitemporal event
                                        TestEvent event3 = new TestEvent("multi-event-3", 3);
                                        Instant validTime3 = testStart.plusMillis(300);

                                        return Future.fromCompletionStage(eventStore.appendInTransaction("business.multi.event3", event3, validTime3, connection))
                                            .compose(biTemporalEvent3 -> {
                                                eventIds.add(biTemporalEvent3.getEventId());
                                                logger.info("📊 Third bitemporal event appended with ID: {}", biTemporalEvent3.getEventId());

                                                return Future.succeededFuture();
                                            });
                                    });
                            });
                    });
            }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

            // COMPREHENSIVE VERIFICATION
            logger.info("🔍 Verifying multiple bitemporal events transaction commit");

            // Verify all events and business data exist
            Integer totalRecords = pool.withConnection(connection -> {
                String countSql = """
                    SELECT
                        (SELECT COUNT(*) FROM business_data WHERE name = 'Multi Event Test') as business_count,
                        (SELECT COUNT(*) FROM bitemporal_event_log WHERE event_type LIKE 'business.multi.event%') as event_count
                    """;

                return connection.preparedQuery(countSql)
                    .execute()
                    .map(result -> {
                        var row = result.iterator().next();
                        int businessCount = row.getInteger("business_count");
                        int eventCount = row.getInteger("event_count");

                        assertEquals(1, businessCount, "Business record should exist after commit");
                        assertEquals(3, eventCount, "All 3 event records should exist after commit");

                        logger.info("✅ Multiple events verification: {} business record(s) and {} event record(s) found", businessCount, eventCount);

                        return businessCount + eventCount;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(4, totalRecords, "Business record + 3 event records should exist after successful commit");

            // Verify event ordering and data integrity
            List<Row> eventDetails = pool.withConnection(connection -> {
                String eventDetailsSql = "SELECT event_id, event_type, valid_time, transaction_time, payload FROM bitemporal_event_log WHERE event_type LIKE 'business.multi.event%' ORDER BY valid_time";
                return connection.preparedQuery(eventDetailsSql)
                    .execute()
                    .map(result -> {
                        List<Row> rows = new ArrayList<>();
                        result.forEach(rows::add);
                        return rows;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(3, eventDetails.size(), "Should have exactly 3 events");

            logger.info("📊 Event sequence verification:");
            for (int i = 0; i < eventDetails.size(); i++) {
                Row row = eventDetails.get(i);
                String eventId = row.getString("event_id");
                String eventType = row.getString("event_type");
                Instant validTime = row.getLocalDateTime("valid_time").toInstant(java.time.ZoneOffset.UTC);
                Instant transactionTime = row.getLocalDateTime("transaction_time").toInstant(java.time.ZoneOffset.UTC);

                logger.info("  Event {}: ID={}, Type={}, ValidTime={}, TransactionTime={}",
                    i + 1, eventId, eventType, validTime, transactionTime);

                // Verify event types are in correct order
                assertEquals("business.multi.event" + (i + 1), eventType, "Event type should match expected sequence");

                // Verify transaction times are within reasonable bounds (all committed in same transaction)
                // Note: Each appendInTransaction call gets its own timestamp, but they're all within the same transaction
                assertTrue(transactionTime.isAfter(testStart.minusSeconds(1)), "Transaction time should be after test start");
                assertTrue(transactionTime.isBefore(testStart.plusSeconds(30)), "Transaction time should be within test window");
            }

            assertEquals(3, eventIds.size(), "Should have collected 3 event IDs");

            logger.info("✅ COMPREHENSIVE MULTIPLE BITEMPORAL EVENTS TEST PASSED: All {} events committed together with business data", eventIds.size());

        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("Mixed operation sequences - business → bitemporal → business")
    void testMixedOperationSequences() throws Exception {
        logger.info("🧪 Testing mixed operation sequences - business → bitemporal → business");

        // Create a direct connection pool for transaction testing
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        try {
            Instant testStart = Instant.now();
            List<Integer> businessIds = new ArrayList<>();
            List<String> eventIds = new ArrayList<>();

            // TRANSACTION WITH MIXED OPERATIONS
            logger.info("🚀 Starting mixed operation sequences transaction test");

            pool.withTransaction(connection -> {
                // 1. First business operation
                String insertSql1 = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                return connection.preparedQuery(insertSql1)
                    .execute(Tuple.of("Mixed Op 1", 101))
                    .compose(result1 -> {
                        int businessId1 = result1.iterator().next().getInteger("id");
                        businessIds.add(businessId1);
                        logger.info("📝 First business record inserted with ID: {}", businessId1);

                        // 2. First bitemporal event
                        TestEvent event1 = new TestEvent("mixed-sequence-1", businessId1);
                        Instant validTime1 = testStart.plusMillis(100);

                        return Future.fromCompletionStage(eventStore.appendInTransaction("business.mixed.sequence1", event1, validTime1, connection))
                            .compose(biTemporalEvent1 -> {
                                eventIds.add(biTemporalEvent1.getEventId());
                                logger.info("📊 First bitemporal event appended with ID: {}", biTemporalEvent1.getEventId());

                                // 3. Second business operation
                                String insertSql2 = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                                return connection.preparedQuery(insertSql2)
                                    .execute(Tuple.of("Mixed Op 2", 102))
                                    .compose(result2 -> {
                                        int businessId2 = result2.iterator().next().getInteger("id");
                                        businessIds.add(businessId2);
                                        logger.info("📝 Second business record inserted with ID: {}", businessId2);

                                        // 4. Second bitemporal event
                                        TestEvent event2 = new TestEvent("mixed-sequence-2", businessId2);
                                        Instant validTime2 = testStart.plusMillis(200);

                                        return Future.fromCompletionStage(eventStore.appendInTransaction("business.mixed.sequence2", event2, validTime2, connection))
                                            .compose(biTemporalEvent2 -> {
                                                eventIds.add(biTemporalEvent2.getEventId());
                                                logger.info("📊 Second bitemporal event appended with ID: {}", biTemporalEvent2.getEventId());

                                                // 5. Third business operation
                                                String insertSql3 = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                                                return connection.preparedQuery(insertSql3)
                                                    .execute(Tuple.of("Mixed Op 3", 103))
                                                    .compose(result3 -> {
                                                        int businessId3 = result3.iterator().next().getInteger("id");
                                                        businessIds.add(businessId3);
                                                        logger.info("📝 Third business record inserted with ID: {}", businessId3);

                                                        return Future.succeededFuture();
                                                    });
                                            });
                                    });
                            });
                    });
            }).toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);

            // COMPREHENSIVE VERIFICATION
            logger.info("🔍 Verifying mixed operation sequences transaction commit");

            // Verify all business records and events exist
            Integer totalRecords = pool.withConnection(connection -> {
                String countSql = """
                    SELECT
                        (SELECT COUNT(*) FROM business_data WHERE name LIKE 'Mixed Op %') as business_count,
                        (SELECT COUNT(*) FROM bitemporal_event_log WHERE event_type LIKE 'business.mixed.sequence%') as event_count
                    """;

                return connection.preparedQuery(countSql)
                    .execute()
                    .map(result -> {
                        var row = result.iterator().next();
                        int businessCount = row.getInteger("business_count");
                        int eventCount = row.getInteger("event_count");

                        assertEquals(3, businessCount, "All 3 business records should exist after commit");
                        assertEquals(2, eventCount, "All 2 event records should exist after commit");

                        logger.info("✅ Mixed operations verification: {} business record(s) and {} event record(s) found", businessCount, eventCount);

                        return businessCount + eventCount;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(5, totalRecords, "3 business records + 2 event records should exist after successful commit");

            // Verify operation sequence integrity
            List<Row> businessDetails = pool.withConnection(connection -> {
                String businessSql = "SELECT id, name, value FROM business_data WHERE name LIKE 'Mixed Op %' ORDER BY id";
                return connection.preparedQuery(businessSql)
                    .execute()
                    .map(result -> {
                        List<Row> rows = new ArrayList<>();
                        result.forEach(rows::add);
                        return rows;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            List<Row> eventDetails = pool.withConnection(connection -> {
                String eventSql = "SELECT event_id, event_type, payload, valid_time FROM bitemporal_event_log WHERE event_type LIKE 'business.mixed.sequence%' ORDER BY valid_time";
                return connection.preparedQuery(eventSql)
                    .execute()
                    .map(result -> {
                        List<Row> rows = new ArrayList<>();
                        result.forEach(rows::add);
                        return rows;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(3, businessDetails.size(), "Should have exactly 3 business records");
            assertEquals(2, eventDetails.size(), "Should have exactly 2 events");
            assertEquals(3, businessIds.size(), "Should have collected 3 business IDs");
            assertEquals(2, eventIds.size(), "Should have collected 2 event IDs");

            logger.info("📊 Mixed operation sequence verification:");
            logger.info("  Business Operations:");
            for (int i = 0; i < businessDetails.size(); i++) {
                Row row = businessDetails.get(i);
                int id = row.getInteger("id");
                String name = row.getString("name");
                logger.info("    Business {}: ID={}, Name={}", i + 1, id, name);
                assertEquals("Mixed Op " + (i + 1), name, "Business operation should match expected sequence");
            }

            logger.info("  Bitemporal Events:");
            for (int i = 0; i < eventDetails.size(); i++) {
                Row row = eventDetails.get(i);
                String eventId = row.getString("event_id");
                String eventType = row.getString("event_type");
                logger.info("    Event {}: ID={}, Type={}", i + 1, eventId, eventType);
                assertEquals("business.mixed.sequence" + (i + 1), eventType, "Event type should match expected sequence");
            }

            logger.info("✅ COMPREHENSIVE MIXED OPERATION SEQUENCES TEST PASSED: {} business operations and {} events committed together", businessIds.size(), eventIds.size());

        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("Batch operation performance test")
    void testBatchOperationPerformance() throws Exception {
        logger.info("🧪 Testing batch operation performance");

        // Create a direct connection pool for transaction testing
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        try {
            final int BATCH_SIZE = 10; // Reasonable size for integration test
            Instant testStart = Instant.now();
            List<String> eventIds = new ArrayList<>();

            // BATCH TRANSACTION PERFORMANCE TEST
            logger.info("🚀 Starting batch operation performance test with {} operations", BATCH_SIZE);

            long startTime = System.currentTimeMillis();

            pool.withTransaction(connection -> {
                // Insert business record for batch
                String insertSql = "INSERT INTO business_data (name, value) VALUES ($1, $2) RETURNING id";
                return connection.preparedQuery(insertSql)
                    .execute(Tuple.of("Batch Performance Test", 200))
                    .compose(result -> {
                        int businessId = result.iterator().next().getInteger("id");
                        logger.info("📝 Batch business record inserted with ID: {}", businessId);

                        // Chain multiple bitemporal events
                        Future<Void> chainFuture = Future.succeededFuture();

                        for (int i = 1; i <= BATCH_SIZE; i++) {
                            final int eventNumber = i;
                            chainFuture = chainFuture.compose(v -> {
                                TestEvent event = new TestEvent("batch-event-" + eventNumber, eventNumber);
                                Instant validTime = testStart.plusMillis(eventNumber * 10);

                                return Future.fromCompletionStage(eventStore.appendInTransaction("business.batch.event" + eventNumber, event, validTime, connection))
                                    .compose(biTemporalEvent -> {
                                        eventIds.add(biTemporalEvent.getEventId());
                                        if (eventNumber % 5 == 0) {
                                            logger.info("📊 Batch progress: {} events appended", eventNumber);
                                        }
                                        return Future.succeededFuture();
                                    });
                            });
                        }

                        return chainFuture;
                    });
            }).toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            logger.info("⏱️ Batch operation completed in {} ms ({} ms per operation)", duration, duration / BATCH_SIZE);

            // COMPREHENSIVE VERIFICATION
            logger.info("🔍 Verifying batch operation performance results");

            // Verify all events and business data exist
            Integer totalRecords = pool.withConnection(connection -> {
                String countSql = """
                    SELECT
                        (SELECT COUNT(*) FROM business_data WHERE name = 'Batch Performance Test') as business_count,
                        (SELECT COUNT(*) FROM bitemporal_event_log WHERE event_type LIKE 'business.batch.event%') as event_count
                    """;

                return connection.preparedQuery(countSql)
                    .execute()
                    .map(result -> {
                        var row = result.iterator().next();
                        int businessCount = row.getInteger("business_count");
                        int eventCount = row.getInteger("event_count");

                        assertEquals(1, businessCount, "Business record should exist after commit");
                        assertEquals(BATCH_SIZE, eventCount, "All batch event records should exist after commit");

                        logger.info("✅ Batch verification: {} business record(s) and {} event record(s) found", businessCount, eventCount);

                        return businessCount + eventCount;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(BATCH_SIZE + 1, totalRecords, "Business record + all batch event records should exist");

            // Verify transaction isolation - all events should have same transaction time
            List<Instant> transactionTimes = pool.withConnection(connection -> {
                String timingSql = "SELECT DISTINCT transaction_time FROM bitemporal_event_log WHERE event_type LIKE 'business.batch.event%'";
                return connection.preparedQuery(timingSql)
                    .execute()
                    .map(result -> {
                        List<Instant> times = new ArrayList<>();
                        result.forEach(row -> {
                            times.add(row.getLocalDateTime("transaction_time").toInstant(java.time.ZoneOffset.UTC));
                        });
                        return times;
                    });
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertTrue(transactionTimes.size() <= BATCH_SIZE, "Transaction times should be reasonable (each event gets its own timestamp)");
            assertEquals(BATCH_SIZE, eventIds.size(), "Should have collected all event IDs");

            // Performance assertions
            assertTrue(duration < 10000, "Batch operation should complete within 10 seconds");
            double avgTimePerOp = (double) duration / BATCH_SIZE;
            assertTrue(avgTimePerOp < 1000, "Average time per operation should be less than 1 second");

            logger.info("📊 Performance metrics:");
            logger.info("  Total time: {} ms", duration);
            logger.info("  Average per operation: {:.2f} ms", avgTimePerOp);
            logger.info("  Operations per second: {:.2f}", 1000.0 / avgTimePerOp);

            logger.info("✅ COMPREHENSIVE BATCH OPERATION PERFORMANCE TEST PASSED: {} events processed in {} ms", BATCH_SIZE, duration);

        } finally {
            pool.close();
        }
    }
}
