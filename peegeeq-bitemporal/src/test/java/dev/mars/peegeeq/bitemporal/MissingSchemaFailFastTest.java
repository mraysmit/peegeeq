package dev.mars.peegeeq.bitemporal;

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

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Negative test proving PeeGeeQManager.start() fails fast when core tables are missing.
 *
 * <p>This test deliberately does NOT run Flyway migrations, leaving the database empty.
 * It verifies that PeeGeeQManager rejects startup with a clear error listing the missing
 * tables, rather than silently swallowing the errors and running in a broken state.</p>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class MissingSchemaFailFastTest {
    private static final Logger logger = LoggerFactory.getLogger(MissingSchemaFailFastTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer postgres = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_no_schema_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withReuse(false);

    private PeeGeeQManager peeGeeQManager;
    private final Map<String, String> originalProperties = new HashMap<>();

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (peeGeeQManager != null) {
            peeGeeQManager.closeReactive()
                .recover(err -> io.vertx.core.Future.succeededFuture())
                .onComplete(v -> {
                    restoreTestProperties();
                    testContext.completeNow();
                });
        } else {
            restoreTestProperties();
            testContext.completeNow();
        }
    }

    /**
     * Verifies that PeeGeeQManager.start() fails with a clear error when
     * no Flyway migrations have been run and core tables do not exist.
     */
    @Test
    void startFailsWhenCoreTables_AreMissing(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing PeeGeeQManager fails fast with missing schema ===");

        // Point PeeGeeQManager at the empty database — NO initializeSchema() call
        configureSystemPropertiesForContainer(postgres);

        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());

        peeGeeQManager.start()
            .onSuccess(v -> testContext.failNow(
                new AssertionError("PeeGeeQManager.start() should have failed — core tables are missing")))
            .onFailure(error -> testContext.verify(() -> {
                logger.info("PeeGeeQManager.start() correctly failed: {}", error.getMessage());

                // Walk the cause chain to find the IllegalStateException with the table list
                Throwable cause = error;
                boolean foundMissingTablesError = false;
                while (cause != null) {
                    String msg = cause.getMessage();
                    if (msg != null && msg.contains("required tables missing")) {
                        foundMissingTablesError = true;
                        // Verify all four core tables are listed
                        assertTrue(msg.contains("outbox"), "Error should list 'outbox' as missing: " + msg);
                        assertTrue(msg.contains("queue_messages"), "Error should list 'queue_messages' as missing: " + msg);
                        assertTrue(msg.contains("dead_letter_queue"), "Error should list 'dead_letter_queue' as missing: " + msg);
                        assertTrue(msg.contains("outbox_topic_subscriptions"),
                            "Error should list 'outbox_topic_subscriptions' as missing: " + msg);
                        break;
                    }
                    cause = cause.getCause();
                }
                assertTrue(foundMissingTablesError,
                    "Error chain should contain 'required tables missing' but was: " + error.getMessage());

                logger.info("PASSED: PeeGeeQManager correctly rejected startup with missing core tables");
                testContext.completeNow();
            }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
        if (testContext.failed()) {
            throw new RuntimeException(testContext.causeOfFailure());
        }
    }

    private void configureSystemPropertiesForContainer(PostgreSQLContainer container) {
        setTestProperty("peegeeq.database.host", container.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(container.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", container.getDatabaseName());
        setTestProperty("peegeeq.database.username", container.getUsername());
        setTestProperty("peegeeq.database.password", container.getPassword());
        setTestProperty("peegeeq.database.schema", "public");
        setTestProperty("peegeeq.database.ssl.enabled", "false");
        setTestProperty("peegeeq.database.pool.shared", "false");
    }

    private void setTestProperty(String key, String value) {
        originalProperties.putIfAbsent(key, System.getProperty(key));
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void restoreTestProperties() {
        for (Map.Entry<String, String> entry : originalProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        originalProperties.clear();
    }
}
