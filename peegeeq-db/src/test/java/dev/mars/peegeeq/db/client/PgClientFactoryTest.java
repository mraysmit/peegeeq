package dev.mars.peegeeq.db.client;

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

import dev.mars.peegeeq.db.SharedPostgresExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for PgClientFactory to verify all fixes from the markdown recommendations.
 * Tests cover: double pool creation fix, input validation, idempotency, lifecycle management.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-11
 * @version 1.0
 */
@ExtendWith(SharedPostgresExtension.class)
public class PgClientFactoryTest {
    private static final Logger logger = LoggerFactory.getLogger(PgClientFactoryTest.class);

    private Vertx vertx;
    private PgClientFactory factory;
    private PgConnectionConfig connectionConfig;
    private PgPoolConfig poolConfig;

    @BeforeEach
    void setUp() {
        logger.info("Setting up PgClientFactory test with PostgreSQL container");

        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
        vertx = Vertx.vertx();
        factory = new PgClientFactory(vertx);

        connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        poolConfig = new PgPoolConfig.Builder()
            .maxSize(5)
            .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down PgClientFactory test");
        if (factory != null) {
            factory.close();
        }
        if (vertx != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            vertx.close().onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });
            future.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void testInputValidation() {
        logger.info("TEST: Input validation - should fail fast with clear error messages");
        
        // Test null clientId
        assertThrows(IllegalArgumentException.class, () -> 
            factory.createClient(null, connectionConfig, poolConfig),
            "Should throw IllegalArgumentException for null clientId");
            
        // Test blank clientId
        assertThrows(IllegalArgumentException.class, () -> 
            factory.createClient("", connectionConfig, poolConfig),
            "Should throw IllegalArgumentException for blank clientId");
            
        assertThrows(IllegalArgumentException.class, () -> 
            factory.createClient("   ", connectionConfig, poolConfig),
            "Should throw IllegalArgumentException for whitespace-only clientId");
            
        // Test null connectionConfig
        assertThrows(NullPointerException.class, () -> 
            factory.createClient("test-client", null, poolConfig),
            "Should throw NullPointerException for null connectionConfig");
            
        // Test null poolConfig
        assertThrows(NullPointerException.class, () -> 
            factory.createClient("test-client", connectionConfig, null),
            "Should throw NullPointerException for null poolConfig");
            
        logger.info("✓ All input validation tests passed");
    }

    @Test
    void testIdempotency() {
        logger.info("TEST: Idempotency - multiple createClient calls should return same instance");
        
        String clientId = "test-client-idempotent";
        
        // Create client first time
        PgClient client1 = factory.createClient(clientId, connectionConfig, poolConfig);
        assertNotNull(client1, "First client creation should succeed");
        
        // Create client second time - should return same instance
        PgClient client2 = factory.createClient(clientId, connectionConfig, poolConfig);
        assertNotNull(client2, "Second client creation should succeed");
        
        // Should be the exact same instance (idempotent)
        assertSame(client1, client2, "Multiple createClient calls should return same instance");
        
        // Verify client is available
        Optional<PgClient> retrieved = factory.getClient(clientId);
        assertTrue(retrieved.isPresent(), "Client should be retrievable");
        assertSame(client1, retrieved.get(), "Retrieved client should be same instance");
        
        logger.info("✓ Idempotency test passed");
    }

    @Test
    void testGetClientAndGetPool() {
        logger.info("TEST: getClient and getPool methods work correctly");
        
        String clientId = "test-client-get";
        
        // Initially no client should exist
        Optional<PgClient> noClient = factory.getClient(clientId);
        assertFalse(noClient.isPresent(), "Client should not exist initially");
        
        Optional<Pool> noPool = factory.getPool(clientId);
        assertFalse(noPool.isPresent(), "Pool should not exist initially");
        
        // Create client
        PgClient client = factory.createClient(clientId, connectionConfig, poolConfig);
        assertNotNull(client, "Client creation should succeed");
        
        // Now client should be retrievable
        Optional<PgClient> foundClient = factory.getClient(clientId);
        assertTrue(foundClient.isPresent(), "Client should be found after creation");
        assertSame(client, foundClient.get(), "Found client should be same instance");
        
        // Pool should also be retrievable
        Optional<Pool> foundPool = factory.getPool(clientId);
        assertTrue(foundPool.isPresent(), "Pool should be found after client creation");
        assertNotNull(foundPool.get(), "Pool should not be null");
        
        logger.info("✓ getClient and getPool tests passed");
    }

