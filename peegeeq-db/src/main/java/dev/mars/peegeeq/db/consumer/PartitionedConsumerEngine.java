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
package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encapsulates the partitioned consumption loop for OFFSET_WATERMARK topics.
 *
 * <p>Coordinates partition assignment, per-partition fetching, message dispatch,
 * offset commits, and watermark sweeping. Used by consumer-group implementations
 * (native and outbox) when the topic's completion tracking mode is OFFSET_WATERMARK.</p>
 *
 * <p>Lifecycle: {@link #start(MessageHandler)} → fetching loop → {@link #stop()} → {@link #close()}</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
public class PartitionedConsumerEngine<T> {

    private static final Logger logger = LoggerFactory.getLogger(PartitionedConsumerEngine.class);
    private static final long DEFAULT_FETCH_INTERVAL_MS = 1000L;
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final Vertx vertx;
    private final PgConnectionManager connectionManager;
    private final String serviceId;
    private final String topic;
    private final String groupName;
    private final String instanceId;
    private final Class<T> payloadType;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final PartitionAssignmentService assignmentService;
    private final PartitionedFetcher fetcher;
    private final PartitionedOffsetManager offsetManager;
    private final WatermarkCalculator watermarkCalculator;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Current partition assignments: partitionKey → generation */
    private final ConcurrentHashMap<String, Integer> assignedPartitions = new ConcurrentHashMap<>();

    /** Per-partition guard: prevents overlapping fetch+process+commit cycles */
    private final ConcurrentHashMap<String, AtomicBoolean> fetchInProgress = new ConcurrentHashMap<>();

    private volatile WatermarkJob watermarkJob;
    private volatile long fetchTimerId = -1;
    private volatile MessageHandler<T> messageHandler;

    public PartitionedConsumerEngine(Vertx vertx,
                              PgConnectionManager connectionManager,
                              String serviceId,
                              String topic,
                              String groupName,
                              String instanceId,
                              Class<T> payloadType,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.groupName = Objects.requireNonNull(groupName, "groupName");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.payloadType = Objects.requireNonNull(payloadType, "payloadType");
        this.objectMapper = objectMapper;

        this.assignmentService = new PartitionAssignmentService(connectionManager, serviceId);
        this.fetcher = new PartitionedFetcher(connectionManager, serviceId);
        this.offsetManager = new PartitionedOffsetManager(connectionManager, serviceId);
        this.watermarkCalculator = new WatermarkCalculator(connectionManager, serviceId);

        logger.info("Created PartitionedConsumerEngine: topic={}, group={}, instance={}",
                topic, groupName, instanceId);
    }

    /**
     * Starts the partitioned consumption engine: joins the group, initializes
     * offsets, starts the fetch loop and watermark job.
     *
     * @param handler the message handler to dispatch messages to
     * @return future that completes when the join and initial setup is done
     */
    public Future<Void> start(MessageHandler<T> handler) {
        Objects.requireNonNull(handler, "handler");
        if (closed.get()) {
            return Future.failedFuture(new IllegalStateException("Engine is closed"));
        }
        if (!running.compareAndSet(false, true)) {
            return Future.failedFuture(new IllegalStateException("Engine is already running"));
        }

        this.messageHandler = handler;

        return assignmentService.joinGroup(topic, groupName, instanceId)
                .compose(assignments -> {
                    logger.info("Joined partitioned group: topic={}, group={}, instance={}, partitions={}",
                            topic, groupName, instanceId, assignments.size());

                    // Initialize offsets for all assigned partitions
                    Future<Void> init = Future.succeededFuture();
                    for (var assignment : assignments) {
                        assignedPartitions.put(assignment.partitionKey(), assignment.generation());
                        init = init.compose(v ->
                                offsetManager.initializeOffset(
                                        topic, groupName, assignment.partitionKey(), assignment.generation()
                                ).map(offset -> (Void) null)
                        );
                    }
                    return init;
                })
                .compose(v -> {
                    // Start periodic fetch loop
                    startFetchLoop();

                    // Start watermark job
                    watermarkJob = new WatermarkJob(vertx, watermarkCalculator, topic);
                    watermarkJob.start();

                    logger.info("PartitionedConsumerEngine started: topic={}, group={}, partitions={}",
                            topic, groupName, assignedPartitions.size());
                        return Future.<Void>succeededFuture();
                })
                .transform(ar -> {
                    if (ar.failed()) {
                        return resetAfterFailedStart(ar.cause());
                    }
                    return Future.succeededFuture();
                });
    }

    private Future<Void> resetAfterFailedStart(Throwable err) {
        running.set(false);
        messageHandler = null;
        return teardown()
                .compose(v -> Future.failedFuture(err));
    }

    /**
     * Stops the engine: cancels timers, leaves the group.
     *
     * @return future completing when leave is done
     */
    public Future<Void> stop() {
        if (!running.compareAndSet(true, false)) {
            return Future.succeededFuture();
        }
        return teardown();
    }

    private Future<Void> teardown() {
        if (fetchTimerId >= 0) {
            vertx.cancelTimer(fetchTimerId);
            fetchTimerId = -1;
        }

        if (watermarkJob != null) {
            watermarkJob.stop();
            watermarkJob = null;
        }

        assignedPartitions.clear();
        fetchInProgress.clear();

        return assignmentService.leaveGroup(topic, groupName, instanceId)
                .onFailure(err ->
                    logger.warn("Failed to leave group during teardown: topic={}, group={}, err={}",
                            topic, groupName, err.getMessage()))
                .transform(ar -> Future.<Void>succeededFuture());
    }

    /**
     * Stops and permanently closes the engine.
     */
    public Future<Void> close() {
        closed.set(true);
        return stop();
    }

    public boolean isRunning() {
        return running.get();
    }

    public Map<String, Integer> getAssignedPartitions() {
        return Map.copyOf(assignedPartitions);
    }

    // ========================================================================
    // Internal: fetch loop
    // ========================================================================

    private void startFetchLoop() {
        fetchTimerId = vertx.setPeriodic(DEFAULT_FETCH_INTERVAL_MS, id -> fetchAllPartitions());
        // Also do an immediate fetch
        fetchAllPartitions();
    }

    private void fetchAllPartitions() {
        if (!running.get()) return;

        for (var entry : assignedPartitions.entrySet()) {
            String partitionKey = entry.getKey();
            int generation = entry.getValue();
            fetchPartition(partitionKey, generation);
        }
    }

    private void fetchPartition(String partitionKey, int generation) {
        AtomicBoolean inProgress = fetchInProgress.computeIfAbsent(partitionKey, k -> new AtomicBoolean(false));
        if (!inProgress.compareAndSet(false, true)) {
            // Previous fetch+process+commit still running skip this tick
            return;
        }

        fetcher.fetch(topic, groupName, partitionKey, DEFAULT_BATCH_SIZE, generation)
                .compose(messages -> processAndCommit(messages, partitionKey, generation))
                .eventually(() -> {
                    inProgress.set(false);
                    return Future.<Void>succeededFuture();
                })
                .onFailure(err -> {
                    if (running.get()) {
                        logger.warn("Fetch failed for partition {}: {}", partitionKey, err.getMessage());
                    }
                });
    }

    private Future<Void> processAndCommit(List<OutboxMessage> messages, String partitionKey, int generation) {
        if (messages.isEmpty()) {
            return Future.succeededFuture();
        }

        // Process messages sequentially in order
        Future<Void> chain = Future.succeededFuture();
        for (OutboxMessage msg : messages) {
            chain = chain.compose(v -> dispatchMessage(msg));
        }

        // After all messages processed, commit the last offset
        long lastId = messages.get(messages.size() - 1).getId();
        return chain.compose(v ->
                offsetManager.commitOffset(topic, groupName, partitionKey, lastId, generation)
                        .map(committed -> {
                            if (committed) {
                                logger.debug("Committed offset: topic={}, group={}, partition={}, offset={}",
                                        topic, groupName, partitionKey, lastId);
                            } else {
                                logger.warn("Offset commit rejected (stale generation): topic={}, group={}, partition={}, gen={}",
                                        topic, groupName, partitionKey, generation);
                            }
                            return (Void) null;
                        })
        );
    }

    @SuppressWarnings("unchecked")
    public Future<Void> dispatchMessage(OutboxMessage outboxMsg) {
        try {
            T payload = parsePayload(outboxMsg.getPayload());
            Map<String, String> headers = parseHeaders(outboxMsg.getHeaders());

            Message<T> message = new SimpleMessage<>(
                    String.valueOf(outboxMsg.getId()),
                    topic,
                    payload,
                    headers,
                    outboxMsg.getCorrelationId(),
                    outboxMsg.getMessageGroup(),
                    outboxMsg.getCreatedAt()
            );

            return messageHandler.handle(message);
        } catch (Exception e) {
            logger.error("Failed to dispatch message {}: {}", outboxMsg.getId(), e.getMessage());
            return Future.failedFuture(e);
        }
    }

    @SuppressWarnings("unchecked")
    public T parsePayload(JsonObject payload) {
        if (payload == null) return null;
        if (payloadType == JsonObject.class) {
            return (T) payload;
        }
        // Both peegeeq-outbox (OutboxProducer) and peegeeq-native (PgNativeQueueProducer)
        // wrap simple/scalar payloads as {"value": <scalar>}. Mirror the unwrap logic used
        // by their respective consumers (OutboxConsumer.parsePayloadFromJsonObject /
        // PgNativeQueueConsumer.parsePayloadFromJsonObject) so partitioned dispatch
        // delivers the same payload shape as the legacy non-partitioned path.
        if (payload.size() == 1 && payload.containsKey("value")) {
            Object value = payload.getValue("value");
            if (payloadType.isInstance(value)) {
                return (T) value;
            }
        }
        if (objectMapper != null) {
            try {
                return objectMapper.readValue(payload.encode(), payloadType);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize payload to " + payloadType.getName(), e);
            }
        }
        if (payloadType == String.class) {
            // Last-resort fallback for String when no ObjectMapper is configured.
            return (T) payload.encode();
        }
        throw new RuntimeException("Cannot deserialize payload: no ObjectMapper configured and type is not String/JsonObject");
    }

    public Map<String, String> parseHeaders(JsonObject headers) {
        if (headers == null) return Map.of();
        Map<String, String> result = new HashMap<>();
        for (String key : headers.fieldNames()) {
            result.put(key, headers.getString(key));
        }
        return result;
    }

    // ========================================================================
    // Static: topic mode detection
    // ========================================================================

    /**
     * Queries the database to check if a topic uses OFFSET_WATERMARK completion
     * tracking mode.
     *
     * @param connectionManager connection manager with an active pool
     * @param serviceId the service ID for pool lookup
     * @param topic the topic to check
     * @return future resolving to true if OFFSET_WATERMARK, false otherwise
     */
    public static Future<Boolean> isOffsetWatermarkTopic(PgConnectionManager connectionManager,
                                                   String serviceId, String topic) {
        return connectionManager.withConnection(serviceId, connection ->
                connection.preparedQuery(
                        "SELECT completion_tracking_mode FROM outbox_topics WHERE topic = $1"
                ).execute(Tuple.of(topic))
                .map(rows -> {
                    if (rows.size() == 0) {
                        return false; // Topic not configured default to REFERENCE_COUNTING behavior
                    }
                    String mode = rows.iterator().next().getString("completion_tracking_mode");
                    return "OFFSET_WATERMARK".equals(mode);
                })
        );
    }
}
