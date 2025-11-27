package dev.mars.peegeeq.db.client;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for PgClientFactory using TestContainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
public class PgClientFactoryCoreTest extends BaseIntegrationTest {

    private PgClientFactory factory;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        factory = new PgClientFactory(manager.getVertx(), meterRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.closeAsync().toCompletionStage().toCompletableFuture().get();
        }
    }

    @Test
    void testPgClientFactoryCreation() {
        assertNotNull(factory);
    }

    @Test
    void testPgClientFactoryCreationWithVertx() throws Exception {
        PgClientFactory f = new PgClientFactory(manager.getVertx());
        assertNotNull(f);
        f.closeAsync().toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testPgClientFactoryCreationWithConnectionManager() throws Exception {
        PgConnectionManager cm = new PgConnectionManager(manager.getVertx());
        PgClientFactory f = new PgClientFactory(cm);
        assertNotNull(f);
        f.closeAsync().toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testPgClientFactoryCreationWithNullVertx() {
        assertThrows(NullPointerException.class, () -> new PgClientFactory((io.vertx.core.Vertx) null));
    }

    @Test
    void testPgClientFactoryCreationWithNullConnectionManager() {
        assertThrows(NullPointerException.class, () -> new PgClientFactory((PgConnectionManager) null));
    }

    @Test
    void testCreateClient() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        PgClient client = factory.createClient("test-client", connectionConfig, poolConfig);
        assertNotNull(client);

        // Verify we get the same client on second call
        PgClient client2 = factory.createClient("test-client", connectionConfig, poolConfig);
        assertSame(client, client2);
    }

    @Test
    void testCreateClientWithDefaultPoolConfig() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgClient client = factory.createClient("test-client-default", connectionConfig);
        assertNotNull(client);
    }

    @Test
    void testCreateClientWithNullClientId() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        assertThrows(IllegalArgumentException.class, () ->
            factory.createClient(null, connectionConfig, poolConfig)
        );
    }

    @Test
    void testCreateClientWithBlankClientId() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        assertThrows(IllegalArgumentException.class, () ->
            factory.createClient("", connectionConfig, poolConfig)
        );
    }

    @Test
    void testCreateClientWithNullConnectionConfig() {
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        assertThrows(NullPointerException.class, () ->
            factory.createClient("test-client", null, poolConfig)
        );
    }

    @Test
    void testCreateClientWithNullPoolConfig() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        assertThrows(NullPointerException.class, () ->
            factory.createClient("test-client", connectionConfig, null)
        );
    }

    @Test
    void testCreateClientWithDifferentConnectionConfig() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig1 = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgConnectionConfig connectionConfig2 = new PgConnectionConfig.Builder()
            .host("different-host")
            .port(5432)
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        factory.createClient("test-client", connectionConfig1, poolConfig);

        assertThrows(IllegalStateException.class, () ->
            factory.createClient("test-client", connectionConfig2, poolConfig)
        );
    }

    @Test
    void testCreateClientWithDifferentPoolConfig() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig1 = new PgPoolConfig.Builder().maxSize(10).build();
        PgPoolConfig poolConfig2 = new PgPoolConfig.Builder().maxSize(20).build();

        factory.createClient("test-client", connectionConfig, poolConfig1);

        assertThrows(IllegalStateException.class, () ->
            factory.createClient("test-client", connectionConfig, poolConfig2)
        );
    }

    @Test
    void testCreateClientWithPreviousConfig() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        factory.createClient("test-client", connectionConfig, poolConfig);

        // Should be able to create client with just ID
        PgClient client = factory.createClient("test-client");
        assertNotNull(client);
    }

    @Test
    void testCreateClientWithoutPreviousConfig() {
        assertThrows(IllegalStateException.class, () ->
            factory.createClient("non-existent-client")
        );
    }

    @Test
    void testGetClient() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        factory.createClient("test-client", connectionConfig, poolConfig);

        Optional<PgClient> client = factory.getClient("test-client");
        assertTrue(client.isPresent());
    }

    @Test
    void testGetClientNonExistent() {
        Optional<PgClient> client = factory.getClient("non-existent");
        assertFalse(client.isPresent());
    }

    @Test
    void testGetPool() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        factory.createClient("test-client", connectionConfig, poolConfig);

        Optional<Pool> pool = factory.getPool("test-client");
        assertTrue(pool.isPresent());
    }

    @Test
    void testGetPoolNonExistent() {
        Optional<Pool> pool = factory.getPool("non-existent");
        assertFalse(pool.isPresent());
    }

    @Test
    void testGetConnectionConfig() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        factory.createClient("test-client", connectionConfig, poolConfig);

        PgConnectionConfig retrieved = factory.getConnectionConfig("test-client");
        assertNotNull(retrieved);
        assertEquals(connectionConfig, retrieved);
    }

    @Test
    void testGetConnectionConfigNonExistent() {
        PgConnectionConfig config = factory.getConnectionConfig("non-existent");
        assertNull(config);
    }

    @Test
    void testGetPoolConfig() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        factory.createClient("test-client", connectionConfig, poolConfig);

        PgPoolConfig retrieved = factory.getPoolConfig("test-client");
        assertNotNull(retrieved);
        assertEquals(poolConfig, retrieved);
    }

    @Test
    void testGetPoolConfigNonExistent() {
        PgPoolConfig config = factory.getPoolConfig("non-existent");
        assertNull(config);
    }

    @Test
    void testGetConnectionManager() {
        assertNotNull(factory.getConnectionManager());
    }

    @Test
    void testGetAvailableClients() {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        factory.createClient("client1", connectionConfig, poolConfig);
        factory.createClient("client2", connectionConfig, poolConfig);

        Set<String> clients = factory.getAvailableClients();
        assertEquals(2, clients.size());
        assertTrue(clients.contains("client1"));
        assertTrue(clients.contains("client2"));
    }

    @Test
    void testRemoveClientAsync() throws Exception {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        factory.createClient("test-client", connectionConfig, poolConfig);

        factory.removeClientAsync("test-client").toCompletionStage().toCompletableFuture().get();

        Optional<PgClient> client = factory.getClient("test-client");
        assertFalse(client.isPresent());
    }

    @Test
    void testRemoveClientAsyncNonExistent() throws Exception {
        // Should not throw exception
        factory.removeClientAsync("non-existent").toCompletionStage().toCompletableFuture().get();
    }

    @Test
    void testCloseAsync() throws Exception {
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();

        factory.createClient("test-client", connectionConfig, poolConfig);

        factory.closeAsync().toCompletionStage().toCompletableFuture().get();

        Set<String> clients = factory.getAvailableClients();
        assertEquals(0, clients.size());
    }
}

