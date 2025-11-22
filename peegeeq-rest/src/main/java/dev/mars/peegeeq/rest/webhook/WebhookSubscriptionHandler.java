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

package dev.mars.peegeeq.rest.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for webhook subscription REST endpoints.
 * 
 * Manages push-based message delivery via webhooks, replacing the anti-pattern
 * request/response polling (GET /messages). When a subscription is created,
 * messages are pushed to the webhook URL via HTTP POST.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-22
 * @version 1.0
 */
public class WebhookSubscriptionHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(WebhookSubscriptionHandler.class);
    
    private final DatabaseSetupService setupService;
    private final ObjectMapper objectMapper;
    private final Vertx vertx;
    private final WebClient webClient;
    
    // In-memory storage for subscriptions (will be replaced with database storage in Phase 3)
    private final Map<String, WebhookSubscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, MessageConsumer<Object>> activeConsumers = new ConcurrentHashMap<>();
    
    public WebhookSubscriptionHandler(DatabaseSetupService setupService, ObjectMapper objectMapper, Vertx vertx) {
        this.setupService = setupService;
        this.objectMapper = objectMapper;
        this.vertx = vertx;
        
        // Create WebClient for making HTTP requests to webhooks
        WebClientOptions options = new WebClientOptions()
            .setConnectTimeout(5000)
            .setIdleTimeout(30);
        this.webClient = WebClient.create(vertx, options);
    }
    
    /**
     * POST /setups/:setupId/queues/:queueName/webhook-subscriptions
     * Creates a new webhook subscription for push-based message delivery.
     */
    public void createSubscription(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        
        try {
            JsonObject body = ctx.body().asJsonObject();
            String webhookUrl = body.getString("webhookUrl");
            
            if (webhookUrl == null || webhookUrl.isEmpty()) {
                sendError(ctx, 400, "webhookUrl is required");
                return;
            }
            
            // Parse optional headers and filters
            Map<String, String> headers = parseHeaders(body.getJsonObject("headers"));
            Map<String, String> filters = parseFilters(body.getJsonObject("filters"));
            
            logger.info("Creating webhook subscription for queue {} in setup: {}", queueName, setupId);
            
            // Verify setup exists and is active
            verifySetupActive(setupId)
                .compose(isActive -> {
                    if (!isActive) {
                        return Future.failedFuture("Setup is not active: " + setupId);
                    }
                    
                    // Create subscription
                    String subscriptionId = UUID.randomUUID().toString();
                    WebhookSubscription subscription = new WebhookSubscription(
                        subscriptionId, setupId, queueName, webhookUrl, headers, filters);
                    
                    subscriptions.put(subscriptionId, subscription);
                    
                    // Start consuming messages and pushing to webhook
                    return startConsumingForWebhook(subscription)
                        .compose(v -> Future.succeededFuture(subscription));
                })
                .onSuccess(subscription -> {
                    JsonObject response = new JsonObject()
                        .put("subscriptionId", subscription.getSubscriptionId())
                        .put("setupId", setupId)
                        .put("queueName", queueName)
                        .put("webhookUrl", subscription.getWebhookUrl())
                        .put("status", subscription.getStatus().toString())
                        .put("createdAt", subscription.getCreatedAt().toString());
                    
                    ctx.response()
                        .setStatusCode(201)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());
                    
                    logger.info("Webhook subscription created: {} for queue {} in setup {}", 
                        subscription.getSubscriptionId(), queueName, setupId);
                })
                .onFailure(throwable -> {
                    logger.error("Error creating webhook subscription for queue: " + queueName, throwable);
                    sendError(ctx, 500, "Failed to create subscription: " + throwable.getMessage());
                });
            
        } catch (Exception e) {
            logger.error("Error parsing subscription request", e);
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
        }
    }
    
    /**
     * GET /webhook-subscriptions/:subscriptionId
     * Gets details about a webhook subscription.
     */
    public void getSubscription(RoutingContext ctx) {
        String subscriptionId = ctx.pathParam("subscriptionId");
        
        WebhookSubscription subscription = subscriptions.get(subscriptionId);
        if (subscription == null) {
            sendError(ctx, 404, "Subscription not found: " + subscriptionId);
            return;
        }
        
        JsonObject response = new JsonObject()
            .put("subscriptionId", subscription.getSubscriptionId())
            .put("setupId", subscription.getSetupId())
            .put("queueName", subscription.getQueueName())
            .put("webhookUrl", subscription.getWebhookUrl())
            .put("status", subscription.getStatus().toString())
            .put("createdAt", subscription.getCreatedAt().toString())
            .put("consecutiveFailures", subscription.getConsecutiveFailures());
        
        if (subscription.getLastDeliveryAttempt() != null) {
            response.put("lastDeliveryAttempt", subscription.getLastDeliveryAttempt().toString());
        }
        if (subscription.getLastSuccessfulDelivery() != null) {
            response.put("lastSuccessfulDelivery", subscription.getLastSuccessfulDelivery().toString());
        }
        
        ctx.response()
            .setStatusCode(200)
            .putHeader("content-type", "application/json")
            .end(response.encode());
    }
    
    /**
     * DELETE /webhook-subscriptions/:subscriptionId
     * Deletes a webhook subscription.
     */
    public void deleteSubscription(RoutingContext ctx) {
        String subscriptionId = ctx.pathParam("subscriptionId");
        
        WebhookSubscription subscription = subscriptions.get(subscriptionId);
        if (subscription == null) {
            sendError(ctx, 404, "Subscription not found: " + subscriptionId);
            return;
        }
        
        // Stop consuming
        MessageConsumer<Object> consumer = activeConsumers.remove(subscriptionId);
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                logger.warn("Error closing consumer for subscription {}: {}", subscriptionId, e.getMessage());
            }
        }
        
        // Mark as deleted
        subscription.setStatus(WebhookSubscriptionStatus.DELETED);
        subscriptions.remove(subscriptionId);
        
        ctx.response()
            .setStatusCode(204)
            .end();
        
        logger.info("Webhook subscription deleted: {}", subscriptionId);
    }
    
    /**
     * Starts consuming messages from the queue and pushing to webhook.
     * Uses MessageConsumer API's push-based pattern.
     */
    private Future<Void> startConsumingForWebhook(WebhookSubscription subscription) {
        CompletableFuture<QueueFactory> queueFactoryFuture = 
            getQueueFactory(subscription.getSetupId(), subscription.getQueueName());
        
        return Future.fromCompletionStage(queueFactoryFuture)
            .compose(queueFactory -> {
                MessageConsumer<Object> consumer = queueFactory.createConsumer(
                    subscription.getQueueName(), Object.class);
                
                // Subscribe with push-based handler
                consumer.subscribe(message -> {
                    // Push message to webhook
                    return deliverToWebhook(subscription, message)
                        .onSuccess(v -> {
                            logger.debug("Message delivered to webhook: {}", subscription.getWebhookUrl());
                            subscription.setLastSuccessfulDelivery(java.time.Instant.now());
                            subscription.resetConsecutiveFailures();
                        })
                        .onFailure(error -> {
                            logger.error("Failed to deliver message to webhook: {}", 
                                subscription.getWebhookUrl(), error);
                            subscription.setLastDeliveryAttempt(java.time.Instant.now());
                            subscription.incrementConsecutiveFailures();
                            
                            // Auto-disable after too many failures
                            if (subscription.getConsecutiveFailures() >= 5) {
                                subscription.setStatus(WebhookSubscriptionStatus.FAILED);
                                logger.warn("Webhook subscription {} disabled after {} consecutive failures", 
                                    subscription.getSubscriptionId(), subscription.getConsecutiveFailures());
                            }
                        })
                        .toCompletionStage()
                        .toCompletableFuture();
                });
                
                activeConsumers.put(subscription.getSubscriptionId(), consumer);
                return Future.succeededFuture();
            });
    }
    
    /**
     * Delivers a message to the webhook URL via HTTP POST.
     */
    private Future<Void> deliverToWebhook(WebhookSubscription subscription, Message<Object> message) {
        try {
            JsonObject payload = new JsonObject()
                .put("subscriptionId", subscription.getSubscriptionId())
                .put("queueName", subscription.getQueueName())
                .put("messageId", message.getId())
                .put("payload", JsonObject.mapFrom(message.getPayload()))
                .put("timestamp", System.currentTimeMillis());
            
            var request = webClient.postAbs(subscription.getWebhookUrl())
                .timeout(10000);
            
            // Add custom headers
            if (subscription.getHeaders() != null) {
                subscription.getHeaders().forEach(request::putHeader);
            }
            
            return request.sendJsonObject(payload)
                .compose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return Future.succeededFuture();
                    } else {
                        return Future.failedFuture("Webhook returned " + response.statusCode());
                    }
                })
                .mapEmpty();
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }
    
    /**
     * Verifies that a setup exists and is active.
     */
    private Future<Boolean> verifySetupActive(String setupId) {
        CompletableFuture<DatabaseSetupStatus> future = setupService.getSetupStatus(setupId);
        
        return Future.fromCompletionStage(future)
            .compose(status -> {
                if (status == null) {
                    return Future.failedFuture("Setup not found: " + setupId);
                }
                return Future.succeededFuture(status == DatabaseSetupStatus.ACTIVE);
            });
    }
    
    /**
     * Gets QueueFactory for a setup.
     */
    private CompletableFuture<QueueFactory> getQueueFactory(String setupId, String queueName) {
        return setupService.getSetupResult(setupId)
            .thenApply(setupResult -> {
                if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                    throw new IllegalStateException("Setup " + setupId + " is not active");
                }
                
                QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                if (queueFactory == null) {
                    throw new IllegalArgumentException("Queue " + queueName + " not found in setup " + setupId);
                }
                
                return queueFactory;
            });
    }
    
    private Map<String, String> parseHeaders(JsonObject headers) {
        if (headers == null) {
            return Map.of();
        }
        Map<String, String> result = new ConcurrentHashMap<>();
        headers.forEach(entry -> result.put(entry.getKey(), entry.getValue().toString()));
        return result;
    }
    
    private Map<String, String> parseFilters(JsonObject filters) {
        if (filters == null) {
            return Map.of();
        }
        Map<String, String> result = new ConcurrentHashMap<>();
        filters.forEach(entry -> result.put(entry.getKey(), entry.getValue().toString()));
        return result;
    }
    
    private void sendError(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
            .put("error", message)
            .put("statusCode", statusCode);
        
        ctx.response()
            .setStatusCode(statusCode)
            .putHeader("content-type", "application/json")
            .end(error.encode());
    }
    
    /**
     * Cleanup method - closes all active consumers and web client.
     */
    public void close() {
        logger.info("Closing WebhookSubscriptionHandler...");
        
        activeConsumers.values().forEach(consumer -> {
            try {
                consumer.close();
            } catch (Exception e) {
                logger.warn("Error closing consumer: {}", e.getMessage());
            }
        });
        
        activeConsumers.clear();
        subscriptions.clear();
        
        if (webClient != null) {
            webClient.close();
        }
        
        logger.info("WebhookSubscriptionHandler closed");
    }
}
