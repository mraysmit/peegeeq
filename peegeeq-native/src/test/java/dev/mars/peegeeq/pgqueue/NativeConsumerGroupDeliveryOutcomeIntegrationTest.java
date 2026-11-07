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
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
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
 * Delivery outcomes of a native consumer group against real PostgreSQL (finding 3).
 *
 * <p>A message that a native group does not process successfully is never deleted. A group
 * predicate that refuses a message writes one exclusion row for that group and leaves the
 * message available to every other consumer. A message that no member accepts is kept with
 * its retry count unchanged. A predicate that throws is a processing failure: the message is
 * retried and dead-lettered at the retry limit, and no handler runs.</p>
 *
 * <p>Contract: {@code docs-design/consumer-groups/PEEGEEQ_DELIVERY_OUTCOME_REDESIGN_PROPOSAL.md},
 * "Delivery Outcome Contract", native queue column.</p>
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@io.vertx.junit5.Timeout(value = 120, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class NativeConsumerGroupDeliveryOutcomeIntegrationTest {

    private static final int MAX_RETRIES = 2;
    /** max-retries counts retries after the first delivery, as in the outbox. */
    private static final String RECORDED_RETRIES =
            "with max-retries N a native message is delivered N + 1 times and its dead letter records N retries";
    private static final long DEADLINE_MS = 20_000;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private String topic;
    private final List<MessageProducer<String>> producers = new ArrayList<>();
    private final List<MessageConsumer<String>> consumers = new ArrayList<>();
    private final List<ConsumerGroup<String>> groups = new ArrayList<>();

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA,
                SchemaComponent.QUEUE_ALL);
        topic = "outcome-" + UUID.randomUUID().toString().substring(0, 8);
        Properties props = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", String.valueOf(MAX_RETRIES))
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
        Future<Void> chain = Future.succeededFuture();
        for (ConsumerGroup<String> group : groups) {
            chain = chain.compose(v -> group.close());
        }
        chain
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
    void memberFilterThatThrowsIsRetriedAndDeadLetteredAndNoHandlerRuns(VertxTestContext testContext) {
        AtomicInteger filterCalls = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        ConsumerGroup<String> group = group("throwing-member-group");
        group.addConsumer("member-1", message -> {
            handlerCalls.incrementAndGet();
            return Future.succeededFuture();
        }, message -> {
            filterCalls.incrementAndGet();
            throw new IllegalStateException("member filter defect");
        });

        group.start()
                .compose(v -> producer().send("payload"))
                .compose(v -> pollUntil(() -> deadLetterCount().map(count -> count == 1L),
                        "the message was not dead-lettered"))
                .compose(v -> messageCount())
                .compose(remaining -> deadLetterRow().map(row -> {
                    assertEquals(0L, remaining, "the dead-lettered message must leave queue_messages");
                    assertEquals("queue_messages", row.getString("original_table"));
                    assertEquals(MAX_RETRIES, row.getInteger("retry_count"), RECORDED_RETRIES);
                    assertTrue(row.getString("failure_reason").contains("member filter defect"),
                            "the dead letter must name the predicate failure: " + row.getString("failure_reason"));
                    assertEquals(MAX_RETRIES + 1, filterCalls.get(),
                            "the member filter must run once per delivery attempt");
                    assertEquals(0, handlerCalls.get(), "no handler may run for a refused delivery");
                    return (Void) null;
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void messageThatNoMemberAcceptsIsKeptWithItsRetryCountUntilAMemberAccepts(VertxTestContext testContext) {
        AtomicInteger refusals = new AtomicInteger();
        Promise<String> delivered = Promise.promise();
        ConsumerGroup<String> group = group("refusing-member-group");
        group.addConsumer("refuses-all", message -> Future.succeededFuture(), message -> {
            refusals.incrementAndGet();
            return false;
        });

        group.start()
                .compose(v -> producer().send("kept"))
                .compose(v -> pollUntil(() -> Future.succeededFuture(refusals.get() >= 3),
                        "the refused message was not offered again"))
                .compose(v -> messageRow())
                .compose(row -> deadLetterCount().map(deadLetters -> {
                    assertEquals("AVAILABLE", row.getString("status"),
                            "a message that no member accepts must stay available");
                    assertEquals(0, row.getInteger("retry_count"),
                            "an unavailable delivery must not change the retry count");
                    assertEquals(0L, deadLetters, "an unavailable delivery must not dead-letter");
                    return (Void) null;
                }))
                .compose(v -> {
                    group.addConsumer("accepts-all", message -> {
                        delivered.tryComplete(message.getPayload());
                        return Future.succeededFuture();
                    });
                    return withTimeout(delivered.future(), "the kept message was not delivered to the accepting member");
                })
                .compose(payload -> pollUntil(() -> messageCount().map(count -> count == 0L),
                        "the delivered message was not deleted")
                        .map(v -> payload))
                .onSuccess(payload -> testContext.verify(() -> {
                    assertEquals("kept", payload);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void messageSentWhileTheGroupHasNoMemberIsKeptUntilAMemberJoins(VertxTestContext testContext) {
        Promise<String> delivered = Promise.promise();
        ConsumerGroup<String> group = group("memberless-group");
        group.addConsumer("leaves", message -> Future.succeededFuture());

        group.start()
                .compose(v -> {
                    assertTrue(group.removeConsumer("leaves"));
                    return producer().send("waiting");
                })
                .compose(v -> pollUntil(() -> messageRow().map(row ->
                                row.getOffsetDateTime("visible_at").isAfter(row.getOffsetDateTime("created_at"))),
                        "the group did not release the message it could not deliver"))
                .compose(v -> messageRow())
                .compose(row -> deadLetterCount().map(deadLetters -> {
                    assertEquals("AVAILABLE", row.getString("status"));
                    assertEquals(0, row.getInteger("retry_count"));
                    assertEquals(0L, deadLetters);
                    return (Void) null;
                }))
                .compose(v -> {
                    group.addConsumer("joins", message -> {
                        delivered.tryComplete(message.getPayload());
                        return Future.succeededFuture();
                    });
                    return withTimeout(delivered.future(), "the kept message was not delivered to the accepting member");
                })
                .onSuccess(payload -> testContext.verify(() -> {
                    assertEquals("waiting", payload);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void anUnavailableMessageDoesNotBlockALaterMessage(VertxTestContext testContext) {
        Promise<String> delivered = Promise.promise();
        ConsumerGroup<String> group = group("head-of-line-group");
        group.addConsumer("accepts-pass", message -> {
            delivered.tryComplete(message.getPayload());
            return Future.succeededFuture();
        }, message -> "pass".equals(message.getPayload()));

        MessageProducer<String> producer = producer();
        group.start()
                .compose(v -> producer.send("hold"))
                .compose(v -> producer.send("pass"))
                .compose(v -> withTimeout(delivered.future(), "the message was not delivered"))
                .compose(payload -> pollUntil(() -> messageCount().map(count -> count == 1L),
                        "the accepted message was not deleted").map(v -> payload))
                .compose(payload -> messageRow().map(row -> {
                    assertEquals("pass", payload);
                    assertEquals("hold", row.getJsonObject("payload").getString("value"),
                            "the refused message must remain");
                    return (Void) null;
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void groupRefusalWritesOneExclusionAndTheGroupDoesNotClaimTheMessageAgain(VertxTestContext testContext) {
        AtomicInteger dropEvaluations = new AtomicInteger();
        List<String> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
        Promise<Void> firstKeep = Promise.promise();
        Promise<Void> secondKeep = Promise.promise();
        ConsumerGroup<String> group = group("refusing-group");
        group.setGroupFilter(message -> {
            if ("drop".equals(message.getPayload())) {
                dropEvaluations.incrementAndGet();
                return false;
            }
            return true;
        });
        group.addConsumer("member", message -> {
            delivered.add(message.getPayload());
            if ("keep-1".equals(message.getPayload())) {
                firstKeep.tryComplete();
            }
            if ("keep-2".equals(message.getPayload())) {
                secondKeep.tryComplete();
            }
            return Future.succeededFuture();
        });

        MessageProducer<String> producer = producer();
        group.start()
                .compose(v -> producer.send("drop"))
                .compose(v -> pollUntil(() -> exclusionCount("refusing-group").map(count -> count == 1L),
                        "the group refusal wrote no exclusion row"))
                .compose(v -> producer.send("keep-1"))
                .compose(v -> withTimeout(firstKeep.future(), "keep-1 was not delivered; the refused message blocks the group"))
                .compose(v -> producer.send("keep-2"))
                .compose(v -> withTimeout(secondKeep.future(), "keep-2 was not delivered; the refused message blocks the group"))
                .compose(v -> pollUntil(() -> messageCount().map(count -> count == 1L),
                        "the accepted messages were not deleted"))
                .compose(v -> messageRow())
                .compose(row -> exclusionCount("refusing-group").map(exclusions -> {
                    assertEquals("drop", row.getJsonObject("payload").getString("value"));
                    assertEquals("AVAILABLE", row.getString("status"),
                            "a refused message must stay available to other consumers");
                    assertEquals(0, row.getInteger("retry_count"), "a refusal is not a failure");
                    assertNull(row.getValue("lock_until"));
                    assertEquals(1L, exclusions, "exactly one exclusion row for the refusing group");
                    assertEquals(1, dropEvaluations.get(),
                            "the group must not claim a message it refused; it is older than keep-2");
                    assertEquals(List.of("keep-1", "keep-2"), delivered);
                    return (Void) null;
                }))
                .compose(v -> deadLetterCount())
                .onSuccess(deadLetters -> testContext.verify(() -> {
                    assertEquals(0L, deadLetters, "a refusal must not dead-letter");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void messageRefusedByOneGroupIsDeliveredToAPlainConsumer(VertxTestContext testContext) {
        Promise<String> plainDelivery = Promise.promise();
        ConsumerGroup<String> group = group("refuses-everything");
        group.setGroupFilter(message -> false);
        group.addConsumer("member", message -> Future.failedFuture(
                new AssertionError("a refused message reached the refusing group's handler")));

        group.start()
                .compose(v -> producer().send("for-someone-else"))
                .compose(v -> pollUntil(() -> exclusionCount("refuses-everything").map(count -> count == 1L),
                        "the group refusal wrote no exclusion row"))
                .compose(v -> {
                    MessageConsumer<String> plain = factory.createConsumer(topic, String.class);
                    consumers.add(plain);
                    return plain.subscribe(message -> {
                        plainDelivery.tryComplete(message.getPayload());
                        return Future.succeededFuture();
                    });
                })
                .compose(v -> withTimeout(plainDelivery.future(), "the plain consumer did not receive the refused message"))
                .compose(payload -> pollUntil(() -> messageCount().map(count -> count == 0L),
                        "the plain consumer did not complete the message").map(v -> payload))
                .compose(payload -> exclusionCount("refuses-everything").map(exclusions -> {
                    assertEquals("for-someone-else", payload);
                    assertEquals(0L, exclusions, "the exclusion row must be deleted with its message");
                    return (Void) null;
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void messageRefusedByOneGroupIsDeliveredToAnotherGroup(VertxTestContext testContext) {
        Promise<String> otherGroupDelivery = Promise.promise();
        ConsumerGroup<String> refusing = group("group-a");
        refusing.setGroupFilter(message -> false);
        refusing.addConsumer("member-a", message -> Future.failedFuture(
                new AssertionError("a refused message reached the refusing group's handler")));
        ConsumerGroup<String> accepting = group("group-b");
        accepting.addConsumer("member-b", message -> {
            otherGroupDelivery.tryComplete(message.getPayload());
            return Future.succeededFuture();
        });

        refusing.start()
                .compose(v -> producer().send("shared"))
                .compose(v -> pollUntil(() -> exclusionCount("group-a").map(count -> count == 1L),
                        "group-a wrote no exclusion row"))
                .compose(v -> accepting.start())
                .compose(v -> withTimeout(otherGroupDelivery.future(), "group-b did not receive the message group-a refused"))
                .onSuccess(payload -> testContext.verify(() -> {
                    assertEquals("shared", payload);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void groupFilterThatThrowsIsRetriedAndDeadLetteredAndNoHandlerRuns(VertxTestContext testContext) {
        AtomicInteger groupFilterCalls = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        ConsumerGroup<String> group = group("throwing-group-filter");
        group.setGroupFilter(message -> {
            groupFilterCalls.incrementAndGet();
            throw new IllegalStateException("group filter defect");
        });
        group.addConsumer("member", message -> {
            handlerCalls.incrementAndGet();
            return Future.succeededFuture();
        });

        group.start()
                .compose(v -> producer().send("payload"))
                .compose(v -> pollUntil(() -> deadLetterCount().map(count -> count == 1L),
                        "the message was not dead-lettered"))
                .compose(v -> deadLetterRow())
                .compose(row -> exclusionCount("throwing-group-filter").map(exclusions -> {
                    assertEquals(MAX_RETRIES, row.getInteger("retry_count"), RECORDED_RETRIES);
                    assertTrue(row.getString("failure_reason").contains("group filter defect"));
                    assertEquals(MAX_RETRIES + 1, groupFilterCalls.get(), RECORDED_RETRIES);
                    assertEquals(0, handlerCalls.get());
                    assertEquals(0L, exclusions, "a throwing predicate is a failure, not a refusal");
                    return (Void) null;
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void handlerSuccessDeletesTheMessageAndWritesNoExclusion(VertxTestContext testContext) {
        Promise<Void> handled = Promise.promise();
        ConsumerGroup<String> group = group("completing-group");
        group.addConsumer("member", message -> {
            handled.tryComplete();
            return Future.succeededFuture();
        });

        group.start()
                .compose(v -> producer().send("done"))
                .compose(v -> withTimeout(handled.future(), "the message was not handled"))
                .compose(v -> pollUntil(() -> messageCount().map(count -> count == 0L),
                        "the completed message was not deleted"))
                .compose(v -> exclusionCount("completing-group"))
                .compose(exclusions -> deadLetterCount().map(deadLetters -> {
                    assertEquals(0L, exclusions);
                    assertEquals(0L, deadLetters);
                    return (Void) null;
                }))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    // ------------------------------------------------------------------------------------

    private ConsumerGroup<String> group(String name) {
        ConsumerGroup<String> group = factory.createConsumerGroup(name, topic, String.class);
        groups.add(group);
        return group;
    }

    private MessageProducer<String> producer() {
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        producers.add(producer);
        return producer;
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
        return count("SELECT COUNT(*) AS c FROM queue_messages WHERE topic = $1", Tuple.of(topic));
    }

    private Future<Long> deadLetterCount() {
        return count("SELECT COUNT(*) AS c FROM dead_letter_queue WHERE topic = $1", Tuple.of(topic));
    }

    private Future<Long> exclusionCount(String groupName) {
        return count("""
                SELECT COUNT(*) AS c FROM queue_message_group_exclusions x
                JOIN queue_messages q ON q.id = x.message_id
                WHERE q.topic = $1 AND x.group_name = $2
                """, Tuple.of(topic, groupName))
                .compose(joined -> count("""
                        SELECT COUNT(*) AS c FROM queue_message_group_exclusions x
                        WHERE x.group_name = $1
                          AND NOT EXISTS (SELECT 1 FROM queue_messages q WHERE q.id = x.message_id)
                        """, Tuple.of(groupName))
                        .map(orphans -> {
                            assertEquals(0L, orphans, "an exclusion row must not outlive its message");
                            return joined;
                        }));
    }

    private Future<Long> count(String sql, Tuple params) {
        return manager.getPool().preparedQuery(sql).execute(params)
                .map(rows -> rows.iterator().next().getLong("c"));
    }

    private Future<Row> messageRow() {
        return singleRow("""
                SELECT status, retry_count, lock_until, visible_at, created_at, payload
                FROM queue_messages WHERE topic = $1
                """);
    }

    private Future<Row> deadLetterRow() {
        return singleRow("""
                SELECT original_table, retry_count, failure_reason
                FROM dead_letter_queue WHERE topic = $1
                """);
    }

    private Future<Row> singleRow(String sql) {
        return manager.getPool().preparedQuery(sql).execute(Tuple.of(topic))
                .compose((RowSet<Row> rows) -> rows.size() == 1
                        ? Future.succeededFuture(rows.iterator().next())
                        : Future.failedFuture(new AssertionError(
                                "expected exactly one row for topic " + topic + ", found " + rows.size())));
    }

}
