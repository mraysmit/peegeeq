package dev.mars.peegeeq.pgqueue;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.util.Properties;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vertx.core.Future;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for PgNativeQueue shutdown scenarios and race condition handling.
 * Validates the fixes for "Pool closed" errors during shutdown.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-07
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PgNativeQueueShutdownTest {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueShutdownTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgNativeQueueFactory queueFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Configure test properties - following existing pattern exactly
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.queue.polling-interval", "PT1S")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .property("peegeeq.metrics.enabled", "true")
                .property("peegeeq.circuit-breaker.enabled", "true")
                .build();

        // Ensure required schema exists for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        // Initialize native queue components using DatabaseService pattern
        DatabaseService databaseService = new PgDatabaseService(manager);
        queueFactory = new PgNativeQueueFactory(databaseService);
        producer = queueFactory.createProducer("test-native-topic", String.class);
        consumer = queueFactory.createConsumer("test-native-topic", String.class);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        try {
            if (consumer != null) consumer.close();
            if (producer != null) producer.close();
        } catch (Exception e) {
            logger.warn("Error closing consumer/producer during teardown: {}", e.getMessage());
        }
        (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.warn("Error during teardown: {}", err.getMessage());
                testContext.completeNow();
            });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testBasicShutdownWithoutErrors(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: basic shutdown without errors");
        // Step 1: Send a simple message
        String testMessage = "Basic shutdown test";
        producer.send(testMessage).await();

        // Step 2: Process the message
        AtomicBoolean messageReceived = new AtomicBoolean(false);

        consumer.subscribe(message -> {
            messageReceived.set(true);
            testContext.completeNow();
            return Future.succeededFuture();
        });

        // Step 3: Wait for processing
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertTrue(messageReceived.get());

        // Step 4: Close consumer (this should not produce "Pool closed" errors)
        consumer.close();
    }

    @Test
    void testShutdownDuringMessageProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: shutdown during message processing");
        // Step 1: Send a message
        String testMessage = "Shutdown during processing test";
        producer.send(testMessage).await();

        // Step 2: Set up consumer that will trigger shutdown during processing
        AtomicBoolean messageReceived = new AtomicBoolean(false);

        consumer.subscribe(message -> {
            messageReceived.set(true);

            // Initiate shutdown immediately after receiving message
            vertx.setTimer(10, id -> {
                try {
                    consumer.close();
                    testContext.completeNow();
                } catch (Exception e) {
                    // Ignore
                }
            });

            return Future.succeededFuture();
        });

        // Step 3: Wait for shutdown
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertTrue(messageReceived.get());
    }

    @Test
    void testFactoryCloseClosesCreatedConsumers(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: factory close closes created consumers");
        consumer.subscribe(message -> Future.succeededFuture());

        PgNativeQueueConsumer<?> concrete = (PgNativeQueueConsumer<?>) consumer;

        // Wait for consumer to be in open state
        vertx.setPeriodic(100, openCheckId -> {
            try {
                if (!isClosed(concrete)) {
                    vertx.cancelTimer(openCheckId);

                    // Now close factory
                    queueFactory.close().onFailure(testContext::failNow);

                    // Wait for consumer to be closed
                    vertx.setPeriodic(100, closeCheckId -> {
                        try {
                            if (isClosed(concrete)) {
                                vertx.cancelTimer(closeCheckId);
                                testContext.verify(() -> {
                                    assertTrue(isClosed(concrete), "Factory close must close consumers it created");
                                    assertEquals(-1L, getListenReconnectTimerId(concrete), "Closed consumers must not retain LISTEN reconnect timers");
                                });
                                testContext.completeNow();
                            }
                        } catch (Exception e) {
                            testContext.failNow(e);
                        }
                    });
                }
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    private static boolean isClosed(PgNativeQueueConsumer<?> consumer) throws Exception {
        Field field = PgNativeQueueConsumer.class.getDeclaredField("closed");
        field.setAccessible(true);
        return ((AtomicBoolean) field.get(consumer)).get();
    }

    private static long getListenReconnectTimerId(PgNativeQueueConsumer<?> consumer) throws Exception {
        Field field = PgNativeQueueConsumer.class.getDeclaredField("listenReconnectTimerId");
        field.setAccessible(true);
        return field.getLong(consumer);
    }
}


