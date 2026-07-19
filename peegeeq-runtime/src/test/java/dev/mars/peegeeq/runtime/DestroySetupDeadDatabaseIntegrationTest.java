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

package dev.mars.peegeeq.runtime;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the VERIFIED teardown contract against a dead database: with the database killed out
 * from under a live setup (an active subscribed consumer holding a LISTEN connection),
 * destroySetup still completes successfully and promptly — closing pools/connections is
 * local socket work and does not require the server to be alive.
 *
 * <p>This matters because destroySetup now surfaces genuine close failures (detach/DELETE
 * can return 503 on a failed close): this test proves a dead database is NOT such a failure,
 * i.e. detach never spuriously errors or hangs just because the database went away. Verified
 * by a live probe run (2026-07-18): all closes succeed against the stopped container.
 *
 * <p>The container is OWNED BY THIS CLASS and deliberately stopped mid-test — it must
 * never be shared with or reused by another suite ({@code withReuse(false)}).
 */
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag(TestCategories.INTEGRATION)
class DestroySetupDeadDatabaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DestroySetupDeadDatabaseIntegrationTest.class);

    // Managed manually (not @Container): the test itself stops the container mid-run.
    private PostgreSQLContainer postgres;
    private DatabaseSetupService setupService;
    private String setupId;
    private MessageConsumer<String> consumer;

    @BeforeAll
    void setUp() {
        postgres = PostgreSQLTestConstants.createContainer(
                "peegeeq_dead_db_test",
                "peegeeq_test",
                "peegeeq_test"
        );
        postgres.start();
        setupService = PeeGeeQRuntime.createDatabaseSetupService();
        setupId = "dead-db-test-" + System.currentTimeMillis();
        logger.info("=== Dead-database destroySetup test — container {}:{} ===",
                postgres.getHost(), postgres.getFirstMappedPort());
    }

    @AfterAll
    void tearDown(VertxTestContext ctx) {
        Future<Void> close = setupService != null ? setupService.close() : Future.succeededFuture();
        close
                .onSuccess(v -> {
                    if (postgres != null && postgres.isRunning()) {
                        postgres.stop();
                    }
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(1)
    @DisplayName("setup with a live subscribed consumer")
    void createSetupWithLiveConsumer(VertxTestContext ctx) {
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .databaseName("dead_db_" + System.currentTimeMillis())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        QueueConfig queueConfig = new QueueConfig.Builder()
                .queueName("deadq")
                .maxRetries(3)
                .visibilityTimeoutSeconds(30)
                .build();

        DatabaseSetupRequest request = new DatabaseSetupRequest(
                setupId, dbConfig, List.of(queueConfig), List.of(), Map.of());

        setupService.createCompleteSetup(request)
                .compose(result -> {
                    QueueFactory factory = result.getQueueFactories().get("deadq");
                    assertNotNull(factory, "native factory for 'deadq' should exist");
                    consumer = factory.createConsumer("deadq", String.class);
                    // Subscribe so a real LISTEN connection is live when the database dies.
                    return consumer.subscribe(message -> Future.succeededFuture());
                })
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
    }

    @Test
    @Order(2)
    @Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
    @DisplayName("destroySetup completes successfully and promptly against a dead database")
    void destroySetupWithDeadDatabaseStillSucceeds(VertxTestContext ctx) {
        // Kill the database for real, on the JUnit thread (container stop is blocking).
        postgres.stop();
        logger.info("Container stopped — calling destroySetup against the dead database");

        // Closing pools/connections is local, so a dead database must NOT fail (or hang) the
        // teardown — destroySetup only fails on genuine close failures, and this is not one.
        setupService.destroySetup(setupId)
                .onSuccess(v -> {
                    logger.info("destroySetup succeeded against the dead database, as verified");
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }
}
