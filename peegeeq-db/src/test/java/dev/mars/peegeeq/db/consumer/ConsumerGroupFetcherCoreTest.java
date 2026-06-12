package dev.mars.peegeeq.db.consumer;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.vertx.junit5.VertxTestContext;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for ConsumerGroupFetcher using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class ConsumerGroupFetcherCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private ConsumerGroupFetcher fetcher;

    @BeforeEach
    void setUp() {
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
        connectionManager.getOrCreateReactivePool("test-fetcher", connectionConfig, poolConfig);
        
        fetcher = new ConsumerGroupFetcher(connectionManager, "test-fetcher");
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
    void testConsumerGroupFetcherCreation() {
        assertNotNull(fetcher);
    }

    @Test
    void testFetchMessagesNoMessages(VertxTestContext testContext) {
        fetcher.fetchMessages("non-existent-topic", "test-group", 10)
            .onComplete(testContext.succeeding(messages -> testContext.verify(() -> {
                assertNotNull(messages);
                assertEquals(0, messages.size());
                testContext.completeNow();
            })));
    }

    @Test
    void testFetchMessagesWithBatchSize(VertxTestContext testContext) {
        fetcher.fetchMessages("test-topic", "test-group", 5)
            .onComplete(testContext.succeeding(messages -> testContext.verify(() -> {
                assertNotNull(messages);
                assertTrue(messages.size() <= 5);
                testContext.completeNow();
            })));
    }

    @Test
    void testFetchMessagesWithLargeBatchSize(VertxTestContext testContext) {
        fetcher.fetchMessages("test-topic", "test-group", 1000)
            .onComplete(testContext.succeeding(messages -> testContext.verify(() -> {
                assertNotNull(messages);
                assertTrue(messages.size() <= 1000);
                testContext.completeNow();
            })));
    }

    @Test
    void testFetchMessagesWithZeroBatchSize(VertxTestContext testContext) {
        fetcher.fetchMessages("test-topic", "test-group", 0)
            .onComplete(testContext.succeeding(messages -> testContext.verify(() -> {
                assertNotNull(messages);
                assertEquals(0, messages.size());
                testContext.completeNow();
            })));
    }

    @Test
    void testFetchMessagesMultipleCalls(VertxTestContext testContext) {
        // First call, then second call via compose
        fetcher.fetchMessages("test-topic", "test-group", 10)
            .compose(messages1 -> {
                assertNotNull(messages1);
                return fetcher.fetchMessages("test-topic", "test-group", 10);
            })
            .onComplete(testContext.succeeding(messages2 -> testContext.verify(() -> {
                assertNotNull(messages2);
                testContext.completeNow();
            })));
    }

    @Test
    void testFetchMessagesWithDifferentTopics(VertxTestContext testContext) {
        fetcher.fetchMessages("topic1", "test-group", 10)
            .compose(messages1 -> {
                assertNotNull(messages1);
                return fetcher.fetchMessages("topic2", "test-group", 10);
            })
            .onComplete(testContext.succeeding(messages2 -> testContext.verify(() -> {
                assertNotNull(messages2);
                testContext.completeNow();
            })));
    }

    @Test
    void testFetchMessagesWithDifferentGroups(VertxTestContext testContext) {
        fetcher.fetchMessages("test-topic", "group1", 10)
            .compose(messages1 -> {
                assertNotNull(messages1);
                return fetcher.fetchMessages("test-topic", "group2", 10);
            })
            .onComplete(testContext.succeeding(messages2 -> testContext.verify(() -> {
                assertNotNull(messages2);
                testContext.completeNow();
            })));
    }
}


