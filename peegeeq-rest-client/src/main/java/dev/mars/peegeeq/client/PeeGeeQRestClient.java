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
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.api.deadletter.DeadLetterStatsInfo;
import dev.mars.peegeeq.api.health.HealthStatusInfo;
import dev.mars.peegeeq.api.health.OverallHealthInfo;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.client.config.ClientConfig;
import dev.mars.peegeeq.client.dto.*;
import dev.mars.peegeeq.client.exception.PeeGeeQApiException;
import dev.mars.peegeeq.client.exception.PeeGeeQNetworkException;
import dev.mars.peegeeq.client.sse.SSEReadStream;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Supplier;

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
 *     .onSuccess(result -> System.out.println("Created: " + result.setupId()))
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
    public Future<SetupResultInfo> createSetup(DatabaseSetupRequest request) {
        // Parsed into the client dto SetupResultInfo: the old Jackson parse into
        // dev.mars.peegeeq.api.setup.DatabaseSetupResult failed at instantiation on
        // every call — that class has no Jackson creator, and its QueueFactory/EventStore
        // map fields never appear in the {setupId, status, queueCount, eventStoreCount,
        // message} payload (fixed 2026-08-10, setups contract review).
        return post("/api/v1/setups", request)
            .map(response -> parseResponse(response, SetupResultInfo.class));
    }

    @Override
    public Future<List<String>> listSetups() {
        // The endpoint wraps its ids in an object: {count, setupIds: [...]}. The old
        // parseListResponse→bodyAsJsonArray() read threw on every call, and the payload
        // carries setup ID strings, not setup objects (fixed 2026-08-10).
        return get("/api/v1/setups")
            .map(response -> {
                JsonArray array = response.bodyAsJsonObject().getJsonArray("setupIds", new JsonArray());
                List<String> setupIds = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    setupIds.add(array.getString(i));
                }
                return setupIds;
            });
    }

    @Override
    public Future<SetupDetailsInfo> getSetup(String setupId) {
        // Parsed into the client dto SetupDetailsInfo matching the real {setupId, status,
        // host, port, databaseName, schema, queueFactories: [names], eventStores: [names]}
        // payload — the old parse into the non-deserializable
        // dev.mars.peegeeq.api.setup.DatabaseSetupResult failed on every call
        // (fixed 2026-08-10, same defect as createSetup).
        return get("/api/v1/setups/" + setupId)
            .map(response -> parseResponse(response, SetupDetailsInfo.class));
    }

    @Override
    public Future<Void> deleteSetup(String setupId) {
        return delete("/api/v1/setups/" + setupId)
            .mapEmpty();
    }

    @Override
    public Future<DatabaseSetupStatus> getSetupStatus(String setupId) {
        // The payload is the object {setupId, status} — the old whole-body Jackson
        // parse into the bare DatabaseSetupStatus enum failed on every call
        // (fixed 2026-08-10).
        return get("/api/v1/setups/" + setupId + "/status")
            .map(response -> DatabaseSetupStatus.valueOf(
                response.bodyAsJsonObject().getString("status")));
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
        // The endpoint expects a {messages: [...]} wrapper and answers with an object
        // carrying messageIds and failures arrays — the old bare-array post was rejected
        // with 400 before parseListResponse could even throw on the object payload
        // (fixed 2026-08-10, messages-stats contract review). The Map wrapper predates
        // the 2026-08-10 JsonObject body-encoding fix in executeRequestOnce and stays
        // correct under it, so it is left as-is.
        String path = String.format("/api/v1/queues/%s/%s/messages/batch", setupId, queueName);
        return post(path, Map.of("messages", messages))
            .compose(response -> {
                JsonObject json = response.bodyAsJsonObject();
                JsonArray failures = json.getJsonArray("failures", new JsonArray());
                if (!failures.isEmpty()) {
                    return Future.failedFuture(new PeeGeeQApiException(
                        "Batch send to queue '" + queueName + "' in setup '" + setupId
                            + "' reported " + failures.size() + " failed message(s): " + failures.encode(),
                        response.statusCode(), "BATCH_PARTIAL_FAILURE", path));
                }
                JsonArray messageIds = json.getJsonArray("messageIds");
                List<MessageSendResult> results = new ArrayList<>();
                for (int i = 0; i < messageIds.size(); i++) {
                    results.add(MessageSendResult.simple(messageIds.getString(i), queueName, setupId));
                }
                return Future.succeededFuture(results);
            });
    }

    @Override
    public Future<QueueStats> getQueueStats(String setupId, String queueName) {
        // Mapped by hand: the payload also carries setupId/implementationType/healthy/
        // successRatePercent/timestamp plus CONDITIONALLY-present percentile keys
        // (processingTimeP50Ms etc., absent until this backend instance has measured),
        // so the old strict Jackson parse into the dto rejected the first key the dto
        // lacks on every call (fixed 2026-08-10, messages-stats contract review).
        // Keys the dto has no field for are ignored.
        String path = String.format("/api/v1/queues/%s/%s/stats", setupId, queueName);
        return get(path)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                return new QueueStats(
                    json.getString("queueName"),
                    json.getLong("totalMessages"),
                    json.getLong("pendingMessages"),
                    json.getLong("processedMessages"),
                    json.getLong("inFlightMessages"),
                    json.getLong("deadLetteredMessages"),
                    json.getDouble("messagesPerSecond"),
                    json.getDouble("avgProcessingTimeMs"));
            });
    }

    @Override
    public Future<QueueDetailsInfo> getQueueDetails(String setupId, String queueName) {
        // Mapped by hand from the real payload keys (name/setup/status/messages/
        // consumers, createdAt as epoch millis) — the old strict Jackson parse into
        // the dto's own field names failed on every call. The dto fields that had no
        // source in this payload (pendingMessages/processedMessages/deadLetterMessages/
        // consumerIds) were dropped from the dto rather than default-filled
        // (fixed 2026-08-10, messages-stats contract review).
        String path = String.format("/api/v1/queues/%s/%s", setupId, queueName);
        return get(path)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                return new QueueDetailsInfo(
                    json.getString("name"),
                    json.getString("setup"),
                    json.getString("implementationType"),
                    !"error".equals(json.getString("status")),
                    json.getLong("messages"),
                    json.getInteger("consumers"),
                    Instant.ofEpochMilli(json.getLong("createdAt")));
            });
    }

    @Override
    public Future<List<String>> getQueueConsumers(String setupId, String queueName) {
        // The consumers array holds OBJECTS ({groupName, topic, status, ...}) — the old
        // getString(i) read threw ClassCastException on the first subscribed consumer
        // (fixed 2026-08-10, messages-stats contract review). The List<String> return
        // keeps the group names.
        String path = String.format("/api/v1/queues/%s/%s/consumers", setupId, queueName);
        return get(path)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                JsonArray consumers = json.getJsonArray("consumers");
                List<String> result = new ArrayList<>();
                for (int i = 0; i < consumers.size(); i++) {
                    result.add(consumers.getJsonObject(i).getString("groupName"));
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
        // Mapped by hand from the real 201 payload {message, groupName, setupId,
        // queueName, groupId, maxMembers, loadBalancingStrategy, sessionTimeout,
        // implementationType, subscriptionConfigured, timestamp} — the old strict
        // Jackson parse into the dto rejected the first key the dto lacks on every
        // call. The payload carries no member count and no lastActivity, so those
        // dto fields map to null rather than fabricated 0/now() (fixed 2026-08-10,
        // consumer-groups contract review). The JsonObject request body is repaired
        // by the 2026-08-10 encode() fix in executeRequestOnce.
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups", setupId, queueName);
        JsonObject body = new JsonObject().put("groupName", groupName);
        return post(path, body)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                return new ConsumerGroupInfo(
                    json.getString("groupName"),
                    json.getString("queueName"),
                    null,
                    null);
            });
    }

    @Override
    public Future<List<ConsumerGroupInfo>> listConsumerGroups(String setupId, String queueName) {
        // The payload wraps the array in a "groups" key ({message, setupId, queueName,
        // groupCount, groups: [...], timestamp}) and each item's lastActivity is epoch
        // millis — the old parseListResponse called bodyAsJsonArray() on the object
        // payload and threw on every call (fixed 2026-08-10, consumer-groups contract
        // review).
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups", setupId, queueName);
        return get(path)
            .map(response -> {
                JsonArray array = response.bodyAsJsonObject().getJsonArray("groups", new JsonArray());
                List<ConsumerGroupInfo> groups = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    JsonObject obj = array.getJsonObject(i);
                    groups.add(new ConsumerGroupInfo(
                        obj.getString("groupName"),
                        obj.getString("queueName"),
                        obj.getInteger("memberCount"),
                        instantFromEpochMillis(obj.getLong("lastActivity"))));
                }
                return groups;
            });
    }

    @Override
    public Future<ConsumerGroupInfo> getConsumerGroup(String setupId, String queueName, String groupName) {
        // Mapped by hand from the real payload {message, groupName, groupId, setupId,
        // queueName, topic, memberCount, consumerIds, isActive, maxMembers,
        // loadBalancingStrategy, sessionTimeout, createdAt, lastActivity (epoch
        // millis), members, timestamp} — the old strict Jackson parse into the dto
        // rejected the first key the dto lacks on every call (fixed 2026-08-10,
        // consumer-groups contract review).
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups/%s", setupId, queueName, groupName);
        return get(path)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                return new ConsumerGroupInfo(
                    json.getString("groupName"),
                    json.getString("queueName"),
                    json.getInteger("memberCount"),
                    instantFromEpochMillis(json.getLong("lastActivity")));
            });
    }

    @Override
    public Future<Void> deleteConsumerGroup(String setupId, String queueName, String groupName) {
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups/%s", setupId, queueName, groupName);
        return delete(path)
            .mapEmpty();
    }

    @Override
    public Future<ConsumerGroupMemberInfo> joinConsumerGroup(String setupId, String queueName, String groupName, String memberName) {
        // Mapped by hand from the real 201 payload {message, groupName, consumerId,
        // memberName, topic, isActive, joinedAt (ISO string or null), memberCount,
        // timestamp} — the server's member id key is "consumerId" while the dto field
        // is memberId, so the old strict Jackson parse failed on every call; the
        // message/timestamp keys are ignored (fixed 2026-08-10, consumer-groups
        // contract review). The JsonObject request body is repaired by the 2026-08-10
        // encode() fix in executeRequestOnce.
        String path = String.format("/api/v1/queues/%s/%s/consumer-groups/%s/members", setupId, queueName, groupName);
        JsonObject body = new JsonObject();
        if (memberName != null) {
            body.put("memberName", memberName);
        }
        return post(path, body)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                String joinedAt = json.getString("joinedAt");
                return new ConsumerGroupMemberInfo(
                    json.getString("consumerId"),
                    json.getString("memberName"),
                    json.getString("groupName"),
                    json.getString("topic"),
                    Boolean.TRUE.equals(json.getBoolean("isActive")),
                    joinedAt != null ? Instant.parse(joinedAt) : null,
                    json.getInteger("memberCount"));
            });
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
            .map(this::parseSubscriptionOptionsInfo);
    }

    @Override
    public Future<SubscriptionOptionsInfo> getSubscriptionOptions(String setupId, String queueName, String groupName) {
        String path = String.format("/api/v1/consumer-groups/%s/%s/%s/subscription", setupId, queueName, groupName);
        return get(path)
            .map(this::parseSubscriptionOptionsInfo);
    }

    /**
     * Maps the subscription-options payloads both subscription endpoints emit:
     * {setupId, queueName, groupName, subscriptionOptions: {startPosition,
     * heartbeatIntervalSeconds, heartbeatTimeoutSeconds[, startFromMessageId]
     * [, startFromTimestamp]}, ...} — the update response adds message/timestamp and
     * no status, the get response adds status/lastHeartbeat/createdAt. The old strict
     * Jackson parse into the dto's flat imagined shape (maxConcurrency/
     * visibilityTimeoutMs/...) failed on every call, and the request dto serialized
     * keys the server never reads; both dtos were reshaped to the server contract
     * (fixed 2026-08-10, consumer-groups contract review).
     */
    private SubscriptionOptionsInfo parseSubscriptionOptionsInfo(HttpResponse<Buffer> response) {
        JsonObject json = response.bodyAsJsonObject();
        JsonObject options = json.getJsonObject("subscriptionOptions");
        String startFromTimestamp = options.getString("startFromTimestamp");
        return new SubscriptionOptionsInfo(
            json.getString("setupId"),
            json.getString("queueName"),
            json.getString("groupName"),
            json.getString("status"),
            options.getString("startPosition"),
            options.getInteger("heartbeatIntervalSeconds"),
            options.getInteger("heartbeatTimeoutSeconds"),
            options.getLong("startFromMessageId"),
            startFromTimestamp != null ? Instant.parse(startFromTimestamp) : null);
    }

    /**
     * Maps the consumer-group handler's epoch-millis timestamps. The handler writes
     * 0L when the group has no stats yet — that encodes absence, not the epoch, so
     * null/0 map to null (consumer-groups contract review, 2026-08-10).
     */
    private static Instant instantFromEpochMillis(Long epochMillis) {
        return (epochMillis == null || epochMillis == 0L) ? null : Instant.ofEpochMilli(epochMillis);
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
    public Future<List<DeadLetterMessageInfo>> listDeadLetters(String setupId, int page, int pageSize) {
        // The endpoint reads limit/offset query params and answers with a BARE ARRAY
        // of message objects — the old call sent page/pageSize keys the server never
        // reads (its limit=50/offset=0 defaults silently applied) and strict-parsed
        // the array into the imagined DeadLetterListResponse wrapper {messages,
        // total, page, pageSize}, failing every call; the server also never emits a
        // total count, so the wrapper could not be populated honestly and was
        // deleted in favour of the list itself (fixed 2026-08-10, deadletter-webhooks
        // contract review).
        String path = String.format("/api/v1/setups/%s/deadletter/messages?limit=%d&offset=%d",
            setupId, pageSize, page * pageSize);
        return get(path)
            .map(response -> {
                JsonArray array = response.bodyAsJsonArray();
                List<DeadLetterMessageInfo> messages = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    messages.add(parseDeadLetterMessage(array.getJsonObject(i)));
                }
                return messages;
            });
    }

    /**
     * Maps one element of the deadletter messages payload: {id, originalTable,
     * originalId, topic, payload, originalCreatedAt, failedAt, failureReason,
     * retryCount[, headers][, correlationId][, messageGroup]} — headers,
     * correlationId and messageGroup are emitted only when present, so absence
     * maps to null, never defaults; the timestamps are ISO-8601 Instant strings
     * (deadletter-webhooks contract review, 2026-08-10).
     */
    private static DeadLetterMessageInfo parseDeadLetterMessage(JsonObject json) {
        Map<String, String> headers = null;
        JsonObject headersJson = json.getJsonObject("headers");
        if (headersJson != null) {
            Map<String, String> parsed = new LinkedHashMap<>();
            for (String key : headersJson.fieldNames()) {
                parsed.put(key, headersJson.getString(key));
            }
            headers = parsed;
        }
        return new DeadLetterMessageInfo(
            json.getLong("id"),
            json.getString("originalTable"),
            json.getLong("originalId"),
            json.getString("topic"),
            json.getString("payload"),
            Instant.parse(json.getString("originalCreatedAt")),
            Instant.parse(json.getString("failedAt")),
            json.getString("failureReason"),
            json.getInteger("retryCount"),
            headers,
            json.getString("correlationId"),
            json.getString("messageGroup"));
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
        // The endpoint reads retentionDays as a QUERY param and answers
        // {success, messagesDeleted, retentionDays} — the old call posted
        // {olderThanDays} in a body the server never reads (the server-side 30-day
        // default silently applied) and read a "deletedCount" key the payload never
        // carries, so the 0L default masked the defect on every call (fixed
        // 2026-08-10, deadletter-webhooks contract review). Absence of
        // messagesDeleted fails the future rather than fabricating 0.
        String path = String.format("/api/v1/setups/%s/deadletter/cleanup?retentionDays=%d", setupId, olderThanDays);
        return post(path, null)
            .compose(response -> {
                JsonObject json = response.bodyAsJsonObject();
                Long messagesDeleted = json.getLong("messagesDeleted");
                if (messagesDeleted == null) {
                    return Future.failedFuture(new PeeGeeQApiException(
                        "Cleanup response is missing messagesDeleted: " + json.encode(),
                        response.statusCode(), "MISSING_RESPONSE_FIELD", path));
                }
                return Future.succeededFuture(messagesDeleted);
            });
    }

    // ========================================================================
    // Subscription Operations
    // ========================================================================

    @Override
    public Future<List<SubscriptionInfo>> listSubscriptions(String setupId, String topic) {
        // The endpoint answers with a BARE ARRAY of subscription objects — the old
        // read called bodyAsJsonObject() and unwrapped a "subscriptions" key the
        // server never emits, so every call failed decoding the array as an object
        // (fixed 2026-08-10, deadletter-webhooks contract review).
        String path = String.format("/api/v1/setups/%s/subscriptions/%s", setupId, topic);
        return get(path)
            .map(response -> {
                JsonArray subscriptions = response.bodyAsJsonArray();
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
    public Future<EventAppendResult> appendEvent(String setupId, String storeName, AppendEventRequest request) {
        // Mapped by hand from the real 201 payload {message, eventStoreName, setupId,
        // eventId, eventType, version, transactionTime (ISO string)} — the old parse
        // fed the payload to Jackson targeting the BiTemporalEvent INTERFACE, which
        // cannot be instantiated, so every call failed after the server had already
        // stored the event (fixed 2026-08-10, event-stores contract review). The
        // request dto serializes its headers as the server's 'metadata' key and its
        // validTime as 'validFrom' — see AppendEventRequest.
        String path = String.format("/api/v1/eventstores/%s/%s/events", setupId, storeName);
        return post(path, request)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                return new EventAppendResult(
                    json.getString("eventStoreName"),
                    json.getString("setupId"),
                    json.getString("eventId"),
                    json.getString("eventType"),
                    json.getLong("version"),
                    instantFromWire(json.getValue("transactionTime")));
            });
    }

    @Override
    public Future<EventQueryResult> queryEvents(String setupId, String storeName, EventQuery query) {
        // Request side: the old call transmitted only eventType/aggregateId/limit/
        // offset and silently dropped every other populated EventQuery filter, so a
        // query carrying e.g. a correlationId filter returned unfiltered results. The
        // handler also reads correlationId, causationId, validTimeFrom/To,
        // transactionTimeFrom/To, sortOrder, includeCorrections, minVersion/
        // maxVersion and afterTransactionTime/afterEventId — all transmitted now;
        // headerFilters has no query param on the handler, so a query carrying one
        // fails instead of silently returning unfiltered events (fixed 2026-08-10,
        // event-stores contract review). Response side: the payload is {message,
        // eventStoreName, setupId, eventCount, limit, offset, totalCount, hasMore,
        // filters, events: [...], timestamp} — the old strict Jackson parse of the
        // whole wrapper into the dto failed on every call; totalCount maps to total
        // and the events array maps per-element into EventInfo.
        if (!query.getHeaderFilters().isEmpty()) {
            return Future.failedFuture(new IllegalArgumentException(
                "headerFilters are not supported by the event-store query endpoint"));
        }
        String path = String.format("/api/v1/eventstores/%s/%s/events", setupId, storeName);
        Map<String, String> queryParams = new LinkedHashMap<>();
        query.getEventType().ifPresent(eventType -> queryParams.put("eventType", eventType));
        query.getAggregateId().ifPresent(aggregateId -> queryParams.put("aggregateId", aggregateId));
        query.getCorrelationId().ifPresent(correlationId -> queryParams.put("correlationId", correlationId));
        query.getCausationId().ifPresent(causationId -> queryParams.put("causationId", causationId));
        query.getValidTimeRange().ifPresent(range -> {
            if (range.getStart() != null) {
                queryParams.put("validTimeFrom", range.getStart().toString());
            }
            if (range.getEnd() != null) {
                queryParams.put("validTimeTo", range.getEnd().toString());
            }
        });
        query.getTransactionTimeRange().ifPresent(range -> {
            if (range.getStart() != null) {
                queryParams.put("transactionTimeFrom", range.getStart().toString());
            }
            if (range.getEnd() != null) {
                queryParams.put("transactionTimeTo", range.getEnd().toString());
            }
        });
        queryParams.put("sortOrder", query.getSortOrder().name());
        queryParams.put("includeCorrections", Boolean.toString(query.isIncludeCorrections()));
        query.getMinVersion().ifPresent(minVersion -> queryParams.put("minVersion", Long.toString(minVersion)));
        query.getMaxVersion().ifPresent(maxVersion -> queryParams.put("maxVersion", Long.toString(maxVersion)));
        if (query.getLimit() > 0) {
            queryParams.put("limit", Integer.toString(query.getLimit()));
        }
        if (query.getOffset() > 0) {
            queryParams.put("offset", Integer.toString(query.getOffset()));
        }
        query.getAfterTransactionTime().ifPresent(after -> queryParams.put("afterTransactionTime", after.toString()));
        query.getAfterEventId().ifPresent(afterEventId -> queryParams.put("afterEventId", afterEventId));
        return get(withQueryParams(path, queryParams))
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                JsonArray array = json.getJsonArray("events");
                List<EventInfo> events = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    events.add(parseEventInfo(array.getJsonObject(i)));
                }
                return new EventQueryResult(events, json.getLong("totalCount"), json.getBoolean("hasMore"));
            });
    }

    @Override
    public Future<EventInfo> getEvent(String setupId, String storeName, String eventId) {
        // The payload wraps the event: {message, eventStoreName, setupId, eventId,
        // event: {...}, timestamp} — the old parse fed the whole wrapper to Jackson
        // targeting the BiTemporalEvent INTERFACE and failed on every call (fixed
        // 2026-08-10, event-stores contract review).
        String path = String.format("/api/v1/eventstores/%s/%s/events/%s", setupId, storeName, eventId);
        return get(path)
            .map(response -> parseEventInfo(response.bodyAsJsonObject().getJsonObject("event")));
    }

    @Override
    public Future<List<EventInfo>> getEventVersions(String setupId, String storeName, String eventId) {
        // The payload wraps the array in a "versions" key ({message, eventStoreName,
        // setupId, eventId, versions: [...], timestamp}) — the old parseListResponse
        // called bodyAsJsonArray() on the object payload and threw on every call, and
        // its per-element target was the non-instantiable BiTemporalEvent interface
        // (fixed 2026-08-10, event-stores contract review).
        String path = String.format("/api/v1/eventstores/%s/%s/events/%s/versions", setupId, storeName, eventId);
        return get(path)
            .map(response -> {
                JsonArray array = response.bodyAsJsonObject().getJsonArray("versions");
                List<EventInfo> versions = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    versions.add(parseEventInfo(array.getJsonObject(i)));
                }
                return versions;
            });
    }

    @Override
    public Future<EventCorrectionResult> appendCorrection(String setupId, String storeName, String eventId,
                                                          CorrectionRequest request) {
        // Mapped by hand from the real 201 payload {message, eventStoreName, setupId,
        // originalEventId, correctionEventId, version, transactionTime (ISO string),
        // correctionReason} — the old parse targeted the BiTemporalEvent INTERFACE
        // and failed on every call (fixed 2026-08-10, event-stores contract review).
        // The request dto serializes correctedPayload as the server's 'eventData' key
        // and validTime as 'validFrom' — the server's strict request mapper rejects
        // unknown keys, so the old field names were refused with 400 before the
        // response parse could even run (see CorrectionRequest).
        String path = String.format("/api/v1/eventstores/%s/%s/events/%s/corrections", setupId, storeName, eventId);
        return post(path, request)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                return new EventCorrectionResult(
                    json.getString("eventStoreName"),
                    json.getString("setupId"),
                    json.getString("originalEventId"),
                    json.getString("correctionEventId"),
                    json.getLong("version"),
                    instantFromWire(json.getValue("transactionTime")),
                    json.getString("correctionReason"));
            });
    }

    @Override
    public Future<EventInfo> getEventAsOf(String setupId, String storeName, String eventId, Instant asOfTime) {
        // The endpoint requires a 'transactionTime' query param — the old call sent
        // 'asOf', a key the handler never reads, so every call was rejected with 400
        // "transactionTime parameter is required"; the payload wraps the event in an
        // "event" key, and the old parse targeted the BiTemporalEvent INTERFACE
        // (fixed 2026-08-10, event-stores contract review).
        String path = String.format("/api/v1/eventstores/%s/%s/events/%s/at", setupId, storeName, eventId);
        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("transactionTime", asOfTime.toString());
        return get(withQueryParams(path, queryParams))
            .map(response -> parseEventInfo(response.bodyAsJsonObject().getJsonObject("event")));
    }

    @Override
    public Future<EventStoreStats> getEventStoreStats(String setupId, String storeName) {
        // Mapped by hand from the real payload {message, eventStoreName, setupId,
        // stats: {eventStoreName, totalEvents, totalCorrections, eventCountsByType},
        // timestamp} — the old strict Jackson parse of the whole wrapper into the dto
        // failed on every call, and the dto's uniqueEventIds/oldestEventTime/
        // newestEventTime/eventsPerSecond fields had no source in any payload and
        // were deleted rather than default-filled (fixed 2026-08-10, event-stores
        // contract review). stats.eventStoreName maps to storeName.
        String path = String.format("/api/v1/eventstores/%s/%s/stats", setupId, storeName);
        return get(path)
            .map(response -> {
                JsonObject json = response.bodyAsJsonObject();
                JsonObject stats = json.getJsonObject("stats");
                Map<String, Long> eventCountsByType = null;
                JsonObject countsJson = stats.getJsonObject("eventCountsByType");
                if (countsJson != null) {
                    Map<String, Long> counts = new LinkedHashMap<>();
                    for (String key : countsJson.fieldNames()) {
                        counts.put(key, countsJson.getLong(key));
                    }
                    eventCountsByType = counts;
                }
                return new EventStoreStats(
                    stats.getString("eventStoreName"),
                    json.getString("setupId"),
                    stats.getLong("totalEvents"),
                    stats.getLong("totalCorrections"),
                    eventCountsByType);
            });
    }

    /**
     * Maps one event object of the query/get/versions/as-of payloads: {eventId,
     * eventType, eventData, validFrom, validTime, validTo, transactionTime,
     * correlationId, causationId, aggregateId, version, metadata}. The duplicate
     * validTime key mirrors validFrom (a read-only alias getter on the server
     * dto) and is ignored. Absent keys map to null, never defaults
     * (event-stores contract review, 2026-08-10).
     */
    private static EventInfo parseEventInfo(JsonObject json) {
        return new EventInfo(
            json.getString("eventId"),
            json.getString("eventType"),
            json.getValue("eventData"),
            instantFromWire(json.getValue("validFrom")),
            instantFromWire(json.getValue("validTo")),
            instantFromWire(json.getValue("transactionTime")),
            json.getString("correlationId"),
            json.getString("causationId"),
            json.getString("aggregateId"),
            json.getLong("version"),
            metadataFromWire(json.getJsonObject("metadata")));
    }

    /**
     * Parses an event-store timestamp from its wire form. The event objects
     * inside the query/get/versions/as-of payloads carry Instants as decimal
     * epoch-second NUMBERS (the REST server's Jackson timestamp default), while
     * the storeEvent/correction responses and SSE frames emit ISO-8601 strings
     * via toString(); absence maps to null (event-stores contract review,
     * 2026-08-10).
     */
    private static Instant instantFromWire(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return Instant.parse(text);
        }
        if (value instanceof Number number) {
            BigDecimal seconds = new BigDecimal(number.toString());
            long secs = seconds.longValue();
            long nanos = seconds.subtract(BigDecimal.valueOf(secs)).movePointRight(9).longValue();
            return Instant.ofEpochSecond(secs, nanos);
        }
        throw new IllegalArgumentException("Unsupported timestamp wire value: " + value);
    }

    /**
     * Maps an event's metadata object (the server's headers map). The
     * query-shape payloads always emit the key (an empty object when the event
     * has no headers); the SSE frame omits it entirely when empty — absence
     * maps to null, never defaults (event-stores contract review, 2026-08-10).
     */
    private static Map<String, String> metadataFromWire(JsonObject metadataJson) {
        if (metadataJson == null) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String key : metadataJson.fieldNames()) {
            metadata.put(key, metadataJson.getString(key));
        }
        return metadata;
    }

    @Override
    public Future<EventStoreDeletionResult> deleteEventStore(String setupId, String storeName) {
        if (setupId == null || setupId.trim().isEmpty()) {
            return Future.failedFuture(new IllegalArgumentException("setupId cannot be null or empty"));
        }
        if (storeName == null || storeName.trim().isEmpty()) {
            return Future.failedFuture(new IllegalArgumentException("storeName cannot be null or empty"));
        }

        String path = String.format("/api/v1/eventstores/%s/%s", setupId, storeName);
        return delete(path)
            .map(response -> parseResponse(response, EventStoreDeletionResult.class));
    }

    // ========================================================================
    // Streaming Operations
    // ========================================================================

    @Override
    public ReadStream<EventInfo> streamEvents(String setupId, String storeName, StreamOptions options) {
        // The SSE stream interleaves control frames (type: connection/subscribed/
        // heartbeat/error) with per-event frames (type: event), and the event-frame
        // keys DIFFER from the query endpoints' event shape: {eventId, eventType,
        // aggregateId, payload, validTime (ISO string), transactionTime (ISO
        // string), version, correlationId[, headers]} — no validTo, no causationId.
        // The old parse fed EVERY frame to Jackson targeting the BiTemporalEvent
        // INTERFACE, which cannot be instantiated, so no frame ever parsed and each
        // one logged a warn (fixed 2026-08-10, event-stores contract review).
        // Control frames map to null, which SSEReadStream skips without emitting.
        String path = String.format("/api/v1/eventstores/%s/%s/events/stream", setupId, storeName);

        Map<String, String> queryParams = new LinkedHashMap<>();
        if (options != null) {
            if (options.getEventType() != null) {
                queryParams.put("eventType", options.getEventType());
            }
            if (options.getAggregateId() != null) {
                queryParams.put("aggregateId", options.getAggregateId());
            }
        }
        path = withQueryParams(path, queryParams);

        HttpClient httpClient = vertx.createHttpClient(new HttpClientOptions()
            .setDefaultHost(host)
            .setDefaultPort(port)
            .setSsl(ssl));

        Future<HttpClientRequest> requestFuture = httpClient.request(HttpMethod.GET, port, host, path)
            .onSuccess(request -> {
                request.putHeader("Accept", "text/event-stream");
                request.putHeader("Cache-Control", "no-cache");
            });

        SSEReadStream<EventInfo> stream = new SSEReadStream<>(requestFuture, json -> {
            if (!"event".equals(json.getString("type"))) {
                return null;
            }
            return new EventInfo(
                json.getString("eventId"),
                json.getString("eventType"),
                json.getValue("payload"),
                instantFromWire(json.getValue("validTime")),
                null,
                instantFromWire(json.getValue("transactionTime")),
                json.getString("correlationId"),
                null,
                json.getString("aggregateId"),
                json.getLong("version"),
                metadataFromWire(json.getJsonObject("headers")));
        }, httpClient::close);

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

        Future<HttpClientRequest> requestFuture = httpClient.request(HttpMethod.GET, port, host, path)
            .onSuccess(request -> {
                request.putHeader("Accept", "text/event-stream");
                request.putHeader("Cache-Control", "no-cache");
            });

        SSEReadStream<JsonObject> stream = new SSEReadStream<>(requestFuture, json -> json, httpClient::close);

        stream.start();
        return stream;
    }

    // ========================================================================
    // Webhook Subscription Operations
    // ========================================================================

    @Override
    public Future<WebhookSubscriptionInfo> createWebhookSubscription(String setupId, String queueName, WebhookSubscriptionRequest request) {
        // Mapped by hand from the real 201 payload {subscriptionId, setupId,
        // queueName, webhookUrl, status, createdAt} — the old strict Jackson parse
        // into the dto's imagined shape happened to accept the payload and silently
        // default-filled the imagined fields (maxRetries/retryDelayMs/
        // messagesDelivered/messagesFailed as 0); the server reads only webhookUrl/
        // headers/filters from the request body, so the request dto's secret/
        // maxRetries/retryDelayMs/contentType keys were serialized and never read.
        // Both dtos were reshaped to the server contract (fixed 2026-08-10,
        // deadletter-webhooks contract review).
        String path = String.format("/api/v1/setups/%s/queues/%s/webhook-subscriptions", setupId, queueName);
        return post(path, request)
            .map(this::parseWebhookSubscriptionInfo);
    }

    @Override
    public Future<WebhookSubscriptionInfo> getWebhookSubscription(String subscriptionId) {
        // Mapped by hand from the real payload {subscriptionId, setupId, queueName,
        // webhookUrl, status, createdAt, consecutiveFailures[, lastDeliveryAttempt]
        // [, lastSuccessfulDelivery]} — the old strict Jackson parse rejected
        // consecutiveFailures, a key the dto lacked, on every call (fixed
        // 2026-08-10, deadletter-webhooks contract review).
        String path = String.format("/api/v1/webhook-subscriptions/%s", subscriptionId);
        return get(path)
            .map(this::parseWebhookSubscriptionInfo);
    }

    /**
     * Maps both webhook-subscription payloads. The create response carries
     * {subscriptionId, setupId, queueName, webhookUrl, status, createdAt}; the get
     * response adds consecutiveFailures and, once a delivery has been attempted,
     * lastDeliveryAttempt/lastSuccessfulDelivery. Timestamps are ISO-8601 Instant
     * strings. Absent keys map to null, never defaults (deadletter-webhooks
     * contract review, 2026-08-10).
     */
    private WebhookSubscriptionInfo parseWebhookSubscriptionInfo(HttpResponse<Buffer> response) {
        JsonObject json = response.bodyAsJsonObject();
        String createdAt = json.getString("createdAt");
        String lastDeliveryAttempt = json.getString("lastDeliveryAttempt");
        String lastSuccessfulDelivery = json.getString("lastSuccessfulDelivery");
        return new WebhookSubscriptionInfo(
            json.getString("subscriptionId"),
            json.getString("setupId"),
            json.getString("queueName"),
            json.getString("webhookUrl"),
            json.getString("status"),
            createdAt != null ? Instant.parse(createdAt) : null,
            json.getInteger("consecutiveFailures"),
            lastDeliveryAttempt != null ? Instant.parse(lastDeliveryAttempt) : null,
            lastSuccessfulDelivery != null ? Instant.parse(lastSuccessfulDelivery) : null);
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
        // Mapped by hand from the systemStats block: the whole-body Jackson parse this
        // method used failed on every call — the payload is {setups, systemStats,
        // recentActivity, timestamp}, and the strict mapper rejected the first unknown
        // property (fixed 2026-08-09, metrics-stack review; the module had zero tests).
        return get("/api/v1/management/overview")
            .map(response -> {
                JsonObject stats = response.bodyAsJsonObject().getJsonObject("systemStats", new JsonObject());
                return new SystemOverview(
                    stats.getInteger("totalSetups", 0),
                    stats.getInteger("totalQueues", 0),
                    stats.getInteger("totalConsumerGroups", 0),
                    stats.getInteger("totalEventStores", 0),
                    stats.getLong("totalMessages", 0L),
                    stats.getInteger("activeConnections", 0),
                    stats.getString("uptime", "")
                );
            });
    }

    @Override
    public Future<JsonObject> getMetrics() {
        return get("/api/v1/management/metrics")
            .map(HttpResponse::bodyAsJsonObject);
    }

    @Override
    public Future<List<QueueInfo>> getQueues() {
        // The endpoint wraps its array: {message, queueCount, queues: [...], timestamp}.
        // The old bodyAsJsonArray() read failed on every call (fixed 2026-08-09).
        return get("/api/v1/management/queues")
            .map(response -> {
                JsonArray array = response.bodyAsJsonObject().getJsonArray("queues", new JsonArray());
                List<QueueInfo> queues = new java.util.ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    JsonObject obj = array.getJsonObject(i);
                    queues.add(new QueueInfo(
                        obj.getString("name"),
                        obj.getString("setupId"),
                        obj.getLong("messageCount", 0L),
                        obj.getInteger("consumerCount", 0),
                        // The flat field is "messageRate" — the endpoint has never
                        // emitted a flat "messagesPerSecond", so the old read always
                        // produced the 0.0 default (fixed 2026-08-09).
                        obj.getDouble("messageRate", 0.0),
                        obj.getString("status", "ACTIVE")
                    ));
                }
                return queues;
            });
    }

    @Override
    public Future<List<EventStoreInfo>> getEventStores() {
        // Wrapped payload: {message, eventStoreCount, eventStores: [...], timestamp}
        // (fixed 2026-08-09, same defect as getQueues).
        return get("/api/v1/management/event-stores")
            .map(response -> {
                JsonArray array = response.bodyAsJsonObject().getJsonArray("eventStores", new JsonArray());
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
        // Wrapped payload: {message, groupCount, consumerGroups: [...], timestamp}
        // (fixed 2026-08-09, same defect as getQueues). The item keys are {name,
        // setup, queueName, implementationType, members, status, partition, lag,
        // subscribedAt} — the old mapping read "memberCount"/"pendingMessages"/
        // "lastActivity", keys this payload never carries, so every row got
        // fabricated 0/0/now() defaults (fixed 2026-08-10, consumer-groups contract
        // review; pendingMessages had no source in any payload and was deleted from
        // the dto, and lastActivity has no source here so it maps to null).
        return get("/api/v1/management/consumer-groups")
            .map(response -> {
                JsonArray array = response.bodyAsJsonObject().getJsonArray("consumerGroups", new JsonArray());
                List<ConsumerGroupInfo> groups = new java.util.ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    JsonObject obj = array.getJsonObject(i);
                    groups.add(new ConsumerGroupInfo(
                        obj.getString("name"),
                        obj.getString("queueName"),
                        obj.getInteger("members"),
                        null
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
        return executeWithRetry(() -> executeRequestOnce(method, path, body), 0);
    }

    private Future<HttpResponse<Buffer>> executeRequestOnce(HttpMethod method, String path, Object body) {
        HttpRequest<Buffer> request = webClient.request(method, path)
            .timeout(config.getTimeout().toMillis())
            .putHeader("Content-Type", "application/json")
            .putHeader("Accept", "application/json");

        Future<HttpResponse<Buffer>> responseFuture;
        if (body instanceof JsonObject jsonObjectBody) {
            // Vert.x JsonObject/JsonArray bodies must go through their own encode():
            // the plain Jackson ObjectMapper serializes them through their bean
            // getters as {"map": {...}, "empty": false} / {"list": [...], "empty":
            // false}, so every JsonObject request body this client ever sent reached
            // the server in that wrapped form and the server-side key reads returned
            // null (cross-cutting request-body defect, found via Phase B probe;
            // fixed 2026-08-10, consumer-groups contract review). Other body types
            // keep the Jackson path.
            responseFuture = request.sendBuffer(Buffer.buffer(jsonObjectBody.encode()));
        } else if (body instanceof JsonArray jsonArrayBody) {
            responseFuture = request.sendBuffer(Buffer.buffer(jsonArrayBody.encode()));
        } else if (body != null) {
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
            .transform(ar -> {
                if (ar.failed()) {
                    return handleNetworkError(ar.cause());
                }
                return Future.succeededFuture(ar.result());
            })
            .compose(response -> handleResponse(response, path));
    }

    private Future<HttpResponse<Buffer>> executeWithRetry(Supplier<Future<HttpResponse<Buffer>>> attemptSupplier,
                                                          int attempt) {
        return attemptSupplier.get().transform(ar -> {
            if (ar.succeeded()) {
                return Future.succeededFuture(ar.result());
            }
            Throwable error = ar.cause();
            if (!isRetryable(error) || attempt >= config.getMaxRetries()) {
                return Future.failedFuture(error);
            }

            Promise<HttpResponse<Buffer>> retryPromise = Promise.promise();
            long delayMillis = Math.max(0L, config.getRetryDelay().toMillis());
            vertx.setTimer(delayMillis, timerId -> executeWithRetry(attemptSupplier, attempt + 1)
                .onComplete(retryPromise));
            return retryPromise.future();
        });
    }

    private boolean isRetryable(Throwable error) {
        if (error instanceof PeeGeeQNetworkException) {
            return true;
        }
        if (error instanceof PeeGeeQApiException apiException) {
            return apiException.isServerError() || apiException.getStatusCode() == 429;
        }
        return false;
    }

    private Future<HttpResponse<Buffer>> handleResponse(HttpResponse<Buffer> response, String path) {
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
            errorMessage, statusCode, errorCode, path));
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

    private String withQueryParams(String path, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return path;
        }

        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            joiner.add(urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()));
        }

        String queryString = joiner.toString();
        return queryString.isEmpty() ? path : path + "?" + queryString;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
