package dev.mars.peegeeq.outbox;

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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the "Client not found: null" and "closed or shutting down" errors
 * that occur when PeeGeeQManager.closeReactive() destroys DB pools while outbox
 * consumers are still polling.
 *
 * <p>Root cause: OutboxFactory registers a no-op close hook with the manager.
 * During shutdown, step 2 (close hooks) does nothing for outbox consumers, so
 * they keep polling. Step 5 (close client factory) clears the pool maps. The
 * still-running consumer polls then hit "Client not found: null" from
 * PgConnectionProvider.getReactivePool().</p>
 *
 * <p>This test proves the bug by:</p>
 * <ol>
 *   <li>Starting a manager, factory, and subscribed consumer</li>
 *   <li>Calling manager.closeReactive() WITHOUT closing the consumer first
 *       (the close hook is a no-op, so the consumer stays alive)</li>
 *   <li>Waiting for the consumer's scheduler to fire after pools are gone</li>
 *   <li>Asserting that "Client not found" or "closed or shutting down" errors
 *       appear in the captured log output</li>
 * </ol>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxConsumerLifecycleBugReproducerTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageConsumer<String> consumer;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("lifecycle-bug-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);

        String topic = "lifecycle-bug-" + UUID.randomUUID().toString().substring(0, 8);
        consumer = outboxFactory.createConsumer(topic, String.class);

        // Attach a Logback ListAppender to capture log output from OutboxConsumer
        // and PgConnectionProvider — the two classes that emit the error messages
        logAppender = new ListAppender<>();
        logAppender.start();

        Logger consumerLogger = (Logger) LoggerFactory.getLogger(OutboxConsumer.class);
        consumerLogger.addAppender(logAppender);

        Logger providerLogger = (Logger) LoggerFactory.getLogger(
                "dev.mars.peegeeq.db.provider.PgConnectionProvider");
        providerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.info("Tearing down: closing resources and manager");
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");

        if (logAppender != null) {
            Logger consumerLogger = (Logger) LoggerFactory.getLogger(OutboxConsumer.class);
            consumerLogger.detachAppender(logAppender);

            Logger providerLogger = (Logger) LoggerFactory.getLogger(
                    "dev.mars.peegeeq.db.provider.PgConnectionProvider");
            providerLogger.detachAppender(logAppender);

            logAppender.stop();
        }

        // Force-close the consumer if still alive (cleanup only)
        if (consumer != null) {
            try { consumer.close(); } catch (Exception ignored) { }
        }
        if (outboxFactory != null) {
            try { outboxFactory.close(); } catch (Exception ignored) { }
        }
    }

    @Test
    void consumerPollingAfterManagerCloseProducesClientNotFoundErrors(VertxTestContext testContext) throws Exception {
        // Subscribe the consumer — this starts the ScheduledExecutorService polling loop
        consumer.subscribe(message -> {
            // No-op handler; we don't care about message processing
            return io.vertx.core.Future.succeededFuture();
        });

        // Give the consumer a moment to start polling and establish the pool
        Thread.sleep(500);

        // Now close the manager WITHOUT closing the consumer/factory first.
        // The OutboxFactory close hook is a no-op, so the consumer stays alive.
        // Step 5 of closeReactive() destroys the client factory / pool maps.
        // The consumer's next poll cycle will hit "Client not found: null".
        manager.closeReactive()
                .onSuccess(v -> {
                    // After manager close completes, the pools are gone.
                    // Wait for the consumer's scheduler to fire a few poll cycles
                    // against the destroyed infrastructure.
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    // Check captured logs for the bug's signature errors
                    List<ILoggingEvent> events = logAppender.list;
                    boolean hasClientNotFound = events.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("Client not found"));
                    boolean hasClosedOrShuttingDown = events.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("closed or shutting down"));
                    boolean hasConnectionProviderUnavailable = events.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("Connection provider unavailable"));

                    boolean bugReproduced = hasClientNotFound || hasClosedOrShuttingDown
                            || hasConnectionProviderUnavailable;

                    if (bugReproduced) {
                        long errorCount = events.stream()
                                .filter(e -> e.getFormattedMessage().contains("Client not found")
                                        || e.getFormattedMessage().contains("closed or shutting down")
                                        || e.getFormattedMessage().contains("Connection provider unavailable"))
                                .count();
                        // Log for visibility in test output
                        System.out.println("=== BUG REPRODUCED === " + errorCount
                                + " shutdown-related error(s) captured from a single consumer");
                    }

                    // The test PASSES if the bug IS reproduced — we are proving the bug exists.
                    // After the fix, this assertion should be flipped to assertFalse.
                    assertTrue(bugReproduced,
                            "Expected 'Client not found' or 'closed or shutting down' errors from "
                                    + "consumer polling after manager destroyed pools, but none appeared. "
                                    + "Captured " + events.size() + " log events.");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }
}
