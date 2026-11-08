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
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
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

import java.util.Properties;
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

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        try { if (consumer != null) consumer.close(); } catch (Exception e) { logger.warn("consumer.close() failed", e); }
        try { if (producer != null) producer.close(); } catch (Exception e) { logger.warn("producer.close() failed", e); }
        // The factory close returns a Future: it is composed, and the manager closes whatever its
        // outcome; the first failure fails the teardown.
        (outboxFactory != null ? outboxFactory.close() : Future.<Void>succeededFuture())
                .transform(factoryResult -> (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                        .transform(managerResult -> {
                            if (factoryResult.failed()) {
                                return Future.<Void>failedFuture(factoryResult.cause());
                            }
                            return managerResult.failed()
                                    ? Future.<Void>failedFuture(managerResult.cause())
                                    : Future.<Void>succeededFuture();
                        }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }


    @Test
    @Timeout(value = 15, timeUnit = TimeUnit.SECONDS)
    void testOutboxRespectsMaxRetriesConfiguration(Vertx vertx, VertxTestContext testContext) {
        Properties configProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .property("peegeeq.queue.max-retries", "2")
                .build();
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", configProps), new SimpleMeterRegistry());

        String testTopic = "test-config-integration-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger attemptCount = new AtomicInteger(0);
        Promise<Void> expectedAttemptsMade = Promise.promise();

        manager.start()
                .compose(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumer = outboxFactory.createConsumer(testTopic, String.class);
                    return consumer.subscribe(message -> {
                        int attempt = attemptCount.incrementAndGet();
                        if (attempt == 3) {
                            expectedAttemptsMade.tryComplete();
                        }
                        return Future.failedFuture(
                            new RuntimeException("INTENTIONAL FAILURE: attempt " + attempt));
                    });
                })
                .compose(v -> producer.send("Message for config integration test"))
                .compose(v -> expectedAttemptsMade.future())
                // A 2 s window in which no further attempt may arrive.
                .compose(v -> vertx.timer(2000))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(3, attemptCount.get(), "Initial attempt + 2 retries; no attempt beyond the configured max retries");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 20, timeUnit = TimeUnit.SECONDS)
    void testOutboxUsesDefaultWhenNoConfigurationSet(Vertx vertx, VertxTestContext testContext) {
        Properties configProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", configProps), new SimpleMeterRegistry());

        String testTopic = "test-default-config-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger attemptCount = new AtomicInteger(0);
        Promise<Void> expectedAttemptsMade = Promise.promise();

        manager.start()
                .compose(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumer = outboxFactory.createConsumer(testTopic, String.class);
                    return consumer.subscribe(message -> {
                        int attempt = attemptCount.incrementAndGet();
                        if (attempt == 4) {
                            expectedAttemptsMade.tryComplete();
                        }
                        return Future.failedFuture(
                            new RuntimeException("INTENTIONAL FAILURE: attempt " + attempt));
                    });
                })
                .compose(v -> producer.send("Message for default config test"))
                .compose(v -> expectedAttemptsMade.future())
                // A 2 s window in which no further attempt may arrive.
                .compose(v -> vertx.timer(2000))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(4, attemptCount.get(), "Initial attempt + 3 retries of the default profile; no attempt beyond them");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testBasicOutboxMessageProcessing(Vertx vertx, VertxTestContext testContext) {
        Properties basicProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", basicProps), new SimpleMeterRegistry());

        String testTopic = "test-basic-processing-" + UUID.randomUUID().toString().substring(0, 8);
        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageProcessed = testContext.checkpoint();

        manager.start()
                .compose(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, manager.getConfiguration());
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumer = outboxFactory.createConsumer(testTopic, String.class);
                    return consumer.subscribe(message -> {
                        messageProcessed.flag();
                        return Future.succeededFuture();
                    });
                })
                .compose(v -> producer.send("Basic processing test message"))
                .onFailure(testContext::failNow);
    }
}
