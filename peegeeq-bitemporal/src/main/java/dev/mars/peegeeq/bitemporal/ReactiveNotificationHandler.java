/*
 * Copyright (c) 2025 Cityline Ltd
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of Cityline Ltd.
 * You shall not disclose such confidential information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Cityline Ltd.
 */

package dev.mars.peegeeq.bitemporal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Reactive notification handler for bi-temporal events using pure Vert.x patterns.
 * 
 * This class replaces the JDBC-based PgListenerConnection with reactive Vert.x
 * LISTEN/NOTIFY functionality, following the patterns established in peegeeq-native.
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

    private final Vertx vertx;
    private final PgConnectOptions connectOptions; // Now final - immutable construction
    private final ObjectMapper objectMapper;
    private final Function<String, Future<BiTemporalEvent<T>>> eventRetriever;
    
    // Connection management
    private volatile PgConnection listenConnection;
    private volatile boolean active = false;
    private volatile boolean shutdown = false;
    private volatile int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long BASE_RECONNECT_DELAY = 1000; // 1 second
    
    // Subscription management - following peegeeq-bitemporal patterns
    private final Map<String, MessageHandler<BiTemporalEvent<T>>> subscriptions = new ConcurrentHashMap<>();
    private final Set<String> listeningChannels = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new ReactiveNotificationHandler.
     * Following peegeeq-native patterns.
     *
     * @param vertx The Vertx instance for reactive operations
     * @param connectOptions PostgreSQL connection options
     * @param objectMapper JSON object mapper
     * @param payloadType The payload type class
     * @param eventRetriever Function to retrieve full events by ID using pure Vert.x Future
     */
    public ReactiveNotificationHandler(Vertx vertx, PgConnectOptions connectOptions,
                                     ObjectMapper objectMapper, Class<T> payloadType,
                                     Function<String, Future<BiTemporalEvent<T>>> eventRetriever) {
        // Simple assignment following PgNativeQueueConsumer pattern
        this.vertx = vertx;
        this.connectOptions = connectOptions;
        this.objectMapper = objectMapper;
        this.eventRetriever = eventRetriever;

        logger.debug("Created ReactiveNotificationHandler for payload type: {}", payloadType.getSimpleName());
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
     *
     * @param eventType The event type to validate
     * @throws IllegalArgumentException if event type is invalid
     */
    private void validateEventType(String eventType) {
        if (eventType != null && !eventType.matches("^[a-zA-Z0-9_]{1,50}$")) {
            throw new IllegalArgumentException("Invalid eventType: " + eventType +
                ". Must contain only alphanumeric characters and underscores, max 50 characters");
        }
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

        if (connectOptions == null) {
            return Future.failedFuture(new IllegalStateException("Connection options not set"));
        }

        logger.info("Starting reactive notification handler for bi-temporal events");
        Promise<Void> promise = Promise.promise();

        // Connect to PostgreSQL using Vert.x reactive patterns
        PgConnection.connect(vertx, connectOptions)
            .onSuccess(conn -> {
                this.listenConnection = conn;
                this.active = true;
                
                logger.debug("Successfully established reactive LISTEN connection to PostgreSQL");

                // Set up notification handler - following peegeeq-native pattern
                conn.notificationHandler(notification -> {
                    String channel = notification.getChannel();
                    String payload = notification.getPayload();
                    
                    logger.debug("Received reactive notification on channel '{}': {}", channel, payload);
                    
                    // Process notification on Vert.x context for proper TransactionPropagation support
                    vertx.runOnContext(v -> handleNotification(channel, payload));
                });

                // Set up connection close handler for automatic reconnection
                conn.closeHandler(v -> {
                    logger.warn("Reactive LISTEN connection closed, attempting reconnection");
                    this.listenConnection = null;
                    this.active = false;

                    // Only attempt reconnection if not shutting down and within retry limits
                    if (!shutdown && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnectAttempts++;
                        long delay = BASE_RECONNECT_DELAY * (1L << Math.min(reconnectAttempts - 1, 5)); // Exponential backoff, max 32 seconds

                        logger.info("Attempting to reconnect reactive notification handler (attempt {}/{})",
                                   reconnectAttempts, MAX_RECONNECT_ATTEMPTS);

                        vertx.setTimer(delay, timerId -> {
                            if (!shutdown && !active) {
                                start().onSuccess(result -> {
                                    // Reset retry counter on successful reconnection
                                    reconnectAttempts = 0;
                                    logger.info("Successfully reconnected reactive notification handler");
                                }).onFailure(error -> {
                                    logger.error("Failed to reconnect reactive notification handler (attempt {}/{}): {}",
                                               reconnectAttempts, MAX_RECONNECT_ATTEMPTS, error.getMessage());

                                    // If we've exhausted all retry attempts, log final failure
                                    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                                        logger.error("Exhausted all reconnection attempts ({}/{}). Reactive notifications will not be available until manual restart.",
                                                   reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
                                    }
                                });
                            }
                        });
                    } else if (shutdown) {
                        logger.debug("Skipping reconnection attempt - handler is shutting down");
                    } else {
                        logger.warn("Exhausted all reconnection attempts ({}/{}). Reactive notifications will not be available.",
                                  reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
                    }
                });

                // Reset reconnect attempts on successful connection
                this.reconnectAttempts = 0;
                promise.complete();
            })
            .onFailure(error -> {
                logger.error("Failed to establish reactive LISTEN connection: {}", error.getMessage());
                promise.fail(error);
            });

        return promise.future();
    }

    /**
     * Stops the reactive notification handler.
     *
     * @return Future that completes when the handler is stopped
     */
    public Future<Void> stop() {
        if (!active) {
            return Future.succeededFuture();
        }

        logger.info("Stopping reactive notification handler");
        this.shutdown = true; // Signal shutdown to prevent reconnection attempts
        Promise<Void> promise = Promise.promise();

        if (listenConnection != null) {
            // Execute UNLISTEN commands for all channels with proper quoting
            Future<Void> unlistenFuture = Future.succeededFuture();
            for (String channel : listeningChannels) {
                unlistenFuture = unlistenFuture.compose(v ->
                    listenConnection.query("UNLISTEN \"" + channel + "\"").execute().mapEmpty()
                );
            }

            unlistenFuture
                .compose(v -> {
                    // Clear the close handler to prevent reconnection attempts during shutdown
                    listenConnection.closeHandler(null);
                    // Close the connection
                    listenConnection.close();
                    return Future.succeededFuture();
                })
                .onComplete(result -> {
                    this.active = false;
                    this.listenConnection = null;
                    this.listeningChannels.clear();
                    logger.info("Reactive notification handler stopped");
                    promise.complete();
                });
        } else {
            this.active = false;
            promise.complete();
        }

        return promise.future();
    }

    /**
     * Adds a subscription for event notifications.
     * Following the same subscription patterns as the original JDBC implementation.
     *
     * @param eventType The event type to subscribe to (null for all types)
     * @param aggregateId The aggregate ID to filter by (null for all aggregates)
     * @param handler The message handler
     * @return Future that completes when the subscription is established
     */
    public Future<Void> subscribe(String eventType, String aggregateId, MessageHandler<BiTemporalEvent<T>> handler) {
        // Validate input parameters FIRST to prevent SQL injection - even before checking if active
        try {
            validateEventType(eventType);
            // Note: aggregateId is not used in channel names, so no validation needed
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(e);
        }

        if (!active) {
            return Future.failedFuture(new IllegalStateException("Notification handler is not active"));
        }

        // Store the subscription handler - following original pattern
        String key = (eventType != null ? eventType : "all") + "_" + (aggregateId != null ? aggregateId : "all");
        subscriptions.put(key, handler);

        // Set up PostgreSQL LISTEN commands - following original pattern
        return setupListenChannels(eventType)
            .onSuccess(v -> logger.debug("Reactive subscription established for eventType='{}', aggregateId='{}'", eventType, aggregateId))
            .onFailure(error -> logger.error("Failed to establish reactive subscription: {}", error.getMessage()));
    }

    /**
     * Sets up PostgreSQL LISTEN commands for the given event type.
     * Following the same channel patterns as the original JDBC implementation.
     * Uses proper PostgreSQL identifier quoting to prevent SQL injection.
     */
    private Future<Void> setupListenChannels(String eventType) {
        if (listenConnection == null) {
            return Future.failedFuture(new IllegalStateException("No active connection"));
        }

        Future<Void> listenFuture = Future.succeededFuture();

        // Always listen to the general bi-temporal events channel
        String generalChannel = "bitemporal_events";
        validateChannelName(generalChannel); // Validate even hardcoded channels
        if (listeningChannels.add(generalChannel)) {
            listenFuture = listenFuture.compose(v ->
                listenConnection.query("LISTEN \"" + generalChannel + "\"").execute()
                    .onSuccess(result -> logger.debug("Started reactive listening on channel: {}", generalChannel))
                    .mapEmpty()
            );
        }

        // If specific event type, also listen to type-specific channel
        if (eventType != null) {
            String typeChannel = "bitemporal_events_" + eventType;
            validateChannelName(typeChannel); // Validate constructed channel name
            if (listeningChannels.add(typeChannel)) {
                listenFuture = listenFuture.compose(v ->
                    listenConnection.query("LISTEN \"" + typeChannel + "\"").execute()
                        .onSuccess(result -> logger.debug("Started reactive listening on channel: {}", typeChannel))
                        .mapEmpty()
                );
            }
        }

        return listenFuture;
    }

    /**
     * Handles PostgreSQL notifications for bi-temporal events.
     * Following the same notification processing logic as the original JDBC implementation.
     */
    private void handleNotification(String channel, String payload) {
        try {
            // Parse the notification payload - following original pattern
            JsonNode payloadJson = objectMapper.readTree(payload);

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
                ? payloadJson.get("aggregate_id").asText() : null;

            // Retrieve the full event from the database - Pure Vert.x 5.x composable Future pattern
            eventRetriever.apply(eventId)
                .onSuccess(event -> {
                    if (event == null) {
                        logger.warn("Event {} not found in database after reactive notification", eventId);
                        return;
                    }

                    // Create message wrapper - following original pattern
                    Message<BiTemporalEvent<T>> message = new SimpleMessage<>(
                        eventId,
                        "bitemporal_events",
                        event,
                        Map.of("event_type", eventType, "aggregate_id", aggregateId != null ? aggregateId : ""),
                        null,
                        null,
                        Instant.now()
                    );

                    // Notify matching subscriptions - following original pattern
                    notifySubscriptions(eventType, aggregateId, message);
                })
                .onFailure(error -> {
                    logger.error("Error retrieving event {} after reactive notification: {}", eventId, error.getMessage());
                });

        } catch (Exception e) {
            logger.error("Error handling reactive notification: {}", e.getMessage(), e);
        }
    }

    /**
     * Notifies matching subscriptions about a new event.
     * Following the exact same subscription notification logic as the original JDBC implementation.
     */
    private void notifySubscriptions(String eventType, String aggregateId, Message<BiTemporalEvent<T>> message) {
        // Notify all-events subscriptions
        notifySubscription("all_all", message);

        // Notify event-type specific subscriptions
        if (eventType != null) {
            notifySubscription(eventType + "_all", message);

            // Notify aggregate-specific subscriptions
            if (aggregateId != null) {
                notifySubscription(eventType + "_" + aggregateId, message);
            }
        }
    }

    /**
     * Notifies a specific subscription.
     * Following the exact same subscription notification logic as the original JDBC implementation.
     */
    private void notifySubscription(String subscriptionKey, Message<BiTemporalEvent<T>> message) {
        MessageHandler<BiTemporalEvent<T>> handler = subscriptions.get(subscriptionKey);
        if (handler != null) {
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

    /**
     * Checks if the handler is active.
     *
     * @return true if the handler is active
     */
    public boolean isActive() {
        return active;
    }
}
