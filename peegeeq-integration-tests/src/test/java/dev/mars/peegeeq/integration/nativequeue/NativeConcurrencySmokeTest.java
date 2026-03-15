package dev.mars.peegeeq.integration.nativequeue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.integration.SmokeTestBase;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@DisplayName("Native Queue Concurrency Tests")
@Tag("integration")
public class NativeConcurrencySmokeTest extends SmokeTestBase {

    private final List<MessageConsumer<?>> activeConsumers = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanup() {
        synchronized (activeConsumers) {
            for (MessageConsumer<?> consumer : activeConsumers) {
                try {
                    consumer.close();
                } catch (Exception e) {
                    // Ignore cleanup errors
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
                setupService.getSetupResult(setupId).thenAccept(result -> {
                    QueueFactory factory = result.getQueueFactories().get(queueName);
                    assertNotNull(factory, "Queue factory should exist");

                    // 3. Start Consumers
                    AtomicInteger consumedCount = new AtomicInteger(0);
                    Set<String> consumedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
                    CountDownLatch allConsumed = new CountDownLatch(messageCount);

                    for (int i = 0; i < consumerCount; i++) {
                        MessageConsumer<Object> consumer = factory.createConsumer(queueName, Object.class);
                        activeConsumers.add(consumer);
                        consumer.subscribe(msg -> {
                            consumedIds.add(msg.getId());
                            consumedCount.incrementAndGet();
                            allConsumed.countDown();
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        });
                    }

                    // 4. Publish Messages via REST API
                    new Thread(() -> {
                        try {
                            for (int i = 0; i < messageCount; i++) {
                                int index = i;
                                JsonObject msg = new JsonObject().put("payload", new JsonObject().put("data", "msg-" + index));
                                CountDownLatch latch = new CountDownLatch(1);
                                webClient.post( "/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                                    .sendJsonObject(msg)
                                    .onComplete(r -> {
                                        if (r.failed()) {
                                            System.err.println("Failed to publish message " + index + ": " + r.cause().getMessage());
                                        } else if (r.result().statusCode() != 200) {
                                            System.err.println("Failed to publish message " + index + ": Status " + r.result().statusCode());
                                        }
                                        latch.countDown();
                                    });
                                // Wait a bit for each message to ensure order/delivery, but not too long
                                if (!latch.await(2, TimeUnit.SECONDS)) {
                                    System.err.println("Timeout publishing message " + index);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }).start();

                    // 5. Wait for consumption
                    new Thread(() -> {
                        try {
                            // Increased timeout to 60s
                            boolean success = allConsumed.await(60, TimeUnit.SECONDS);
                            if (success) {
                                assertEquals(messageCount, consumedCount.get());
                                assertEquals(messageCount, consumedIds.size(), "Should have no duplicates");
                                
                                // Cleanup
                                webClient.delete( "/api/v1/database-setup/" + setupId).send();
                                testContext.completeNow();
                            } else {
                                testContext.failNow(new AssertionError("Timeout waiting for messages. Consumed: " + consumedCount.get() + "/" + messageCount));
                            }
                        } catch (InterruptedException e) {
                            testContext.failNow(e);
                        }
                    }).start();

                }).exceptionally(ex -> {
                    testContext.failNow(ex);
                    return null;
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
                setupService.getSetupResult(setupId).thenAccept(result -> {
                    QueueFactory factory = result.getQueueFactories().get(queueName);
                    
                    // 1. Start Consumer 1
                    MessageConsumer<Object> consumer1 = factory.createConsumer(queueName, Object.class);
                    activeConsumers.add(consumer1);
                    CountDownLatch latch1 = new CountDownLatch(1);
                    consumer1.subscribe(msg -> {
                        latch1.countDown();
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    });

                    // 2. Publish Message 1
                    webClient.post( "/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                        .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("data", "msg-1")))
                        .onSuccess(r -> {
                            if (r.statusCode() != 200) {
                                testContext.failNow(new AssertionError("Failed to publish msg-1: " + r.statusCode()));
                                return;
                            }
                            try {
                                // Wait for consumption
                                boolean received = latch1.await(10, TimeUnit.SECONDS);
                                assertTrue(received, "Consumer 1 should receive message");
                                
                                // 3. Stop Consumer 1
                                consumer1.unsubscribe();
                                activeConsumers.remove(consumer1); // Remove from auto-cleanup as we closed it manually
                                
                                // 4. Publish Message 2 (while no consumer is active)
                                webClient.post( "/api/v1/queues/" + setupId + "/" + queueName + "/messages")
                                    .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("data", "msg-2")))
                                    .onSuccess(r2 -> {
                                        if (r2.statusCode() != 200) {
                                            testContext.failNow(new AssertionError("Failed to publish msg-2: " + r2.statusCode()));
                                            return;
                                        }
                                        // 5. Start Consumer 2
                                        MessageConsumer<Object> consumer2 = factory.createConsumer(queueName, Object.class);
                                        activeConsumers.add(consumer2);
                                        CountDownLatch latch2 = new CountDownLatch(1);
                                        consumer2.subscribe(msg -> {
                                            if (msg.getPayload().toString().contains("msg-2")) {
                                                latch2.countDown();
                                            }
                                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                                        });
                                        
                                        // 6. Verify Consumer 2 picks up the message
                                        new Thread(() -> {
                                            try {
                                                boolean received2 = latch2.await(15, TimeUnit.SECONDS);
                                                if (received2) {
                                                    webClient.delete( "/api/v1/database-setup/" + setupId).send();
                                                    testContext.completeNow();
                                                } else {
                                                    testContext.failNow(new AssertionError("Consumer 2 did not receive pending message"));
                                                }
                                            } catch (InterruptedException e) {
                                                testContext.failNow(e);
                                            }
                                        }).start();
                                    });
                                    
                            } catch (InterruptedException e) {
                                testContext.failNow(e);
                            }
                        });

                }).exceptionally(ex -> {
                    testContext.failNow(ex);
                    return null;
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Destroy setup should stop native listeners without reconnect noise")
    void testDestroySetupStopsNativeListenersCleanly(Vertx vertx) throws Exception {
        String setupId = generateSetupId();
        String queueName = "shutdown_queue";
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ShutdownLogCaptureAppender appender = new ShutdownLogCaptureAppender();
        rootLogger.addAppender(appender);
        appender.start();

        try {
            DatabaseSetupResult result = setupService.createCompleteSetup(createSetupRequest(setupId, queueName))
                    .get(60, TimeUnit.SECONDS);
            QueueFactory factory = result.getQueueFactories().get(queueName);

            assertNotNull(factory, "Queue factory should exist for shutdown regression test");

            MessageConsumer<Object> consumer = factory.createConsumer(queueName, Object.class);
            activeConsumers.add(consumer);

            consumer.subscribe(msg -> java.util.concurrent.CompletableFuture.completedFuture(null));

            CompletableFuture<Void> subscriberReady = new CompletableFuture<>();
            long subscriberTimer = vertx.setPeriodic(100, id -> {
                try {
                    if (getFieldValue(consumer, "subscriber") != null) {
                        subscriberReady.complete(null);
                    }
                } catch (Exception e) {
                    subscriberReady.completeExceptionally(e);
                }
            });
            subscriberReady.orTimeout(5, SECONDS).join();
            vertx.cancelTimer(subscriberTimer);

            assertNotNull(getFieldValue(consumer, "subscriber"), "Subscribed native consumer should have a LISTEN connection");

            int logStartIndex = appender.size();

            setupService.destroySetup(setupId).get(30, TimeUnit.SECONDS);
            activeConsumers.remove(consumer);

            CompletableFuture<Void> consumerClosed = new CompletableFuture<>();
            long closedTimer = vertx.setPeriodic(100, id -> {
                try {
                    if (getAtomicBooleanField(consumer, "closed")) {
                        consumerClosed.complete(null);
                    }
                } catch (Exception e) {
                    consumerClosed.completeExceptionally(e);
                }
            });
            consumerClosed.orTimeout(5, SECONDS).join();
            vertx.cancelTimer(closedTimer);

            assertNull(getFieldValue(consumer, "subscriber"), "Destroyed setup must clear native LISTEN connection");
            assertEquals(-1L, getLongField(consumer, "listenReconnectTimerId"),
                    "Destroyed setup must not retain LISTEN reconnect timers");
            assertTrue(getAtomicBooleanField(consumer, "closed"), "Destroyed setup must close native consumers");
            assertFalse(getAtomicBooleanField(consumer, "subscribed"), "Destroyed setup must leave consumer unsubscribed");

            assertNoShutdownReconnectLogs(appender.snapshotFrom(logStartIndex), queueName);
        } finally {
            rootLogger.detachAppender(appender);
            appender.stop();

            try {
                if (setupService.getAllActiveSetupIds().get(5, TimeUnit.SECONDS).contains(setupId)) {
                    setupService.destroySetup(setupId).get(10, TimeUnit.SECONDS);
                }
            } catch (Exception ignore) {
                // Cleanup best-effort only.
            }
        }
    }

    private DatabaseSetupRequest createSetupRequest(String setupId, String queueName) {
        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                .host(getPostgresHost())
                .port(getPostgresPort())
                .databaseName("smoke_db_" + setupId.replace("-", "_"))
                .username(getPostgresUsername())
                .password(getPostgresPassword())
                .schema("public")
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

    private static Object getFieldValue(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static long getLongField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static boolean getAtomicBooleanField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicBoolean) field.get(target)).get();
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
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
