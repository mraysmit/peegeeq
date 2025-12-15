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

package dev.mars.peegeeq.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.api.deadletter.DeadLetterStatsInfo;
import dev.mars.peegeeq.api.health.HealthStatusInfo;
import dev.mars.peegeeq.api.health.OverallHealthInfo;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.*;
import dev.mars.peegeeq.client.exception.PeeGeeQApiException;
import dev.mars.peegeeq.client.exception.PeeGeeQNetworkException;
import dev.mars.peegeeq.client.sse.SSEReadStream;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * HTTP implementation of the PeeGeeQ client using Vert.x WebClient.
 * 
 * <p>This client provides non-blocking access to all PeeGeeQ REST API operations.
 * 
 * <p>Example usage:
 * <pre>{@code
 * Vertx vertx = Vertx.vertx();
 * ClientConfig config = ClientConfig.builder()
 *     .baseUrl("http://localhost:8080")
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 * 
 * PeeGeeQClient client = PeeGeeQRestClient.create(vertx, config);
 * 
 * client.createSetup(request)
 *     .onSuccess(result -> System.out.println("Created: " + result.getSetupId()))
 *     .onFailure(err -> System.err.println("Failed: " + err.getMessage()));
 * }</pre>
 */
public class PeeGeeQRestClient implements PeeGeeQClient {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQRestClient.class);

    private final Vertx vertx;
    private final WebClient webClient;
    private final ClientConfig config;
    private final ObjectMapper objectMapper;
    private final String host;
    private final int port;
    private final boolean ssl;

    private PeeGeeQRestClient(Vertx vertx, ClientConfig config) {
        this.vertx = Objects.requireNonNull(vertx, "vertx must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");

        // Parse base URL
        URI uri = URI.create(config.getBaseUrl());
        this.host = uri.getHost();
        this.port = uri.getPort() > 0 ? uri.getPort() : (uri.getScheme().equals("https") ? 443 : 80);
        this.ssl = uri.getScheme().equals("https");

        // Configure pool options (Vert.x 5.x uses PoolOptions for connection pool configuration)
        PoolOptions poolOptions = new PoolOptions()
            .setHttp1MaxSize(config.getPoolSize());

        // Configure HTTP client options
        HttpClientOptions httpClientOptions = new HttpClientOptions()
            .setDefaultHost(host)
            .setDefaultPort(port)
            .setSsl(ssl)
            .setConnectTimeout((int) config.getTimeout().toMillis())
            .setTrustAll(config.isTrustAllCertificates());

        // Create HttpClient with pool options, then wrap with WebClient
        HttpClient httpClient = vertx.createHttpClient(httpClientOptions, poolOptions);

        // Configure WebClient options for additional web-specific settings
        WebClientOptions webClientOptions = new WebClientOptions(httpClientOptions)
            .setFollowRedirects(true)
            .setUserAgentEnabled(true);

        this.webClient = WebClient.wrap(httpClient, webClientOptions);

        // Configure ObjectMapper
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        logger.info("PeeGeeQ client created for {}:{} (SSL: {}, poolSize: {})", host, port, ssl, config.getPoolSize());
    }

    /**
     * Creates a new PeeGeeQ REST client.
     *
     * @param vertx the Vert.x instance
     * @param config the client configuration
     * @return a new client instance
     */
    public static PeeGeeQClient create(Vertx vertx, ClientConfig config) {
        return new PeeGeeQRestClient(vertx, config);
    }

    /**
     * Creates a new PeeGeeQ REST client with default configuration.
     *
     * @param vertx the Vert.x instance
     * @return a new client instance
     */
    public static PeeGeeQClient create(Vertx vertx) {
        return create(vertx, ClientConfig.defaults());
    }

    // ========================================================================
    // Setup Operations
    // ========================================================================

    @Override
    public Future<DatabaseSetupResult> createSetup(DatabaseSetupRequest request) {
        return post("/api/v1/setups", request)
            .map(response -> parseResponse(response, DatabaseSetupResult.class));
    }

    @Override
    public Future<List<DatabaseSetupResult>> listSetups() {
        return get("/api/v1/setups")
            .map(response -> parseListResponse(response, DatabaseSetupResult.class));
    }

    @Override
    public Future<DatabaseSetupResult> getSetup(String setupId) {
        return get("/api/v1/setups/" + setupId)
            .map(response -> parseResponse(response, DatabaseSetupResult.class));
    }

    @Override
    public Future<Void> deleteSetup(String setupId) {
        return delete("/api/v1/setups/" + setupId)
            .mapEmpty();
    }

    @Override
    public Future<DatabaseSetupStatus> getSetupStatus(String setupId) {
        return get("/api/v1/setups/" + setupId + "/status")
            .map(response -> parseResponse(response, DatabaseSetupStatus.class));
    }

    @Override
    public Future<Void> addQueue(String setupId, QueueConfig queueConfig) {
        String path = String.format("/api/v1/setups/%s/queues", setupId);
        return post(path, queueConfig)
            .mapEmpty();
    }

    @Override
    public Future<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig) {
        String path = String.format("/api/v1/setups/%s/eventstores", setupId);
        return post(path, eventStoreConfig)
            .mapEmpty();
    }

    // ========================================================================
    // Queue Operations
    // ========================================================================

    @Override
    public Future<MessageSendResult> sendMessage(String setupId, String queueName, MessageRequest message) {
        String path = String.format("/api/v1/queues/%s/%s/messages", setupId, queueName);
        return post(path, message)
            .map(response -> parseResponse(response, MessageSendResult.class));
    }

    @Override
    public Future<List<MessageSendResult>> sendBatch(String setupId, String queueName, List<MessageRequest> messages) {
        String path = String.format("/api/v1/queues/%s/%s/messages/batch", setupId, queueName);
        return post(path, messages)
            .map(response -> parseListResponse(response, MessageSendResult.class));
    }

    @Override
    public Future<QueueStats> getQueueStats(String setupId, String queueName) {
        String path = String.format("/api/v1/queues/%s/%s/stats", setupId, queueName);
        return get(path)
            .map(response -> parseResponse(response, QueueStats.class));
    }

    @Override
    public Future<QueueDetailsInfo> getQueueDetails(String setupId, String queueName) {
        String path = String.format("/api/v1/queues/%s/%s", setupId, queueName);
        return get(path)
            .map(response -> parseResponse(response, QueueDetailsInfo.class));
    }

    @Override
    public Future<List<String>> getQueueConsumers(String setupId, String queueName) {
        String path = String.format("/api/v1/queues/%s/%s/consumers", setupId, queueName);
        return get(path)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                JsonArray consumers = json.getJsonArray("consumers", new JsonArray());
                List<String> result = new ArrayList<>();
                for (int i = 0; i < consumers.size(); i++) {
                    result.add(consumers.getString(i));
                }
                return result;
            });
    }

    @Override
    public Future<JsonObject> getQueueBindings(String setupId, String queueName) {
        String path = String.format("/api/v1/queues/%s/%s/bindings", setupId, queueName);
        return get(path)
            .map(HttpResponse::bodyAsJsonObject);
    }

    @Override
    public Future<Long> purgeQueue(String setupId, String queueName) {
        String path = String.format("/api/v1/queues/%s/%s/purge", setupId, queueName);
        return post(path, new JsonObject())
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                return json.getLong("purgedCount", 0L);
            });
    }

    // ========================================================================
    // Consumer Group Operations
    // ========================================================================

    @Override
    public Future<ConsumerGroupInfo> createConsumerGroup(String setupId, String queueName, String groupName) {
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups", setupId, queueName);
        JsonObject body = new JsonObject().put("groupName", groupName);
        return post(path, body)
            .map(response -> parseResponse(response, ConsumerGroupInfo.class));
    }

    @Override
    public Future<List<ConsumerGroupInfo>> listConsumerGroups(String setupId, String queueName) {
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups", setupId, queueName);
        return get(path)
            .map(response -> parseListResponse(response, ConsumerGroupInfo.class));
    }

    @Override
    public Future<ConsumerGroupInfo> getConsumerGroup(String setupId, String queueName, String groupName) {
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups/%s", setupId, queueName, groupName);
        return get(path)
            .map(response -> parseResponse(response, ConsumerGroupInfo.class));
    }

    @Override
    public Future<Void> deleteConsumerGroup(String setupId, String queueName, String groupName) {
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups/%s", setupId, queueName, groupName);
        return delete(path)
            .mapEmpty();
    }

    @Override
    public Future<ConsumerGroupMemberInfo> joinConsumerGroup(String setupId, String queueName, String groupName, String memberName) {
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups/%s/members", setupId, queueName, groupName);
        JsonObject body = new JsonObject();
        if (memberName != null) {
            body.put("memberName", memberName);
        }
        return post(path, body)
            .map(response -> parseResponse(response, ConsumerGroupMemberInfo.class));
    }

    @Override
    public Future<Void> leaveConsumerGroup(String setupId, String queueName, String groupName, String memberId) {
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups/%s/members/%s", setupId, queueName, groupName, memberId);
        return delete(path)
            .mapEmpty();
    }

    @Override
    public Future<SubscriptionOptionsInfo> updateSubscriptionOptions(String setupId, String queueName, String groupName, SubscriptionOptionsRequest options) {
        String path = String.format("/api/v1/consumer-groups/%s/%s/%s/subscription", setupId, queueName, groupName);
        return post(path, options)
            .map(response -> parseResponse(response, SubscriptionOptionsInfo.class));
    }

    @Override
    public Future<SubscriptionOptionsInfo> getSubscriptionOptions(String setupId, String queueName, String groupName) {
        String path = String.format("/api/v1/consumer-groups/%s/%s/%s/subscription", setupId, queueName, groupName);
        return get(path)
            .map(response -> parseResponse(response, SubscriptionOptionsInfo.class));
    }

    @Override
    public Future<Void> deleteSubscriptionOptions(String setupId, String queueName, String groupName) {
        String path = String.format("/api/v1/consumer-groups/%s/%s/%s/subscription", setupId, queueName, groupName);
        return delete(path)
            .mapEmpty();
    }

    // ========================================================================
    // Dead Letter Queue Operations
    // ========================================================================

    @Override
    public Future<DeadLetterListResponse> listDeadLetters(String setupId, int page, int pageSize) {
        String path = String.format("/api/v1/setups/%s/deadletter/messages?page=%d&pageSize=%d", setupId, page, pageSize);
        return get(path)
            .map(response -> parseResponse(response, DeadLetterListResponse.class));
    }

    @Override
    public Future<DeadLetterMessageInfo> getDeadLetter(String setupId, long messageId) {
        String path = String.format("/api/v1/setups/%s/deadletter/messages/%d", setupId, messageId);
        return get(path)
            .map(response -> parseResponse(response, DeadLetterMessageInfo.class));
    }

    @Override
    public Future<Void> reprocessDeadLetter(String setupId, long messageId) {
        String path = String.format("/api/v1/setups/%s/deadletter/messages/%d/reprocess", setupId, messageId);
        return post(path, new JsonObject())
            .mapEmpty();
    }

    @Override
    public Future<Void> deleteDeadLetter(String setupId, long messageId) {
        String path = String.format("/api/v1/setups/%s/deadletter/messages/%d", setupId, messageId);
        return delete(path)
            .mapEmpty();
    }

    @Override
    public Future<DeadLetterStatsInfo> getDeadLetterStats(String setupId) {
        String path = String.format("/api/v1/setups/%s/deadletter/stats", setupId);
        return get(path)
            .map(response -> parseResponse(response, DeadLetterStatsInfo.class));
    }

    @Override
    public Future<Long> cleanupDeadLetters(String setupId, int olderThanDays) {
        String path = String.format("/api/v1/setups/%s/deadletter/cleanup", setupId);
        JsonObject body = new JsonObject().put("olderThanDays", olderThanDays);
        return post(path, body)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                return json.getLong("deletedCount", 0L);
            });
    }

    // ========================================================================
    // Subscription Operations
    // ========================================================================

    @Override
    public Future<List<SubscriptionInfo>> listSubscriptions(String setupId, String topic) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", setupId, topic);
        return get(path)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                JsonArray subscriptions = json.getJsonArray("subscriptions", new JsonArray());
                List<SubscriptionInfo> result = new ArrayList<>();
                for (int i = 0; i < subscriptions.size(); i++) {
                    result.add(parseJson(subscriptions.getJsonObject(i).encode(), SubscriptionInfo.class));
                }
                return result;
            });
    }

    @Override
    public Future<SubscriptionInfo> getSubscription(String setupId, String topic, String groupName) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s", setupId, topic, groupName);
        return get(path)
            .map(response -> parseResponse(response, SubscriptionInfo.class));
    }

    @Override
    public Future<Void> pauseSubscription(String setupId, String topic, String groupName) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/pause", setupId, topic, groupName);
        return post(path, new JsonObject())
            .mapEmpty();
    }

    @Override
    public Future<Void> resumeSubscription(String setupId, String topic, String groupName) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/resume", setupId, topic, groupName);
        return post(path, new JsonObject())
            .mapEmpty();
    }

    @Override
    public Future<Void> cancelSubscription(String setupId, String topic, String groupName) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s", setupId, topic, groupName);
        return delete(path)
            .mapEmpty();
    }

    @Override
    public Future<Void> updateHeartbeat(String setupId, String topic, String groupName) {
        String path = String.format("/api/v1/setups/%s/subscriptions/%s/%s/heartbeat", setupId, topic, groupName);
        return post(path, new JsonObject())
            .mapEmpty();
    }

    // ========================================================================
    // Health Operations
    // ========================================================================

    @Override
    public Future<OverallHealthInfo> getHealth(String setupId) {
        String path = String.format("/api/v1/setups/%s/health", setupId);
        return get(path)
            .map(response -> parseResponse(response, OverallHealthInfo.class));
    }

    @Override
    public Future<List<HealthStatusInfo>> listComponentHealth(String setupId) {
        String path = String.format("/api/v1/setups/%s/health/components", setupId);
        return get(path)
            .map(response -> parseListResponse(response, HealthStatusInfo.class));
    }

    @Override
    public Future<HealthStatusInfo> getComponentHealth(String setupId, String componentName) {
        String path = String.format("/api/v1/setups/%s/health/components/%s", setupId, componentName);
        return get(path)
            .map(response -> parseResponse(response, HealthStatusInfo.class));
    }

    // ========================================================================
    // Event Store Operations
    // ========================================================================

    @Override
    public Future<BiTemporalEvent> appendEvent(String setupId, String storeName, AppendEventRequest request) {
        String path = String.format("/api/v1/eventstores/%s/%s/events", setupId, storeName);
        return post(path, request)
            .map(response -> parseResponse(response, BiTemporalEvent.class));
    }

    @Override
    public Future<EventQueryResult> queryEvents(String setupId, String storeName, EventQuery query) {
        String path = String.format("/api/v1/eventstores/%s/%s/events", setupId, storeName);
        // Build query parameters
        StringBuilder queryParams = new StringBuilder("?");
        if (query.getEventType() != null) {
            queryParams.append("eventType=").append(query.getEventType()).append("&");
        }
        if (query.getLimit() > 0) {
            queryParams.append("limit=").append(query.getLimit()).append("&");
        }
        if (query.getOffset() > 0) {
            queryParams.append("offset=").append(query.getOffset()).append("&");
        }
        return get(path + queryParams)
            .map(response -> parseResponse(response, EventQueryResult.class));
    }

    @Override
    public Future<BiTemporalEvent> getEvent(String setupId, String storeName, String eventId) {
        String path = String.format("/api/v1/eventstores/%s/%s/events/%s", setupId, storeName, eventId);
        return get(path)
            .map(response -> parseResponse(response, BiTemporalEvent.class));
    }

    @Override
    public Future<List<BiTemporalEvent>> getEventVersions(String setupId, String storeName, String eventId) {
        String path = String.format("/api/v1/eventstores/%s/%s/events/%s/versions", setupId, storeName, eventId);
        return get(path)
            .map(response -> parseListResponse(response, BiTemporalEvent.class));
    }

    @Override
    public Future<BiTemporalEvent> appendCorrection(String setupId, String storeName, String eventId,
                                                     CorrectionRequest request) {
        String path = String.format("/api/v1/eventstores/%s/%s/events/%s/corrections", setupId, storeName, eventId);
        return post(path, request)
            .map(response -> parseResponse(response, BiTemporalEvent.class));
    }

    @Override
    public Future<BiTemporalEvent> getEventAsOf(String setupId, String storeName, String eventId, Instant asOfTime) {
        String path = String.format("/api/v1/eventstores/%s/%s/events/%s/at?asOf=%s",
            setupId, storeName, eventId, asOfTime.toString());
        return get(path)
            .map(response -> parseResponse(response, BiTemporalEvent.class));
    }

    @Override
    public Future<EventStoreStats> getEventStoreStats(String setupId, String storeName) {
        String path = String.format("/api/v1/eventstores/%s/%s/stats", setupId, storeName);
        return get(path)
            .map(response -> parseResponse(response, EventStoreStats.class));
    }

    // ========================================================================
    // Streaming Operations
    // ========================================================================

    @Override
    public ReadStream<BiTemporalEvent> streamEvents(String setupId, String storeName, StreamOptions options) {
        String path = String.format("/api/v1/eventstores/%s/%s/events/stream", setupId, storeName);

        // Build query parameters
        StringBuilder queryParams = new StringBuilder();
        if (options != null) {
            if (options.getEventType() != null) {
                queryParams.append("eventType=").append(options.getEventType());
            }
            if (options.getAggregateId() != null) {
                if (queryParams.length() > 0) queryParams.append("&");
                queryParams.append("aggregateId=").append(options.getAggregateId());
            }
        }
        if (queryParams.length() > 0) {
            path = path + "?" + queryParams;
        }

        HttpClient httpClient = vertx.createHttpClient(new HttpClientOptions()
            .setDefaultHost(host)
            .setDefaultPort(port)
            .setSsl(ssl));

        String finalPath = path;
        HttpClientRequest request = httpClient.request(HttpMethod.GET, port, host, finalPath)
            .toCompletionStage().toCompletableFuture().join();
        request.putHeader("Accept", "text/event-stream");
        request.putHeader("Cache-Control", "no-cache");

        SSEReadStream<BiTemporalEvent> stream = new SSEReadStream<>(request, json -> {
            try {
                return objectMapper.readValue(json.encode(), BiTemporalEvent.class);
            } catch (Exception e) {
                logger.warn("Failed to parse BiTemporalEvent from SSE: {}", e.getMessage());
                return null;
            }
        });

        stream.start();
        return stream;
    }

    @Override
    public ReadStream<JsonObject> streamMessages(String setupId, String queueName, StreamOptions options) {
        String path = String.format("/api/v1/queues/%s/%s/stream", setupId, queueName);

        HttpClient httpClient = vertx.createHttpClient(new HttpClientOptions()
            .setDefaultHost(host)
            .setDefaultPort(port)
            .setSsl(ssl));

        HttpClientRequest request = httpClient.request(HttpMethod.GET, port, host, path)
            .toCompletionStage().toCompletableFuture().join();
        request.putHeader("Accept", "text/event-stream");
        request.putHeader("Cache-Control", "no-cache");

        SSEReadStream<JsonObject> stream = new SSEReadStream<>(request, json -> json);

        stream.start();
        return stream;
    }

    // ========================================================================
    // Webhook Subscription Operations
    // ========================================================================

    @Override
    public Future<WebhookSubscriptionInfo> createWebhookSubscription(String setupId, String queueName, WebhookSubscriptionRequest request) {
        String path = String.format("/api/v1/setups/%s/queues/%s/webhook-subscriptions", setupId, queueName);
        return post(path, request)
            .map(response -> parseResponse(response, WebhookSubscriptionInfo.class));
    }

    @Override
    public Future<WebhookSubscriptionInfo> getWebhookSubscription(String subscriptionId) {
        String path = String.format("/api/v1/webhook-subscriptions/%s", subscriptionId);
        return get(path)
            .map(response -> parseResponse(response, WebhookSubscriptionInfo.class));
    }

    @Override
    public Future<Void> deleteWebhookSubscription(String subscriptionId) {
        String path = String.format("/api/v1/webhook-subscriptions/%s", subscriptionId);
        return delete(path)
            .mapEmpty();
    }

    // ========================================================================
    // Management API Operations
    // ========================================================================

    @Override
    public Future<JsonObject> getGlobalHealth() {
        return get("/api/v1/health")
            .map(HttpResponse::bodyAsJsonObject);
    }

    @Override
    public Future<SystemOverview> getSystemOverview() {
        return get("/api/v1/management/overview")
            .map(response -> parseResponse(response, SystemOverview.class));
    }

    @Override
    public Future<JsonObject> getMetrics() {
        return get("/api/v1/management/metrics")
            .map(HttpResponse::bodyAsJsonObject);
    }

    @Override
    public Future<List<QueueInfo>> getQueues() {
        return get("/api/v1/management/queues")
            .map(response -> {
                JsonArray array = response.bodyAsJsonArray();
                List<QueueInfo> queues = new java.util.ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    JsonObject obj = array.getJsonObject(i);
                    queues.add(new QueueInfo(
                        obj.getString("name"),
                        obj.getString("setupId"),
                        obj.getLong("messageCount", 0L),
                        obj.getInteger("consumerCount", 0),
                        obj.getDouble("messagesPerSecond", 0.0),
                        obj.getString("status", "ACTIVE")
                    ));
                }
                return queues;
            });
    }

    @Override
    public Future<List<EventStoreInfo>> getEventStores() {
        return get("/api/v1/management/event-stores")
            .map(response -> {
                JsonArray array = response.bodyAsJsonArray();
                List<EventStoreInfo> stores = new java.util.ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    JsonObject obj = array.getJsonObject(i);
                    stores.add(new EventStoreInfo(
                        obj.getString("name"),
                        obj.getString("setupId"),
                        obj.getLong("eventCount", 0L),
                        obj.getLong("correctionCount", 0L),
                        obj.getInteger("subscriberCount", 0),
                        obj.getString("status", "ACTIVE")
                    ));
                }
                return stores;
            });
    }

    @Override
    public Future<List<ConsumerGroupInfo>> getConsumerGroups() {
        return get("/api/v1/management/consumer-groups")
            .map(response -> {
                JsonArray array = response.bodyAsJsonArray();
                List<ConsumerGroupInfo> groups = new java.util.ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    JsonObject obj = array.getJsonObject(i);
                    groups.add(new ConsumerGroupInfo(
                        obj.getString("groupName", obj.getString("name")),
                        obj.getString("queueName"),
                        obj.getInteger("memberCount", 0),
                        obj.getLong("pendingMessages", 0L),
                        obj.getInstant("lastActivity", java.time.Instant.now())
                    ));
                }
                return groups;
            });
    }

    @Override
    public Future<List<JsonObject>> getMessages(String setupId, String queueName, int count) {
        return get("/api/v1/queues/" + setupId + "/" + queueName + "/messages?count=" + count)
            .map(response -> {
                JsonArray array = response.bodyAsJsonArray();
                List<JsonObject> messages = new java.util.ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    messages.add(array.getJsonObject(i));
                }
                return messages;
            });
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Override
    public void close() {
        if (webClient != null) {
            webClient.close();
            logger.info("PeeGeeQ client closed");
        }
    }

    // ========================================================================
    // HTTP Helper Methods
    // ========================================================================

    private Future<HttpResponse<Buffer>> get(String path) {
        return executeRequest(HttpMethod.GET, path, null);
    }

    private Future<HttpResponse<Buffer>> post(String path, Object body) {
        return executeRequest(HttpMethod.POST, path, body);
    }

    private Future<HttpResponse<Buffer>> delete(String path) {
        return executeRequest(HttpMethod.DELETE, path, null);
    }

    private Future<HttpResponse<Buffer>> executeRequest(HttpMethod method, String path, Object body) {
        HttpRequest<Buffer> request = webClient.request(method, path)
            .timeout(config.getTimeout().toMillis())
            .putHeader("Content-Type", "application/json")
            .putHeader("Accept", "application/json");

        Future<HttpResponse<Buffer>> responseFuture;
        if (body != null) {
            try {
                String json = objectMapper.writeValueAsString(body);
                responseFuture = request.sendBuffer(Buffer.buffer(json));
            } catch (JsonProcessingException e) {
                return Future.failedFuture(new PeeGeeQNetworkException(
                    "Failed to serialize request body", host, port, false, e));
            }
        } else {
            responseFuture = request.send();
        }

        return responseFuture
            .recover(this::handleNetworkError)
            .compose(this::handleResponse);
    }

    private Future<HttpResponse<Buffer>> handleResponse(HttpResponse<Buffer> response) {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            return Future.succeededFuture(response);
        }

        // Parse error response
        String errorMessage = "Unknown error";
        String errorCode = null;
        try {
            JsonObject errorJson = response.bodyAsJsonObject();
            errorMessage = errorJson.getString("message", errorJson.getString("error", "Unknown error"));
            errorCode = errorJson.getString("error");
        } catch (Exception ignored) {
            if (response.bodyAsString() != null) {
                errorMessage = response.bodyAsString();
            }
        }

        return Future.failedFuture(new PeeGeeQApiException(
            errorMessage, statusCode, errorCode, null));
    }

    private Future<HttpResponse<Buffer>> handleNetworkError(Throwable error) {
        boolean isTimeout = error.getMessage() != null &&
            error.getMessage().toLowerCase().contains("timeout");
        return Future.failedFuture(new PeeGeeQNetworkException(
            error.getMessage(), host, port, isTimeout, error));
    }

    // ========================================================================
    // JSON Parsing Helper Methods
    // ========================================================================

    private <T> T parseResponse(HttpResponse<Buffer> response, Class<T> type) {
        try {
            return objectMapper.readValue(response.bodyAsString(), type);
        } catch (JsonProcessingException e) {
            throw new PeeGeeQNetworkException(
                "Failed to parse response: " + e.getMessage(), host, port, false, e);
        }
    }

    private <T> List<T> parseListResponse(HttpResponse<Buffer> response, Class<T> type) {
        try {
            JsonArray array = response.bodyAsJsonArray();
            List<T> result = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                result.add(objectMapper.readValue(array.getJsonObject(i).encode(), type));
            }
            return result;
        } catch (Exception e) {
            throw new PeeGeeQNetworkException(
                "Failed to parse list response: " + e.getMessage(), host, port, false, e);
        }
    }

    private <T> T parseJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new PeeGeeQNetworkException(
                "Failed to parse JSON: " + e.getMessage(), host, port, false, e);
        }
    }
}
