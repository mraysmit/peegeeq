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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

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

    @Container
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("native_queue_test");
        container.withUsername("test_user");
        container.withPassword("test_pass");
        return container;
    }

    private PeeGeeQManager manager;
    private PgNativeQueueFactory queueFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() {
        // Configure test properties - following existing pattern exactly
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", postgres.getHost());
        testProps.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        testProps.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        testProps.setProperty("peegeeq.database.username", postgres.getUsername());
        testProps.setProperty("peegeeq.database.password", postgres.getPassword());
        testProps.setProperty("peegeeq.database.ssl.enabled", "false");
        testProps.setProperty("peegeeq.queue.polling-interval", "PT1S");
        testProps.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        testProps.setProperty("peegeeq.metrics.enabled", "true");
        testProps.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Ensure required schema exists for native queue tests
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        // Set system properties
        testProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Initialize native queue components using DatabaseService pattern
        DatabaseService databaseService = new PgDatabaseService(manager);
        queueFactory = new PgNativeQueueFactory(databaseService);
        producer = queueFactory.createProducer("test-native-topic", String.class);
        consumer = queueFactory.createConsumer("test-native-topic", String.class);
    }

    @AfterEach
    void tearDown() {
        try {
            if (consumer != null) {
                consumer.close();
            }
            if (producer != null) {
                producer.close();
            }
            if (manager != null) {
                manager.closeReactive().toCompletionStage().toCompletableFuture().join();
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }

        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.visibility-timeout");
        System.clearProperty("peegeeq.metrics.enabled");
        System.clearProperty("peegeeq.circuit-breaker.enabled");
    }

    @Test
    void testBasicShutdownWithoutErrors(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Step 1: Send a simple message
        String testMessage = "Basic shutdown test";
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Step 2: Process the message
        AtomicBoolean messageReceived = new AtomicBoolean(false);

        consumer.subscribe(message -> {
            messageReceived.set(true);
            testContext.completeNow();
            return CompletableFuture.completedFuture(null);
        });

        // Step 3: Wait for processing
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertTrue(messageReceived.get());

        // Step 4: Close consumer (this should not produce "Pool closed" errors)
        consumer.close();
    }

    @Test
    void testShutdownDuringMessageProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Step 1: Send a message
        String testMessage = "Shutdown during processing test";
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

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

            return CompletableFuture.completedFuture(null);
        });

        // Step 3: Wait for shutdown
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        assertTrue(messageReceived.get());
    }

    @Test
    void testFactoryCloseClosesCreatedConsumers(Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer.subscribe(message -> CompletableFuture.completedFuture(null));

        PgNativeQueueConsumer<?> concrete = (PgNativeQueueConsumer<?>) consumer;

        // Wait for consumer to be in open state
        vertx.setPeriodic(100, openCheckId -> {
            try {
                if (!isClosed(concrete)) {
                    vertx.cancelTimer(openCheckId);

                    // Now close factory
                    queueFactory.close();

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


