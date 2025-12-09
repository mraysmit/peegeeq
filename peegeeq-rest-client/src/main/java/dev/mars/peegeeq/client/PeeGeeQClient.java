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

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventQuery;
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.api.deadletter.DeadLetterStatsInfo;
import dev.mars.peegeeq.api.health.HealthStatusInfo;
import dev.mars.peegeeq.api.health.OverallHealthInfo;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.client.dto.*;
import io.vertx.core.Future;
import io.vertx.core.streams.ReadStream;

import java.util.List;

/**
 * Main interface for the PeeGeeQ REST client.
 * 
 * <p>This client provides programmatic access to all PeeGeeQ REST API operations.
 * All methods return Vert.x {@link Future} for non-blocking async operations.
 * 
 * <p>Example usage:
 * <pre>{@code
 * PeeGeeQClient client = PeeGeeQRestClient.create(vertx, ClientConfig.defaults());
 * 
 * client.createSetup(request)
 *     .onSuccess(result -> System.out.println("Setup created: " + result.getSetupId()))
 *     .onFailure(err -> System.err.println("Failed: " + err.getMessage()));
 * }</pre>
 * 
 * @see PeeGeeQRestClient
 */
public interface PeeGeeQClient extends AutoCloseable {

    // ========================================================================
    // Setup Operations
    // ========================================================================

    /**
     * Creates a new database setup with queues and event stores.
     *
     * @param request the setup configuration
     * @return future containing the setup result
     */
    Future<DatabaseSetupResult> createSetup(DatabaseSetupRequest request);

    /**
     * Lists all active database setups.
     *
     * @return future containing list of setup results
     */
    Future<List<DatabaseSetupResult>> listSetups();

    /**
     * Gets details for a specific setup.
     *
     * @param setupId the setup identifier
     * @return future containing the setup result
     */
    Future<DatabaseSetupResult> getSetup(String setupId);

    /**
     * Deletes a database setup and all associated resources.
     *
     * @param setupId the setup identifier
     * @return future that completes when deletion is done
     */
    Future<Void> deleteSetup(String setupId);

    // ========================================================================
    // Queue Operations
    // ========================================================================

    /**
     * Sends a message to a queue.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param message the message to send
     * @return future containing the send result
     */
    Future<MessageSendResult> sendMessage(String setupId, String queueName, MessageRequest message);

    /**
     * Sends multiple messages to a queue in a batch.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param messages the messages to send
     * @return future containing list of send results
     */
    Future<List<MessageSendResult>> sendBatch(String setupId, String queueName, List<MessageRequest> messages);

    /**
     * Gets statistics for a queue.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @return future containing queue statistics
     */
    Future<QueueStats> getQueueStats(String setupId, String queueName);

    // ========================================================================
    // Consumer Group Operations
    // ========================================================================

    /**
     * Creates a new consumer group for a queue.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param groupName the consumer group name
     * @return future containing the consumer group info
     */
    Future<ConsumerGroupInfo> createConsumerGroup(String setupId, String queueName, String groupName);

    /**
     * Lists all consumer groups for a queue.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @return future containing list of consumer groups
     */
    Future<List<ConsumerGroupInfo>> listConsumerGroups(String setupId, String queueName);

    /**
     * Gets details for a specific consumer group.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param groupName the consumer group name
     * @return future containing the consumer group info
     */
    Future<ConsumerGroupInfo> getConsumerGroup(String setupId, String queueName, String groupName);

    /**
     * Deletes a consumer group.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param groupName the consumer group name
     * @return future that completes when deletion is done
     */
    Future<Void> deleteConsumerGroup(String setupId, String queueName, String groupName);

    // ========================================================================
    // Dead Letter Queue Operations
    // ========================================================================

    /**
     * Lists dead letter messages with pagination.
     *
     * @param setupId the setup identifier
     * @param page the page number (0-based)
     * @param pageSize the page size
     * @return future containing the dead letter list response
     */
    Future<DeadLetterListResponse> listDeadLetters(String setupId, int page, int pageSize);

    /**
     * Gets a specific dead letter message.
     *
     * @param setupId the setup identifier
     * @param messageId the message identifier
     * @return future containing the dead letter message info
     */
    Future<DeadLetterMessageInfo> getDeadLetter(String setupId, long messageId);

    /**
     * Reprocesses a dead letter message.
     *
     * @param setupId the setup identifier
     * @param messageId the message identifier
     * @return future that completes when reprocessing is initiated
     */
    Future<Void> reprocessDeadLetter(String setupId, long messageId);

