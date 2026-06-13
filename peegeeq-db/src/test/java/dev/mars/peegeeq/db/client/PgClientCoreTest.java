// ===== FILE 1: PgClientCoreTest.java =====
package dev.mars.peegeeq.db.client;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

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
        connectionManager.getOrCreateReactivePool("test-client", connectionConfig, poolConfig);
        
        client = new PgClient("test-client", connectionManager);
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
    void testPgClientCreation() {
        assertNotNull(client);
    }

    @Test
    void testGetReactiveConnection(VertxTestContext testContext) {
        client.getReactiveConnection()
            .compose(connection ->
                connection.query("SELECT 1 as value")
                    .execute()
                    .map(rowSet -> {
                        Integer result = rowSet.iterator().next().getInteger("value");
                        assertEquals(1, result);
                        return connection;
                    })
                    .compose(conn -> conn.close().map(v -> conn))
            )
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
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
    void testWithReactiveConnection(VertxTestContext testContext) {
        client.withReactiveConnection(connection ->
            connection.query("SELECT 42 as value")
                .execute()
                .map(rowSet -> {
                    assertEquals(42, rowSet.iterator().next().getInteger("value"));
                    return null;
                })
        )
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testWithReactiveConnectionResult(VertxTestContext testContext) {
        client.withReactiveConnectionResult(connection ->
            connection.query("SELECT 99 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        )
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertEquals(99, result);
                testContext.completeNow();
            })));
    }

    @Test
    void testClose() {
        // Close should not throw exception
        client.close();
    }
}
