package dev.mars.peegeeq.db.provider;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.client.PgClientFactory;
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
 * CORE tests for PgConnectionProvider using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
public class PgConnectionProviderCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private PgClientFactory clientFactory;
    private PgConnectionProvider connectionProvider;

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx());
        clientFactory = new PgClientFactory(connectionManager);
        
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        clientFactory.createClient("test-client", connectionConfig, poolConfig);
        
        connectionProvider = new PgConnectionProvider(clientFactory);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientFactory != null) {
            clientFactory.closeAsync().toCompletionStage().toCompletableFuture().get();
        }
    }

    @Test
    void testPgConnectionProviderCreation() {
        assertNotNull(connectionProvider);
    }

    @Test
    void testGetReactivePool() throws Exception {
        Pool pool = connectionProvider.getReactivePool("test-client")
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(pool);
    }

    @Test
    void testGetReactivePoolNonExistentClient() throws Exception {
        try {
            connectionProvider.getReactivePool("non-existent-client")
                .toCompletionStage().toCompletableFuture().get();
            fail("Expected IllegalArgumentException");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    void testGetConnection() throws Exception {
        SqlConnection connection = connectionProvider.getConnection("test-client")
            .toCompletionStage().toCompletableFuture().get();
        assertNotNull(connection);
        connection.close();
    }

    @Test
    void testGetConnectionNonExistentClient() throws Exception {
        try {
            connectionProvider.getConnection("non-existent-client")
                .toCompletionStage().toCompletableFuture().get();
            fail("Expected exception");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    void testWithConnection() throws Exception {
        Integer result = connectionProvider.withConnection("test-client", connection ->
            connection.query("SELECT 1 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        ).toCompletionStage().toCompletableFuture().get();
        
        assertEquals(1, result);
    }

    @Test
    void testWithConnectionNonExistentClient() throws Exception {
        try {
            connectionProvider.withConnection("non-existent-client", connection ->
                connection.query("SELECT 1")
                    .execute()
                    .map(rowSet -> 1)
            ).toCompletionStage().toCompletableFuture().get();
            fail("Expected exception");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    void testWithTransaction() throws Exception {
        Integer result = connectionProvider.withTransaction("test-client", connection ->
            connection.query("SELECT 1 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        ).toCompletionStage().toCompletableFuture().get();
        
        assertEquals(1, result);
    }

    @Test
    void testWithTransactionNonExistentClient() throws Exception {
        try {
            connectionProvider.withTransaction("non-existent-client", connection ->
                connection.query("SELECT 1")
                    .execute()
                    .map(rowSet -> 1)
            ).toCompletionStage().toCompletableFuture().get();
            fail("Expected exception");
        } catch (Exception e) {
            // Expected
        }
    }
}

