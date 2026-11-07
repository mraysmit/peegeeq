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

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A native delivery that is not acknowledged within the visibility timeout is a failed
 * attempt (finding 5).
 *
 * <p>Before the correction a handler that never settled was redelivered once per visibility
 * timeout with no bound: {@code retry_count} stayed 0, {@code max-retries} did not apply,
 * and the message starved every later message of the topic. A lock left by a consumer that
 * stopped without settling had the same effect. Each expiry now counts one attempt, and the
 * message is dead-lettered at the retry limit.</p>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@io.vertx.junit5.Timeout(value = 120, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class NativeQueueVisibilityExpiryIntegrationTest {

    private static final int MAX_RETRIES = 2;
    /** max-retries counts retries after the first delivery, as in the outbox. */
    private static final String RECORDED_RETRIES =
            "with max-retries N a native message is delivered N + 1 times and its dead letter records N retries";
    private static final long DEADLINE_MS = 30_000;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private String topic;
    private final List<MessageProducer<String>> producers = new ArrayList<>();
    private final List<MessageConsumer<String>> consumers = new ArrayList<>();

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA,
                SchemaComponent.QUEUE_ALL);
        topic = "visibility-" + UUID.randomUUID().toString().substring(0, 8);
        Properties props = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", String.valueOf(MAX_RETRIES))
                .property("peegeeq.queue.visibility-timeout", "PT1S")
                .property("peegeeq.queue.polling-interval", "PT0.2S")
                .build();
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", props), new SimpleMeterRegistry());
        manager.start()
                .map(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    QueueFactoryProvider provider = new PgQueueFactoryProvider();
                    PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                    factory = provider.createFactory("native", databaseService);
                    return (Void) null;
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Throwable syncFailure = null;
        for (MessageConsumer<String> consumer : consumers) {
            try {
                consumer.close();
            } catch (Exception e) {
                syncFailure = e;
            }
        }
        for (MessageProducer<String> producer : producers) {
            try {
                producer.close();
            } catch (Exception e) {
                syncFailure = e;
            }
        }
        Throwable closeFailure = syncFailure;
        Future.<Void>succeededFuture()
                .compose(v -> factory != null ? factory.close() : Future.<Void>succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                .onSuccess(v -> {
                    if (closeFailure == null) {
                        testContext.completeNow();
                    } else {
                        testContext.failNow(closeFailure);
                    }
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void handlerThatNeverSettlesIsDeadLetteredAtTheRetryLimit(VertxTestContext testContext) {
        AtomicInteger deliveries = new AtomicInteger();
        MessageConsumer<String> consumer = consumer();
        consumer.subscribe(message -> {
                    deliveries.incrementAndGet();
                    return Promise.<Void>promise().future();
                })
                .compose(v -> producer().send("hangs"))
                .compose(v -> pollUntil(() -> deadLetterCount().map(count -> count == 1L),
                        "the unacknowledged message was not dead-lettered"))
                .compose(v -> messageCount())
                .compose(remaining -> deadLetterRow().map(row -> {
                    assertEquals(0L, remaining, "the dead-lettered message must leave queue_messages");
                    assertEquals(MAX_RETRIES, row.getInteger("retry_count"), RECORDED_RETRIES);
                    assertTrue(row.getString("failure_reason").contains("visibility timeout"),
                            "the dead letter must name the visibility timeout: " + row.getString("failure_reason"));
                    assertEquals(MAX_RETRIES + 1, deliveries.get(),
                            "an unacknowledged message must be delivered max-retries + 1 times");
                    return (Void) null;
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void expiryBelowTheLimitCountsOneAttemptAndRedelivers(VertxTestContext testContext) {
        AtomicInteger deliveries = new AtomicInteger();
        Promise<Void> secondDelivery = Promise.promise();
        MessageConsumer<String> consumer = consumer();
        consumer.subscribe(message -> {
                    if (deliveries.incrementAndGet() == 1) {
                        return Promise.<Void>promise().future();
                    }
                    secondDelivery.tryComplete();
                    return Future.<Void>failedFuture(new IllegalStateException("second attempt holds the row"));
                })
                .compose(v -> producer().send("retried"))
                .compose(v -> withTimeout(secondDelivery.future(), "the expired message was not delivered again"))
                .compose(v -> pollUntil(() -> deadLetterCount().map(count -> count == 1L),
                        "the message was not dead-lettered after its final failed attempt"))
                .compose(v -> deadLetterRow().map(row -> {
                    assertEquals(MAX_RETRIES + 1, deliveries.get(), RECORDED_RETRIES);
                    assertEquals(MAX_RETRIES, row.getInteger("retry_count"), RECORDED_RETRIES);
                    return (Void) null;
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void aHungMessageDoesNotStarveALaterMessageForever(VertxTestContext testContext) {
        Promise<Void> laterDelivered = Promise.promise();
        MessageConsumer<String> consumer = consumer();
        MessageProducer<String> producer = producer();
        consumer.subscribe(message -> {
                    if ("hangs".equals(message.getPayload())) {
                        return Promise.<Void>promise().future();
                    }
                    laterDelivered.tryComplete();
                    return Future.succeededFuture();
                })
                .compose(v -> producer.send("hangs"))
                .compose(v -> producer.send("later"))
                .compose(v -> withTimeout(laterDelivered.future(), "the later message was starved by the hung message"))
                .compose(v -> pollUntil(() -> deadLetterCount().map(count -> count == 1L),
                        "the hung message was not dead-lettered"))
                .compose(v -> pollUntil(() -> messageCount().map(count -> count == 0L),
                        "the later message was not deleted"))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void lockLeftByAStoppedConsumerAtTheLimitIsDeadLetteredWithoutADelivery(VertxTestContext testContext) {
        AtomicInteger deliveries = new AtomicInteger();
        insertExpiredLock("orphaned-at-limit", MAX_RETRIES)
                .compose(v -> consumer().subscribe(message -> {
                    deliveries.incrementAndGet();
                    return Future.succeededFuture();
                }))
                .compose(v -> pollUntil(() -> deadLetterCount().map(count -> count == 1L),
                        "the expired lock at the retry limit was not dead-lettered"))
                .compose(v -> messageCount())
                .compose(remaining -> deadLetterRow().map(row -> {
                    assertEquals(0L, remaining);
                    assertEquals(MAX_RETRIES, row.getInteger("retry_count"), RECORDED_RETRIES);
                    assertEquals(0, deliveries.get(),
                            "a message whose attempts are spent must not be delivered again");
                    return (Void) null;
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void lockLeftByAStoppedConsumerOneBelowTheLimitIsRedeliveredOnce(VertxTestContext testContext) {
        Promise<String> delivered = Promise.promise();
        insertExpiredLock("orphaned-below-limit", MAX_RETRIES - 1)
                .compose(v -> consumer().subscribe(message -> {
                    delivered.tryComplete(message.getPayload());
                    return Future.succeededFuture();
                }))
                .compose(v -> withTimeout(delivered.future(), "the orphaned message was not redelivered"))
                .compose(payload -> pollUntil(() -> messageCount().map(count -> count == 0L),
                        "the redelivered message was not deleted").map(v -> payload))
                .compose(payload -> deadLetterCount().map(deadLetters -> {
                    assertEquals("orphaned-below-limit", payload);
                    assertEquals(0L, deadLetters);
                    return (Void) null;
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ------------------------------------------------------------------------------------

    private MessageConsumer<String> consumer() {
        MessageConsumer<String> consumer = factory.createConsumer(topic, String.class);
        consumers.add(consumer);
        return consumer;
    }

    private MessageProducer<String> producer() {
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        producers.add(producer);
        return producer;
    }

    private Future<Void> insertExpiredLock(String payload, int retryCount) {
        return manager.getPool().withTransaction(conn -> conn.preparedQuery("""
                        INSERT INTO queue_messages (topic, payload, status, lock_until, retry_count)
                        VALUES ($1, $2, 'LOCKED', now() - interval '1 minute', $3)
                        """)
                .execute(Tuple.of(topic, new JsonObject().put("value", payload), retryCount))
                .map(rows -> (Void) null));
    }

    /** Fails with the given message when the future does not complete within the deadline. */
    private <T> Future<T> withTimeout(Future<T> future, String failureMessage) {
        Promise<T> bounded = Promise.promise();
        long timerId = manager.getVertx().setTimer(DEADLINE_MS,
                ignored -> bounded.tryFail(new AssertionError(failureMessage + " within " + DEADLINE_MS + " ms")));
        future
                .onSuccess(value -> {
                    manager.getVertx().cancelTimer(timerId);
                    bounded.tryComplete(value);
                })
                .onFailure(error -> {
                    manager.getVertx().cancelTimer(timerId);
                    bounded.tryFail(error);
                });
        return bounded.future();
    }

    private Future<Void> pollUntil(Supplier<Future<Boolean>> condition, String failureMessage) {
        return pollUntil(condition, System.currentTimeMillis() + DEADLINE_MS, failureMessage);
    }

    private Future<Void> pollUntil(Supplier<Future<Boolean>> condition, long deadline, String failureMessage) {
        return condition.get().compose(met -> {
            if (met) {
                return Future.<Void>succeededFuture();
            }
            if (System.currentTimeMillis() >= deadline) {
                return Future.<Void>failedFuture(new AssertionError(
                        failureMessage + " within " + DEADLINE_MS + " ms"));
            }
            return manager.getVertx().timer(100).compose(t -> pollUntil(condition, deadline, failureMessage));
        });
    }

    private Future<Long> messageCount() {
        return count("SELECT COUNT(*) AS c FROM queue_messages WHERE topic = $1");
    }

    private Future<Long> deadLetterCount() {
        return count("SELECT COUNT(*) AS c FROM dead_letter_queue WHERE topic = $1");
    }

    private Future<Long> count(String sql) {
        return manager.getPool().preparedQuery(sql).execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getLong("c"));
    }

    private Future<Row> deadLetterRow() {
        return manager.getPool().preparedQuery("""
                        SELECT retry_count, failure_reason FROM dead_letter_queue WHERE topic = $1
                        """)
                .execute(Tuple.of(topic))
                .compose((RowSet<Row> rows) -> rows.size() == 1
                        ? Future.succeededFuture(rows.iterator().next())
                        : Future.failedFuture(new AssertionError(
                                "expected exactly one dead letter for topic " + topic + ", found " + rows.size())));
    }
}
