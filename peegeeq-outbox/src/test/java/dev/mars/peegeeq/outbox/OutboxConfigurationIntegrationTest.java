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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration test to verify that the outbox module correctly uses the max-retries configuration.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxConfigurationIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConfigurationIntegrationTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled",
        "peegeeq.queue.max-retries", "peegeeq.queue.polling-interval"
    };

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        System.clearProperty("peegeeq.queue.max-retries");

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }
    }


    @Test
    void testOutboxRespectsMaxRetriesConfiguration(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: outbox respects max retries configuration");
        System.setProperty("peegeeq.queue.max-retries", "2");

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("basic-test"), new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());

        String testTopic = "test-config-integration-" + UUID.randomUUID().toString().substring(0, 8);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);

        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(3);

        producer.send("Message for config integration test").await();

        consumer.subscribe(message -> {
            attemptCount.incrementAndGet();
            retryCheckpoint.flag();
            return Future.failedFuture(
                new RuntimeException("INTENTIONAL FAILURE: attempt " + attemptCount.get()));
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
            "Should have attempted processing exactly 3 times (initial + 2 retries)");

        // Wait to confirm no additional attempts beyond max retries
        vertx.timer(2000).await();
        assertEquals(3, attemptCount.get(), "Should not exceed configured max retries");
    }

    @Test
    void testOutboxUsesDefaultWhenNoConfigurationSet(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: outbox uses default when no configuration set");
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("basic-test"), new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());

        String testTopic = "test-default-config-" + UUID.randomUUID().toString().substring(0, 8);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);

        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(4);

        consumer.subscribe(message -> {
            attemptCount.incrementAndGet();
            retryCheckpoint.flag();
            return Future.failedFuture(
                new RuntimeException("INTENTIONAL FAILURE: attempt " + attemptCount.get()));
        });

        producer.send("Message for default config test").await();

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS),
            "Should have attempted processing exactly 4 times (initial + 3 retries from default profile)");

        // Wait to confirm no additional attempts beyond max retries
        vertx.timer(2000).await();
        assertEquals(4, attemptCount.get(), "Should not exceed default max retries");
    }

    @Test
    void testBasicOutboxMessageProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: basic outbox message processing");
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("basic-test"), new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());

        String testTopic = "test-basic-processing-" + UUID.randomUUID().toString().substring(0, 8);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);

        Checkpoint messageProcessed = testContext.checkpoint();
        AtomicInteger processedCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            if (processedCount.incrementAndGet() == 1) {
                messageProcessed.flag();
            }
            return Future.succeededFuture();
        });

        producer.send("Basic processing test message").await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should have processed the message within timeout");
        assertEquals(1, processedCount.get(), "Should process exactly one message");
    }
}
