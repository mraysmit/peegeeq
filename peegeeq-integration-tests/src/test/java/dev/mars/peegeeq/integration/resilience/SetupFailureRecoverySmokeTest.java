package dev.mars.peegeeq.integration.resilience;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

public class SetupFailureRecoverySmokeTest extends SmokeTestBase {

    @Test
    void testInvalidSchemaNameRejected() {
        String setupId = UUID.randomUUID().toString();
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("peegeeq_test_" + setupId.replace("-", "_"))
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("invalid-schema-name") // Invalid: contains hyphen
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(setupId, dbConfig, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

        // We expect the future to fail immediately with IllegalArgumentException
        Exception exception = assertThrows(ExecutionException.class, () -> {
            setupService.createCompleteSetup(request).get();
        });
        
        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(exception.getCause().getMessage().contains("Invalid schema name"));
    }

    @Test
    void testPartialSetupCleanup() throws Exception {
        String setupId = UUID.randomUUID().toString();
        String dbName = "peegeeq_fail_" + setupId.replace("-", "_");
        
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName(dbName)
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("valid_schema")
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(setupId, dbConfig, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());

        // Create a service that fails during EventStore creation (Step 4)
        // We use a provider that throws RuntimeException
        // We create it inside vertx context to ensure it shares the same Vertx instance
        CompletableFuture<PeeGeeQDatabaseSetupService> serviceFuture = new CompletableFuture<>();
        vertx.runOnContext(v -> {
            PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService(manager -> {
                throw new RuntimeException("Simulated failure in Step 4");
            });
            serviceFuture.complete(service);
        });
        PeeGeeQDatabaseSetupService failingService = serviceFuture.get();

        // Execute setup and expect failure
        Exception exception = assertThrows(ExecutionException.class, () -> {
            failingService.createCompleteSetup(request).get();
        });

        // The outer exception wraps the RuntimeException from createCompleteSetup
        assertTrue(exception.getCause().getMessage().contains("Failed to create database setup"));
        // The cause of that exception should be our simulated failure
        assertTrue(exception.getCause().getCause().getMessage().contains("Simulated failure in Step 4"));

        // Verify database does not exist
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(dbName)
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(1))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

        try {
            // Attempt to connect. If DB is gone, connection should fail.
            pool.getConnection().toCompletionStage().toCompletableFuture().get();
            fail("Database should have been dropped");
        } catch (ExecutionException e) {
            // Expected
            String msg = e.getCause().getMessage();
            assertTrue(msg.contains("database \"" + dbName + "\" does not exist"), 
                "Expected 'database does not exist' error, but got: " + msg);
        } finally {
            pool.close();
        }
    }
}
