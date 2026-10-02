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
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.QUEUE_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxConsumerConcurrencyIT {
    private static final String GROUP_A = "group-a";
    private static final String GROUP_B = "group-b";

    @Container
    private static final PostgreSQLContainer postgres =
            PostgreSQLTestConstants.createStandardContainer();

    private final List<Promise<Void>> handlerReleases = new CopyOnWriteArrayList<>();

    private PeeGeeQManager manager;
    private OutboxFactory factory;
    private MessageProducer<String> producer;
    private String topic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(
                postgres,
                PostgreSQLTestConstants.TEST_SCHEMA,
                QUEUE_ALL);
        topic = "outbox-concurrency-" + UUID.randomUUID().toString().substring(0, 8);

        Properties properties = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.pool.max-size", "1")
                .property("peegeeq.metrics.enabled", "false")
                .property("peegeeq.queue.dead-consumer-detection.enabled", "false")
                .property("peegeeq.queue.consumer-group-retry.enabled", "false")
                .build();
        PeeGeeQConfiguration configuration =
                new PeeGeeQConfiguration("outbox-concurrency-test", properties);
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        manager.start()
                .onSuccess(ignored -> testContext.verify(() -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), configuration);
                    producer = factory.createProducer(topic, String.class);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        handlerReleases.forEach(release -> release.tryComplete());
        Future<Void> factoryClose = factory != null
                ? factory.close()
                : Future.succeededFuture();
        factoryClose
                .transform(factoryResult -> {
                    Future<Void> managerClose = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return managerClose.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(ignored -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void consumerThreadsOneLimitsClaimedAndActiveWork(VertxTestContext testContext)
            throws InterruptedException {
        int messageCount = 8;
        // The initial poll sees an existing backlog. Releasing a slot must continue
        // draining it without waiting for the next idle poll one minute later.
        OutboxConsumer<String> consumer = createConsumer(1, messageCount, Duration.ofMinutes(1));
        Promise<Void> releaseHandlers = trackedRelease();
        Promise<Void> firstHandlerEntered = Promise.promise();
        Promise<Void> allHandlersEntered = Promise.promise();
        AtomicInteger activeHandlers = new AtomicInteger();
        AtomicInteger maximumActiveHandlers = new AtomicInteger();
        AtomicInteger enteredHandlers = new AtomicInteger();
        AtomicInteger processingRowsAtBarrier = new AtomicInteger();
        Set<String> observedPayloads = ConcurrentHashMap.newKeySet();

        sendDistinctGroupedMessages(messageCount)
                .compose(ignored -> consumer.subscribe(message -> {
                    observedPayloads.add(message.getPayload());
                    int active = activeHandlers.incrementAndGet();
                    maximumActiveHandlers.accumulateAndGet(active, Math::max);
                    firstHandlerEntered.tryComplete();
                    if (enteredHandlers.incrementAndGet() == messageCount) {
                        allHandlersEntered.tryComplete();
                    }
                    return releaseHandlers.future()
                            .eventually(() -> {
                                activeHandlers.decrementAndGet();
                                return Future.succeededFuture();
                            });
                }))
                .compose(ignored -> firstHandlerEntered.future())
                .compose(ignored -> countProcessingRows())
                .compose(processingRows -> {
                    processingRowsAtBarrier.set(processingRows);
                    releaseHandlers.tryComplete();
                    return allHandlersEntered.future();
                })
                .compose(ignored -> consumer.closeAsync())
                .eventually(() -> {
                    releaseHandlers.tryComplete();
                    return consumer.closeAsync();
                })
                .onSuccess(ignored -> testContext.verify(() -> {
                    assertEquals(1, processingRowsAtBarrier.get(),
                            "consumerThreads=1 must admit only one claimed row");
                    assertEquals(1, maximumActiveHandlers.get(),
                            "consumerThreads=1 must allow only one active handler");
                    assertEquals(messageCount, observedPayloads.size(),
                            "every distinct message must continue after capacity is released");
                    assertEquals(0, activeHandlers.get(),
                            "all handler capacity must be released before close settles");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void consumerThreadsAllowsConfiguredParallelHandlers(VertxTestContext testContext)
            throws InterruptedException {
        int consumerThreads = 3;
        int messageCount = 8;
        OutboxConsumer<String> consumer = createConsumer(consumerThreads, messageCount);
        Promise<Void> releaseHandlers = trackedRelease();
        Promise<Void> firstHandlerEntered = Promise.promise();
        Promise<Void> allHandlersEntered = Promise.promise();
        AtomicInteger activeHandlers = new AtomicInteger();
        AtomicInteger maximumActiveHandlers = new AtomicInteger();
        AtomicInteger enteredHandlers = new AtomicInteger();
        AtomicInteger processingRowsAtBarrier = new AtomicInteger();
        AtomicInteger maximumAtBarrier = new AtomicInteger();

        sendDistinctGroupedMessages(messageCount)
                .compose(ignored -> consumer.subscribe(message -> {
                    int active = activeHandlers.incrementAndGet();
                    maximumActiveHandlers.accumulateAndGet(active, Math::max);
                    firstHandlerEntered.tryComplete();
                    if (enteredHandlers.incrementAndGet() == messageCount) {
                        allHandlersEntered.tryComplete();
                    }
                    return releaseHandlers.future()
                            .eventually(() -> {
                                activeHandlers.decrementAndGet();
                                return Future.succeededFuture();
                            });
                }))
                .compose(ignored -> firstHandlerEntered.future())
                .compose(ignored -> countProcessingRows())
                .compose(processingRows -> {
                    processingRowsAtBarrier.set(processingRows);
                    maximumAtBarrier.set(maximumActiveHandlers.get());
                    releaseHandlers.tryComplete();
                    return allHandlersEntered.future();
                })
                .compose(ignored -> consumer.closeAsync())
                .eventually(() -> {
                    releaseHandlers.tryComplete();
                    return consumer.closeAsync();
                })
                .onSuccess(ignored -> testContext.verify(() -> {
                    assertEquals(consumerThreads, processingRowsAtBarrier.get(),
                            "the atomic claim must use the configured handler capacity");
                    assertEquals(consumerThreads, maximumAtBarrier.get(),
                            "different message groups must use the configured parallelism");
                    assertEquals(consumerThreads, maximumActiveHandlers.get(),
                            "active handlers must never exceed consumerThreads");
                    assertEquals(0, activeHandlers.get(),
                            "all handler capacity must be released before close settles");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void sameGroupStaysOrderedWhileAnotherGroupUsesOverlappingPollCapacity(
            VertxTestContext testContext) throws InterruptedException {
        OutboxConsumer<String> consumer = createConsumer(3, 1);
        Promise<Void> releaseFirstGroupMessage = trackedRelease();
        Promise<Void> firstGroupMessageEntered = Promise.promise();
        Promise<Void> otherGroupEntered = Promise.promise();
        Promise<Void> allHandlersEntered = Promise.promise();
        AtomicInteger enteredHandlers = new AtomicInteger();
        AtomicBoolean secondGroupMessageEntered = new AtomicBoolean();
        AtomicBoolean secondGroupMessageEnteredBeforeRelease = new AtomicBoolean();
        List<String> groupAOrder = new CopyOnWriteArrayList<>();

        producer.send("group-a-1", Map.of(), "correlation-a-1", GROUP_A)
                .compose(ignored -> producer.send(
                        "group-a-2", Map.of(), "correlation-a-2", GROUP_A))
                .compose(ignored -> producer.send(
                        "group-b-1", Map.of(), "correlation-b-1", GROUP_B))
                .compose(ignored -> consumer.subscribe(message -> {
                    String payload = message.getPayload();
                    if (payload.startsWith("group-a-")) {
                        groupAOrder.add(payload);
                    }
                    if ("group-a-1".equals(payload)) {
                        firstGroupMessageEntered.tryComplete();
                        markHandlerEntered(enteredHandlers, allHandlersEntered, 3);
                        return releaseFirstGroupMessage.future();
                    }
                    if ("group-a-2".equals(payload)) {
                        secondGroupMessageEntered.set(true);
                    }
                    if ("group-b-1".equals(payload)) {
                        otherGroupEntered.tryComplete();
                    }
                    markHandlerEntered(enteredHandlers, allHandlersEntered, 3);
                    return Future.succeededFuture();
                }))
                .compose(ignored -> firstGroupMessageEntered.future())
                .compose(ignored -> otherGroupEntered.future())
                .compose(ignored -> {
                    secondGroupMessageEnteredBeforeRelease.set(secondGroupMessageEntered.get());
                    releaseFirstGroupMessage.tryComplete();
                    return allHandlersEntered.future();
                })
                .compose(ignored -> consumer.closeAsync())
                .eventually(() -> {
                    releaseFirstGroupMessage.tryComplete();
                    return consumer.closeAsync();
                })
                .onSuccess(ignored -> testContext.verify(() -> {
                    assertFalse(secondGroupMessageEnteredBeforeRelease.get(),
                            "a later message in the same group must wait for the earlier handler");
                    assertTrue(secondGroupMessageEntered.get(),
                            "the later same-group message must continue after release");
                    assertEquals(List.of("group-a-1", "group-a-2"), groupAOrder,
                            "messages in one group must enter the handler in publication order");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    private OutboxConsumer<String> createConsumer(int consumerThreads, int batchSize) {
        return createConsumer(consumerThreads, batchSize, Duration.ofMillis(5));
    }

    @SuppressWarnings("unchecked")
    private OutboxConsumer<String> createConsumer(int consumerThreads, int batchSize, Duration pollingInterval) {
        OutboxConsumerConfig consumerConfig = OutboxConsumerConfig.builder()
                .pollingInterval(pollingInterval)
                .batchSize(batchSize)
                .consumerThreads(consumerThreads)
                .build();
        return (OutboxConsumer<String>) factory.createConsumer(topic, String.class, consumerConfig);
    }

    private Promise<Void> trackedRelease() {
        Promise<Void> release = Promise.promise();
        handlerReleases.add(release);
        return release;
    }

    private Future<Void> sendDistinctGroupedMessages(int messageCount) {
        List<Future<Void>> sends = new ArrayList<>(messageCount);
        for (int index = 0; index < messageCount; index++) {
            sends.add(producer.send(
                    "message-" + index,
                    Map.of(),
                    "correlation-" + index,
                    "group-" + index));
        }
        return Future.all(sends).mapEmpty();
    }

    private Future<Integer> countProcessingRows() {
        return manager.getPool()
                .preparedQuery("SELECT COUNT(*) AS processing_count FROM outbox "
                        + "WHERE topic = $1 AND status = 'PROCESSING'")
                .execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getLong("processing_count").intValue());
    }

    private static void markHandlerEntered(
            AtomicInteger enteredHandlers,
            Promise<Void> allHandlersEntered,
            int expectedHandlers) {
        if (enteredHandlers.incrementAndGet() == expectedHandlers) {
            allHandlersEntered.tryComplete();
        }
    }
}
