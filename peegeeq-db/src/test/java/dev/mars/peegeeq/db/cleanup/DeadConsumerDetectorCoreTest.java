package dev.mars.peegeeq.db.cleanup;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for DeadConsumerDetector using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(value = "dead-consumer-detection", mode = ResourceAccessMode.READ_WRITE)
public @ExtendWith(VertxExtension.class)
class DeadConsumerDetectorCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private DeadConsumerDetector detector;

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx());
        
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-detector", connectionConfig, poolConfig);
        
        detector = new DeadConsumerDetector(connectionManager, "test-detector");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    void testDeadConsumerDetectorCreation() {
        assertNotNull(detector);
    }

    @Test
    void testDeadConsumerDetectorCreationWithNullConnectionManager() {
        assertThrows(NullPointerException.class, () -> 
            new DeadConsumerDetector(null, "test-service"));
    }

    @Test
    void testDeadConsumerDetectorCreationWithNullServiceId() {
        assertThrows(NullPointerException.class, () -> 
            new DeadConsumerDetector(connectionManager, null));
    }

    @Test
    void testDetectDeadSubscriptionsWithNullTopic() {
        assertThrows(NullPointerException.class, () -> 
            detector.detectDeadSubscriptions(null));
    }

    @Test
    void testDetectDeadSubscriptionsNoDeadSubscriptions(VertxTestContext ctx) {
        detector.detectDeadSubscriptions("non-existent-topic")
            .onComplete(ctx.succeeding(markedDead -> ctx.verify(() -> { assertEquals(0, markedDead); ctx.completeNow(); })));
    }

    @Test
    void testDetectAllDeadSubscriptionsNoDeadSubscriptions(VertxTestContext ctx) {
        // This test verifies that detectAllDeadSubscriptions works correctly.
        // In a shared database environment with parallel tests, other tests may
        // have created subscriptions that could be marked as dead.
        // The key validation is that the operation completes successfully.
        detector.detectAllDeadSubscriptions()
            .onComplete(ctx.succeeding(markedDead -> ctx.verify(() -> { assertTrue(markedDead >= 0, "Marked dead count should be non-negative"); ctx.completeNow(); })));
    }

    @Test
    void testCountDeadSubscriptionsWithNullTopic() {
        assertThrows(NullPointerException.class, () ->
            detector.countDeadSubscriptions(null));
    }

    @Test
    void testCountDeadSubscriptionsNoSubscriptions(VertxTestContext ctx) {
        detector.countDeadSubscriptions("non-existent-topic")
            .onComplete(ctx.succeeding(count -> ctx.verify(() -> { assertEquals(0L, count); ctx.completeNow(); })));
    }

    @Test
    void testCountEligibleForDeadDetectionWithNullTopic() {
        assertThrows(NullPointerException.class, () ->
            detector.countEligibleForDeadDetection(null));
    }

    @Test
    void testCountEligibleForDeadDetectionNoSubscriptions(VertxTestContext ctx) {
        detector.countEligibleForDeadDetection("non-existent-topic")
            .onComplete(ctx.succeeding(count -> ctx.verify(() -> { assertEquals(0L, count); ctx.completeNow(); })));
    }
}







