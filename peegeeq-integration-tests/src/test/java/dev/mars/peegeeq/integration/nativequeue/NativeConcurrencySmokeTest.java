package dev.mars.peegeeq.integration.nativequeue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.integration.SmokeTestBase;
import dev.mars.peegeeq.pgqueue.PgNativeQueueConsumer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@DisplayName("Native Queue Concurrency Tests")
@Tag("integration")
public class NativeConcurrencySmokeTest extends SmokeTestBase {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(NativeConcurrencySmokeTest.class);

    private final List<MessageConsumer<?>> activeConsumers = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanup() {
        synchronized (activeConsumers) {
            for (MessageConsumer<?> consumer : activeConsumers) {
                try {
                    consumer.close();
                } catch (Exception e) {
                    logger.warn("Failed to close consumer during cleanup", e);
                }
            }
            activeConsumers.clear();
        }
    }

    @Test
    @DisplayName("Verify consumer group load balancing (SKIP LOCKED)")
    void testConsumerGroupLoadBalancing(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "concurrent_queue";
        int messageCount = 50;
        int consumerCount = 5;

        // 1. Create Setup
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);
        
        webClient.post( "/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .onSuccess(res -> {
                if (res.statusCode() != 200 && res.statusCode() != 201) {
                    testContext.failNow(new AssertionError("Setup failed with status: " + res.statusCode()));
                    return;
                }
                
                // 2. Get QueueFactory
                setupService.getSetupResult(setupId).onSuccess(result -> {
                    QueueFactory factory = result.getQueueFactories().get(queueName);
                    if (factory == null) {
                        testContext.failNow(new AssertionError("Queue factory should exist"));
                        return;
                    }

                    // 3. Start Consumers
                    AtomicInteger consumedCount = new AtomicInteger(0);
                    Set<String> consumedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
                    Promise<Void> allConsumedPromise = Promise.promise();

                    for (int i = 0; i < consumerCount; i++) {
                        MessageConsumer<Object> consumer = factory.createConsumer(queueName, Object.class);
                        activeConsumers.add(consumer);
                        consumer.subscribe(msg -> {
                            consumedIds.add(msg.getId());
                            if (consumedCount.incrementAndGet() >= messageCount) {
                                allConsumedPromise.tryComplete();
                            }
                            return Future.succeededFuture();
                        });
                    }

                    // Publish all messages; failures are logged but do not block publishing
                    List<Future<Void>> publishFutures = new ArrayList<>();
                    for (int i = 0; i < messageCount; i++) {
                        int idx = i;
                        publishFutures.add(
                            webClient.post("/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                                .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("data", "msg-" + idx)))
                                .onFailure(err -> logger.warn("Failed to publish message {}: {}", idx, err.getMessage()))
                                .mapEmpty()
                        );
                    }
                    Future.all(publishFutures)
                        .onFailure(err -> logger.warn("Some messages failed to publish: {}", err.getMessage()));

                    // Guard against infinite wait: fail after 60 s if not all consumed
                    long timerId = vertx.setTimer(60_000, ignored ->
                        allConsumedPromise.tryFail(new AssertionError(
                            "Timeout waiting for messages. Consumed: " + consumedCount.get() + "/" + messageCount)));

                    allConsumedPromise.future()
                        .onSuccess(v -> {
                            vertx.cancelTimer(timerId);
                            testContext.verify(() -> {
                                assertEquals(messageCount, consumedCount.get());
                                assertEquals(messageCount, consumedIds.size(), "Should have no duplicates");
                                webClient.delete("/api/v1/database-setup/" + setupId).send();
                            });
                            testContext.completeNow();
                        })
                        .onFailure(testContext::failNow);

                }).onFailure(ex -> {
                    testContext.failNow(ex);
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Verify notification recovery (Consumer Restart)")
    void testNotificationRecovery(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "recovery_queue";

        JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);
        
        webClient.post( "/api/v1/database-setup/create")
            .sendJsonObject(setupRequest)
            .onSuccess(res -> {
                setupService.getSetupResult(setupId).onSuccess(result -> {
                    QueueFactory factory = result.getQueueFactories().get(queueName);
                    if (factory == null) {
                        testContext.failNow(new AssertionError("Queue factory should exist for recovery test"));
                        return;
                    }
                    
                    // 1. Start Consumer 1  use a Promise so we can chain off message receipt
                    MessageConsumer<Object> consumer1 = factory.createConsumer(queueName, Object.class);
                    activeConsumers.add(consumer1);
                    Promise<Void> msg1Promise = Promise.promise();
                    consumer1.subscribe(msg -> {
                        msg1Promise.tryComplete();
                        return Future.succeededFuture();
                    });

                    // 2. Publish Message 1, then wait for it via the promise
                    webClient.post("/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                        .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("data", "msg-1")))
                        .compose(r -> {
                            if (r.statusCode() != 200) {
                                return Future.failedFuture(new AssertionError("Failed to publish msg-1: " + r.statusCode()));
                            }
                            return msg1Promise.future(); // wait for Consumer 1 to receive the message
                        })
                        .compose(v -> {
                            // 3. Stop Consumer 1
                            consumer1.unsubscribe();
                            activeConsumers.remove(consumer1);

                            // 4. Publish Message 2 while no consumer is active
                            return webClient.post("/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                                .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("data", "msg-2")));
                        })
                        .compose(r2 -> {
                            if (r2.statusCode() != 200) {
                                return Future.failedFuture(new AssertionError("Failed to publish msg-2: " + r2.statusCode()));
                            }
                            // 5. Start Consumer 2  use a Promise to detect msg-2
                            MessageConsumer<Object> consumer2 = factory.createConsumer(queueName, Object.class);
                            activeConsumers.add(consumer2);
                            Promise<Void> msg2Promise = Promise.promise();
                            consumer2.subscribe(msg -> {
                                if (msg.getPayload().toString().contains("msg-2")) {
                                    msg2Promise.tryComplete();
                                }
                                return Future.succeededFuture();
                            });
                            return msg2Promise.future();
                        })
                        .onSuccess(v -> {
                            webClient.delete("/api/v1/database-setup/" + setupId).send();
                            testContext.completeNow();
                        })
                        .onFailure(testContext::failNow);

                }).onFailure(ex -> {
                    testContext.failNow(ex);
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Destroy setup should stop native listeners without reconnect noise")
    void testDestroySetupStopsNativeListenersCleanly(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "shutdown_queue";
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ShutdownLogCaptureAppender appender = new ShutdownLogCaptureAppender();
        rootLogger.addAppender(appender);
        appender.start();

        AtomicInteger logStartIndex = new AtomicInteger();
        AtomicReference<PgNativeQueueConsumer<Object>> consumerRef = new AtomicReference<>();

        setupService.createCompleteSetup(createSetupRequest(setupId, queueName))
            .compose(result -> {
                QueueFactory factory = result.getQueueFactories().get(queueName);
                if (factory == null) {
                    return Future.<Void>failedFuture(
                            new AssertionError("Queue factory should exist for shutdown regression test"));
                }
                @SuppressWarnings("unchecked")
                PgNativeQueueConsumer<Object> consumer =
                        (PgNativeQueueConsumer<Object>) factory.createConsumer(queueName, Object.class);
                consumerRef.set(consumer);
                activeConsumers.add(consumer);

                // subscribe() completes once the native LISTEN is established — chain off it
                // instead of polling for readiness (and so the send/subscribe Future is observed).
                return consumer.subscribe(msg -> Future.succeededFuture())
                    .compose(v -> {
                        // Snapshot the log position, then destroy the setup.
                        logStartIndex.set(appender.size());
                        activeConsumers.remove(consumer);

                        // destroySetup tears down the per-setup, manager-owned Vert.x (each setup
                        // owns its Vert.x). Its returned Future completes on that dying context, so
                        // a continuation *chained on it* is dispatched to a dead event loop and
                        // dropped — the test would hang. So do NOT chain on it: fire it (surfacing
                        // failure best-effort), and instead observe its effect by polling the
                        // consumer teardown on the infrastructure Vert.x (the shared field). The
                        // first pollUntil tick runs here while the per-setup context is still alive;
                        // vertx.timer() then moves the recursion onto the infra event loop before
                        // the per-setup Vert.x is closed, so the poll and everything after it survive.
                        // destroySetup closes the consumer synchronously (isClosed() is already true),
                        // but the native LISTEN connection is torn down asynchronously by
                        // stopListening(), so poll the full teardown state the verify block asserts.
                        setupService.destroySetup(setupId).onFailure(testContext::failNow);
                        return pollUntil(vertx,
                                () -> consumer.isClosed()
                                        && !consumer.hasActiveListenConnection()
                                        && !consumer.hasPendingListenReconnect()
                                        && !consumer.isSubscribed(),
                                System.currentTimeMillis() + 10_000, "native consumer fully torn down");
                    });
            })
            // Detach the appender and best-effort-destroy the setup regardless of outcome.
            .eventually(() -> {
                rootLogger.detachAppender(appender);
                appender.stop();
                return setupService.getAllActiveSetupIds()
                        .compose(ids -> ids.contains(setupId)
                                ? setupService.destroySetup(setupId)
                                : Future.<Void>succeededFuture())
                        .transform(ar -> Future.<Void>succeededFuture());
            })
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                PgNativeQueueConsumer<Object> consumer = consumerRef.get();
                assertFalse(consumer.hasActiveListenConnection(),
                        "Destroyed setup must clear native LISTEN connection");
                assertFalse(consumer.hasPendingListenReconnect(),
                        "Destroyed setup must not retain LISTEN reconnect timers");
                assertTrue(consumer.isClosed(), "Destroyed setup must close native consumers");
                assertFalse(consumer.isSubscribed(), "Destroyed setup must leave consumer unsubscribed");
                assertNoShutdownReconnectLogs(appender.snapshotFrom(logStartIndex.get()), queueName);
                testContext.completeNow();
            })));
    }

    private DatabaseSetupRequest createSetupRequest(String setupId, String queueName) {
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(getPostgresHost())
                .port(getPostgresPort())
                .databaseName("smoke_db_" + setupId.replace("-", "_"))
                .username(getPostgresUsername())
                .password(getPostgresPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .templateDatabase("template0")
                .encoding("UTF8")
                .build();

        QueueConfig queueConfig = new QueueConfig.Builder()
                .queueName(queueName)
                .maxRetries(3)
                .visibilityTimeoutSeconds(30)
                .build();

        return new DatabaseSetupRequest(setupId, dbConfig, List.of(queueConfig), List.of(), Map.of());
    }

    private static void assertNoShutdownReconnectLogs(List<ILoggingEvent> events, String queueName) {
        List<String> forbiddenEvents = events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("LISTEN connection closed unexpectedly")
                        || message.contains("Reconnecting LISTEN")
                        || message.contains("Failed to start LISTEN")
                        || message.contains("Pool closed"))
                .toList();

        assertTrue(forbiddenEvents.isEmpty(),
                "Shutdown should not emit reconnect/error log events for queue " + queueName + ": " + forbiddenEvents);
    }

    /**
     * Reactively waits until {@code condition} holds, re-checking every 100 ms via a Vert.x
     * timer (no blocking, no reflection). Fails the returned Future if {@code deadline} passes first.
     */
    private static Future<Void> pollUntil(Vertx vertx, java.util.function.BooleanSupplier condition,
                                          long deadline, String description) {
        if (condition.getAsBoolean()) {
            return Future.succeededFuture();
        }
        if (System.currentTimeMillis() >= deadline) {
            return Future.failedFuture(new AssertionError(description + " — condition not met within timeout"));
        }
        return vertx.timer(100).compose(t -> pollUntil(vertx, condition, deadline, description));
    }

    private static final class ShutdownLogCaptureAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent eventObject) {
            events.add(eventObject);
        }

        private int size() {
            return events.size();
        }

        private List<ILoggingEvent> snapshotFrom(int startIndex) {
            synchronized (events) {
                return new ArrayList<>(events.subList(Math.min(startIndex, events.size()), events.size()));
            }
        }
    }
}
