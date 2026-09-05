package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.RejectedMessageException;
import dev.mars.peegeeq.api.messaging.ServerSideFilter;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.database.MetricsProvider;
import dev.mars.peegeeq.api.database.NoOpMetricsProvider;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Removed JDBC imports - no longer needed after migration to Vert.x 5.x reactive patterns
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox pattern message consumer implementation.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
public class OutboxConsumer<T> implements dev.mars.peegeeq.api.messaging.MessageConsumer<T> {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumer.class);
    private static final int PERSISTENCE_RECOVERY_RETRIES = 3;
    private static final long PERSISTENCE_RECOVERY_DELAY_MS = 100;

    private final PgClientFactory clientFactory;
    private final DatabaseService databaseService;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Class<T> payloadType;
    private final MetricsProvider metrics;
    private final PeeGeeQConfiguration configuration;
    private final OutboxConsumerConfig consumerConfig;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean claimInProgress = new AtomicBoolean(false);
    private final AtomicInteger processingInFlight = new AtomicInteger(0);
    // Per-consumer scan position, not an acknowledgement: rejected rows stay PENDING.
    // Wrap after an empty scan so changed filters can accept older rows later.
    private final AtomicLong filteredScanPosition = new AtomicLong(0);

    // Client ID for pool lookup - null means use default pool (resolved by
    // PgClientFactory)
    private final String clientId;

    // Tenant schema name  used to qualify all SQL table references
    private final String schemaName;

    // Vert.x instance for timer-based polling
    private final Vertx vertx;

    // Consumer group name for tracking which messages this consumer has processed
    private String consumerGroupName;
    private boolean enforceSubscriptionBounds;

    private MessageHandler<T> messageHandler;
    private volatile long pollingTimerId = -1;

    // Guards the transition from accepting poll cycles to closing. Every poll cycle
    // registered before close begins must finish its handler and terminal database
    // update before the owning factory allows the shared database pool to close.
    private final Object lifecycleLock = new Object();
    private final Set<Future<Void>> inFlightProcessing = new HashSet<>();
    private final Object groupOrderingLock = new Object();
    private final Map<String, Future<Void>> groupProcessingTails = new HashMap<>();
    private Future<Void> closeFuture;

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics) {
        this(clientFactory, objectMapper, topic, payloadType, metrics, null, null, null);
    }

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration) {
        this(clientFactory, objectMapper, topic, payloadType, metrics, configuration, null, null);
    }

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration, String clientId) {
        this(clientFactory, objectMapper, topic, payloadType, metrics, configuration, clientId, null);
    }

    public OutboxConsumer(PgClientFactory clientFactory, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration, String clientId, OutboxConsumerConfig consumerConfig) {
        this.clientFactory = clientFactory;
        this.databaseService = null;
        this.vertx = clientFactory.getConnectionManager().getVertx();
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = configuration;
        this.consumerConfig = consumerConfig;
        this.clientId = clientId; // null means use default pool
        // null schema  unqualified SQL (relies on search_path); non-null  schema-qualified SQL
        this.schemaName = configuration != null ? configuration.getDatabaseConfig().getSchema() : null;

        logger.info(
                "Created outbox consumer for topic: {} with configuration: {}, consumerConfig: {} (clientId: {})",
                topic, configuration != null ? "enabled" : "disabled",
                consumerConfig != null ? consumerConfig : "default",
                clientId != null ? clientId : "default");
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics) {
        this(databaseService, objectMapper, topic, payloadType, metrics, null, null, null);
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration) {
        this(databaseService, objectMapper, topic, payloadType, metrics, configuration, null, null);
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration, String clientId) {
        this(databaseService, objectMapper, topic, payloadType, metrics, configuration, clientId, null);
    }

    public OutboxConsumer(DatabaseService databaseService, ObjectMapper objectMapper,
            String topic, Class<T> payloadType, MetricsProvider metrics,
            PeeGeeQConfiguration configuration, String clientId, OutboxConsumerConfig consumerConfig) {
        this.clientFactory = null;
        this.databaseService = databaseService;
        this.vertx = Objects.requireNonNull(databaseService.getVertx(),
                "DatabaseService.getVertx() must not return null  provide a running Vertx instance");
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.payloadType = payloadType;
        this.metrics = metrics != null ? metrics : NoOpMetricsProvider.INSTANCE;
        this.configuration = configuration;
        this.consumerConfig = consumerConfig;
        this.clientId = clientId; // null means use default pool
        // null schema  unqualified SQL (relies on search_path); non-null  schema-qualified SQL
        this.schemaName = configuration != null ? configuration.getDatabaseConfig().getSchema() : null;

        logger.info(
                "Created outbox consumer for topic: {} (using DatabaseService) with configuration: {}, consumerConfig: {} (clientId: {})",
                topic, configuration != null ? "enabled" : "disabled",
                consumerConfig != null ? consumerConfig : "default",
                clientId != null ? clientId : "default");
    }

    /**
     * Sets the consumer group name for this consumer.
     * This is used to track which messages have been processed by which consumer
     * groups.
     *
     * @param consumerGroupName The name of the consumer group
     */
    public void setConsumerGroupName(String consumerGroupName) {
        this.consumerGroupName = consumerGroupName;
        logger.info("Set consumer group name to '{}' for topic '{}'", consumerGroupName, topic);
    }

    /**
     * Restricts claims to the start boundary stored for this consumer group in
     * {@code outbox_topic_subscriptions}. Must be configured before subscription.
     */
    void enforceSubscriptionBounds() {
        this.enforceSubscriptionBounds = true;
    }

    @Override
    public Future<Void> subscribe(MessageHandler<T> handler) {
        if (closed.get()) {
            return Future.failedFuture(new IllegalStateException("Consumer is closed"));
        }

        logger.info("Subscribing to topic: {} with handler: {}", topic, handler.getClass().getSimpleName());

        this.messageHandler = handler;
        boolean wasSubscribed = subscribed.compareAndSet(false, true);

        if (wasSubscribed) {
            logger.info("Starting polling for topic: {}", topic);
            startPolling();
            logger.info("Subscribed to topic: {}", topic);
        } else {
            logger.warn("Already subscribed to topic: {}", topic);
        }
        return Future.succeededFuture();
    }

    @Override
    public void unsubscribe() {
        if (subscribed.compareAndSet(true, false)) {
            logger.info("Unsubscribed from topic: {}", topic);
        }
    }

    private void startPolling() {
        // Get polling interval - consumerConfig takes precedence over configuration
        Duration pollingInterval = getEffectivePollingInterval();

        long pollingIntervalMs = pollingInterval.toMillis();

        logger.info("Starting polling for topic {} with interval: {} ms", topic, pollingIntervalMs);

        // Use Vert.x periodic timer for polling automatically canceled on vertx.close()
        // Fire an initial poll immediately, then schedule periodic
        vertx.runOnContext(v -> scheduledProcessMessages());
        pollingTimerId = vertx.setPeriodic(pollingIntervalMs, id -> scheduledProcessMessages());

        logger.info("Scheduled Vert.x periodic timer for topic {} with interval: {}", topic, pollingInterval);
    }

    private void scheduledProcessMessages() {
        Future<Void> processing;
        synchronized (lifecycleLock) {
            if (!subscribed.get() || closed.get()) {
                logger.debug("Skipping message processing - subscribed: {}, closed: {} for topic {}",
                        subscribed.get(), closed.get(), topic);
                return;
            }
            logger.debug("Processing available messages for topic {}", topic);

            // Register the Future while holding the lifecycle lock so closeAsync()
            // cannot miss a poll cycle that passed the active-state check.
            try {
                processing = processAvailableMessages();
                inFlightProcessing.add(processing);
            } catch (Exception e) {
                logger.error("Failed to start reactive message processing for topic {}: {}", topic, e.getMessage(), e);
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
                .onSuccess(result -> logger.debug("Successfully processed messages for topic {}", topic))
                .onFailure(error -> {
                    if (isShutdownRelatedError(error)) {
                        logger.debug("Reactive message processing skipped (shutdown) for topic {}: {}", topic,
                                error.getMessage());
                    } else {
                        logger.error("Reactive message processing failed for topic {}: {}", topic,
                                error.getMessage(), error);
                    }
                });
    }

    /**
     * Processes available messages using Vert.x 5.x patterns.
     */
    private Future<Void> processAvailableMessages() {
        logger.debug("OUTBOX-DEBUG: processAvailableMessages() called for topic: {}", topic);
        // Check if consumer is closed to prevent infinite retry loops during shutdown
        if (closed.get()) {
            logger.debug("OUTBOX-DEBUG: Skipping message processing - consumer closed for topic {}", topic);
            return Future.succeededFuture();
        }

        logger.debug("OUTBOX-DEBUG: Consumer is active, proceeding with message processing for topic: {}", topic);

        int reservedCapacity = 0;
        try {
            int batchSize = getEffectiveBatchSize();
            int maxThreads = getEffectiveConsumerThreads();

            // Only one claim query may select the next ordered rows at a time. Handler
            // pipelines may still overlap up to consumerThreads after the claim settles.
            if (!claimInProgress.compareAndSet(false, true)) {
                logger.debug("A message claim is already in progress for topic {}; skipping this poll", topic);
                return Future.succeededFuture();
            }

            reservedCapacity = reserveProcessingCapacity(maxThreads, batchSize);
            if (reservedCapacity == 0) {
                claimInProgress.set(false);
                logger.debug("No remaining handler capacity for topic {} ({}/{})",
                        topic, processingInFlight.get(), maxThreads);
                return Future.succeededFuture();
            }
            int admittedCapacity = reservedCapacity;

            // Build one atomic claim statement. Subscription bounds and payload
            // filters must both be applied inside the locked subquery so excluded
            // rows remain PENDING for other subscriptions.
            ServerSideFilter filter = consumerConfig != null ? consumerConfig.getServerSideFilter() : null;
            String sqOutbox = qualifyTable("outbox");
            List<Object> queryParams = new ArrayList<>();
            queryParams.add(OffsetDateTime.now());
            queryParams.add(topic);
            queryParams.add(admittedCapacity);

            StringBuilder additionalConditions = new StringBuilder();
            int nextParameter = 4;
            if (enforceSubscriptionBounds) {
                String sqSubscriptions = qualifyTable("outbox_topic_subscriptions");
                additionalConditions.append("""
                          AND EXISTS (
                              SELECT 1
                              FROM %s subscription
                              WHERE subscription.topic = $2
                                AND subscription.group_name = $%d
                                AND (subscription.start_from_message_id IS NULL
                                     OR outbox_message.id >= subscription.start_from_message_id)
                                AND (subscription.start_from_timestamp IS NULL
                                     OR outbox_message.created_at >= subscription.start_from_timestamp)
                          )
                        """.formatted(sqSubscriptions, nextParameter));
                queryParams.add(consumerGroupName);
                nextParameter++;
            }

            if (filter != null) {
                String filterCondition = filter.toSqlCondition(nextParameter);
                additionalConditions.append(" AND ").append(filterCondition).append('\n');
                queryParams.addAll(filter.getParameters());
                nextParameter += filter.getParameters().size();
                logger.debug("OUTBOX-DEBUG: Using server-side filter: {}, SQL filter: {}", filter, filterCondition);
            }

            long scanPosition = filteredScanPosition.get();
            if (scanPosition > 0) {
                additionalConditions.append(" AND outbox_message.id > $")
                        .append(nextParameter).append('\n');
                queryParams.add(scanPosition);
            }

            String sql = """
                    UPDATE %s
                    SET status = 'PROCESSING', processed_at = $1
                    WHERE id IN (
                        SELECT outbox_message.id
                        FROM %s outbox_message
                        WHERE outbox_message.topic = $2
                          AND outbox_message.status = 'PENDING'
                    %s    ORDER BY outbox_message.created_at ASC, outbox_message.id ASC
                        LIMIT $3
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, payload, headers, correlation_id, message_group, created_at,
                              EXTRACT(EPOCH FROM (now() - created_at)) * 1000.0 AS delivery_latency_ms
                    """.formatted(sqOutbox, sqOutbox, additionalConditions);
            Tuple params = Tuple.from(queryParams);

            return getReactivePoolFuture()
                    .compose(pool -> pool.preparedQuery(sql).execute(params))
                    .transform(claimResult -> {
                        claimInProgress.set(false);
                        if (claimResult.failed()) {
                            releaseProcessingCapacity(admittedCapacity);
                            return Future.<Void>failedFuture(claimResult.cause());
                        }

                        var rowSet = claimResult.result();
                        int claimedMessages = rowSet.size();
                        if (claimedMessages > admittedCapacity) {
                            releaseProcessingCapacity(admittedCapacity);
                            return Future.<Void>failedFuture(new IllegalStateException(
                                    "Claim returned " + claimedMessages + " rows after reserving "
                                            + admittedCapacity + " handler slots for topic " + topic));
                        }
                        releaseProcessingCapacity(admittedCapacity - claimedMessages);
                        if (rowSet.size() == 0) {
                            filteredScanPosition.compareAndSet(scanPosition, 0);
                            logger.debug("No pending messages found for topic {}", topic);
                            return Future.<Void>succeededFuture();
                        }

                        logger.debug("Found {} messages to process for topic {}", rowSet.size(), topic);

                        List<Row> orderedRows = new ArrayList<>(claimedMessages);
                        for (Row row : rowSet) {
                            orderedRows.add(row);
                        }
                        orderedRows.sort(Comparator.comparingLong(row -> row.getLong("id")));

                        List<Future<Void>> messagePipelines = new ArrayList<>(claimedMessages);
                        for (Row row : orderedRows) {
                            messagePipelines.add(processReservedRow(row));
                        }
                        return Future.join(messagePipelines).mapEmpty();
                    })
                    .onFailure(error -> {
                        if (isShutdownRelatedError(error)) {
                            logger.debug("Expected error during shutdown for topic {}: {}", topic, error.getMessage());
                        } else {
                            logger.error("Error querying messages for topic {}: {}", topic, error.getMessage());
                        }
                    });

        } catch (Exception e) {
            claimInProgress.set(false);
            if (reservedCapacity > 0) {
                releaseProcessingCapacity(reservedCapacity);
            }
            if (isShutdownRelatedError(e)) {
                logger.debug("Expected error during shutdown for topic {}: {}", topic, e.getMessage());
                if (!closed.get()) {
                    closed.set(true);
                }
            } else {
                logger.error("Failed to process messages reactively for topic {}: {}", topic, e.getMessage(), e);
            }
            return Future.failedFuture(e);
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
            throw new IllegalArgumentException("Handler capacity release cannot be negative: " + capacity);
        }

        while (true) {
            int current = processingInFlight.get();
            if (current < capacity) {
                throw new IllegalStateException(
                        "Cannot release " + capacity + " handler slots when only " + current
                                + " are reserved for topic " + topic);
            }
            if (processingInFlight.compareAndSet(current, current - capacity)) {
                return;
            }
        }
    }

    private Future<Void> processReservedRow(Row row) {
        Future<Void> processing;
        try {
            processing = processRowInGroupOrder(row);
            if (processing == null) {
                processing = Future.failedFuture(
                        new IllegalStateException("Reserved row processing returned null Future"));
            }
        } catch (Exception error) {
            processing = Future.failedFuture(error);
        }

        return processing.eventually(() -> {
            releaseProcessingCapacity(1);
            return Future.succeededFuture();
        }).compose(v -> {
            // Refill released capacity while draining a backlog. The periodic timer
            // is for idle scans; it must not throttle every individual message.
            // Queue the continuation to avoid recursive completion on the event loop.
            synchronized (lifecycleLock) {
                if (subscribed.get() && !closed.get()) {
                    vertx.runOnContext(ignored -> scheduledProcessMessages());
                }
            }
            return Future.succeededFuture();
        });
    }

    private Future<Void> processRowInGroupOrder(Row row) {
        String messageGroup = row.getString("message_group");
        if (messageGroup == null) {
            return processRow(row);
        }

        Future<Void> orderedProcessing;
        synchronized (groupOrderingLock) {
            Future<Void> previous = groupProcessingTails.get(messageGroup);
            orderedProcessing = previous == null
                    ? processRow(row)
                    : previous.transform(ignored -> processRow(row));
            groupProcessingTails.put(messageGroup, orderedProcessing);
        }

        return orderedProcessing.eventually(() -> {
            synchronized (groupOrderingLock) {
                if (groupProcessingTails.get(messageGroup) == orderedProcessing) {
                    groupProcessingTails.remove(messageGroup);
                }
            }
            return Future.succeededFuture();
        });
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Returns a fully-qualified table reference when a schema name is configured,
     * or an unqualified table name when schema is null (relies on connection search_path).
     */
    private String qualifyTable(String tableName) {
        return schemaName != null ? quoteIdentifier(schemaName) + "." + tableName : tableName;
    }

    private boolean isShutdownRelatedError(Throwable error) {
        if (closed.get()) {
            return true;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RejectedExecutionException
                    || cause instanceof ClosedChannelException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Processes a single row from the database.
     */
    private Future<Void> processRow(Row row) {
        try {
            String messageId = String.valueOf(row.getLong("id"));
            JsonObject payloadJson = row.getJsonObject("payload");
            JsonObject headersJson = row.getJsonObject("headers");
            String correlationId = row.getString("correlation_id");

            // Delivery latency (telemetry G2): enqueue → claim, computed by the
            // claim statement on the DATABASE clock (now() - created_at), so no
            // JVM/DB clock skew enters the measurement. Recorded for every
            // claimed message — delivery happened even if processing fails.
            Double deliveryLatencyMs = row.getDouble("delivery_latency_ms");
            if (deliveryLatencyMs != null && deliveryLatencyMs >= 0) {
                metrics.recordMessageDeliveryLatency(topic, "outbox",
                        java.time.Duration.ofNanos(Math.round(deliveryLatencyMs * 1_000_000.0)));
            }

            T payload = parsePayloadFromJsonObject(payloadJson);
            Map<String, String> headers = parseHeadersFromJsonObject(headersJson);

            // Add correlation ID to headers if present
            if (correlationId != null) {
                headers.put("correlationId", correlationId);
            }

            Message<T> message = new OutboxMessage<>(messageId, payload,
                    row.getLocalDateTime("created_at").toInstant(java.time.ZoneOffset.UTC), headers);

            // Extract traceparent from message headers for proper trace propagation
            String traceparent = headers.get("traceparent");
            TraceCtx traceCtx = TraceContextUtil.parseOrCreate(traceparent);

            // If consumer group is set, create a child span for fan-out trace propagation
            if (consumerGroupName != null) {
                traceCtx = traceCtx.childSpan("consumer-group:" + consumerGroupName + "/process");
            }

            // Set MDC for trace context before processing intentionally NOT using
            // try-with-resources because async processing needs MDC values to persist
            // across the handler call. Cleanup happens in the eventually() block via
            // clearTraceMDC().
            TraceContextUtil.mdcScope(traceCtx);
            TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, messageId);
            TraceContextUtil.setMDC(TraceContextUtil.MDC_TOPIC, topic);
            if (consumerGroupName != null) {
                TraceContextUtil.setMDC(TraceContextUtil.MDC_CONSUMER_GROUP, consumerGroupName);
            }
            if (correlationId != null) {
                TraceContextUtil.setMDC(TraceContextUtil.MDC_CORRELATION_ID, correlationId);
            }

            // Process message reactively handler and all downstream operations
            // return Future<Void>, so no worker thread is needed.
            return processMessageWithCompletion(message, messageId)
                    .transform(ar -> {
                        if (ar.failed()) {
                            String persistenceError = ar.cause().getMessage() != null
                                    ? ar.cause().getMessage()
                                    : ar.cause().getClass().getSimpleName();
                            logger.error(
                                    "Failed to persist terminal state for message {} on topic {}: {} - resetting for retry",
                                    messageId, topic, persistenceError, ar.cause());
                            return resetMessageAfterPersistenceFailure(messageId, persistenceError);
                        }
                        return Future.succeededFuture();
                    })
                    .eventually(() -> {
                        TraceContextUtil.clearTraceMDC();
                        return Future.succeededFuture();
                    });

        } catch (Exception e) {
            logger.error("Failed to process row for topic {}: {}", topic, e.getMessage(), e);
            return Future.failedFuture(e);
        }
    }

    /**
     * Restores a claimed message to PENDING when its completion, retry, or dead-letter
     * persistence operation fails. A bounded retry allows the reactive pool to discard
     * and replace a connection that was lost during the failed operation.
     */
    private Future<Void> resetMessageAfterPersistenceFailure(String messageId, String errorMessage) {
        return resetMessageAfterPersistenceFailure(
                messageId,
                errorMessage,
                PERSISTENCE_RECOVERY_RETRIES);
    }

    private Future<Void> resetMessageAfterPersistenceFailure(
            String messageId,
            String errorMessage,
            int remainingRetries) {
        String sql = "UPDATE %s SET status = 'PENDING', processed_at = NULL, error_message = $1 WHERE id = $2"
                .formatted(qualifyTable("outbox"));
        Tuple params = Tuple.of(
                "Terminal persistence failure: " + errorMessage,
                Long.parseLong(messageId));

        return getReactivePoolFuture()
                .compose(pool -> pool.preparedQuery(sql).execute(params))
                .compose(result -> {
                    if (result.rowCount() == 0) {
                        // A concurrent delete leaves no PROCESSING row to strand. The
                        // failed completion remains observable through its metric/log,
                        // but terminal-state recovery has no remaining work to persist.
                        logger.info(
                                "Message {} no longer exists after terminal persistence failure; no reset required",
                                messageId);
                    } else {
                        logger.info("Reset message {} to PENDING after terminal persistence failure", messageId);
                    }
                    return Future.succeededFuture();
                })
                .transform(result -> {
                    if (result.succeeded()) {
                        return Future.succeededFuture();
                    }
                    if (remainingRetries == 0) {
                        logger.error(
                                "Failed to reset message {} after terminal persistence failure; "
                                        + "stuck-message recovery is required",
                                messageId, result.cause());
                        return Future.failedFuture(result.cause());
                    }
                    logger.warn(
                            "Failed to reset message {} after terminal persistence failure; retrying in {} ms "
                                    + "({} retries remaining): {}",
                            messageId,
                            PERSISTENCE_RECOVERY_DELAY_MS,
                            remainingRetries,
                            result.cause().getMessage());
                    return vertx.timer(PERSISTENCE_RECOVERY_DELAY_MS)
                            .compose(v -> resetMessageAfterPersistenceFailure(
                                    messageId,
                                    errorMessage,
                                    remainingRetries - 1));
                });
    }

    /**
     * Processes a message and marks it as completed when done.
     */
    private Future<Void> processMessageWithCompletion(Message<T> message, String messageId) {
        logger.debug("Processing message {} from topic {} in thread {}",
                messageId, topic, Thread.currentThread().getName());

        Instant processingStart = Instant.now();

        // Wrap the message handler call in try-catch to handle both:
        // 1. Direct exceptions thrown from the handler method
        // 2. Failed futures returned by the handler
        // 3. Null returns from the handler method
        Future<Void> processingFuture;
        try {
            processingFuture = messageHandler.handle(message);

            // Handle null return from message handler
            if (processingFuture == null) {
                logger.warn("Message handler returned null Future for message {}: treating as failure",
                        messageId);
                processingFuture = Future.failedFuture(
                        new IllegalStateException("Message handler returned null Future"));
            }
        } catch (Throwable directFailure) {
            // User handlers may throw an Error as well as an Exception. Keep every
            // synchronous handler failure inside the reactive retry/DLQ pipeline.
            logger.debug("Message handler threw direct failure for message {}: {}",
                    messageId, directFailure.getMessage());
            processingFuture = Future.failedFuture(directFailure);
        }

        return processingFuture
            .transform(ar -> {
                if (ar.succeeded()) {
                    // Record successful processing metrics
                    Duration processingTime = Duration.between(processingStart, Instant.now());
                    metrics.recordMessageReceived(topic);
                    metrics.recordMessageProcessed(topic, processingTime);

                    // Completion persistence failures must propagate to processRow's
                    // terminal-state recovery. They are not handler failures and must
                    // never consume the message retry budget.
                    return markMessageCompleted(messageId)
                            .onSuccess(ignored -> logger.debug(
                                    "Successfully processed message {} for consumer group {}",
                                    messageId,
                                    consumerGroupName));
                }
                Throwable error = ar.cause();
                Throwable rootCause = error;
                if (error instanceof CompletionException && error.getCause() != null) {
                    rootCause = error.getCause();
                }

                // Group-filter rejected messages should be reset to PENDING so other
                // consumer groups can still process them. The rejection is group-level,
                // not message-level it must not affect the global outbox status.
                if (rootCause instanceof RejectedMessageException) {
                    logger.debug("Message {} rejected by consumer group filter, resetting to PENDING: {}",
                            messageId, rootCause.getMessage());
                    return resetFilteredMessageToPending(messageId);
                }

                // Filtered messages should be reset to PENDING, not treated as failures
                if (rootCause instanceof MessageFilteredException) {
                    logger.debug("Message {} filtered by consumer group, resetting to PENDING: {}",
                            messageId, rootCause.getMessage());
                    return resetFilteredMessageToPending(messageId);
                }

                logger.warn("Message processing failed for {} in consumer group {}: {}",
                        messageId, consumerGroupName, rootCause.getMessage());

                // Record failed message metrics
                metrics.recordMessageFailed(topic, rootCause.getClass().getSimpleName());

                // Handle retry/dead-letter persistence and wait for durability.
                String failureReason = rootCause.getClass().getSimpleName() + ": "
                        + (rootCause.getMessage() != null ? rootCause.getMessage() : "No message");
                return handleMessageFailureWithRetry(messageId, failureReason);
            });
    }


    /**
     * Marks a message as completed using Vert.x reactive patterns.
     * CRITICAL: For financial systems, completion MUST be guaranteed or message
     * reprocessed.
     */
    private Future<Void> markMessageCompleted(String messageId) {
        String sql = "UPDATE %s SET status = 'COMPLETED', processed_at = $1 WHERE id = $2"
                .formatted(qualifyTable("outbox"));

        return getReactivePoolFuture()
                .compose(pool -> pool.preparedQuery(sql)
                        .execute(Tuple.of(OffsetDateTime.now(), Long.parseLong(messageId))))
                .compose(result -> {
                    if (result.rowCount() == 0) {
                        return Future.failedFuture(new IllegalStateException(
                                "Completion update affected 0 rows for message " + messageId));
                    }
                    logger.debug("Successfully marked message {} as completed", messageId);
                    return Future.succeededFuture();
                })
                .onFailure(error -> {
                    if (isShutdownRelatedError(error)) {
                        logger.warn(
                                "Shutdown during completion of message {} - message may be stuck in PROCESSING",
                                messageId);
                    } else {
                        logger.error(
                                "CRITICAL: Failed to mark message {} as completed: {} - MESSAGE MAY BE REPROCESSED",
                                messageId, error.getMessage());
                        metrics.recordMessageFailed(topic, "COMPLETION_FAILURE");
                    }
                })
                .mapEmpty();
    }

    /**
     * Resets a filtered message back to PENDING so it can be picked up by other
     * consumer groups. This is used when a consumer group's filter rejects a
     * message the message is not completed or failed, just not relevant to
     * this group.
     */
    private Future<Void> resetFilteredMessageToPending(String messageId) {
        String sql = "UPDATE %s SET status = 'PENDING', processed_at = NULL WHERE id = $1"
                .formatted(qualifyTable("outbox"));

        return getReactivePoolFuture()
                .compose(pool -> pool.preparedQuery(sql)
                        .execute(Tuple.of(Long.parseLong(messageId))))
                .compose(result -> {
                    filteredScanPosition.accumulateAndGet(Long.parseLong(messageId), Math::max);
                    logger.debug("Reset filtered message {} to PENDING", messageId);
                    return Future.succeededFuture();
                })
                .onFailure(error -> {
                    if (closed.get()) {
                        logger.debug("Pool closed during filtered message reset for {} - expected during shutdown",
                                messageId);
                    } else {
                        logger.warn("Failed to reset filtered message {} to PENDING: {}", messageId,
                                error.getMessage());
                    }
                })
                .mapEmpty();
    }

    // Removed deprecated resetMessageStatus(Connection, String) method - JDBC usage
    // has been deprecated
    // Message status operations should now use reactive patterns with
    // getReactivePoolFuture()

    /**
     * Handles message failure with proper retry logic and max retries checking
     * using Vert.x reactive patterns.
     */
    private Future<Void> handleMessageFailureWithRetry(String messageId, String errorMessage) {
        String selectSql = "SELECT retry_count, max_retries FROM %s WHERE id = $1"
                .formatted(qualifyTable("outbox"));

        return getReactivePoolFuture()
                .compose(pool -> pool.preparedQuery(selectSql)
                        .execute(Tuple.of(Long.parseLong(messageId))))
                .compose(result -> {
                    if (result.size() > 0) {
                        io.vertx.sqlclient.Row row = result.iterator().next();
                        int currentRetryCount = row.getInteger("retry_count") != null ? row.getInteger("retry_count")
                                : 0;

                        int maxRetries = getEffectiveMaxRetries();

                        // If no config provided, check database for max_retries
                        if (consumerConfig == null && configuration == null) {
                            Integer dbMaxRetries = row.getInteger("max_retries");
                            if (dbMaxRetries != null && dbMaxRetries > 0) {
                                maxRetries = dbMaxRetries;
                            }
                        }

                        logger.debug("Message {} failure handling: currentRetryCount={}, maxRetries={}",
                                messageId, currentRetryCount, maxRetries);

                        if (currentRetryCount >= maxRetries) {
                            return storeDeadLetterMessage(messageId, currentRetryCount, errorMessage);
                        } else {
                            return incrementRetryAndReset(messageId, currentRetryCount, errorMessage);
                        }
                    } else {
                        logger.warn("Message {} not found when handling failure", messageId);
                        return Future.succeededFuture();
                    }
                })
                .onFailure(error -> {
                    if (isShutdownRelatedError(error)) {
                        logger.debug(
                                "Expected error during shutdown for message {} failure handling",
                                messageId);
                    } else {
                        logger.warn("Failed to handle message failure for {}: {}", messageId, error.getMessage());
                    }
                })
                .mapEmpty();
    }

    // Removed deprecated resetMessageStatusAsync() method - JDBC usage has been
    // deprecated
    // Message status operations should now use reactive patterns with
    // getReactivePoolFuture()

    // Removed deprecated incrementRetryAndReset(Connection, String, int, String)
    // method - JDBC usage has been deprecated
    // Retry operations should now use reactive patterns with
    // getReactivePoolFuture()

    // Removed deprecated moveToDeadLetterQueue(Connection, String, int, String)
    // method - JDBC usage has been deprecated
    // Dead letter queue operations should now use reactive patterns with
    // getReactivePoolFuture()

    /**
     * Increments retry count and resets message for retry using Vert.x reactive
     * patterns.
     */
    private Future<Void> incrementRetryAndReset(String messageId, int currentRetryCount, String errorMessage) {
        String sql = "UPDATE %s SET retry_count = $1, status = 'PENDING', processed_at = NULL, error_message = $2 WHERE id = $3"
                .formatted(qualifyTable("outbox"));

        return getReactivePoolFuture()
                .compose(pool -> pool.preparedQuery(sql)
                        .execute(Tuple.of(currentRetryCount + 1, errorMessage, Long.parseLong(messageId))))
                .onSuccess(result -> {
                    logger.debug("Incremented retry count to {} and reset message {} for retry",
                            currentRetryCount + 1, messageId);
                })
                .onFailure(error -> {
                    if (isShutdownRelatedError(error)) {
                        logger.debug(
                                "Expected error during shutdown for message {} retry increment",
                                messageId);
                    } else {
                        logger.warn("Failed to increment retry count for message {}: {}", messageId,
                                error.getMessage());
                    }
                })
                .mapEmpty();
    }

    /**
     * Moves a message to dead letter queue after max retries exceeded using Vert.x
     * reactive patterns.
     */
    private Future<Void> storeDeadLetterMessage(String messageId, int retryCount, String errorMessage) {
        String selectSql = "SELECT topic, payload, created_at, headers, correlation_id, message_group FROM %s WHERE id = $1"
                .formatted(qualifyTable("outbox"));

        return getReactivePoolFuture()
                .compose(pool -> pool.preparedQuery(selectSql)
                        .execute(Tuple.of(Long.parseLong(messageId)))
                        .compose(result -> {
                            if (result.size() > 0) {
                                io.vertx.sqlclient.Row row = result.iterator().next();
                                String topic = row.getString("topic");
                                JsonObject payload = row.getJsonObject("payload");
                                java.time.LocalDateTime createdAtLocal = row.getLocalDateTime("created_at");
                                java.time.OffsetDateTime createdAt = createdAtLocal.atOffset(java.time.ZoneOffset.UTC);
                                JsonObject headers = row.getJsonObject("headers");
                                String correlationId = row.getString("correlation_id");
                                String messageGroup = row.getString("message_group");

                                if (topic != null) {
                                    // Vert.x Pool.withTransaction() automatically handles event loop context
                                    // No explicit executeOnVertxContext wrapper needed - Pool manages this
                                    // internally
                                    return pool.withTransaction(client -> {
                                        String insertSql = """
                                                INSERT INTO %s (original_table, original_id, topic, payload,
                                                                original_created_at, failure_reason, retry_count,
                                                                headers, correlation_id, message_group)
                                                VALUES ('outbox', $1, $2, $3::jsonb, $4, $5, $6, $7::jsonb, $8, $9)
                                                """.formatted(qualifyTable("dead_letter_queue"));

                                        return client.preparedQuery(insertSql)
                                                .execute(Tuple.of(
                                                        Long.parseLong(messageId), topic, payload, createdAt,
                                                        errorMessage, retryCount, headers, correlationId, messageGroup))
                                                .compose(insertResult -> {
                                                    String updateSql = "UPDATE %s SET status = 'DEAD_LETTER', error_message = $1 WHERE id = $2"
                                                            .formatted(qualifyTable("outbox"));
                                                    return client.preparedQuery(updateSql)
                                                            .execute(Tuple.of(errorMessage, Long.parseLong(messageId)));
                                                });
                                    })
                                            .onSuccess(updateResult -> {
                                                logger.info("Moved message {} to dead letter queue after {} retries",
                                                        messageId, retryCount);
                                            })
                                            .onFailure(error -> {
                                                if (isShutdownRelatedError(error)) {
                                                    logger.debug(
                                                            "Expected error during shutdown for message {} dead letter queue operation",
                                                            messageId);
                                                } else {
                                                    logger.error("Failed to move message {} to dead letter queue: {}",
                                                            messageId, error.getMessage());
                                                }
                                            })
                                            .mapEmpty();
                                }
                            } else {
                                logger.warn("Message {} not found when trying to move to dead letter queue", messageId);
                            }
                            return Future.succeededFuture();
                        }))
                .onFailure(error -> {
                    if (isShutdownRelatedError(error)) {
                        logger.debug(
                                "Expected error during shutdown for message {} details retrieval",
                                messageId);
                    } else {
                        logger.error("Failed to retrieve message {} details for dead letter queue: {}", messageId,
                                error.getMessage());
                    }
                })
                .mapEmpty();
    }

    // Removed deprecated deleteMessage() method - JDBC usage has been deprecated
    // Message deletion should now use reactive patterns with
    // getReactivePoolFuture().compose(...)

    // Removed deprecated resetMessageStatus() method - JDBC usage has been
    // deprecated
    // Message status operations should now use reactive patterns with
    // getReactivePoolFuture().compose(...)

    // Removed deprecated getDataSource() method - JDBC usage has been deprecated in
    // favor of Vert.x 5.x reactive patterns
    // All database operations should now use getReactivePoolFuture().compose(...)
    // for better performance and consistency

    @Override
    public void close() {
        closeAsync().onFailure(error ->
                logger.error("Failed to close outbox consumer for topic {}", topic, error));
    }

    /**
     * Stops new polling and completes only after every poll cycle that already
     * started has finished its handler and terminal database update.
     *
     * @return a shared Future representing this consumer's close operation
     */
    public Future<Void> closeAsync() {
        synchronized (lifecycleLock) {
            if (closeFuture != null) {
                return closeFuture;
            }

            closed.set(true);
            unsubscribe();

            // Cancel Vert.x periodic timer
            if (pollingTimerId != -1) {
                vertx.cancelTimer(pollingTimerId);
                pollingTimerId = -1;
            }

            List<Future<Void>> processingSnapshot = new ArrayList<>(inFlightProcessing);
            Future<Void> awaitProcessing = processingSnapshot.isEmpty()
                    ? Future.succeededFuture()
                    : Future.join(processingSnapshot).mapEmpty();

            closeFuture = awaitProcessing
                    .onSuccess(ignored -> logger.info("Closed outbox consumer for topic: {}", topic))
                    .onFailure(error -> logger.error(
                            "Failed while closing outbox consumer for topic {}", topic, error));
            return closeFuture;
        }
    }

    /**
     * Reactive acquisition of the pool without blocking.
     * Uses clientId for pool lookup - null clientId is resolved to the default pool
     * by PgClientFactory.
     */
    private Future<Pool> getReactivePoolFuture() {
        // clientId can be null - PgClientFactory/ConnectionProvider resolves null to
        // the default pool
        if (databaseService != null) {
            var connectionProvider = databaseService.getConnectionProvider();
            if (connectionProvider == null) {
                return Future.failedFuture(new IllegalStateException(
                        "ConnectionProvider is not available database service may be shutting down"));
            }
            return connectionProvider.getReactivePool(clientId);
        }
        if (clientFactory != null) {
            try {
                var connectionConfig = clientFactory.getConnectionConfig(clientId);
                var poolConfig = clientFactory.getPoolConfig(clientId);
                if (connectionConfig == null) {
                    String poolName = clientId != null ? clientId : "default";
                    return Future.failedFuture(
                            new IllegalStateException("Connection configuration '" + poolName + "' not found"));
                }
                if (poolConfig == null) {
                    poolConfig = new dev.mars.peegeeq.db.config.PgPoolConfig.Builder().build();
                }
                // Use clientId for pool creation - null is resolved to default by
                // PgConnectionManager
                String resolvedClientId = clientId != null ? clientId
                        : dev.mars.peegeeq.db.PeeGeeQDefaults.DEFAULT_POOL_ID;
                Pool pool = clientFactory.getConnectionManager()
                        .getOrCreateReactivePool(resolvedClientId, connectionConfig, poolConfig);
                return Future.succeededFuture(pool);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }
        return Future.failedFuture(new IllegalStateException("No client factory or database service available"));
    }

    /**
     * Parse payload from JsonObject back to the expected type.
     * Handles both simple values (wrapped in {"value": ...}) and complex objects.
     *
     * : Use the same ObjectMapper that was used for serialization
     * instead of JsonObject.mapTo() which uses Vert.x's internal ObjectMapper.
     * This ensures consistent Instant/LocalDateTime serialization/deserialization.
     */
    private T parsePayloadFromJsonObject(JsonObject payload) throws Exception {
        if (payload == null)
            return null;

        // Check if this is a simple value wrapped in {"value": ...}
        if (payload.size() == 1 && payload.containsKey("value")) {
            Object value = payload.getValue("value");
            if (payloadType.isInstance(value)) {
                @SuppressWarnings("unchecked")
                T result = (T) value;
                return result;
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

    // Helper methods to get effective configuration values
    // OutboxConsumerConfig takes precedence over PeeGeeQConfiguration

    private Duration getEffectivePollingInterval() {
        if (consumerConfig != null) {
            return consumerConfig.getPollingInterval();
        }
        if (configuration != null) {
            return configuration.getQueueConfig().getPollingInterval();
        }
        return Duration.ofMillis(500); // default
    }

    private int getEffectiveBatchSize() {
        if (consumerConfig != null) {
            return consumerConfig.getBatchSize();
        }
        if (configuration != null) {
            return configuration.getQueueConfig().getBatchSize();
        }
        return 1; // default
    }

    private int getEffectiveConsumerThreads() {
        if (consumerConfig != null) {
            return consumerConfig.getConsumerThreads();
        }
        if (configuration != null) {
            return configuration.getQueueConfig().getConsumerThreads();
        }
        return 1; // default
    }

    private int getEffectiveMaxRetries() {
        if (consumerConfig != null) {
            return consumerConfig.getMaxRetries();
        }
        if (configuration != null) {
            return configuration.getQueueConfig().getMaxRetries();
        }
        return 3; // default
    }
}
