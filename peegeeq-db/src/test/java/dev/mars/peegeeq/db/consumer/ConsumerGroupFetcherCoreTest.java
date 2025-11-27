package dev.mars.peegeeq.db.consumer;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

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
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

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
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx());
        
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-fetcher", connectionConfig, poolConfig);
        
        fetcher = new ConsumerGroupFetcher(connectionManager, "test-fetcher");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testConsumerGroupFetcherCreation() {
        assertNotNull(fetcher);
    }

    @Test
    void testFetchMessagesNoMessages() throws Exception {
        List<OutboxMessage> messages = fetcher.fetchMessages("non-existent-topic", "test-group", 10)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages);
        assertEquals(0, messages.size());
    }

    @Test
    void testFetchMessagesWithBatchSize() throws Exception {
        List<OutboxMessage> messages = fetcher.fetchMessages("test-topic", "test-group", 5)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages);
        assertTrue(messages.size() <= 5);
    }

    @Test
    void testFetchMessagesWithLargeBatchSize() throws Exception {
        List<OutboxMessage> messages = fetcher.fetchMessages("test-topic", "test-group", 1000)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages);
        assertTrue(messages.size() <= 1000);
    }

    @Test
    void testFetchMessagesWithZeroBatchSize() throws Exception {
        List<OutboxMessage> messages = fetcher.fetchMessages("test-topic", "test-group", 0)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages);
        assertEquals(0, messages.size());
    }

    @Test
    void testFetchMessagesMultipleCalls() throws Exception {
        // First call
        List<OutboxMessage> messages1 = fetcher.fetchMessages("test-topic", "test-group", 10)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages1);

        // Second call
        List<OutboxMessage> messages2 = fetcher.fetchMessages("test-topic", "test-group", 10)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages2);
    }

    @Test
    void testFetchMessagesWithDifferentTopics() throws Exception {
        List<OutboxMessage> messages1 = fetcher.fetchMessages("topic1", "test-group", 10)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages1);

        List<OutboxMessage> messages2 = fetcher.fetchMessages("topic2", "test-group", 10)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages2);
    }

    @Test
    void testFetchMessagesWithDifferentGroups() throws Exception {
        List<OutboxMessage> messages1 = fetcher.fetchMessages("test-topic", "group1", 10)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages1);

        List<OutboxMessage> messages2 = fetcher.fetchMessages("test-topic", "group2", 10)
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(messages2);
    }
}


