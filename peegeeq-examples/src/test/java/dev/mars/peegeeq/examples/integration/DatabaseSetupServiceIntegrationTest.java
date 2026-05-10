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

package dev.mars.peegeeq.examples.integration;

import dev.mars.peegeeq.api.setup.*;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for DatabaseSetupService using TestContainers.
 * 
 * Tests the complete database setup functionality including:
 * - Template database creation
 * - Schema migrations
 * - Queue table creation
 * - Event store table creation
 * - Resource cleanup
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-18
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class DatabaseSetupServiceIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseSetupServiceIntegrationTest.class);
    
    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_setup_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(256 * 1024 * 1024L);
        container.withReuse(false);
        return container;
    }
    
    private DatabaseSetupService setupService;
    private String testSetupId;
    
    @BeforeEach
    void setUp() {
        // Use PeeGeeQRuntime to obtain the setup service - respects layer boundaries
        setupService = PeeGeeQRuntime.createDatabaseSetupService();
        testSetupId = "test-setup-" + System.currentTimeMillis();
        logger.info("Starting test with setup ID: {}", testSetupId);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (setupService != null) {
            setupService.close()
                    .onSuccess(v -> {
                        setupService = null;
                        ctx.completeNow();
                    })
                    .onFailure(ctx::failNow);
        } else {
            ctx.completeNow();
        }
    }

    @Test
    void testCreateCompleteSetupWithQueuesAndEventStores(Vertx vertx, VertxTestContext ctx) {
        logger.info("=== Testing Complete Database Setup Creation ===");
        
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("test_app_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();
        
        List<QueueConfig> queues = List.of(
                new QueueConfig.Builder()
                        .queueName("orders")
                        .maxRetries(3)
                        .visibilityTimeoutSeconds(30)
                        .deadLetterEnabled(true)
                        .build(),
                new QueueConfig.Builder()
                        .queueName("notifications")
                        .maxRetries(5)
                        .visibilityTimeoutSeconds(60)
                        .deadLetterEnabled(true)
                        .build()
        );
        
        List<EventStoreConfig> eventStores = List.of(
                new EventStoreConfig.Builder()
                        .eventStoreName("order-events")
                        .tableName("order_events")
                        .biTemporalEnabled(true)
                        .notificationPrefix("order_events_")
                        .build(),
                new EventStoreConfig.Builder()
                        .eventStoreName("user-events")
                        .tableName("user_events")
                        .biTemporalEnabled(true)
                        .notificationPrefix("user_events_")
                        .build()
        );
        
        DatabaseSetupRequest request = new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                queues,
                eventStores,
                Map.of("test", "true")
        );

        setupService.createCompleteSetup(request)
                .compose(result -> {
                    assertNotNull(result);
                    assertEquals(testSetupId, result.getSetupId());
                    assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus());
                    assertNotNull(result.getQueueFactories());
                    assertNotNull(result.getEventStores());
                    assertTrue(result.getCreatedAt() > 0);
                    logger.info("Setup created successfully: {}", result.getSetupId());
                    return verifyDatabaseExists(vertx, dbConfig.getDatabaseName());
                })
                .onSuccess(v -> {
                    logger.info("=== Complete Database Setup Creation Test Passed ===");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    void testGetSetupStatus(VertxTestContext ctx) {
        logger.info("=== Testing Setup Status Retrieval ===");

        setupService.createCompleteSetup(createMinimalSetupRequest())
                .compose(result -> setupService.getSetupStatus(testSetupId))
                .onComplete(ctx.succeeding(status -> ctx.verify(() -> {
                    assertNotNull(status);
                    assertEquals(DatabaseSetupStatus.ACTIVE, status);
                    ctx.completeNow();
                })));
    }

    @Test
    void testAddQueueToExistingSetup(VertxTestContext ctx) {
        logger.info("=== Testing Add Queue to Existing Setup ===");

        QueueConfig newQueue = new QueueConfig.Builder()
                .queueName("payments")
                .maxRetries(5)
                .visibilityTimeoutSeconds(45)
                .deadLetterEnabled(true)
                .build();

        setupService.createCompleteSetup(createMinimalSetupRequest())
                .compose(result -> setupService.addQueue(testSetupId, newQueue))
                .onSuccess(v -> {
                    logger.info("Queue added successfully to existing setup");
                    logger.info("=== Add Queue to Existing Setup Test Passed ===");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    void testAddEventStoreToExistingSetup(VertxTestContext ctx) {
        logger.info("=== Testing Add Event Store to Existing Setup ===");

        EventStoreConfig newEventStore = new EventStoreConfig.Builder()
                .eventStoreName("payment-events")
                .tableName("payment_events")
                .biTemporalEnabled(true)
                .notificationPrefix("payment_events_")
                .build();

        setupService.createCompleteSetup(createMinimalSetupRequest())
                .compose(result -> setupService.addEventStore(testSetupId, newEventStore))
                .onSuccess(v -> {
                    logger.info("Event store added successfully to existing setup");
                    logger.info("=== Add Event Store to Existing Setup Test Passed ===");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    void testDestroySetup(VertxTestContext ctx) {
        logger.info("=== Testing Setup Destruction ===");

        setupService.createCompleteSetup(createMinimalSetupRequest())
                .compose(result -> setupService.destroySetup(testSetupId))
                .compose(v -> setupService.getSetupStatus(testSetupId)
                        .compose(
                                status -> Future.failedFuture(new AssertionError("Expected getSetupStatus to fail after destroy")),
                                err -> Future.succeededFuture()
                        ))
                .onSuccess(v -> {
                    logger.info("Setup destroyed successfully");
                    logger.info("=== Setup Destruction Test Passed ===");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    void testInvalidSetupRequest(VertxTestContext ctx) {
        logger.info("=== Testing Invalid Setup Request Handling ===");

        DatabaseConfig invalidConfig = new DatabaseConfig.Builder()
                .host("invalid-host")
                .port(9999)
                .databaseName("invalid_db")
                .username("invalid_user")
                .password("invalid_pass")
                .build();

        DatabaseSetupRequest invalidRequest = new DatabaseSetupRequest(
                "invalid-setup",
                invalidConfig,
                List.of(),
                List.of(),
                Map.of()
        );

        setupService.createCompleteSetup(invalidRequest)
                .onSuccess(result -> ctx.failNow(new AssertionError("Expected createCompleteSetup to fail for invalid config")))
                .onFailure(err -> {
                    logger.info("Invalid setup request properly rejected");
                    logger.info("=== Invalid Setup Request Handling Test Passed ===");
                    ctx.completeNow();
                });
    }

    private DatabaseSetupRequest createMinimalSetupRequest() {
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("minimal_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema("public")
                .build();

        return new DatabaseSetupRequest(
                testSetupId,
                dbConfig,
                List.of(),
                List.of(),
                Map.of()
        );
    }

    private Future<Void> verifyDatabaseExists(Vertx vertx, String databaseName) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase("postgres")
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
        Pool pool = PgBuilder.pool()
                .connectingTo(connectOptions)
                .using(vertx)
                .build();
        return pool.preparedQuery("SELECT 1 FROM pg_database WHERE datname = $1")
                .execute(Tuple.of(databaseName))
                .compose(rows -> rows.iterator().hasNext()
                        ? Future.<Void>succeededFuture()
                        : Future.failedFuture(new AssertionError("Database should exist: " + databaseName)))
                .eventually(() -> pool.close());
    }
}

