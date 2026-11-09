package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Tests for parallel processing capabilities in the outbox pattern.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxParallelProcessingTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxParallelProcessingTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "parallel-test-topic-" + UUID.randomUUID().toString().substring(0, 8);

        // Set up database connection
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.consumer.threads", "4")
                .property("peegeeq.queue.batch-size", "5")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);

        // Debug: Verify configuration is loaded correctly
        logger.info("Configuration Debug:");
        logger.info("   - Consumer threads configured: {}", config.getQueueConfig().getConsumerThreads());
        logger.info("   - Batch size configured: {}", config.getQueueConfig().getBatchSize());
        logger.info("   - Polling interval configured: {}", config.getQueueConfig().getPollingInterval());

        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumer = outboxFactory.createConsumer(testTopic, String.class);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext tearDownContext) {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> closeFactory = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        closeFactory
                .transform(factoryResult -> {
                    Future<Void> closeManager = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return closeManager.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> tearDownContext.completeNow())
                .onFailure(tearDownContext::failNow);
    }

    @Test
    @Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
    void testParallelConsumerProcessing(Vertx vertx, VertxTestContext testContext) {
        // Many messages with a long handler, so several handlers must be in flight at once.
        int messageCount = 20;
        Promise<Void> allCompleted = Promise.promise();
        Set<String> processingThreads = ConcurrentHashMap.newKeySet();
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        consumer.subscribe(message -> {
            String threadName = Thread.currentThread().getName();
            processingThreads.add(threadName);

            int count = processedCount.incrementAndGet();
            int concurrent = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(concurrent, Math::max);
            logger.info("Processing message {} on thread: {} - {}", count, threadName, message.getPayload());

            return vertx.timer(2000).map(id -> {
                inFlight.decrementAndGet();
                if (completedCount.incrementAndGet() == messageCount) {
                    allCompleted.tryComplete();
                }
                return null;
            });
        }).compose(v -> {
            List<Future<Void>> sends = new ArrayList<>(messageCount);
            for (int i = 0; i < messageCount; i++) {
                sends.add(producer.send("Parallel message " + i));
            }
            return Future.all(sends).mapEmpty();
        })
          .compose(sent -> allCompleted.future())
          .onSuccess(done -> testContext.verify(() -> {
              logger.info("Messages processed: {}, threads: {}, maximum concurrent handlers: {}",
                  processedCount.get(), processingThreads, maxConcurrent.get());
              assertEquals(messageCount, processedCount.get(), "Should process every message once");
              assertTrue(maxConcurrent.get() > 1,
                  "Multiple message-handler Futures should be in flight concurrently");
              // In reactive mode, message handlers run on Vert.x event loop threads.
              assertFalse(processingThreads.isEmpty(), "Should have captured processing thread names");
              testContext.completeNow();
          }))
          .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testBatchProcessing(VertxTestContext testContext) {
        int messageCount = 20;
        // A strict checkpoint: exactly twenty deliveries; a twenty-first fails the test.
        CountDownLatch completionCheckpoint = testContext.checkpoint().asLatch(messageCount);

        consumer.subscribe(message -> {
            completionCheckpoint.countDown();
            return Future.succeededFuture();
        }).compose(v -> {
            List<Future<Void>> sends = new ArrayList<>(messageCount);
            for (int i = 0; i < messageCount; i++) {
                sends.add(producer.send("Batch message " + i));
            }
            return Future.all(sends).mapEmpty();
        })
          .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 45, timeUnit = TimeUnit.SECONDS)
    void testConcurrentProducers(VertxTestContext testContext) {
        int producerCount = 3;
        int messagesPerProducer = 5;
        int totalMessages = producerCount * messagesPerProducer;

        // A strict checkpoint: exactly fifteen deliveries; a sixteenth fails the test.
        CountDownLatch completionCheckpoint = testContext.checkpoint().asLatch(totalMessages);
        List<MessageProducer<String>> concurrentProducers = new ArrayList<>();

        consumer.subscribe(message -> {
            completionCheckpoint.countDown();
            return Future.succeededFuture();
        }).compose(v -> {
            List<Future<Void>> sends = new ArrayList<>(totalMessages);
            for (int p = 0; p < producerCount; p++) {
                MessageProducer<String> concurrentProducer = outboxFactory.createProducer(testTopic, String.class);
                concurrentProducers.add(concurrentProducer);
                for (int m = 0; m < messagesPerProducer; m++) {
                    sends.add(concurrentProducer.send("Producer-" + p + "-Message-" + m));
                }
            }
            return Future.all(sends).mapEmpty();
        })
          .transform(TestCleanup.cleanup(() -> {
              // MessageProducer.close() is synchronous and void.
              for (MessageProducer<String> concurrentProducer : concurrentProducers) {
                  try { concurrentProducer.close(); } catch (Exception e) { logger.warn("Producer close failed: {}", e.getMessage()); }
              }
              return Future.succeededFuture();
          }))
          .onFailure(testContext::failNow);
    }
}