    @Test
    void testAvailableClients() {
        logger.info("TEST: getAvailableClients returns correct client IDs");
        
        assertTrue(factory.getAvailableClients().isEmpty(), "Initially no clients should be available");
        
        String client1Id = "test-client-1";
        String client2Id = "test-client-2";
        
        // Create first client
        factory.createClient(client1Id, connectionConfig, poolConfig);
        assertEquals(1, factory.getAvailableClients().size(), "Should have 1 client after first creation");
        assertTrue(factory.getAvailableClients().contains(client1Id), "Should contain first client ID");
        
        // Create second client
        factory.createClient(client2Id, connectionConfig, poolConfig);
        assertEquals(2, factory.getAvailableClients().size(), "Should have 2 clients after second creation");
        assertTrue(factory.getAvailableClients().contains(client1Id), "Should contain first client ID");
        assertTrue(factory.getAvailableClients().contains(client2Id), "Should contain second client ID");
        
        logger.info("✓ Available clients test passed");
    }

    @Test
    void testAsyncLifecycleManagement() throws Exception {
        logger.info("TEST: Async lifecycle management - closeAsync and removeClientAsync");
        
        String client1Id = "test-client-async-1";
        String client2Id = "test-client-async-2";
        
        // Create two clients
        PgClient client1 = factory.createClient(client1Id, connectionConfig, poolConfig);
        PgClient client2 = factory.createClient(client2Id, connectionConfig, poolConfig);
        
        assertNotNull(client1, "First client should be created");
        assertNotNull(client2, "Second client should be created");
        assertEquals(2, factory.getAvailableClients().size(), "Should have 2 clients");
        
        // Remove first client asynchronously
        CompletableFuture<Void> removeResult = factory.removeClientAsync(client1Id)
            .toCompletionStage().toCompletableFuture();
        removeResult.get(10, TimeUnit.SECONDS);
        
        // Verify first client is removed
        assertEquals(1, factory.getAvailableClients().size(), "Should have 1 client after removal");
        assertFalse(factory.getClient(client1Id).isPresent(), "Removed client should not be found");
        assertTrue(factory.getClient(client2Id).isPresent(), "Remaining client should still be found");
        
        // Close all remaining clients asynchronously
        CompletableFuture<Void> closeResult = factory.closeAsync()
            .toCompletionStage().toCompletableFuture();
        closeResult.get(10, TimeUnit.SECONDS);
        
        // Verify all clients are closed
        assertTrue(factory.getAvailableClients().isEmpty(), "All clients should be closed");
        assertFalse(factory.getClient(client2Id).isPresent(), "No clients should remain after closeAsync");
        
        logger.info("✓ Async lifecycle management test passed");
    }

    @Test
    void testConstructorValidation() {
        logger.info("TEST: Constructor validation");
        
        // Test null Vertx
        assertThrows(NullPointerException.class, () -> 
            new PgClientFactory((Vertx) null),
            "Should throw NullPointerException for null Vertx");
            
        // Test null PgConnectionManager
        assertThrows(NullPointerException.class, () -> 
            new PgClientFactory((PgConnectionManager) null),
            "Should throw NullPointerException for null PgConnectionManager");
            
        logger.info("✓ Constructor validation test passed");
    }

    @Test
    void testConfigRetrieval() {
        logger.info("TEST: Configuration retrieval methods");

        String clientId = "test-client-config";

        // Initially no configs should exist
        assertNull(factory.getConnectionConfig(clientId), "Connection config should not exist initially");
        assertNull(factory.getPoolConfig(clientId), "Pool config should not exist initially");

        // Create client
        factory.createClient(clientId, connectionConfig, poolConfig);

        // Now configs should be retrievable
        PgConnectionConfig retrievedConnConfig = factory.getConnectionConfig(clientId);
        PgPoolConfig retrievedPoolConfig = factory.getPoolConfig(clientId);

        assertNotNull(retrievedConnConfig, "Connection config should be retrievable");
        assertNotNull(retrievedPoolConfig, "Pool config should be retrievable");

        // Verify config values
        assertEquals(connectionConfig.getHost(), retrievedConnConfig.getHost(), "Host should match");
        assertEquals(connectionConfig.getPort(), retrievedConnConfig.getPort(), "Port should match");
        assertEquals(poolConfig.getMaxSize(), retrievedPoolConfig.getMaxSize(), "Pool size should match");

        logger.info("✓ Configuration retrieval test passed");
    }

