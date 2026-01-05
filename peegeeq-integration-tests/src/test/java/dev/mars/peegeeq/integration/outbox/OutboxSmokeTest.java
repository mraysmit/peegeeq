package dev.mars.peegeeq.integration.outbox;

import dev.mars.peegeeq.integration.SmokeTestBase;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.SMOKE)
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox Pattern Smoke Tests")
public class OutboxSmokeTest extends SmokeTestBase {

    private static final String QUEUE_NAME = "outbox_smoke_queue";

    @Test
    @DisplayName("Verify basic outbox publish and delivery flow via Webhook")
    void testOutboxPublishAndDelivery(VertxTestContext testContext) {
        String setupId = generateSetupId();
        // Create setup request with OUTBOX queue type
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);
        setupRequest.getJsonArray("queues").getJsonObject(0).put("type", "outbox");

        // Prepare Webhook Receiver
        ConcurrentLinkedQueue<JsonObject> receivedMessages = new ConcurrentLinkedQueue<>();
        int webhookPort = 9090 + (int)(Math.random() * 100); // Simple random port
        String webhookPath = "/webhook-" + setupId;
        String webhookUrl = "http://localhost:" + webhookPort + webhookPath;

        HttpServer webhookServer = vertx.createHttpServer()
            .requestHandler(req -> {
                if (req.path().equals(webhookPath) && req.method().name().equals("POST")) {
                    req.bodyHandler(body -> {
                        logger.info("Webhook received: {}", body.toString());
                        receivedMessages.add(body.toJsonObject());
                        req.response().setStatusCode(200).end();
                    });
                } else {
                    req.response().setStatusCode(404).end();
                }
            });

        webhookServer.listen(webhookPort)
            .compose(server -> {
                logger.info("Webhook server started on port {}", webhookPort);
                // 1. Create Database Setup
                return webClient.post( "/api/v1/database-setup/create")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(setupRequest);
            })
            .compose(setupResponse -> {
                assertTrue(setupResponse.statusCode() == 200 || setupResponse.statusCode() == 201, 
                    "Setup creation failed with status " + setupResponse.statusCode());
                logger.info("Setup created: {}", setupId);

                // 2. Register Webhook Subscription
                JsonObject webhookReq = new JsonObject().put("webhookUrl", webhookUrl);
                return webClient.post( 
                        "/api/v1/setups/" + setupId + "/queues/" + QUEUE_NAME + "/webhook-subscriptions")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(webhookReq);
            })
            .compose(subResponse -> {
                assertTrue(subResponse.statusCode() == 200 || subResponse.statusCode() == 201, "Webhook subscription failed");
                logger.info("Webhook subscribed: {}", webhookUrl);

                // 3. Publish Message to Outbox
                JsonObject messagePayload = new JsonObject()
                    .put("payload", new JsonObject().put("data", "outbox-test-1"))
                    .put("correlationId", "corr-1");
                
                return webClient.post( 
                        "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .putHeader("content-type", "application/json")
                    .sendJsonObject(messagePayload);
            })
            .onComplete(testContext.succeeding(response -> {
                assertEquals(200, response.statusCode(), "Message publish failed");
                logger.info("Message published to outbox");

                // 4. Verify Delivery (Non-blocking Poll)
                long start = System.currentTimeMillis();
                long timeout = 5000;
                
                vertx.setPeriodic(100, id -> {
                    if (!receivedMessages.isEmpty()) {
                        vertx.cancelTimer(id);
                        testContext.verify(() -> {
                            JsonObject received = receivedMessages.poll();
                            assertEquals("outbox-test-1", received.getJsonObject("payload").getString("data"));
                            assertEquals("corr-1", received.getString("correlationId"));
                            
                            webhookServer.close();
                            cleanupSetup(setupId);
                            testContext.completeNow();
                        });
                    } else if (System.currentTimeMillis() - start > timeout) {
                        vertx.cancelTimer(id);
                        testContext.failNow("Webhook did not receive message in time");
                        webhookServer.close();
                        cleanupSetup(setupId);
                    }
                });
            }));
    }

    @Test
    @DisplayName("Verify outbox messages are processed in FIFO order")
    void testOutboxOrdering(VertxTestContext testContext) {
        String setupId = generateSetupId();
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, QUEUE_NAME);
        setupRequest.getJsonArray("queues").getJsonObject(0).put("type", "outbox");

        ConcurrentLinkedQueue<JsonObject> receivedMessages = new ConcurrentLinkedQueue<>();
        int webhookPort = 9091 + (int)(Math.random() * 100);
        String webhookPath = "/webhook-ordering-" + setupId;
        String webhookUrl = "http://localhost:" + webhookPort + webhookPath;

        HttpServer webhookServer = vertx.createHttpServer()
            .requestHandler(req -> {
                if (req.path().equals(webhookPath)) {
                    req.bodyHandler(body -> {
                        receivedMessages.add(body.toJsonObject());
                        req.response().setStatusCode(200).end();
                    });
                }
            });

        webhookServer.listen(webhookPort)
            .compose(server -> webClient.post( "/api/v1/database-setup/create")
                    .sendJsonObject(setupRequest))
            .compose(r -> webClient.post( 
                    "/api/v1/setups/" + setupId + "/queues/" + QUEUE_NAME + "/webhook-subscriptions")
                    .sendJsonObject(new JsonObject().put("webhookUrl", webhookUrl)))
            .compose(r -> {
                // Publish 3 messages
                return webClient.post( "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("seq", 1)));
            })
            .compose(r -> webClient.post( "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("seq", 2))))
            .compose(r -> webClient.post( "/api/v1/queues/" + setupId + "/" + QUEUE_NAME + "/messages")
                    .sendJsonObject(new JsonObject().put("payload", new JsonObject().put("seq", 3))))
            .onComplete(testContext.succeeding(response -> {
                // Verify Delivery (Non-blocking Poll)
                long start = System.currentTimeMillis();
                long timeout = 15000;

                vertx.setPeriodic(100, id -> {
                    if (receivedMessages.size() >= 3) {
                        vertx.cancelTimer(id);
                        testContext.verify(() -> {
                            assertEquals(3, receivedMessages.size(), "Did not receive all 3 messages");
                            
                            // Verify Order
                            assertEquals(1, receivedMessages.poll().getJsonObject("payload").getInteger("seq"));
                            assertEquals(2, receivedMessages.poll().getJsonObject("payload").getInteger("seq"));
                            assertEquals(3, receivedMessages.poll().getJsonObject("payload").getInteger("seq"));

                            webhookServer.close();
                            cleanupSetup(setupId);
                            testContext.completeNow();
                        });
                    } else if (System.currentTimeMillis() - start > timeout) {
                        vertx.cancelTimer(id);
                        testContext.failNow("Did not receive all 3 messages in time. Received: " + receivedMessages.size());
                        webhookServer.close();
                        cleanupSetup(setupId);
                    }
                });
            }));
    }

    private void cleanupSetup(String setupId) {
        webClient.delete( "/api/v1/setups/" + setupId)
            .send()
            .onFailure(err -> logger.warn("Failed to cleanup setup {}", setupId, err));
    }
}
