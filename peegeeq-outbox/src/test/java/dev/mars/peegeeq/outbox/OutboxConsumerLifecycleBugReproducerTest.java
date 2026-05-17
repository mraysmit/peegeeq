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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for consumer lifecycle during manager shutdown.
 *
 * <p>Originally reproduced a bug where PeeGeeQManager.closeReactive() destroyed DB pools
 * while outbox consumers were still polling, producing "Client not found: null" and
 * "closed or shutting down" errors.</p>
 *
 * <p>The bug is now fixed: OutboxFactory registers a close hook that calls
 * {@code consumer.close()} before the manager destroys pools.
 * This test is a regression guard it asserts that no such errors appear.</p>
 *
 * <p>Scenario:</p>
 * <ol>
 *   <li>Start a manager, factory, and subscribed consumer</li>
 *   <li>Call manager.closeReactive() without explicitly closing the consumer first</li>
 *   <li>Wait for pools to be destroyed and any stray polls to fire</li>
 *   <li>Assert that NO "Client not found" or "closed or shutting down" errors appear
 *       (the close hook must have cleanly stopped the consumer first)</li>
 * </ol>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxConsumerLifecycleBugReproducerTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerLifecycleBugReproducerTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageConsumer<String> consumer;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            DatabaseService databaseService = new PgDatabaseService(manager);
            outboxFactory = new OutboxFactory(databaseService, config);

            String topic = "lifecycle-bug-" + UUID.randomUUID().toString().substring(0, 8);
            consumer = outboxFactory.createConsumer(topic, String.class);

            // Attach a Logback ListAppender to capture log output from OutboxConsumer
            // and PgConnectionProvider the two classes that emit the error messages
            logAppender = new ListAppender<>();
            logAppender.start();

            ch.qos.logback.classic.Logger consumerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OutboxConsumer.class);
            consumerLogger.addAppender(logAppender);

            ch.qos.logback.classic.Logger providerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                    "dev.mars.peegeeq.db.provider.PgConnectionProvider");
            providerLogger.addAppender(logAppender);
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown() {
        logger.info("Tearing down: closing resources and manager");
        if (logAppender != null) {
            ch.qos.logback.classic.Logger consumerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OutboxConsumer.class);
            consumerLogger.detachAppender(logAppender);

            ch.qos.logback.classic.Logger providerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                    "dev.mars.peegeeq.db.provider.PgConnectionProvider");
            providerLogger.detachAppender(logAppender);

            logAppender.stop();
        }

        // Force-close the consumer if still alive (cleanup only)
        if (consumer != null) {
            try { consumer.close(); } catch (Exception e) { logger.warn("consumer.close() failed", e); }
        }
        if (outboxFactory != null) {
            try { outboxFactory.close(); } catch (Exception e) { logger.warn("outboxFactory.close() failed", e); }
        }
    }

    @Test
    void consumerPollingAfterManagerCloseProducesClientNotFoundErrors(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        // Subscribe the consumer this starts polling
        consumer.subscribe(message -> io.vertx.core.Future.succeededFuture());

        // Give the consumer a moment to start polling and establish the pool,
        // then close the manager WITHOUT explicitly closing the consumer/factory first.
        // If the OutboxFactory close hook properly calls consumer.close(), the consumer
        // will be cleanly stopped before pools are destroyed the bug is fixed.
        // If the hook is a no-op, the consumer keeps polling and hits "Client not found".
        vertx.timer(500)
                .compose(v -> manager.closeReactive())
                .compose(v -> vertx.timer(2000))
                .onSuccess(v -> {
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

                    long errorCount = events.stream()
                            .filter(e -> e.getFormattedMessage().contains("Client not found")
                                    || e.getFormattedMessage().contains("closed or shutting down")
                                    || e.getFormattedMessage().contains("Connection provider unavailable"))
                            .count();

                    if (bugReproduced) {
                        logger.info("=== BUG REPRODUCED === {} shutdown-related error(s) captured", errorCount);
                    } else {
                        logger.info("=== BUG FIXED === consumer was closed cleanly by OutboxFactory hook. {} log events captured.", events.size());
                    }

                    // The OutboxFactory close hook now properly calls consumer.close() before
                    // pools are destroyed, so no "Client not found" errors should appear.
                    // This test is a regression guard: if the close hook is broken in future,
                    // error messages will reappear and this assertion will catch it.
                    testContext.verify(() -> assertFalse(bugReproduced,
                            "OutboxFactory close hook should have cleanly closed the consumer before pools "
                                    + "were destroyed, but " + errorCount + " shutdown-related error(s) appeared."));
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }
}