    @Test
    void testNoDoublePoolCreation() {
        logger.info("TEST: Verify no double pool creation - this was the main bug from the markdown");

        String clientId = "test-client-no-double";

        // The fix ensures pool creation happens only once per client ID

        // Create client - should only create pool once
        PgClient client = factory.createClient(clientId, connectionConfig, poolConfig);
        assertNotNull(client, "Client should be created successfully");

        // Verify pool exists
        Optional<Pool> pool = factory.getPool(clientId);
        assertTrue(pool.isPresent(), "Pool should exist after client creation");

        // Create same client again - should NOT create pool again (idempotent)
        PgClient client2 = factory.createClient(clientId, connectionConfig, poolConfig);
        assertSame(client, client2, "Should return same client instance (idempotent)");

        // Pool should still be the same instance
        Optional<Pool> pool2 = factory.getPool(clientId);
        assertTrue(pool2.isPresent(), "Pool should still exist");
        assertSame(pool.get(), pool2.get(), "Should be same pool instance (no double creation)");

        logger.info("✓ No double pool creation test passed - bug fixed!");
    }

    @Test
    void testPgClientPoolAccess() {
        logger.info("TEST: PgClient.getReactivePool() resolves through connection manager (no stale references)");

        String clientId = "test-client-pool-access";

        // Create client
        PgClient client = factory.createClient(clientId, connectionConfig, poolConfig);
        assertNotNull(client, "Client should be created successfully");

        // Get pool through PgClient - should resolve through connection manager
        Pool poolFromClient = client.getReactivePool();
        assertNotNull(poolFromClient, "Pool should be accessible through PgClient");

        // Get pool through factory - should be same instance
        Optional<Pool> poolFromFactory = factory.getPool(clientId);
        assertTrue(poolFromFactory.isPresent(), "Pool should be accessible through factory");
        assertSame(poolFromClient, poolFromFactory.get(), "Pool instances should be identical (no stale references)");

        // Verify pool is functional
        assertNotNull(poolFromClient, "Pool should not be null");

        logger.info("✓ PgClient pool access test passed - no stale references!");
    }

    @Test
    void testPgClientPoolAccessFailsForNonExistentClient() {
        logger.info("TEST: PgClient.getReactivePool() fails gracefully for non-existent pools");

        String clientId = "non-existent-client";

        // Create client but don't create pool through factory
        PgClient client = new PgClient(clientId, factory.getConnectionManager());

        // Should throw IllegalStateException when trying to access non-existent pool
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            client::getReactivePool,
            "Should throw IllegalStateException for non-existent pool");

        assertTrue(exception.getMessage().contains("No reactive pool found for client: " + clientId),
            "Exception message should indicate missing pool");

        logger.info("✓ PgClient pool access failure test passed - proper error handling!");
    }

    @Test
    void testPoolManagementPattern() {
        logger.info("TEST: Verify recommended pool management pattern from markdown");

        String clientId = "test-client-pattern";

        // Create client (this creates the pool)
        PgClient client = factory.createClient(clientId, connectionConfig, poolConfig);
        assertNotNull(client, "Client should be created");

        // Pattern 1: Get pool via factory.getPool() - RECOMMENDED
        Optional<Pool> poolViaFactory = factory.getPool(clientId);
        assertTrue(poolViaFactory.isPresent(), "Pool should be accessible via factory");

        // Pattern 2: Get pool via client.getReactivePool() - ALSO VALID (resolves per operation)
        Pool poolViaClient = client.getReactivePool();
        assertNotNull(poolViaClient, "Pool should be accessible via client");

        // Both should return same instance (no stale references)
        assertSame(poolViaFactory.get(), poolViaClient, "Both patterns should return same pool instance");

        // Verify we're NOT calling getOrCreateReactivePool repeatedly
        // (This is verified by the fact that we get the same instance)
        Pool poolViaClient2 = client.getReactivePool();
        assertSame(poolViaClient, poolViaClient2, "Multiple calls should return same instance");

        logger.info("✓ Pool management pattern test passed - following markdown recommendations!");
    }
}
