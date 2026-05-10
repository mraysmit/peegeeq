package dev.mars.peegeeq.integration.resilience;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@ExtendWith(VertxExtension.class)
public class SetupFailureRecoverySmokeTest extends SmokeTestBase {

    @Test
    void testInvalidSchemaNameRejected(Vertx vertx, VertxTestContext testContext) {
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

        setupService.createCompleteSetup(request)
            .onSuccess(result -> testContext.failNow(new AssertionError("Expected future to fail with IllegalArgumentException, but it succeeded")))
            .onFailure(err -> testContext.verify(() -> {
                assertNotNull(err);
                assertInstanceOf(IllegalArgumentException.class, err);
                // Use case-insensitive check as validator uses "Schema" not "schema"
                String causeMsg = err.getMessage().toLowerCase();
                assertTrue(causeMsg.contains("invalid") && causeMsg.contains("schema") && causeMsg.contains("name"),
                        "Exception should mention invalid schema name. Got: " + err.getMessage());
                testContext.completeNow();
            }));
    }

    @Test
    void testPartialSetupCleanup(Vertx vertx, VertxTestContext testContext) {
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

        PeeGeeQDatabaseSetupService failingService = new PeeGeeQDatabaseSetupService(manager -> {
            throw new RuntimeException("Simulated failure in Step 4");
        });

        // Phase 1: verify setup fails with the expected error
        // Phase 2: verify the failed setup is not retained as active
        Checkpoint checkpoint = testContext.checkpoint(2);

        failingService.createCompleteSetup(request)
            .onSuccess(v -> testContext.failNow(new AssertionError("Expected setup to fail")))
            .onFailure(setupErr -> {
                testContext.verify(() -> {
                    assertNotNull(setupErr);
                    assertTrue(setupErr.getMessage().contains("Failed to create database setup"),
                            "Got: " + setupErr.getMessage());
                    assertTrue(setupErr.getCause().getMessage().contains("Simulated failure in Step 4"),
                            "Got: " + setupErr.getCause().getMessage());
                });
                checkpoint.flag();

                // Verify failed setup was not retained as active
                failingService.getSetupStatus(setupId)
                    .onSuccess(v -> testContext.failNow(
                            new AssertionError("Expected status check to fail with SetupNotFoundException")))
                    .onFailure(statusErr -> testContext.verify(() -> {
                        assertInstanceOf(PeeGeeQDatabaseSetupService.SetupNotFoundException.class, statusErr,
                                "Failed setup should not remain registered as active");
                        checkpoint.flag();
                    }));
            });
    }
}
