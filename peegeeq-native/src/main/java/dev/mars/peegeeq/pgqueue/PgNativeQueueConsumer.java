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
package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.ServerSideFilter;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;

import io.vertx.sqlclient.Tuple;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native PostgreSQL queue message consumer.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class PgNativeQueueConsumer<T> implements dev.mars.peegeeq.api.messaging.MessageConsumer<T> {
    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueConsumer.class);

    private final VertxPoolAdapter poolAdapter;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private final String notifyChannel;
    private final MetricsProvider metrics;
    private final PeeGeeQConfiguration configuration;
    private final ConsumerConfig consumerConfig;
    private final Long minimumMessageId;
    private final OffsetDateTime minimumCreatedAt;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger processingInFlight = new AtomicInteger(0);
    private final Object lifecycleLock = new Object();
    private final Set<Future<Void>> inFlightProcessing = new HashSet<>();

    private MessageHandler<T> messageHandler;
    private PgConnection subscriber;
    // Reconnect/backoff state for LISTEN connection (dedicated, non-pooled)
    private long listenReconnectTimerId = -1;
    private int listenBackoffMs = 1000;
    private long pollingTimerId = -1;
    private long cleanupTimerId = -1;
    private Future<Void> closeFuture;

    private record HandlerSettlement(Throwable failure, boolean visibilityExpired) {
        private static HandlerSettlement succeeded() {
            return new HandlerSettlement(null, false);
        }

        private static HandlerSettlement failed(Throwable failure) {
            return new HandlerSettlement(failure, false);
        }

        private static HandlerSettlement expired() {
            return new HandlerSettlement(null, true);
        }
    }

    // The configuration-less constructor was removed deliberately: the consumer's
    // LISTEN channel derives from the configured schema, and PeeGeeQ has no default
    // schema — a consumer without configuration would silently listen on "public_" channels.

    public PgNativeQueueConsumer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration) {
        this(poolAdapter, objectMapper, topic, payloadType, metrics, configuration,
            null, null, null);
    }

    public PgNativeQueueConsumer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration, ConsumerConfig consumerConfig) {
        this(poolAdapter, objectMapper, topic, payloadType, metrics, configuration,
            consumerConfig, null, null);
    }

    PgNativeQueueConsumer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration, Long minimumMessageId,
            OffsetDateTime minimumCreatedAt) {
        this(poolAdapter, objectMapper, topic, payloadType, metrics, configuration,
            null, minimumMessageId, minimumCreatedAt);
    }

    private PgNativeQueueConsumer(VertxPoolAdapter poolAdapter, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration, ConsumerConfig consumerConfig,
            Long minimumMessageId, OffsetDateTime minimumCreatedAt) {
        if (minimumMessageId != null && minimumCreatedAt != null) {
            throw new IllegalArgumentException(
                "Only one native queue start-position boundary may be configured");
        }
        this.poolAdapter = poolAdapter;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = java.util.Objects.requireNonNull(configuration,
            "configuration cannot be null — PeeGeeQ has no default schema");
        this.consumerConfig = consumerConfig;
        this.minimumMessageId = minimumMessageId;
        this.minimumCreatedAt = minimumCreatedAt;
        this.notifyChannel = NativeQueueChannels.channelFor(
            configuration.getDatabaseConfig().getSchema(), topic);
        // Determine consumer threads for logging (no dedicated executor; async
        // operations)
        int consumerThreads = consumerConfig != null ? consumerConfig.getConsumerThreads()
                : (configuration != null ? configuration.getQueueConfig().getConsumerThreads() : 1);

        logger.info(
            "Created native queue consumer for topic: {} with consumer mode: {} (threads: {}, minimumMessageId: {}, minimumCreatedAt: {})",
            topic, consumerConfig != null ? consumerConfig.getMode() : "default", consumerThreads,
            minimumMessageId, minimumCreatedAt);
    }

    // --- LISTEN lifecycle state accessors ---
    // Read-only views of the consumer's native LISTEN/close lifecycle so shutdown and
    // regression tests can observe it through a real API instead of reflection. The
    // underlying state is set by subscribe()/stopListening()/scheduleListenReconnect()/close().

    /** @return true while a dedicated native LISTEN connection is open for this consumer. */
    public boolean hasActiveListenConnection() {
        return subscriber != null;
    }

    /** @return true while a LISTEN reconnect timer is scheduled (backoff in progress). */
    public boolean hasPendingListenReconnect() {
        return listenReconnectTimerId != -1;
    }

    /** @return true once this consumer has been closed. */
    public boolean isClosed() {
        return closed.get();
    }

    /** @return true while this consumer holds an active subscription. */
    public boolean isSubscribed() {
        return subscribed.get();
    }

    @Override
    public Future<Void> subscribe(MessageHandler<T> handler) {
        logger.info("Subscribe called for topic: {}, closed: {}, subscribed: {}", topic, closed.get(),
                subscribed.get());

        if (closed.get()) {
            logger.error("Cannot subscribe - consumer is closed for topic: {}", topic);
            return Future.failedFuture(new IllegalStateException("Consumer is closed"));
        }

        if (subscribed.compareAndSet(false, true)) {
            logger.info("Starting subscription for topic: {}", topic);
            this.messageHandler = handler;

            // Determine consumer mode - use ConsumerConfig if available, otherwise default to HYBRID
            ConsumerMode mode = consumerConfig != null ? consumerConfig.getMode() : ConsumerMode.HYBRID;
            logger.info("Using consumer mode: {} for topic: {}", mode, topic);

            // Start polling based on mode (sync timer setup completes immediately)
            if (mode == ConsumerMode.POLLING_ONLY || mode == ConsumerMode.HYBRID) {
                startPolling();
                logger.info("Started polling for topic: {}", topic);
            } else {
                logger.info("Skipping polling setup for LISTEN_NOTIFY_ONLY mode on topic: {}", topic);
            }

            // Start LISTEN/NOTIFY based on mode returns Future that completes when LISTEN is established
            if (mode == ConsumerMode.LISTEN_NOTIFY_ONLY || mode == ConsumerMode.HYBRID) {
                return startListening()
                        .onSuccess(v -> logger.info("Subscribed to topic: {} with mode: {}", topic, mode))
                        .onFailure(err -> {
                            if (closed.get()) {
                                logger.debug("Subscription aborted for topic: {} - consumer closed", topic);
                            } else if (mode == ConsumerMode.LISTEN_NOTIFY_ONLY) {
                                logger.error("Error during subscription for topic: {}", topic, err);
                            } else {
                                logger.warn("LISTEN setup failed for topic: {} (polling active as fallback): {}", topic, err.getMessage());
                            }
                        });
            } else {
                logger.info("Subscribed to topic: {} with mode: {}", topic, mode);
                return Future.succeededFuture();
            }
        } else {
            logger.error("Cannot subscribe - consumer is already subscribed for topic: {}", topic);
            return Future.failedFuture(new IllegalStateException("Already subscribed"));
        }
    }

    @Override
    public void unsubscribe() {
        if (subscribed.compareAndSet(true, false)) {
            stopListening()
                    .onFailure(err -> logger.warn(
                            "Failed to stop LISTEN subscription for topic {}: {}",
                            topic, err.getMessage(), err));
            this.messageHandler = null;
            logger.info("Unsubscribed from topic: {}", topic);
        }
    }

    private Future<Void> startListening() {
        logger.debug("startListening() called for topic: {}, notifyChannel: {}", topic, notifyChannel);
        if (!shouldMaintainListenSubscription()) {
            logger.debug("Consumer is closed, skipping LISTEN setup for topic: {}", topic);
            return Future.succeededFuture();
        }

        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null) {
            logger.error("No Vert.x instance available from pool adapter; cannot start LISTEN for topic: {}", topic);
            return Future.failedFuture("No Vert.x instance available from pool adapter for topic: " + topic);
        }

        return poolAdapter.connectDedicated()
                .compose(conn -> conn.query("LISTEN \"" + notifyChannel + "\"")
                        .execute()
                        .map(conn))
                .compose(conn -> {
                    if (!shouldMaintainListenSubscription()) {
                        return conn.close();
                    }
                    // Reset backoff on successful connect
                    listenBackoffMs = 1000;
                    this.subscriber = conn;
                    logger.info("Started listening on channel: {}", notifyChannel);

                    conn.notificationHandler(notification -> {
                        if (shouldMaintainListenSubscription() && notifyChannel.equals(notification.getChannel())) {
                            logger.debug("Received notification on channel: {}", notifyChannel);
                            processAvailableMessages();
                        }
                    });
                    conn.closeHandler(v -> {
                        if (!shouldMaintainListenSubscription()) {
                            logger.debug("LISTEN connection closed during shutdown for channel: {}", notifyChannel);
                        } else {
                            logger.warn("LISTEN connection closed unexpectedly for channel: {} - will reconnect", notifyChannel);
                        }
                        this.subscriber = null;
                        scheduleListenReconnect();
                    });
                    conn.exceptionHandler(err -> {
                        if (!shouldMaintainListenSubscription()) {
                            logger.debug("LISTEN error during shutdown on channel {}: {}", notifyChannel,
                                    err.getMessage());
                        } else if (isListenOnlyMode()) {
                            logger.error("LISTEN error on channel {}: {}", notifyChannel, err.getMessage());
                        } else {
                            logger.warn("LISTEN error on channel {} (polling active as fallback): {}", notifyChannel, err.getMessage());
                        }
                        try {
                            conn.close()
                                    .onFailure(closeError -> logger.warn(
                                            "Failed to close LISTEN connection for channel {} after error: {}",
                                            notifyChannel, closeError.getMessage(), closeError));
                        } catch (Exception closeError) {
                            logger.warn("Failed to initiate LISTEN connection close for channel {}: {}",
                                    notifyChannel, closeError.getMessage(), closeError);
                        }
                    });

                    ConsumerMode mode = consumerConfig != null ? consumerConfig.getMode() : ConsumerMode.HYBRID;
                    if (mode == ConsumerMode.LISTEN_NOTIFY_ONLY) {
                        processAvailableMessages();
                    }
                    return Future.<Void>succeededFuture();
                })
                .onFailure(err -> {
                    if (!shouldMaintainListenSubscription()) {
                        logger.debug("Failed to start LISTEN during shutdown on channel {}: {}", notifyChannel,
                                err.getMessage());
                    } else if (isListenOnlyMode()) {
                        logger.error("Failed to start LISTEN on channel {}: {}", notifyChannel, err.getMessage());
                    } else {
                        logger.warn("Failed to start LISTEN on channel {} (polling active as fallback): {}", notifyChannel, err.getMessage());
                    }
                    scheduleListenReconnect();
                });
    }

    private Future<Void> stopListening() {
        Vertx vertx = poolAdapter.getVertx();
        if (vertx != null && listenReconnectTimerId != -1) {
            vertx.cancelTimer(listenReconnectTimerId);
            listenReconnectTimerId = -1;
        }
        if (subscriber != null) {
            PgConnection connectionToClose = subscriber;
            subscriber = null; // Clear reference first to prevent new operations

            logger.debug("Executing UNLISTEN for channel: {}", notifyChannel);
            return connectionToClose
                    .query("UNLISTEN \"" + notifyChannel + "\"")
                    .execute()
                    .onSuccess(rs -> logger.info("Stopped listening on channel: {}", notifyChannel))
                    .onFailure(err -> logger.warn(
                            "Error during UNLISTEN for channel {}: {}", notifyChannel,
                            err.getMessage(), err))
                    .eventually(() -> connectionToClose.close()
                            .onFailure(closeError -> logger.warn(
                                    "Error closing connection after UNLISTEN on channel {}: {}",
                                    notifyChannel, closeError.getMessage(), closeError)))
                    .mapEmpty();
        }
        return Future.succeededFuture();
    }

    private void scheduleListenReconnect() {
        if (!shouldMaintainListenSubscription())
            return;
        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null) {
            logger.error("Cannot schedule LISTEN reconnect: Vert.x is null");
            return;
        }
        long delay = listenBackoffMs;
        listenReconnectTimerId = vertx.setTimer(delay, id -> {
            if (!shouldMaintainListenSubscription()) {
                listenReconnectTimerId = -1;
                return;
            }
            listenReconnectTimerId = -1;
            logger.info("Reconnecting LISTEN on channel {} after {} ms", notifyChannel, delay);
            startListening();
        });
        // Exponential backoff capped at 30 seconds
        listenBackoffMs = Math.min(listenBackoffMs * 2, 30_000);
    }

    private boolean shouldMaintainListenSubscription() {
        return subscribed.get() && !closed.get();
    }

    private boolean isListenOnlyMode() {
        return consumerConfig != null && consumerConfig.getMode() == ConsumerMode.LISTEN_NOTIFY_ONLY;
    }

    void closeSubscriberConnectionForTest() {
        PgConnection connectionToClose = subscriber;
        if (connectionToClose != null) {
            connectionToClose.close()
                    .onFailure(error -> logger.warn(
                            "Test-triggered LISTEN connection close failed for channel {}: {}",
                            notifyChannel, error.getMessage(), error));
        }
    }

    private void startPolling() {
        // Get polling interval from ConsumerConfig first, then PeeGeeQConfiguration,
        // then default
        Duration pollingInterval;
        if (consumerConfig != null) {
            pollingInterval = consumerConfig.getPollingInterval();
        } else if (configuration != null) {
            pollingInterval = configuration.getQueueConfig().getPollingInterval();
        } else {
            pollingInterval = Duration.ofSeconds(5); // Use new default
        }

        long pollingIntervalMs = pollingInterval.toMillis();

        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null && Vertx.currentContext() != null) {
            vertx = Vertx.currentContext().owner();
        }
        if (vertx == null) {
            logger.error("No Vert.x instance available; cannot start polling for topic: {}", topic);
            return;
        }

        // Periodic polling using Vert.x timers
        pollingTimerId = vertx.setPeriodic(pollingIntervalMs, id -> {
            if (closed.get())
                return;
            try {
                logger.debug("Polling for messages on topic: {} (interval: {}ms)", topic,
                        pollingIntervalMs);
                processAvailableMessages();
            } catch (Exception e) {
                if (!closed.get()) {
                    logger.error("Error in scheduled message processing for topic {}: {}", topic, e.getMessage());
                }
            }
        });

        // Expired lock cleanup every 10 seconds
        cleanupTimerId = vertx.setPeriodic(10_000, id -> {
            if (closed.get())
                return;
            try {
                releaseExpiredLocks();
            } catch (Exception e) {
                if (!closed.get()) {
                    logger.error("Error in scheduled expired locks cleanup for topic {}: {}", topic, e.getMessage());
                }
            }
        });

        logger.info("Started polling for topic {} with interval: {}", topic, pollingInterval);
    }

    private void processAvailableMessages() {
        logger.debug("processAvailableMessages() called for topic: {}", topic);
        Future<Void> processing;
        synchronized (lifecycleLock) {
            if (!subscribed.get() || messageHandler == null || closed.get()) {
                logger.debug(
                        "Skipping message processing - subscribed: {}, messageHandler: {}, closed: {} for topic {}",
                        subscribed.get(), messageHandler != null, closed.get(), topic);
                return;
            }
            try {
                processing = processAvailableMessagesInternal();
                inFlightProcessing.add(processing);
            } catch (Exception error) {
                logger.error("Failed to start message processing for topic {}: {}",
                        topic, error.getMessage(), error);
                return;
            }
        }

        processing
                .eventually(() -> {
                    synchronized (lifecycleLock) {
                        inFlightProcessing.remove(processing);
                    }
                    return Future.succeededFuture();
                })
                .onFailure(error -> logger.error(
                        "Message processing pipeline failed for topic {}: {}",
                        topic, error.getMessage(), error));
    }

    private Future<Void> processAvailableMessagesInternal() {
        int reservedCapacity = 0;
        try {
            final Pool pool = poolAdapter.getPoolOrThrow();

            int batchSize = consumerConfig != null
                    ? consumerConfig.getBatchSize()
                    : configuration.getQueueConfig().getBatchSize();
            int maxThreads = consumerConfig != null && consumerConfig.getConsumerThreads() > 0
                    ? consumerConfig.getConsumerThreads()
                    : configuration.getQueueConfig().getConsumerThreads();
            reservedCapacity = reserveProcessingCapacity(maxThreads, batchSize);
            if (reservedCapacity == 0) {
                logger.debug("No remaining capacity (processingInFlight={} >= maxThreads={}), skip claim",
                        processingInFlight.get(), maxThreads);
                return Future.succeededFuture();
            }
            int admittedCapacity = reservedCapacity;
            long lockDurationSeconds = configuration.getQueueConfig().getVisibilityTimeout().toSeconds();

            ServerSideFilter filter = consumerConfig != null ? consumerConfig.getServerSideFilter() : null;
            String startPositionCondition = "";
            List<Object> startPositionParameters = new ArrayList<>(1);
            if (minimumMessageId != null) {
                startPositionCondition = " AND id >= $4";
                startPositionParameters.add(minimumMessageId);
            } else if (minimumCreatedAt != null) {
                startPositionCondition = " AND created_at >= $4";
                startPositionParameters.add(minimumCreatedAt);
            }

            String sql;
            Tuple params;
            if (filter != null) {
                int filterParameterOffset = 4 + startPositionParameters.size();
                String filterCondition = filter.toSqlCondition(filterParameterOffset);
                sql = """
                        WITH c AS (
                            SELECT id FROM queue_messages
                            WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
                              %s
                              AND %s
                            ORDER BY priority DESC, created_at ASC
                            LIMIT $2
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE queue_messages q
                        SET status = 'LOCKED', lock_until = now() + make_interval(secs => $3)
                        FROM c
                        WHERE q.id = c.id
                        RETURNING q.id, q.payload, q.headers, q.correlation_id, q.message_group,
                                  q.retry_count, q.created_at,
                                  EXTRACT(EPOCH FROM (now() - q.created_at)) * 1000.0 AS delivery_latency_ms
                        """.formatted(startPositionCondition, filterCondition);
                List<Object> allParameters = new ArrayList<>();
                allParameters.add(topic);
                allParameters.add(admittedCapacity);
                allParameters.add(lockDurationSeconds);
                allParameters.addAll(startPositionParameters);
                allParameters.addAll(filter.getParameters());
                params = Tuple.from(allParameters);
            } else {
                sql = """
                        WITH c AS (
                            SELECT id FROM queue_messages
                            WHERE topic = $1 AND status = 'AVAILABLE' AND visible_at <= now()
                              %s
                            ORDER BY priority DESC, created_at ASC
                            LIMIT $2
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE queue_messages q
                        SET status = 'LOCKED', lock_until = now() + make_interval(secs => $3)
                        FROM c
                        WHERE q.id = c.id
                        RETURNING q.id, q.payload, q.headers, q.correlation_id, q.message_group,
                                  q.retry_count, q.created_at,
                                  EXTRACT(EPOCH FROM (now() - q.created_at)) * 1000.0 AS delivery_latency_ms
                        """.formatted(startPositionCondition);
                List<Object> allParameters = new ArrayList<>();
                allParameters.add(topic);
                allParameters.add(admittedCapacity);
                allParameters.add(lockDurationSeconds);
                allParameters.addAll(startPositionParameters);
                params = Tuple.from(allParameters);
            }

            Vertx vertx = poolAdapter.getVertx();
            if (vertx == null && Vertx.currentContext() != null) {
                vertx = Vertx.currentContext().owner();
            }
            if (vertx == null) {
                releaseProcessingCapacity(admittedCapacity);
                return Future.failedFuture(
                        new IllegalStateException("No Vert.x instance available for topic " + topic));
            }
            Vertx operationVertx = vertx;
            return executeOnVertxContext(operationVertx, () -> withRetry(operationVertx,
                    () -> pool.withTransaction(conn -> conn.preparedQuery(sql).execute(params)),
                    3, 50))
                    .transform(claimResult -> {
                        if (claimResult.failed()) {
                            releaseProcessingCapacity(admittedCapacity);
                            return Future.failedFuture(claimResult.cause());
                        }

                        var rows = claimResult.result();
                        int claimedMessages = rows.size();
                        if (claimedMessages > admittedCapacity) {
                            releaseProcessingCapacity(admittedCapacity);
                            return Future.failedFuture(new IllegalStateException(
                                    "Claim returned " + claimedMessages + " rows after reserving "
                                            + admittedCapacity + " capacity slots for topic " + topic));
                        }
                        releaseProcessingCapacity(admittedCapacity - claimedMessages);
                        if (rows.size() == 0) {
                            logger.debug("No messages found for topic {}", topic);
                            return Future.succeededFuture();
                        }
                        logger.debug("Processing {} messages for topic {}", rows.size(), topic);
                        List<Future<Void>> messagePipelines = new ArrayList<>();
                        for (Row row : rows) {
                            messagePipelines.add(processReservedMessage(row));
                        }
                        return Future.join(messagePipelines).map(ignored -> (Void) null);
                    })
                    .onFailure(error -> logger.error(
                            "Error processing messages for topic {}: {}",
                            topic, error.getMessage(), error));
        } catch (Exception error) {
            if (reservedCapacity > 0) {
                releaseProcessingCapacity(reservedCapacity);
            }
            logger.error("Error starting message processing for topic {}: {}",
                    topic, error.getMessage(), error);
            return Future.failedFuture(error);
        }
    }

    private int reserveProcessingCapacity(int maxThreads, int batchSize) {
        while (true) {
            int current = processingInFlight.get();
            int remaining = maxThreads - current;
            if (remaining <= 0) {
                return 0;
            }

            int reservation = Math.min(batchSize, remaining);
            if (processingInFlight.compareAndSet(current, current + reservation)) {
                return reservation;
            }
        }
    }

    private void releaseProcessingCapacity(int capacity) {
        if (capacity == 0) {
            return;
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity release cannot be negative: " + capacity);
        }

        while (true) {
            int current = processingInFlight.get();
            if (current < capacity) {
                throw new IllegalStateException(
                        "Cannot release " + capacity + " capacity slots when only " + current
                                + " are reserved for topic " + topic);
            }
            if (processingInFlight.compareAndSet(current, current - capacity)) {
                return;
            }
        }
    }

    private Future<Void> processReservedMessage(Row row) {
        Future<Void> processing;
        try {
            processing = processMessageWithoutTransaction(row);
            if (processing == null) {
                processing = Future.failedFuture(
                        new IllegalStateException("Reserved message processing returned null Future"));
            }
        } catch (Exception error) {
            processing = Future.failedFuture(error);
        }

        return processing.eventually(() -> {
            try {
                releaseProcessingCapacity(1);
            } finally {
                TraceContextUtil.clearTraceMDC();
                processAvailableMessages();
            }
            return Future.succeededFuture();
        });
    }

    /**
     * : Process message without transaction since locking is already
     * committed.
     * This prevents transaction rollback from undoing the LOCKED status.
     */
    private Future<Void> processMessageWithoutTransaction(Row row) {
        Long messageIdLong = row.getLong("id");
        String messageId = messageIdLong.toString();

        // Delivery latency (telemetry G2): enqueue → claim, computed by the
        // claim statement on the DATABASE clock (now() - created_at), so no
        // JVM/DB clock skew enters the measurement. Recorded for every claimed
        // message — delivery happened even if processing later fails.
        Double deliveryLatencyMs = row.getDouble("delivery_latency_ms");
        if (deliveryLatencyMs != null && deliveryLatencyMs >= 0) {
            metrics.recordMessageDeliveryLatency(topic, "native",
                    java.time.Duration.ofNanos(Math.round(deliveryLatencyMs * 1_000_000.0)));
        }

        try {
            // Parse payload and headers (updated for JSONB objects)
            JsonObject payload = row.getJsonObject("payload");
            JsonObject headers = row.getJsonObject("headers");

            // Parse headers and payload (updated for JSONB objects)
            T parsedPayload = parsePayloadFromJsonObject(payload);
            Map<String, String> headerMap = parseHeadersFromJsonObject(headers);
            String correlationId = row.getString("correlation_id");
            String messageGroup = row.getString("message_group");

            // Add correlationId to headers if present so that consumers relying on headers (like WebhookSubscriptionHandler) can find it
            if (correlationId != null) {
                headerMap.put("correlationId", correlationId);
            }

            // Set MDC from message headers for distributed tracing
            boolean hasTrace = TraceContextUtil.setMDCFromMessageHeaders(headerMap);
            
            // If no trace context exists in headers, create a new one (Start a new trace)
            if (!hasTrace) {
                TraceCtx newTrace = TraceCtx.createNew();
                TraceContextUtil.setMDC(TraceContextUtil.MDC_TRACE_ID, newTrace.traceId());
                TraceContextUtil.setMDC(TraceContextUtil.MDC_SPAN_ID, newTrace.spanId());
            }

            TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, messageId);
            TraceContextUtil.setMDC(TraceContextUtil.MDC_TOPIC, topic);
            if (correlationId != null) {
                TraceContextUtil.setMDC(TraceContextUtil.MDC_CORRELATION_ID, correlationId);
            }

            // Get message handler
            MessageHandler<T> handler = this.messageHandler;
            if (handler == null) {
                TraceContextUtil.clearTraceMDC();
                return Future.failedFuture(new IllegalStateException(
                        "Message handler is unavailable for admitted message " + messageId));
            }

            // Create message (following existing pattern)
            Message<T> message = new SimpleMessage<>(
                    messageId, topic, parsedPayload, headerMap, correlationId, messageGroup, java.time.Instant.now());

            long startTime = System.currentTimeMillis();
            var traceCtx = TraceContextUtil.captureTraceContext();

            Future<Void> processingFuture;
            try {
                processingFuture = handler.handle(message);
                if (processingFuture == null) {
                    processingFuture = Future.failedFuture(
                            new IllegalStateException("Message handler returned null Future"));
                }
            } catch (Exception processingError) {
                processingFuture = Future.failedFuture(processingError);
            }

            int previousRetryCount = row.getInteger("retry_count") != null
                    ? row.getInteger("retry_count")
                    : 0;
            return settleHandlerWithinVisibility(processingFuture, messageIdLong, messageId)
                    .compose(settlement -> {
                        try (var scope = TraceContextUtil.mdcScope(traceCtx)) {
                            if (settlement.visibilityExpired()) {
                                return Future.succeededFuture();
                            }

                            if (settlement.failure() == null) {
                                long processingTime = System.currentTimeMillis() - startTime;
                                metrics.recordMessageReceived(topic);
                                metrics.recordMessageProcessed(
                                        topic, java.time.Duration.ofMillis(processingTime));
                                return deleteMessage(messageIdLong, messageId);
                            }

                            Throwable processingError = settlement.failure();
                            logger.warn("Message handler failed for message {}: {}",
                                    messageId, processingError.getMessage(), processingError);
                            return handleProcessingFailure(
                                    messageIdLong,
                                    messageId,
                                    previousRetryCount + 1,
                                    processingError);
                        }
                    });

        } catch (Exception e) {
            logger.error("Error parsing message {}: {}", messageId, e.getMessage(), e);
            int retryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count") : 0;
            return handleProcessingFailure(messageIdLong, messageId, retryCount + 1, e);
        }
    }

    // Removed processMessageInThread - replaced with transaction-based
    // processMessageWithTransaction

    private Future<HandlerSettlement> settleHandlerWithinVisibility(
            Future<Void> handlerFuture,
            Long messageIdLong,
            String messageId) {
        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null && Vertx.currentContext() != null) {
            vertx = Vertx.currentContext().owner();
        }
        if (vertx == null) {
            return Future.failedFuture(
                    new IllegalStateException("No Vert.x instance available for handler visibility timeout"));
        }

        Promise<HandlerSettlement> settlement = Promise.promise();
        AtomicBoolean decided = new AtomicBoolean(false);
        long visibilityTimeoutMs = Math.max(
                1L, configuration.getQueueConfig().getVisibilityTimeout().toMillis());
        Vertx operationVertx = vertx;
        long timeoutId = operationVertx.setTimer(visibilityTimeoutMs, ignored -> {
            if (!decided.compareAndSet(false, true)) {
                return;
            }

            logger.warn(
                    "Message handler visibility expired for message {}; relinquishing the stale delivery",
                    messageId);
            releaseTimedOutMessage(messageIdLong, messageId)
                    .onSuccess(v -> settlement.tryComplete(HandlerSettlement.expired()))
                    .onFailure(settlement::tryFail);
        });

        handlerFuture
                .onSuccess(v -> {
                    if (decided.compareAndSet(false, true)) {
                        operationVertx.cancelTimer(timeoutId);
                        settlement.tryComplete(HandlerSettlement.succeeded());
                    }
                })
                .onFailure(error -> {
                    if (decided.compareAndSet(false, true)) {
                        operationVertx.cancelTimer(timeoutId);
                        settlement.tryComplete(HandlerSettlement.failed(error));
                    }
                });
        return settlement.future();
    }

    private Future<Void> releaseTimedOutMessage(Long messageIdLong, String messageId) {
        try {
            Pool pool = poolAdapter.getPoolOrThrow();
            String sql = """
                    UPDATE queue_messages
                    SET status = 'AVAILABLE', lock_until = NULL
                    WHERE id = $1 AND status = 'LOCKED' AND lock_until <= now()
                    """;
            Vertx vertx = poolAdapter.getVertx();
            if (vertx == null && Vertx.currentContext() != null) {
                vertx = Vertx.currentContext().owner();
            }

            Future<io.vertx.sqlclient.RowSet<Row>> release = vertx == null
                    ? pool.withTransaction(conn -> conn.preparedQuery(sql)
                            .execute(Tuple.of(messageIdLong)))
                    : withRetry(vertx,
                            () -> pool.withTransaction(conn -> conn.preparedQuery(sql)
                                    .execute(Tuple.of(messageIdLong))),
                            3, 50);
            return release
                    .map(ignored -> (Void) null)
                    .onSuccess(v -> logger.debug(
                            "Relinquished stale delivery for message {} after visibility timeout",
                            messageId))
                    .onFailure(error -> logger.error(
                            "Failed to relinquish stale delivery for message {}: {}",
                            messageId, error.getMessage(), error));
        } catch (Exception error) {
            logger.error("Error relinquishing stale delivery for message {}: {}",
                    messageId, error.getMessage(), error);
            return Future.failedFuture(error);
        }
    }

    private Future<Void> deleteMessage(Long messageIdLong, String messageId) {
        try {
            Pool pool = poolAdapter.getPoolOrThrow();
            String sql = "DELETE FROM queue_messages WHERE id = $1";
            Vertx vertx = poolAdapter.getVertx();
            if (vertx == null && Vertx.currentContext() != null) {
                vertx = Vertx.currentContext().owner();
            }

            Future<?> deletion = vertx == null
                    ? pool.withTransaction(conn ->
                            conn.preparedQuery(sql).execute(Tuple.of(messageIdLong)))
                    : withRetry(vertx,
                            () -> pool.withTransaction(conn ->
                                    conn.preparedQuery(sql).execute(Tuple.of(messageIdLong))),
                            3, 50);
            return deletion
                    .map(ignored -> (Void) null)
                    .onSuccess(result -> logger.debug("Deleted processed message: {}", messageId))
                    .onFailure(error -> logger.error(
                            "Failed to delete message {}: {}", messageId, error.getMessage(), error));
        } catch (Exception error) {
            logger.error("Error initiating deletion for message {}: {}",
                    messageId, error.getMessage(), error);
            return Future.failedFuture(error);
        }
    }

    private Future<Void> handleProcessingFailure(
            Long messageIdLong,
            String messageId,
            int retryCount,
            Throwable error) {
        try {
            if (retryCount >= configuration.getQueueConfig().getMaxRetries()) {
                return moveToDeadLetterQueue(messageIdLong, messageId, error.getMessage());
            }

            Pool pool = poolAdapter.getPoolOrThrow();
            String sql = "UPDATE queue_messages "
                    + "SET status = 'AVAILABLE', lock_until = NULL, retry_count = $2 WHERE id = $1";
            Vertx vertx = poolAdapter.getVertx();
            if (vertx == null && Vertx.currentContext() != null) {
                vertx = Vertx.currentContext().owner();
            }

            Future<?> reset = vertx == null
                    ? pool.withTransaction(conn -> conn.preparedQuery(sql)
                            .execute(Tuple.of(messageIdLong, retryCount)))
                    : withRetry(vertx,
                            () -> pool.withTransaction(conn -> conn.preparedQuery(sql)
                                    .execute(Tuple.of(messageIdLong, retryCount))),
                            3, 50);
            return reset
                    .map(ignored -> (Void) null)
                    .onSuccess(result -> logger.debug(
                            "Reset message {} for retry (attempt {})", messageId, retryCount))
                    .onFailure(updateError -> logger.error(
                            "Failed to reset message {} for retry: {}",
                            messageId, updateError.getMessage(), updateError));
        } catch (Exception persistenceError) {
            logger.error("Error handling processing failure for message {}: {}",
                    messageId, persistenceError.getMessage(), persistenceError);
            return Future.failedFuture(persistenceError);
        }
    }

    private Future<Void> moveToDeadLetterQueue(
            Long messageIdLong,
            String messageId,
            String errorMessage) {
        try {
            Pool pool = poolAdapter.getPoolOrThrow();
            logger.warn("Message {} exceeded retry limit, moving to dead letter queue: {}",
                    messageId, errorMessage);

            String selectSql = """
                    SELECT payload, headers, correlation_id, message_group, retry_count, created_at
                    FROM queue_messages
                    WHERE id = $1
                    """;
            String insertSql = """
                    INSERT INTO dead_letter_queue (
                        original_table, original_id, topic, payload, headers,
                        correlation_id, message_group, retry_count, failure_reason,
                        failed_at, original_created_at
                    ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, now(), $10)
                    """;
            String deleteSql = "DELETE FROM queue_messages WHERE id = $1";

            java.util.function.Supplier<Future<io.vertx.sqlclient.RowSet<Row>>> moveOperation = () ->
                    pool.withTransaction(conn -> conn.preparedQuery(selectSql)
                            .execute(Tuple.of(messageIdLong))
                            .compose(selectResult -> {
                                if (selectResult.size() == 0) {
                                    return Future.failedFuture(
                                            new IllegalStateException("Message " + messageId
                                                    + " not found during dead-letter persistence"));
                                }
                                Row row = selectResult.iterator().next();
                                return conn.preparedQuery(insertSql)
                                        .execute(Tuple.of(
                                                "queue_messages",
                                                messageIdLong,
                                                topic,
                                                row.getJsonObject("payload"),
                                                row.getJsonObject("headers"),
                                                row.getString("correlation_id"),
                                                row.getString("message_group"),
                                                row.getInteger("retry_count"),
                                                errorMessage,
                                                row.get(OffsetDateTime.class, "created_at")))
                                        .compose(inserted -> conn.preparedQuery(deleteSql)
                                                .execute(Tuple.of(messageIdLong)));
                            }));

            Vertx vertx = poolAdapter.getVertx();
            if (vertx == null && Vertx.currentContext() != null) {
                vertx = Vertx.currentContext().owner();
            }
            Future<?> move = vertx == null
                    ? moveOperation.get()
                    : withRetry(vertx, moveOperation, 3, 50);
            return move
                    .map(ignored -> (Void) null)
                    .onSuccess(result -> {
                        logger.info("Moved message {} to dead letter queue", messageId);
                        metrics.recordMessageDeadLettered(topic, errorMessage);
                    })
                    .onFailure(moveError -> logger.error(
                            "Failed to move message {} to dead letter queue: {}",
                            messageId, moveError.getMessage(), moveError));
        } catch (Exception moveError) {
            logger.error("Error moving message {} to dead letter queue: {}",
                    messageId, moveError.getMessage(), moveError);
            return Future.failedFuture(moveError);
        }
    }

    // Advisory lock release method removed - using pg_try_advisory_xact_lock
    // (transaction-level locks)
    // These are automatically released when transactions end, eliminating the need
    // for manual release
    // This completely eliminates ExclusiveLock warnings by letting PostgreSQL
    // handle cleanup

    private void releaseExpiredLocks() {
        Future<Void> cleanup;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return;
            }
            try {
                cleanup = releaseExpiredLocksInternal();
                inFlightProcessing.add(cleanup);
            } catch (Exception error) {
                logger.error("Error starting expired-lock cleanup for topic {}: {}",
                        topic, error.getMessage(), error);
                return;
            }
        }

        cleanup
                .eventually(() -> {
                    synchronized (lifecycleLock) {
                        inFlightProcessing.remove(cleanup);
                    }
                    return Future.succeededFuture();
                })
                .onFailure(error -> logger.error(
                        "Failed to query expired locks for topic {}: {}",
                        topic, error.getMessage(), error));
    }

    private Future<Void> releaseExpiredLocksInternal() {
        final Pool pool = poolAdapter.getPoolOrThrow();
        String updateSql = """
                UPDATE queue_messages
                SET status = 'AVAILABLE', lock_until = NULL
                WHERE topic = $1 AND status = 'LOCKED' AND lock_until < now()
                """;

        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null && Vertx.currentContext() != null) {
            vertx = Vertx.currentContext().owner();
        }
        Future<io.vertx.sqlclient.RowSet<Row>> cleanup = vertx == null
                ? pool.withTransaction(conn -> conn.preparedQuery(updateSql).execute(Tuple.of(topic)))
                : withRetry(vertx,
                        () -> pool.withTransaction(conn -> conn.preparedQuery(updateSql)
                                .execute(Tuple.of(topic))),
                        3, 50);
        return cleanup
                .onSuccess(updateResult -> {
                    if (updateResult.rowCount() > 0) {
                        logger.debug(
                                "Reset {} expired locks for topic: {} - advisory locks will auto-release",
                                updateResult.rowCount(), topic);
                        processAvailableMessages();
                    }
                })
                .map(ignored -> (Void) null);
    }

    @Override
    public void close() {
        closeAsync().onFailure(error -> logger.error(
                "Failed to close native queue consumer for topic {}", topic, error));
    }

    /**
     * Stops admission of new poll cycles and settles only after every admitted
     * handler and its terminal database operation has completed.
     *
     * @return the shared Future representing this consumer's close operation
     */
    public Future<Void> closeAsync() {
        synchronized (lifecycleLock) {
            if (closeFuture != null) {
                return closeFuture;
            }

            logger.info("Starting graceful shutdown of native queue consumer for topic: {}", topic);
            closed.set(true);
            subscribed.set(false);
            Future<Void> listenClose = stopListening();

            Vertx vertx = poolAdapter.getVertx();
            if (vertx == null && Vertx.currentContext() != null) {
                vertx = Vertx.currentContext().owner();
            }
            if (vertx != null) {
                if (pollingTimerId != -1) {
                    vertx.cancelTimer(pollingTimerId);
                    pollingTimerId = -1;
                }
                if (cleanupTimerId != -1) {
                    vertx.cancelTimer(cleanupTimerId);
                    cleanupTimerId = -1;
                }
            }

            List<Future<Void>> processingSnapshot = new ArrayList<>(inFlightProcessing);
            Future<Void> processingDrain = processingSnapshot.isEmpty()
                    ? Future.<Void>succeededFuture()
                    : Future.join(processingSnapshot).map(ignored -> (Void) null);

            closeFuture = Future.join(listenClose, processingDrain)
                    .map(ignored -> (Void) null)
                    .eventually(() -> {
                        messageHandler = null;
                        logger.info("Completed graceful shutdown of native queue consumer for topic: {}", topic);
                        return Future.<Void>succeededFuture();
                    });
            return closeFuture;
        }
    }

    /**
     * Executes a Future-returning operation on the Vert.x context.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * the proper execution context for Vert.x operations.
     *
     * Following the exact pattern from peegeeq-outbox OutboxProducer with
     * additional
     * safety checks to prevent RejectedExecutionException during shutdown.
     *
     * @param vertx     The Vertx instance
     * @param operation The operation to execute that returns a Future
     * @return Future that completes when the operation completes
     */
    private static <T> Future<T> executeOnVertxContext(Vertx vertx, java.util.function.Supplier<Future<T>> operation) {
        // : Check if Vert.x instance is null or closed before attempting
        // operations
        if (vertx == null) {
            logger.debug("Vert.x instance is null, returning failed future");
            return Future.failedFuture("Vert.x instance is null");
        }

        try {
            Context context = vertx.getOrCreateContext();
            if (context == Vertx.currentContext()) {
                // Already on Vert.x context, execute directly
                try {
                    return operation.get();
                } catch (Exception e) {
                    // : Handle RejectedExecutionException and other errors gracefully
                    if (e.getMessage() != null && (e.getMessage().contains("event executor terminated") ||
                            e.getMessage().contains("RejectedExecutionException"))) {
                        logger.debug("Event executor terminated during direct execution: {}",
                                e.getMessage());
                        return Future.failedFuture("Event executor terminated");
                    }
                    return Future.failedFuture(e);
                }
            } else {
                // Execute on Vert.x context using runOnContext
                Promise<T> promise = Promise.promise();
                // Capture Trace Context
                var traceCtx = TraceContextUtil.captureTraceContext();
                try {
                    context.runOnContext(v -> {
                        // Keep MDC scope open through async callbacks, closed in terminal handlers
                        var scope = TraceContextUtil.mdcScope(traceCtx);
                        try {
                            operation.get()
                                    .onSuccess(result -> {
                                        scope.close();
                                        promise.complete(result);
                                    })
                                    .onFailure(err -> {
                                        scope.close();
                                        promise.fail(err);
                                    });
                        } catch (Exception e) {
                            scope.close();
                            // : Handle exceptions during context execution
                            if (e.getMessage() != null && (e.getMessage().contains("event executor terminated") ||
                                    e.getMessage().contains("RejectedExecutionException"))) {
                                logger.debug("Event executor terminated during context execution: {}",
                                        e.getMessage());
                                promise.fail("Event executor terminated");
                            } else {
                                promise.fail(e);
                            }
                        }
                    });
                } catch (Exception e) {
                    // : Handle RejectedExecutionException when scheduling on context
                    if (e.getMessage() != null && (e.getMessage().contains("event executor terminated") ||
                            e.getMessage().contains("RejectedExecutionException"))) {
                        logger.debug("Event executor terminated when scheduling on context: {}",
                                e.getMessage());
                        return Future.failedFuture("Event executor terminated");
                    }
                    return Future.failedFuture(e);
                }
                return promise.future();
            }
        } catch (Exception e) {
            // : Handle any other exceptions during context creation
            if (e.getMessage() != null && (e.getMessage().contains("event executor terminated") ||
                    e.getMessage().contains("RejectedExecutionException"))) {
                logger.debug("Event executor terminated during context creation: {}", e.getMessage());
                return Future.failedFuture("Event executor terminated");
            }
            return Future.failedFuture(e);
        }
    }

    /**
     * Determine if the failure is retryable based on PostgreSQL SQLSTATE codes.
     * Retries are applied for serialization failures (40001) and deadlocks (40P01).
     */
    private static boolean isRetryable(Throwable t) {
        if (t == null)
            return false;
        // Unwrap
        Throwable cause = t;
        while (cause.getCause() != null && !(cause instanceof PgException)) {
            cause = cause.getCause();
        }
        if (cause instanceof PgException) {
            String code = ((PgException) cause).getSqlState();
            return "40001".equals(code) || "40P01".equals(code);
        }
        return false;
    }

    /**
     * Execute an async operation with bounded retries and exponential backoff for
     * retryable errors.
     */
    private static <T> Future<T> withRetry(Vertx vertx,
            java.util.function.Supplier<Future<T>> operation,
            int maxAttempts,
            long initialBackoffMs) {
        Promise<T> promise = Promise.promise();
        executeWithRetryAttempt(vertx, operation, maxAttempts, 1, initialBackoffMs, promise);
        return promise.future();
    }

    private static <T> void executeWithRetryAttempt(Vertx vertx,
            java.util.function.Supplier<Future<T>> operation,
            int maxAttempts,
            int attempt,
            long backoffMs,
            Promise<T> promise) {
        try {
            operation.get()
                    .onSuccess(promise::complete)
                    .onFailure(err -> {
                        if (attempt < maxAttempts && isRetryable(err)) {
                            long nextBackoff = Math.min(backoffMs * 2, 1000L);
                            long delay = backoffMs <= 0 ? 0 : backoffMs;
                            // Schedule next attempt on Vert.x timer
                            vertx.setTimer(delay, id -> executeWithRetryAttempt(vertx, operation, maxAttempts,
                                    attempt + 1, nextBackoff, promise));
                        } else {
                            promise.fail(err);
                        }
                    });
        } catch (Throwable e) {
            if (attempt < maxAttempts && isRetryable(e)) {
                long nextBackoff = Math.min(backoffMs * 2, 1000L);
                long delay = backoffMs <= 0 ? 0 : backoffMs;
                vertx.setTimer(delay, id -> executeWithRetryAttempt(vertx, operation, maxAttempts, attempt + 1,
                        nextBackoff, promise));
            } else {
                promise.fail(e);
            }
        }
    }

    /**
     * Gets or creates a shared Vertx instance for proper context management.
     * This ensures that TransactionPropagation.CONTEXT works correctly by providing
     * a consistent Vertx context across all PgNativeQueueConsumer instances.
     *
     * Following the exact pattern from peegeeq-outbox OutboxProducer with
     * additional
     * safety checks to prevent usage of closed instances.
     *
     * @return The shared Vertx instance, or null if it has been closed
     */

    /**
     * Parse payload from JsonObject back to the expected type.
     * Handles both simple values (wrapped in {"value": ...}) and complex objects.
     *
     * : Use the same ObjectMapper that was used for serialization
     * instead of JsonObject.mapTo() which uses Vert.x's internal ObjectMapper.
     * This ensures consistent Instant/LocalDateTime serialization/deserialization.
     */
    private T parsePayloadFromJsonObject(JsonObject payload) throws Exception {
        if (payload == null || payload.isEmpty())
            return null;

        // Check if this is a simple value wrapped in {"value": ...}
        if (payload.size() == 1 && payload.containsKey("value")) {
            Object value = payload.getValue("value");
            if (payloadType.isInstance(value)) {
                @SuppressWarnings("unchecked")
                T result = (T) value;
                return result;
            }
            // If the inner value is a JSON structure, decode from the inner structure
            if (value instanceof io.vertx.core.json.JsonObject) {
                String inner = ((io.vertx.core.json.JsonObject) value).encode();
                return objectMapper.readValue(inner, payloadType);
            }
            if (value instanceof io.vertx.core.json.JsonArray) {
                String inner = ((io.vertx.core.json.JsonArray) value).encode();
                return objectMapper.readValue(inner, payloadType);
            }
            // If the inner value is a simple scalar, attempt a direct conversion
            if (value instanceof Number || value instanceof CharSequence || value instanceof Boolean) {
                return objectMapper.convertValue(value, payloadType);
            }
        }

        // : For complex objects, use the configured ObjectMapper
        // instead of JsonObject.mapTo() to ensure consistent
        // serialization/deserialization
        // This fixes the Instant deserialization issue with Vert.x's
        // InstantDeserializer
        try {
            String jsonString = payload.encode();
            return objectMapper.readValue(jsonString, payloadType);
        } catch (Exception e) {
            logger.error("Failed to deserialize payload using ObjectMapper for type {}: {}",
                    payloadType.getSimpleName(), e.getMessage());
            logger.debug("Payload JSON: {}", payload.encode());
            throw e;
        }
    }

    /**
     * Parse headers from JsonObject to Map<String, String>.
     */
    private Map<String, String> parseHeadersFromJsonObject(JsonObject headers) {
        if (headers == null || headers.isEmpty())
            return new HashMap<>();

        Map<String, String> result = new HashMap<>();
        for (String key : headers.fieldNames()) {
            Object value = headers.getValue(key);
            result.put(key, value != null ? value.toString() : null);
        }
        return result;
    }
}
