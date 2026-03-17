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

package dev.mars.peegeeq.bitemporal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Reactive notification handler for bi-temporal events using pure Vert.x
 * patterns.
 * 
 * This class replaces the JDBC-based PgListenerConnection with reactive Vert.x
 * LISTEN/NOTIFY functionality, following the patterns established in
 * peegeeq-native.
 *
 * Requirements:
 * - Uses Vert.x PgConnection for reactive LISTEN/NOTIFY
 * - Proper error handling with automatic reconnection
 * - Non-blocking notification processing
 * - Context-aware execution for TransactionPropagation compatibility
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-07
 * @version 1.0
 */
public class ReactiveNotificationHandler<T> {
    private static final Logger logger = LoggerFactory.getLogger(ReactiveNotificationHandler.class);
    private static final int MAX_EVENT_TYPE_LENGTH = 50;
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile(
            "^(?:[a-zA-Z0-9_]+|\\*)(?:\\.(?:[a-zA-Z0-9_]+|\\*))*$");

    private final Vertx vertx;
    private final PgConnectOptions connectOptions; // Now final - immutable construction
    private final ObjectMapper objectMapper;
    private final Function<String, Future<BiTemporalEvent<T>>> eventRetriever;
    private final String schema;
    private final String tableName;

    // Connection management
    private volatile PgConnection listenConnection;
    private volatile boolean active = false;
    private volatile boolean shutdown = false;
    private volatile Future<Void> startInProgress;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long BASE_RECONNECT_DELAY = 1000; // 1 second

    // Subscription management
    private final Map<SubscriptionKey, CopyOnWriteArrayList<MessageHandler<BiTemporalEvent<T>>>> subscriptions = new ConcurrentHashMap<>();
    private final Set<String> listeningChannels = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new ReactiveNotificationHandler.
     * Following peegeeq-native patterns.
     *
     * @param vertx          The Vertx instance for reactive operations
     * @param connectOptions PostgreSQL connection options
     * @param objectMapper   JSON object mapper
     * @param payloadType    The payload type class
     * @param eventRetriever Function to retrieve full events by ID using pure
     *                       Vert.x Future
     */
    public ReactiveNotificationHandler(Vertx vertx, PgConnectOptions connectOptions,
            ObjectMapper objectMapper, Class<T> payloadType,
            Function<String, Future<BiTemporalEvent<T>>> eventRetriever) {
        this(vertx, connectOptions, objectMapper, payloadType, eventRetriever, "public", "bitemporal_event_log");
    }

    public ReactiveNotificationHandler(Vertx vertx, PgConnectOptions connectOptions,
            ObjectMapper objectMapper, Class<T> payloadType,
            Function<String, Future<BiTemporalEvent<T>>> eventRetriever,
            String schema, String tableName) {
        // Simple assignment following PgNativeQueueConsumer pattern
        this.vertx = vertx;
        this.connectOptions = connectOptions;
        this.objectMapper = objectMapper;
        this.eventRetriever = eventRetriever;
        this.schema = schema != null ? schema : "public";
        this.tableName = tableName != null ? tableName : "bitemporal_event_log";

        logger.debug("Created ReactiveNotificationHandler for payload type: {} (schema: {}, table: {})",
                payloadType.getSimpleName(), this.schema, this.tableName);
        logger.debug("Connection options: host={}, port={}, database={}",
                connectOptions.getHost(), connectOptions.getPort(), connectOptions.getDatabase());
    }

    /**
     * Validates channel name to prevent SQL injection.
     * Following PostgreSQL identifier rules: alphanumeric and underscore only.
     *
     * @param channelName The channel name to validate
     * @throws IllegalArgumentException if channel name is invalid
     */
    private void validateChannelName(String channelName) {
        if (channelName == null || channelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Channel name cannot be null or empty");
        }

        // PostgreSQL identifiers: alphanumeric, underscore, max 63 chars
        if (!channelName.matches("^[a-zA-Z0-9_]{1,63}$")) {
            throw new IllegalArgumentException("Invalid channel name: " + channelName +
                    ". Must contain only alphanumeric characters and underscores, max 63 characters");
        }
    }