    /**
     * Deletes a dead letter message.
     *
     * @param setupId the setup identifier
     * @param messageId the message identifier
     * @return future that completes when deletion is done
     */
    Future<Void> deleteDeadLetter(String setupId, long messageId);

    /**
     * Gets dead letter queue statistics.
     *
     * @param setupId the setup identifier
     * @return future containing the statistics
     */
    Future<DeadLetterStatsInfo> getDeadLetterStats(String setupId);

    // ========================================================================
    // Subscription Operations
    // ========================================================================

    /**
     * Lists subscriptions for a topic.
     *
     * @param setupId the setup identifier
     * @param topic the topic name
     * @return future containing list of subscriptions
     */
    Future<List<SubscriptionInfo>> listSubscriptions(String setupId, String topic);

    /**
     * Gets a specific subscription.
     *
     * @param setupId the setup identifier
     * @param topic the topic name
     * @param groupName the consumer group name
     * @return future containing the subscription info
     */
    Future<SubscriptionInfo> getSubscription(String setupId, String topic, String groupName);

    /**
     * Pauses a subscription.
     *
     * @param setupId the setup identifier
     * @param topic the topic name
     * @param groupName the consumer group name
     * @return future that completes when paused
     */
    Future<Void> pauseSubscription(String setupId, String topic, String groupName);

    /**
     * Resumes a paused subscription.
     *
     * @param setupId the setup identifier
     * @param topic the topic name
     * @param groupName the consumer group name
     * @return future that completes when resumed
     */
    Future<Void> resumeSubscription(String setupId, String topic, String groupName);

    /**
     * Cancels a subscription.
     *
     * @param setupId the setup identifier
     * @param topic the topic name
     * @param groupName the consumer group name
     * @return future that completes when cancelled
     */
    Future<Void> cancelSubscription(String setupId, String topic, String groupName);

    // ========================================================================
    // Health Operations
    // ========================================================================

    /**
     * Gets overall health status for a setup.
     *
     * @param setupId the setup identifier
     * @return future containing the overall health info
     */
    Future<OverallHealthInfo> getHealth(String setupId);

    /**
     * Lists health status for all components.
     *
     * @param setupId the setup identifier
     * @return future containing list of component health statuses
     */
    Future<List<HealthStatusInfo>> listComponentHealth(String setupId);

    /**
     * Gets health status for a specific component.
     *
     * @param setupId the setup identifier
     * @param componentName the component name
     * @return future containing the component health status
     */
    Future<HealthStatusInfo> getComponentHealth(String setupId, String componentName);

    // ========================================================================
    // Event Store Operations
    // ========================================================================

    /**
     * Appends an event to an event store.
     *
     * @param setupId the setup identifier
     * @param storeName the event store name
     * @param request the event to append
     * @return future containing the appended event
     */
    Future<BiTemporalEvent> appendEvent(String setupId, String storeName, AppendEventRequest request);

    /**
     * Queries events from an event store.
     *
     * @param setupId the setup identifier
     * @param storeName the event store name
     * @param query the query parameters
     * @return future containing the query result
     */
    Future<EventQueryResult> queryEvents(String setupId, String storeName, EventQuery query);

    /**
     * Gets a specific event by ID.
     *
     * @param setupId the setup identifier
     * @param storeName the event store name
     * @param eventId the event identifier
     * @return future containing the event
     */
    Future<BiTemporalEvent> getEvent(String setupId, String storeName, String eventId);

    /**
     * Gets all versions of an event.
     *
     * @param setupId the setup identifier
     * @param storeName the event store name
     * @param eventId the event identifier
     * @return future containing list of event versions
     */
    Future<List<BiTemporalEvent>> getEventVersions(String setupId, String storeName, String eventId);

    /**
     * Appends a correction to an event.
     *
     * @param setupId the setup identifier
     * @param storeName the event store name
     * @param eventId the event identifier
     * @param request the correction request
     * @return future containing the corrected event
     */
    Future<BiTemporalEvent> appendCorrection(String setupId, String storeName, String eventId,
                                              CorrectionRequest request);

    // ========================================================================
    // Streaming Operations
    // ========================================================================

    /**
     * Streams events from an event store using SSE.
     *
     * @param setupId the setup identifier
     * @param storeName the event store name
     * @param options streaming options
     * @return a read stream of events
     */
    ReadStream<BiTemporalEvent> streamEvents(String setupId, String storeName, StreamOptions options);

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Closes the client and releases resources.
     */
    @Override
    void close();
}

