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
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for PgConnectionProvider using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
public class PgConnectionProviderCoreTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PgConnectionProviderCoreTest.class);

    private PgConnectionManager connectionManager;
    private PgClientFactory clientFactory;
    private PgConnectionProvider connectionProvider;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        try {
            connectionManager = new PgConnectionManager(manager.getVertx());
            clientFactory = new PgClientFactory(connectionManager);
            
            PostgreSQLContainer postgres = getPostgres();
            PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

            PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
            clientFactory.createClient("test-client", connectionConfig, poolConfig);
            
            connectionProvider = new PgConnectionProvider(clientFactory);
            testContext.completeNow();
        } catch (Exception e) {
            testContext.failNow(e);
        }
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
    void testPgConnectionProviderCreation(VertxTestContext testContext) {
        assertNotNull(connectionProvider);
        testContext.completeNow();
    }

    @Test
    void testGetReactivePool(VertxTestContext testContext) {
        connectionProvider.getReactivePool("test-client")
            .onSuccess(pool -> {
                assertNotNull(pool);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    /**
     * Verifies that {@link PgConnectionProvider#getReactivePool(String)} returns a failed
     * {@code Future} for an unregistered client name, logging an ERROR.
     *
     * <p><strong>INTENTIONAL ERROR TEST:</strong> The next ERROR log
     * ('Failed to get reactive connection for client: non-existent-client') is EXPECTED —
     * this test deliberately requests a pool for an unknown client to verify error propagation.
     */
    @Test
    void testGetReactivePoolNonExistentClient(VertxTestContext testContext) {
        logger.error("===== INTENTIONAL ERROR TEST ===== The next ERROR log ('Failed to get reactive connection for client: non-existent-client') is EXPECTED this test deliberately requests a pool for an unregistered client name");
        connectionProvider.getReactivePool("non-existent-client")
            .onSuccess(pool -> testContext.failNow("Expected Exception"))
            .onFailure(err -> {
                assertTrue(err instanceof IllegalArgumentException);
                testContext.completeNow();
            });
    }

    @Test
    void testGetConnection(VertxTestContext testContext) {
        connectionProvider.getConnection("test-client")
            .onSuccess(connection -> {
                assertNotNull(connection);
                connection.close().onSuccess(v -> testContext.completeNow()).onFailure(testContext::failNow);
            })
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetConnectionNonExistentClient(VertxTestContext testContext) {
        connectionProvider.getConnection("non-existent-client")
            .onSuccess(pool -> testContext.failNow("Expected Exception"))
            .onFailure(err -> testContext.completeNow());
    }

    @Test
    void testWithConnection(VertxTestContext testContext) {
        connectionProvider.withConnection("test-client", connection ->
            connection.query("SELECT 1 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        ).onSuccess(result -> {
            assertEquals(1, result);
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    /**
     * Verifies that {@link PgConnectionProvider#withConnection(String, java.util.function.Function)}
     * returns a failed {@code Future} for an unregistered client name, logging an ERROR.
     *
     * <p><strong>INTENTIONAL ERROR TEST:</strong> The next ERROR log
     * ('Failed to execute operation with connection for client: non-existent-client') is EXPECTED —
     * this test deliberately uses an unknown client name to verify the error propagation path.
     */
    @Test
    void testWithConnectionNonExistentClient(VertxTestContext testContext) {
        logger.error("===== INTENTIONAL ERROR TEST ===== The next ERROR log ('Failed to execute operation with connection for client: non-existent-client') is EXPECTED this test deliberately uses an unregistered client name");
        connectionProvider.withConnection("non-existent-client", connection ->
            connection.query("SELECT 1")
                .execute()
                .map(rowSet -> 1)
        )
        .onSuccess(result -> testContext.failNow("Expected exception"))
        .onFailure(err -> testContext.completeNow());
    }

    @Test
    void testWithTransaction(VertxTestContext testContext) {
        connectionProvider.withTransaction("test-client", connection ->
            connection.query("SELECT 1 as value")
                .execute()
                .map(rowSet -> rowSet.iterator().next().getInteger("value"))
        ).onSuccess(result -> {
            assertEquals(1, result);
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    /**
     * Verifies that {@link PgConnectionProvider#withTransaction(String, java.util.function.Function)}
     * returns a failed {@code Future} for an unregistered client name, logging an ERROR.
     *
     * <p><strong>INTENTIONAL ERROR TEST:</strong> The next ERROR log
     * ('Failed to execute operation with transaction for client: non-existent-client') is EXPECTED —
     * this test deliberately uses an unknown client name to verify the error propagation path.
     */
    @Test
    void testWithTransactionNonExistentClient(VertxTestContext testContext) {
        logger.error("===== INTENTIONAL ERROR TEST ===== The next ERROR log ('Failed to execute operation with transaction for client: non-existent-client') is EXPECTED this test deliberately uses an unregistered client name");
        connectionProvider.withTransaction("non-existent-client", connection ->
            connection.query("SELECT 1")
                .execute()
                .map(rowSet -> 1)
        )
        .onSuccess(result -> testContext.failNow("Expected exception"))
        .onFailure(err -> testContext.completeNow());
    }
}

