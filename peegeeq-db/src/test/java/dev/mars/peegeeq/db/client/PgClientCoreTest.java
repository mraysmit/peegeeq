package dev.mars.peegeeq.db.client;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for PgClient using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class PgClientCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private PgClient client;

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
        connectionManager.getOrCreateReactivePool("test-client", connectionConfig, poolConfig);
        
        client = new PgClient("test-client", connectionManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testPgClientCreation() {
        assertNotNull(client);
    }

    @Test
    void testGetReactiveConnection() throws Exception {
        SqlConnection connection = client.getReactiveConnection()
            .toCompletionStage().toCompletableFuture().get();

        assertNotNull(connection);

        // Verify connection works
        Integer result = connection.query("SELECT 1 as value")
            .execute()
            .map(rowSet -> rowSet.iterator().next().getInteger("value"))
            .toCompletionStage().toCompletableFuture().get();

        assertEquals(1, result);

        connection.close().toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testGetReactivePool() {
        Pool pool = client.getReactivePool();
        assertNotNull(pool);
    }

    @Test
    void testGetReactivePoolNonExistentClient() {
        PgClient nonExistentClient = new PgClient("non-existent", connectionManager);
        
        assertThrows(IllegalStateException.class, () -> nonExistentClient.getReactivePool());
    }

    @Test
    void testWithReactiveConnection() throws Exception {
        client.withReactiveConnection(connection ->
            connection.query("SELECT 42 as value")
                .execute()
                .map(rowSet -> {
                    assertEquals(42, rowSet.iterator().next().getInteger("value"));
                    return null;
                })
        ).toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testWithReactiveConnectionResult() throws Exception {
        Integer result = client.withReactiveConnectionResult(connection ->
            connection.query("SELECT 99 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        ).toCompletionStage().toCompletableFuture().get();

        assertEquals(99, result);
    }

    @Test
    void testClose() {
        // Close should not throw exception
        client.close();
    }
}

