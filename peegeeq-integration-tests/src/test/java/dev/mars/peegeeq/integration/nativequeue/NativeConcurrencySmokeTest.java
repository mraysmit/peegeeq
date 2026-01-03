package dev.mars.peegeeq.integration.nativequeue;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@DisplayName("Native Queue Concurrency Tests")
public class NativeConcurrencySmokeTest extends SmokeTestBase {

    private final List<MessageConsumer<?>> activeConsumers = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanup() {
        synchronized (activeConsumers) {
            for (MessageConsumer<?> consumer : activeConsumers) {
                try {
                    consumer.unsubscribe();
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
        
        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
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
                                webClient.post(REST_PORT, REST_HOST, "/api/v1/queues/" + setupId + "/" + queueName + "/messages")
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
                                webClient.delete(REST_PORT, REST_HOST, "/api/v1/database-setup/" + setupId).send();
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
        
        webClient.post(REST_PORT, REST_HOST, "/api/v1/database-setup/create")
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
                    webClient.post(REST_PORT, REST_HOST, "/api/v1/queues/" + setupId + "/" + queueName + "/messages")
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
                                webClient.post(REST_PORT, REST_HOST, "/api/v1/queues/" + setupId + "/" + queueName + "/messages")
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
                                                    webClient.delete(REST_PORT, REST_HOST, "/api/v1/database-setup/" + setupId).send();
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
}