    /**
     * Validates event type to prevent SQL injection in channel names.
     * Allows alphanumeric characters, underscores, dots, and asterisks for
     * wildcards.
     * Dots are converted to underscores for channel names.
     *
     * @param eventType The event type to validate
     * @throws IllegalArgumentException if event type is invalid
     */
    private void validateEventType(String eventType) {
        if (eventType != null
                && (eventType.isBlank()
                        || eventType.length() > MAX_EVENT_TYPE_LENGTH
                        || !EVENT_TYPE_PATTERN.matcher(eventType).matches())) {
            throw new IllegalArgumentException("Invalid eventType: " + eventType +
                    ". Must use dot-separated segments containing only alphanumeric characters and underscores, or '*' as a whole segment, max 50 characters");
        }
    }

    /**
     * Checks if the event type pattern contains wildcards.
     * Wildcards use '*' to match one or more segments.
     *
     * @param eventType The event type pattern
     * @return true if the pattern contains wildcards
     */
    private boolean isWildcardPattern(String eventType) {
        return eventType != null && eventType.contains("*");
    }

    /**
     * Checks if an event type matches a wildcard pattern.
     * The '*' matches exactly one segment (separated by dots).
     *
     * @param pattern   The wildcard pattern (e.g., "order.*", "*.created",
     *                  "*.order.*")
     * @param eventType The actual event type to match
     * @return true if the event type matches the pattern
     */
    private boolean matchesWildcardPattern(String pattern, String eventType) {
        if (pattern == null || eventType == null) {
            return false;
        }

        String[] patternParts = pattern.split("\\.");
        String[] eventParts = eventType.split("\\.");

        // Must have same number of segments
        if (patternParts.length != eventParts.length) {
            return false;
        }

        // Check each segment
        for (int i = 0; i < patternParts.length; i++) {
            String patternPart = patternParts[i];
            String eventPart = eventParts[i];

            // '*' matches any single segment
            if (!"*".equals(patternPart) && !patternPart.equals(eventPart)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Converts an event type to a safe PostgreSQL channel name suffix.
     * Dots are replaced with underscores since PostgreSQL identifiers don't allow
     * dots.
     *
     * @param eventType The event type to convert
     * @return A safe channel name suffix
     */
    private String toChannelSuffix(String eventType) {
        if (eventType == null) {
            return null;
        }
        return eventType.replace('.', '_');
    }

    /**
     * Creates a safe PostgreSQL channel name that respects the 63-character limit.
     *
     * Strategy:
     * - PostgreSQL channel names are limited to 63 characters
     * - For long table names, we truncate and add a hash suffix for uniqueness
     * - Uses MD5 hash (same as SQL trigger) for deterministic, consistent naming
     * - Format: prefix_truncatedName_hash (if needed)
     *
     * Examples:
     * - Short name: "public_bitemporal_events_orders" (no truncation)
     * - Long name: "public_bitemporal_events_workflow-event-store-1767344124935"
     *   becomes: "public_bitemporal_events_workflow_a1b2c3d4"
     *
     * @param prefix The channel prefix (e.g., "public_bitemporal_events_")
     * @param tableName The table name (may be long)
     * @param suffix Optional suffix for event type (can be null)
     * @return A safe channel name within the 63-character limit
     */
    private String createSafeChannelName(String prefix, String tableName, String suffix) {
        // PostgreSQL identifier max length is 63 characters
        final int MAX_CHANNEL_LENGTH = 63;

        // Clean up table name: remove schema prefix if present, replace hyphens with underscores
        String cleanTableName = tableName.contains(".")
            ? tableName.substring(tableName.lastIndexOf('.') + 1)
            : tableName;
        cleanTableName = cleanTableName.replace('-', '_');

        // Build base channel name
        String baseChannel = prefix + cleanTableName;
        if (suffix != null && !suffix.isEmpty()) {
            baseChannel += "_" + suffix;
        }

        // If within limit, return as-is
        if (baseChannel.length() <= MAX_CHANNEL_LENGTH) {
            return baseChannel;
        }

        // Channel name too long - need to truncate with hash for uniqueness
        // Use MD5 hash (first 8 chars) for deterministic suffix - same as SQL trigger
        String md5Hash;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(baseChannel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Convert to hex and take first 8 characters (same as SQL: substr(md5(...), 1, 8))
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            md5Hash = hexString.toString().substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            // Fallback to hashCode if MD5 not available (shouldn't happen)
            md5Hash = String.format("%08x", baseChannel.hashCode() & 0xFFFFFFFFL).substring(0, 8);
        }

        String hashSuffix = "_" + md5Hash;

        // Truncate to fit: 63 - length(hashSuffix) = available for prefix
        int maxPrefixLength = MAX_CHANNEL_LENGTH - hashSuffix.length();
        String truncatedBase = baseChannel.substring(0, Math.min(baseChannel.length(), maxPrefixLength));
        String result = truncatedBase + hashSuffix;

        logger.debug("Truncated channel name from '{}' to '{}' (hash: {})",
            baseChannel.substring(0, Math.min(baseChannel.length(), 80)), result, md5Hash);

        return result;
    }

    /**
     * Starts the reactive notification handler.
     * Following peegeeq-native patterns for connection management.
     *
     * @return Future that completes when the handler is started
     */
    public Future<Void> start() {
        if (active) {
            return Future.succeededFuture();
        }

        Future<Void> inProgress = startInProgress;
        if (inProgress != null) {
            return inProgress;
        }

        if (connectOptions == null) {
            return Future.failedFuture(new IllegalStateException("Connection options not set"));
        }

        Promise<Void> promise;
        synchronized (this) {
            if (active) {
                return Future.succeededFuture();
            }
            if (startInProgress != null) {
                return startInProgress;
            }

            // Start can be called after a previous stop(); re-enable reconnect behavior.
            this.shutdown = false;
            logger.info("Starting reactive notification handler for bi-temporal events");

            promise = Promise.promise();
            startInProgress = promise.future();
        }

        // Connect to PostgreSQL using Vert.x reactive patterns
        connectReactive()
                .onSuccess(conn -> {
                    synchronized (this) {
                        startInProgress = null;
                    }

                    if (shutdown) {
                        conn.closeHandler(null);
                        conn.close();
                        promise.fail(new IllegalStateException("Notification handler was stopped during startup"));
                        return;
                    }

                    this.listenConnection = conn;
                    this.active = true;

                    logger.debug("Successfully established reactive LISTEN connection to PostgreSQL");

                    // Set up notification handler - following peegeeq-native pattern
                    conn.notificationHandler(notification -> {
                        String channel = notification.getChannel();
                        String payload = notification.getPayload();

                        logger.debug("Received reactive notification on channel '{}': {}", channel, payload);

                        // Process notification on Vert.x context for proper TransactionPropagation
                        // support
                        vertx.runOnContext(v -> handleNotification(channel, payload));
                    });

                    // Set up connection close handler for automatic reconnection
                    conn.closeHandler(v -> {
                        logger.warn("Reactive LISTEN connection closed, attempting reconnection");
                        this.listenConnection = null;
                        this.active = false;

                        // Only attempt reconnection if not shutting down and within retry limits.
                        // Note: get-then-increment is not strictly atomic, but this handler runs
                        // on the Vert.x event loop (single-threaded context) so TOCTOU is safe.
                        if (!shutdown && reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) {
                            int attempt = reconnectAttempts.incrementAndGet();
                            long delay = BASE_RECONNECT_DELAY * (1L << Math.min(attempt - 1, 5)); // Exponential
                                                                                                            // backoff,
                                                                                                            // max 32
                                                                                                            // seconds

                            logger.info("Attempting to reconnect reactive notification handler (attempt {}/{})",
                                    attempt, MAX_RECONNECT_ATTEMPTS);

                            vertx.setTimer(delay, timerId -> {
                                if (!shutdown && !active) {
                                    start().onSuccess(result -> {
                                        // Reset retry counter on successful reconnection
                                        reconnectAttempts.set(0);
                                        logger.info("Successfully reconnected reactive notification handler");
                                    }).onFailure(error -> {
                                        logger.error(
                                                "Failed to reconnect reactive notification handler (attempt {}/{}): {}",
                                                reconnectAttempts.get(), MAX_RECONNECT_ATTEMPTS, error.getMessage());

                                        // If we've exhausted all retry attempts, log final failure
                                        if (reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
                                            logger.error(
                                                    "Exhausted all reconnection attempts ({}/{}). Reactive notifications will not be available until manual restart.",
                                                    reconnectAttempts.get(), MAX_RECONNECT_ATTEMPTS);
                                        }
                                    });
                                }
                            });
                        } else if (shutdown) {
                            logger.debug("Skipping reconnection attempt - handler is shutting down");
                        } else {
                            logger.warn(
                                    "Exhausted all reconnection attempts ({}/{}). Reactive notifications will not be available.",
                                    reconnectAttempts.get(), MAX_RECONNECT_ATTEMPTS);
                        }
                    });

                    replayListenChannelsForExistingSubscriptions()
                            .onSuccess(v -> {
                                // Reset reconnect attempts on successful connection
                                this.reconnectAttempts.set(0);
                                promise.complete();
                            })
                            .onFailure(error -> {
                                logger.error("Failed to re-establish LISTEN channels after connect: {}",
                                        error.getMessage(), error);
                                this.listenConnection = null;
                                this.active = false;
                                conn.closeHandler(null);
                                conn.close();
                                promise.fail(error);
                            });
                })
                .onFailure(error -> {
                    synchronized (this) {
                        startInProgress = null;
                    }
                    logger.error("Failed to establish reactive LISTEN connection: {}", error.getMessage());
                    promise.fail(error);
                });

        return promise.future();
    }

    private Future<Void> replayListenChannelsForExistingSubscriptions() {
        if (subscriptions.isEmpty()) {
            return Future.succeededFuture();
        }

        // Channel state belongs to a connection; rebuild after reconnect so stale
        // in-memory flags don't suppress LISTEN commands.
        listeningChannels.clear();

        Set<String> distinctEventTypes = new LinkedHashSet<>();
        for (SubscriptionKey key : subscriptions.keySet()) {
            distinctEventTypes.add(key.eventType());
        }

        Future<Void> replayFuture = Future.succeededFuture();
        for (String eventType : distinctEventTypes) {
            replayFuture = replayFuture.compose(v -> setupListenChannels(eventType));
        }
        return replayFuture;
    }

    Map<SubscriptionKey, CopyOnWriteArrayList<MessageHandler<BiTemporalEvent<T>>>> subscriptionsView() {
        return subscriptions;
    }

    Set<String> listeningChannelsView() {
        return listeningChannels;
    }

    boolean hasListenConnection() {
        return listenConnection != null;
    }

    /**
     * Hook for establishing LISTEN connections.
     * Kept overridable for deterministic failure/race-path testing.
     */
    Future<PgConnection> connectReactive() {
        return PgConnection.connect(vertx, connectOptions);
    }

    Future<Void> listenOnChannel(PgConnection connection, String channel) {
        return connection.query("LISTEN \"" + channel + "\"")
                .execute()
                .onSuccess(result -> logger.debug("Started reactive listening on channel: {}", channel))
                .mapEmpty();
    }

    Future<Void> unlistenChannel(PgConnection connection, String channel) {
        return connection.query("UNLISTEN \"" + channel + "\"")
                .execute()
                .mapEmpty();
    }

    Future<Void> closeListenConnection(PgConnection connection) {
        connection.closeHandler(null);
        return connection.close();
    }

    /**
     * Stops the reactive notification handler.
     *
     * @return Future that completes when the handler is stopped
     */
    public Future<Void> stop() {
        logger.info("Stopping reactive notification handler");
        this.shutdown = true; // Signal shutdown to prevent reconnection attempts
        this.active = false;

        PgConnection connectionToClose = this.listenConnection;
        this.listenConnection = null;

        if (connectionToClose != null) {
            // Execute UNLISTEN commands for all channels with proper quoting
            Future<Void> unlistenFuture = Future.succeededFuture();
            for (String channel : listeningChannels) {
                unlistenFuture = unlistenFuture.compose(v -> unlistenChannel(connectionToClose, channel));
            }

            Promise<Void> promise = Promise.promise();
            unlistenFuture.onComplete(unlistenResult -> {
                // Always attempt to close the connection, even if UNLISTEN failed.
                closeListenConnection(connectionToClose).onComplete(closeResult -> {
                    this.listeningChannels.clear();
                    this.subscriptions.clear();

                    if (unlistenResult.failed()) {
                        Throwable unlistenError = unlistenResult.cause();
                        if (closeResult.failed()) {
                            unlistenError.addSuppressed(closeResult.cause());
                        }
                        logger.error("Failed to stop reactive notification handler cleanly: {}",
                                unlistenError.getMessage(), unlistenError);
                        promise.fail(unlistenError);
                        return;
                    }

                    if (closeResult.failed()) {
                        Throwable closeError = closeResult.cause();
                        logger.error("Failed to stop reactive notification handler cleanly: {}",
                                closeError.getMessage(), closeError);
                        promise.fail(closeError);
                        return;
                    }

                    logger.info("Reactive notification handler stopped");
                    promise.complete();
                });
            });

            return promise.future();
        } else {
            this.listeningChannels.clear();
            this.subscriptions.clear();
            return Future.succeededFuture();
        }
    }

    /**
     * Adds a subscription for event notifications.
     * Following the same subscription patterns as the original JDBC implementation.
     *
     * @param eventType   The event type to subscribe to (null for all types)
     * @param aggregateId The aggregate ID to filter by (null for all aggregates)
     * @param handler     The message handler
     * @return Future that completes when the subscription is established
     */
    public Future<Void> subscribe(String eventType, String aggregateId, MessageHandler<BiTemporalEvent<T>> handler) {
        if (handler == null) {
            return Future.failedFuture(new IllegalArgumentException("Handler cannot be null"));
        }

        // Validate input parameters FIRST to prevent SQL injection - even before
        // checking if active
        try {
            validateEventType(eventType);
            // Note: aggregateId is not used in channel names, so no validation needed
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }

        if (!active) {
            return Future.failedFuture(new IllegalStateException("Notification handler is not active"));
        }

        // Store subscription handlers by structured key.
        // Multiple handlers can subscribe to the same key without overwriting.
        SubscriptionKey key = SubscriptionKey.of(eventType, aggregateId);
    subscriptions.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(handler);

        // Set up PostgreSQL LISTEN commands - following original pattern
        return setupListenChannels(eventType)
                .onSuccess(v -> logger.debug("Reactive subscription established for eventType='{}', aggregateId='{}'",
                        eventType, aggregateId))
        .onFailure(error -> {
            removeSubscriptionHandler(key, handler);
            logger.error("Failed to establish reactive subscription: {}", error.getMessage());
        });
    }

    /**
     * Sets up PostgreSQL LISTEN commands for the given event type.
     *
     * Channel strategy:
     * - Exact match (e.g., "order.created"): Listen ONLY on type-specific channel
     * - Wildcard (e.g., "order.*"): Listen ONLY on general channel, filter in
     * application
     * - All events (null): Listen ONLY on general channel
     *
     * Uses proper PostgreSQL identifier quoting to prevent SQL injection.
     * Uses createSafeChannelName to ensure channel names stay within 63-character limit.
     */
    private Future<Void> setupListenChannels(String eventType) {
        if (listenConnection == null) {
            return Future.failedFuture(new IllegalStateException("No active connection"));
        }

        String prefix = schema + "_bitemporal_events_";

        // Create safe general channel name (without event type)
        String generalChannel = createSafeChannelName(prefix, tableName, null);

        // Determine which channel to listen on based on subscription type
        if (eventType == null || isWildcardPattern(eventType)) {
            // All events (null) or wildcard pattern: listen on general channel only
            // Wildcard filtering is done in handleNotification
            return ensureChannelListening(generalChannel);
        } else {
            // Exact match: listen ONLY on type-specific channel
            String eventTypeSuffix = toChannelSuffix(eventType);
            String typeChannel = createSafeChannelName(prefix, tableName, eventTypeSuffix);
            return ensureChannelListening(typeChannel);
        }
    }

    private Future<Void> ensureChannelListening(String channel) {
        validateChannelName(channel);
        if (listeningChannels.contains(channel)) {
            return Future.succeededFuture();
        }

        return listenOnChannel(listenConnection, channel)
                .onSuccess(v -> listeningChannels.add(channel));
    }

    private void removeSubscriptionHandler(SubscriptionKey key, MessageHandler<BiTemporalEvent<T>> handler) {
        subscriptions.computeIfPresent(key, (ignored, handlers) -> {
            handlers.remove(handler);
            return handlers.isEmpty() ? null : handlers;
        });
    }

    /**
     * Handles PostgreSQL notifications for bi-temporal events.
     * Following the same notification processing logic as the original JDBC
     * implementation.
     *
     * Includes deduplication to handle the case where the same event is received on
     * multiple channels (e.g., both '{schema}_bitemporal_events_{table}' general channel
     * and '{schema}_bitemporal_events_{table}_{event_type}' type-specific channel).
     */
    private void handleNotification(String channel, String payload) {
        try {
            // Parse the notification payload - following original pattern
            JsonNode payloadJson = objectMapper.readTree(payload);

            // Extract debug information about channel names (if available)
            String actualChannelName = payloadJson.has("channel_name") ? payloadJson.get("channel_name").asText() : channel;
            String originalChannelName = payloadJson.has("original_channel_name") ? payloadJson.get("original_channel_name").asText() : null;

            // Debug: Log channel name information for troubleshooting
            if (originalChannelName != null && !originalChannelName.equals(actualChannelName)) {
                logger.debug("Received notification on hashed channel: actual='{}', original='{}' (len={})",
                    actualChannelName, originalChannelName, originalChannelName.length());
            }

            // Validate required fields are present
            JsonNode eventIdNode = payloadJson.get("event_id");
            JsonNode eventTypeNode = payloadJson.get("event_type");

            if (eventIdNode == null || eventIdNode.isNull()) {
                logger.warn("Received notification with missing event_id field: {}", payload);
                return;
            }

            if (eventTypeNode == null || eventTypeNode.isNull()) {
                logger.warn("Received notification with missing event_type field: {}", payload);
                return;
            }

            String eventId = eventIdNode.asText();
            String eventType = eventTypeNode.asText();
            String aggregateId = payloadJson.has("aggregate_id") && !payloadJson.get("aggregate_id").isNull()
                    ? payloadJson.get("aggregate_id").asText()
                    : null;

            // Retrieve the full event from the database - Pure Vert.x 5.x composable Future
            // pattern
            eventRetriever.apply(eventId)
                    .onSuccess(event -> {
                        if (event == null) {
                            logger.warn("Event {} not found in database after reactive notification", eventId);
                            return;
                        }

                        // Set MDC for processing
                        boolean hasTrace = false;
                        if (event.getHeaders() != null) {
                            hasTrace = TraceContextUtil.setMDCFromMessageHeaders(event.getHeaders());
                        }
                        
                        if (!hasTrace) {
                            TraceCtx newTrace = TraceCtx.createNew();
                            TraceContextUtil.setMDC(TraceContextUtil.MDC_TRACE_ID, newTrace.traceId());
                            TraceContextUtil.setMDC(TraceContextUtil.MDC_SPAN_ID, newTrace.spanId());
                        }
                        
                        if (event.getCorrelationId() != null) {
                            TraceContextUtil.setMDC(TraceContextUtil.MDC_CORRELATION_ID, event.getCorrelationId());
                        }
                        TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, eventId);

                        try {
                            // Create message wrapper - following original pattern
                            Message<BiTemporalEvent<T>> message = new SimpleMessage<>(
                                    eventId,
                                    "bitemporal_events",
                                    event,
                                    Map.of("event_type", eventType, "aggregate_id", aggregateId != null ? aggregateId : ""),
                                    null,
                                    null,
                                    Instant.now());

                            // Notify matching subscriptions - following original pattern
                            notifySubscriptions(eventType, aggregateId, message);
                        } finally {
                            TraceContextUtil.clearTraceMDC();
                        }
                    })
                    .onFailure(error -> {
                        // Don't log errors during shutdown - connection loss is expected
                        if (!shutdown) {
                            logger.error("Error retrieving event {} after reactive notification: {}", eventId,
                                    error.getMessage());
                        } else {
                            logger.debug("Ignoring event retrieval error during shutdown for event {}", eventId);
                        }
                    });

        } catch (Exception e) {
            // Don't log errors during shutdown - connection loss is expected
            if (!shutdown) {
                logger.error("Error handling reactive notification: {}", e.getMessage());
            } else {
                logger.debug("Ignoring notification handling error during shutdown: {}", e.getMessage());
            }
        }
    }

    /**
     * Notifies matching subscriptions about a new event.
     * Handles exact matches, wildcard patterns, and all-events subscriptions.
     */
    private void notifySubscriptions(String eventType, String aggregateId, Message<BiTemporalEvent<T>> message) {
        // Notify all-events subscriptions
        notifySubscription(SubscriptionKey.allEvents(), message);

        // Notify event-type specific subscriptions (exact match)
        if (eventType != null) {
            notifySubscription(SubscriptionKey.of(eventType, null), message);

            // Notify aggregate-specific subscriptions
            if (aggregateId != null) {
                notifySubscription(SubscriptionKey.of(eventType, aggregateId), message);
            }
        }

        // Check wildcard pattern subscriptions
        // Iterate through all subscriptions to find matching wildcard patterns
        for (Map.Entry<SubscriptionKey, CopyOnWriteArrayList<MessageHandler<BiTemporalEvent<T>>>> entry : subscriptions.entrySet()) {
            SubscriptionKey key = entry.getKey();
            if (key.hasWildcardEventType()
                    && matchesWildcardPattern(key.eventType(), eventType)
                    && key.aggregateMatches(aggregateId)) {
                notifySubscription(key, message, entry.getValue());
            }
        }
    }

    /**
     * Notifies a specific subscription.
     * Following the exact same subscription notification logic as the original JDBC
     * implementation.
     */
    private void notifySubscription(SubscriptionKey subscriptionKey, Message<BiTemporalEvent<T>> message) {
        List<MessageHandler<BiTemporalEvent<T>>> handlers = subscriptions.get(subscriptionKey);
        notifySubscription(subscriptionKey, message, handlers);
    }

    private void notifySubscription(SubscriptionKey subscriptionKey, Message<BiTemporalEvent<T>> message,
            List<MessageHandler<BiTemporalEvent<T>>> handlers) {
        if (handlers != null) {
            for (MessageHandler<BiTemporalEvent<T>> handler : handlers) {
                try {
                    handler.handle(message).exceptionally(throwable -> {
                        logger.error("Error in reactive subscription handler for key '{}': {}",
                                subscriptionKey, throwable.getMessage(), throwable);
                        return null;
                    });
                } catch (Exception e) {
                    logger.error("Error invoking reactive subscription handler for key '{}': {}",
                            subscriptionKey, e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Checks if the handler is active.
     *
     * @return true if the handler is active
     */
    public boolean isActive() {
        return active;
    }
}
