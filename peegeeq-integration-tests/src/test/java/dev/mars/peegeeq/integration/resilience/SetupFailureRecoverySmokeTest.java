package dev.mars.peegeeq.integration.resilience;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import dev.mars.peegeeq.integration.SmokeTestBase;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Tag;
import java.util.Collections;
import java.util.UUID;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class SetupFailureRecoverySmokeTest extends SmokeTestBase {

    @Test
    void testInvalidSchemaNameRejected() throws InterruptedException {
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
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        setupService.createCompleteSetup(request)
            .onFailure(err -> {
                errorRef.set(err);
                latch.countDown();
            })
            .onSuccess(result -> latch.countDown());

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Operation timed out");
        assertNotNull(errorRef.get(), "Expected future to fail");
        assertInstanceOf(IllegalArgumentException.class, errorRef.get());
        // Use case-insensitive check as validator uses "Schema" not "schema"
        String causeMsg = errorRef.get().getMessage().toLowerCase();
        assertTrue(causeMsg.contains("invalid") && causeMsg.contains("schema") && causeMsg.contains("name"),
                   "Exception should mention invalid schema name. Got: " + errorRef.get().getMessage());
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
        CountDownLatch serviceLatch = new CountDownLatch(1);
        AtomicReference<PeeGeeQDatabaseSetupService> serviceRef = new AtomicReference<>();
        vertx.runOnContext(v -> {
            PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService(manager -> {
                throw new RuntimeException("Simulated failure in Step 4");
            });
            serviceRef.set(service);
            serviceLatch.countDown();
        });
        assertTrue(serviceLatch.await(5, TimeUnit.SECONDS), "Service creation timed out");
        PeeGeeQDatabaseSetupService failingService = serviceRef.get();

        // Execute setup and expect failure
        CountDownLatch setupLatch = new CountDownLatch(1);
        AtomicReference<Throwable> setupErrorRef = new AtomicReference<>();

        failingService.createCompleteSetup(request)
            .onFailure(err -> {
                setupErrorRef.set(err);
                setupLatch.countDown();
            })
            .onSuccess(result -> setupLatch.countDown());

        assertTrue(setupLatch.await(10, TimeUnit.SECONDS), "Setup timed out");
        assertNotNull(setupErrorRef.get(), "Expected setup to fail");

        // The exception wraps the RuntimeException from createCompleteSetup
        assertTrue(setupErrorRef.get().getMessage().contains("Failed to create database setup"));
        // The cause of that exception should be our simulated failure
        assertTrue(setupErrorRef.get().getCause().getMessage().contains("Simulated failure in Step 4"));

        // Verify failed setup was not retained as active.
        CountDownLatch statusLatch = new CountDownLatch(1);
        AtomicReference<Throwable> statusErrorRef = new AtomicReference<>();

        failingService.getSetupStatus(setupId)
            .onFailure(err -> {
                statusErrorRef.set(err);
                statusLatch.countDown();
            })
            .onSuccess(result -> statusLatch.countDown());

        assertTrue(statusLatch.await(5, TimeUnit.SECONDS), "Status check timed out");
        assertNotNull(statusErrorRef.get(), "Expected status check to fail");
        assertInstanceOf(PeeGeeQDatabaseSetupService.SetupNotFoundException.class, statusErrorRef.get(),
            "Failed setup should not remain registered as active");
    }
}
