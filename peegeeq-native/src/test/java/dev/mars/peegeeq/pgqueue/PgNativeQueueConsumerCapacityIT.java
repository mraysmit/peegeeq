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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.DEAD_LETTER_QUEUE;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.NATIVE_QUEUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PgNativeQueueConsumerCapacityIT {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueConsumerCapacityIT.class);
    private static final String TOPIC = "it-native-capacity-topic";
    private static final int MESSAGE_COUNT = 32;

    @Container
    private static final PostgreSQLContainer postgres =
            PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgNativeQueueFactory factory;
    private MessageProducer<String> producer;
    private PgNativeQueueConsumer<String> consumer;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(
                postgres,
                PostgreSQLTestConstants.TEST_SCHEMA,
                NATIVE_QUEUE,
                DEAD_LETTER_QUEUE);

        Properties testProperties = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.pool.min-size", "1")
                .property("peegeeq.database.pool.max-size", "1")
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .property("peegeeq.metrics.enabled", "false")
                .build();
        PeeGeeQConfiguration configuration =
                new PeeGeeQConfiguration("native-capacity-test", testProperties);
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        manager.start()
                .onSuccess(ignored -> testContext.verify(() -> {
                    factory = new PgNativeQueueFactory(new PgDatabaseService(manager));
                    producer = factory.createProducer(TOPIC, String.class);
                    ConsumerConfig consumerConfig = ConsumerConfig.builder()
                            .mode(ConsumerMode.POLLING_ONLY)
                            .pollingInterval(Duration.ofMillis(10))
                            .batchSize(16)
                            .consumerThreads(1)
                            .build();
                    consumer = (PgNativeQueueConsumer<String>) factory.createConsumer(
                            TOPIC, String.class, consumerConfig);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        Future<Void> producerClose = Future.succeededFuture();
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception error) {
                logger.error("Failed to close native capacity-test producer", error);
                producerClose = Future.failedFuture(error);
            }
        }

        Future<Void> resourceClose = (factory != null ? factory.close() : Future.<Void>succeededFuture())
                .compose(ignored -> manager != null
                        ? manager.closeReactive()
                        : Future.<Void>succeededFuture());
        Future.join(producerClose, resourceClose)
                .onSuccess(ignored -> testContext.completeNow())
                .onFailure(error -> {
                    logger.error("Native capacity-test teardown failed", error);
                    testContext.failNow(error);
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void consumerThreadsAtomicallyLimitsAdmittedHandlers(VertxTestContext testContext)
            throws InterruptedException {
        AtomicInteger activeHandlers = new AtomicInteger();
        AtomicInteger maximumActiveHandlers = new AtomicInteger();
        AtomicInteger maximumAtBarrier = new AtomicInteger();
        Set<String> expectedPayloads = ConcurrentHashMap.newKeySet();
        Set<String> observedPayloads = ConcurrentHashMap.newKeySet();
        Promise<Void> firstHandlerEntered = Promise.promise();
        Promise<Void> allMessagesEntered = Promise.promise();
        Promise<Void> releaseHandlers = Promise.promise();

        consumer.subscribe(message -> {
            String payload = message.getPayload();
            if (!observedPayloads.add(payload)) {
                AssertionError duplicate = new AssertionError("Duplicate native delivery: " + payload);
                allMessagesEntered.tryFail(duplicate);
                return Future.failedFuture(duplicate);
            }
            int active = activeHandlers.incrementAndGet();
            maximumActiveHandlers.accumulateAndGet(active, Math::max);
            firstHandlerEntered.tryComplete();
            if (observedPayloads.size() == MESSAGE_COUNT) {
                allMessagesEntered.tryComplete();
            }
            return releaseHandlers.future()
                    .eventually(() -> {
                        activeHandlers.decrementAndGet();
                        return Future.succeededFuture();
                    });
        })
        .compose(ignored -> {
            List<Future<?>> sends = new ArrayList<>(MESSAGE_COUNT);
            for (int index = 0; index < MESSAGE_COUNT; index++) {
                String payload = "capacity-message-" + index;
                expectedPayloads.add(payload);
                sends.add(producer.send(payload));
            }
            return Future.all(sends).mapEmpty();
        })
        .compose(ignored -> firstHandlerEntered.future())
        .compose(ignored -> {
            return manager.getPool().getConnection()
                    .compose(connection -> connection.close())
                    .compose(barrierIgnored -> {
                        maximumAtBarrier.set(maximumActiveHandlers.get());
                        releaseHandlers.tryComplete();
                        return allMessagesEntered.future();
                    });
        })
        .compose(ignored -> consumer.closeAsync())
        .eventually(() -> {
            releaseHandlers.tryComplete();
            return consumer.closeAsync();
        })
        .onSuccess(ignored -> testContext.verify(() -> {
            assertEquals(1, maximumAtBarrier.get(),
                    "consumerThreads=1 must atomically limit admitted handlers to one");
            assertEquals(0, activeHandlers.get(),
                    "all admitted handler capacity must be released after settlement");
            assertEquals(expectedPayloads, observedPayloads,
                    "all queued messages must continue exactly once after capacity is released");
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }
}
