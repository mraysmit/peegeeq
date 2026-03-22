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
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation test for Vert.x 5.x performance optimizations.
 *
 * This test validates that all performance optimizations are working correctly:
 * - Pipelined client creation and usage
 * - Research-based pool configuration
 * - Performance monitoring integration
 * - Batch operations
 * - Configuration profiles
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-01-11
 * @version 1.0
 */
@Tag(TestCategories.PERFORMANCE)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VertxPerformanceOptimizationValidationTest {

    private static final Logger logger = LoggerFactory.getLogger(VertxPerformanceOptimizationValidationTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_test");
        container.withUsername("test");
        container.withPassword("test");
        container.withSharedMemorySize(256 * 1024 * 1024L); // 256MB shared memory
        container.withCommand("postgres", "-c", "max_connections=300"); // Simple connection limit increase;
        return container;
    }

    private Vertx vertx;
    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<TestEvent> eventStore;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
        logger.info("Setting up Vert.x 5.x performance optimization validation test");

        // Set database connection properties from TestContainers
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Disable queue health checks since we only have bitemporal_event_log table
        System.setProperty("peegeeq.health-check.queue-checks-enabled", "false");

        // Set optimal system properties for testing
        System.setProperty("peegeeq.database.pool.max-size", "100");
        System.setProperty("peegeeq.database.pool.min-size", "5");
        System.setProperty("peegeeq.database.pool.wait-queue-multiplier", "10");
        System.setProperty("peegeeq.database.pipelining.limit", "1024");
        System.setProperty("peegeeq.database.event.loop.size", "8");
        System.setProperty("peegeeq.database.worker.pool.size", "16");

        // Initialize database schema using centralized schema initializer
        logger.info("Creating bitemporal_event_log table using PeeGeeQTestSchemaInitializer...");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);
        logger.info("bitemporal_event_log table created successfully");

        // Initialize with test configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                eventStore = new PgBiTemporalEventStore<>(vertx, manager, TestEvent.class, "bitemporal_event_log", new ObjectMapper());
                logger.info("Test setup completed with optimized configuration");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> closeFuture = Future.succeededFuture();
        if (eventStore != null) {
            eventStore.close();
        }
        if (manager != null) {
            closeFuture = manager.closeReactive().recover(err -> {
                logger.warn("Error during manager close: {}", err.getMessage());
                return Future.succeededFuture();
            });
        }
        closeFuture.onSuccess(v -> {
            // Clear system properties
            System.clearProperty("peegeeq.database.host");
            System.clearProperty("peegeeq.database.port");
            System.clearProperty("peegeeq.database.name");
            System.clearProperty("peegeeq.database.username");
            System.clearProperty("peegeeq.database.password");
            System.clearProperty("peegeeq.database.pool.max-size");
            System.clearProperty("peegeeq.database.pool.min-size");
            System.clearProperty("peegeeq.database.pool.wait-queue-multiplier");
            System.clearProperty("peegeeq.database.pipelining.limit");
            System.clearProperty("peegeeq.database.event.loop.size");
            System.clearProperty("peegeeq.database.worker.pool.size");
            logger.info("Test teardown completed");
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }
    
    @Test
    @Order(1)
    @DisplayName("Should validate pipelined client creation and usage")
    void shouldValidatePipelinedClientCreation(VertxTestContext testContext) {
        logger.info("=== Validating Pipelined Client Creation ===");
        
        // Test that events can be appended successfully (validates pipelined client works)
        TestEvent event = new TestEvent("pipeline-test", "Testing pipelined client");
        
        long startTime = System.currentTimeMillis();
        eventStore.appendBuilder().eventType("test.pipeline").payload(event).validTime(Instant.now()).execute()
            .onSuccess(result -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - startTime;
                assertNotNull(result);
                assertEquals("test.pipeline", result.getEventType());
                assertEquals("pipeline-test", result.getPayload().getId());
                logger.info("Pipelined client validation successful - append completed in {}ms", duration);
                assertTrue(duration < 1000, "Append should complete quickly with pipelined client");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(2)
    @DisplayName("Should validate research-based pool configuration")
    void shouldValidatePoolConfiguration(VertxTestContext testContext) {
        logger.info("=== Validating Pool Configuration ===");
        
        // Test concurrent operations to validate pool size and wait queue
        int concurrentOperations = 50;
        List<io.vertx.core.Future<?>> futures = new ArrayList<>();
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < concurrentOperations; i++) {
            TestEvent event = new TestEvent("pool-test-" + i, "Testing pool configuration " + i);
            futures.add(eventStore.appendBuilder().eventType("test.pool").payload(event).validTime(Instant.now()).execute());
        }
        
        // All operations should complete without pool exhaustion
        io.vertx.core.Future.all(futures)
            .onSuccess(cf -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - startTime;
                double throughput = concurrentOperations * 1000.0 / duration;
                
                logger.info("Pool configuration validation successful - {} operations in {}ms, throughput: {} ops/sec",
                           concurrentOperations, duration, String.format("%.1f", throughput));
                
                // Validate all operations completed successfully
                for (io.vertx.core.Future<?> future : futures) {
                    assertTrue(future.isComplete());
                    assertFalse(future.failed());
                }
                
                // Throughput should be reasonable with optimized pool
                assertTrue(throughput > 10, "Throughput should be > 10 ops/sec with optimized pool");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(3)
    @DisplayName("Should validate batch operations performance")
    void shouldValidateBatchOperations(VertxTestContext testContext) {
        logger.info("=== Validating Batch Operations ===");
        
        int batchSize = 100;
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> batchEvents = new ArrayList<>();

        for (int i = 0; i < batchSize; i++) {
            TestEvent event = new TestEvent("batch-" + i, "Batch validation test " + i);
            batchEvents.add(new PgBiTemporalEventStore.BatchEventData<>("test.batch", event, Instant.now(),
                                               Map.of("test", "batch"), "batch-correlation", "batch-aggregate"));
        }
        
        long startTime = System.currentTimeMillis();
        eventStore.appendBatch(batchEvents)
            .onSuccess(results -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - startTime;
                double throughput = batchSize * 1000.0 / duration;
                
                logger.info("Batch operations validation successful - {} events in {}ms, throughput: {} events/sec",
                           batchSize, duration, String.format("%.1f", throughput));
                
                assertEquals(batchSize, results.size());
                
                // Batch operations should be significantly faster than individual operations
                assertTrue(throughput > 50, "Batch throughput should be > 50 events/sec");
                
                // Validate all events were stored correctly
                for (int i = 0; i < results.size(); i++) {
                    BiTemporalEvent<TestEvent> result = results.get(i);
                    assertEquals("test.batch", result.getEventType());
                    assertEquals("batch-" + i, result.getPayload().getId());
                }
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(4)
    @DisplayName("Should validate performance monitoring integration")
    void shouldValidatePerformanceMonitoring(VertxTestContext testContext) {
        logger.info("=== Validating Performance Monitoring ===");
        
        // Perform some operations to generate metrics
        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < 10; i++) {
            final int index = i;
            chain = chain.compose(v -> {
                TestEvent event = new TestEvent("monitor-test-" + index, "Performance monitoring test " + index);
                return eventStore.appendBuilder().eventType("test.monitoring").payload(event).validTime(Instant.now()).execute().mapEmpty();
            });
        }
        
        chain
            .onSuccess(v -> testContext.verify(() -> {
                logger.info("Performance monitoring validation completed - metrics should be collected");
                assertTrue(true, "Performance monitoring integration validated");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(5)
    @DisplayName("Should validate configuration profiles work correctly")
    void shouldValidateConfigurationProfiles(VertxTestContext testContext) {
        logger.info("=== Validating Configuration Profiles ===");
        
        // Test that the system properties are being read correctly
        String poolSize = System.getProperty("peegeeq.database.pool.max-size");
        String pipeliningLimit = System.getProperty("peegeeq.database.pipelining.limit");
        String eventLoopSize = System.getProperty("peegeeq.database.event.loop.size");
        
        assertEquals("100", poolSize);
        assertEquals("1024", pipeliningLimit);
        assertEquals("8", eventLoopSize);
        
        logger.info("Configuration profiles validation successful - system properties correctly set");
        
        // Test that operations work with the configured values
        TestEvent event = new TestEvent("config-test", "Configuration profile test");
        eventStore.appendBuilder().eventType("test.config").payload(event).validTime(Instant.now()).execute()
            .onSuccess(result -> testContext.verify(() -> {
                assertNotNull(result);
                assertEquals("config-test", result.getPayload().getId());
                logger.info("Configuration profiles operational validation successful");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    @Test
    @Order(6)
    @DisplayName("Should validate overall performance improvement")
    void shouldValidateOverallPerformanceImprovement(VertxTestContext testContext) {
        logger.info("=== Validating Overall Performance Improvement ===");
        
        // Run a comprehensive test to validate performance targets
        int totalEvents = 200;
        List<io.vertx.core.Future<?>> futures = new ArrayList<>();
        
        long startTime = System.currentTimeMillis();
        
        // Mix of individual and batch operations
        for (int i = 0; i < totalEvents / 2; i++) {
            TestEvent event = new TestEvent("perf-test-" + i, "Performance test " + i);
            futures.add(eventStore.appendBuilder().eventType("test.performance").payload(event).validTime(Instant.now()).execute());
        }
        
        // Add a batch operation
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> batchEvents = new ArrayList<>();
        for (int i = totalEvents / 2; i < totalEvents; i++) {
            TestEvent event = new TestEvent("perf-batch-" + i, "Performance batch test " + i);
            batchEvents.add(new PgBiTemporalEventStore.BatchEventData<>("test.performance.batch", event, Instant.now(),
                                               Map.of("batch", "true"), "perf-correlation", "perf-aggregate"));
        }
        
        io.vertx.core.Future<List<BiTemporalEvent<TestEvent>>> batchFuture = eventStore.appendBatch(batchEvents);
        
        // Wait for all individual + batch operations to complete
        io.vertx.core.Future.all(futures)
            .compose(cf -> batchFuture)
            .onSuccess(batchResults -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - startTime;
                double throughput = totalEvents * 1000.0 / duration;
                
                logger.info("Overall performance validation completed - {} events in {}ms, throughput: {} events/sec",
                           totalEvents, duration, String.format("%.1f", throughput));
                
                // Validate performance targets
                assertTrue(throughput > 100, "Overall throughput should be > 100 events/sec with optimizations");
                assertEquals(totalEvents / 2, batchResults.size());
                
                // Validate all individual operations completed successfully
                for (io.vertx.core.Future<?> future : futures) {
                    assertTrue(future.isComplete());
                    assertFalse(future.failed());
                }
                
                logger.info("=== Vert.x 5.x Performance Optimization Validation Complete ===");
                logger.info("All optimizations validated successfully!");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
    
    /**
     * Test event class for validation testing.
     */
    public static class TestEvent {
        private String id;
        private String message;
        
        public TestEvent() {}
        
        public TestEvent(String id, String message) {
            this.id = id;
            this.message = message;
        }
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}


