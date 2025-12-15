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
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.api.deadletter.DeadLetterStatsInfo;
import dev.mars.peegeeq.api.health.HealthStatusInfo;
import dev.mars.peegeeq.api.health.OverallHealthInfo;
import dev.mars.peegeeq.api.setup.DatabaseSetupRequest;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.client.dto.*;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;

import java.util.List;

import java.time.Instant;
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

    /**
     * Gets the status of a database setup.
     *
     * @param setupId the setup identifier
     * @return future containing the setup status
     */
    Future<DatabaseSetupStatus> getSetupStatus(String setupId);

    /**
     * Adds a queue to an existing setup.
     *
     * @param setupId the setup identifier
     * @param queueConfig the queue configuration
     * @return future that completes when the queue is added
     */
    Future<Void> addQueue(String setupId, QueueConfig queueConfig);

    /**
     * Adds an event store to an existing setup.
     *
     * @param setupId the setup identifier
     * @param eventStoreConfig the event store configuration
     * @return future that completes when the event store is added
     */
    Future<Void> addEventStore(String setupId, EventStoreConfig eventStoreConfig);

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

    /**
     * Gets detailed information about a queue.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @return future containing queue details
     */
    Future<QueueDetailsInfo> getQueueDetails(String setupId, String queueName);

    /**
     * Gets list of consumers for a queue.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @return future containing list of consumer IDs
     */
    Future<List<String>> getQueueConsumers(String setupId, String queueName);

    /**
     * Gets queue bindings.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @return future containing bindings as JSON
     */
    Future<JsonObject> getQueueBindings(String setupId, String queueName);

    /**
     * Purges all messages from a queue.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @return future containing number of messages purged
     */
    Future<Long> purgeQueue(String setupId, String queueName);

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

    /**
     * Joins a consumer group as a member.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param groupName the consumer group name
     * @param memberName optional member name
     * @return future containing the member info
     */
    Future<ConsumerGroupMemberInfo> joinConsumerGroup(String setupId, String queueName, String groupName, String memberName);

    /**
     * Leaves a consumer group.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param groupName the consumer group name
     * @param memberId the member identifier
     * @return future that completes when leave is done
     */
    Future<Void> leaveConsumerGroup(String setupId, String queueName, String groupName, String memberId);

    /**
     * Updates subscription options for a consumer group.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param groupName the consumer group name
     * @param options the subscription options
     * @return future containing the updated options
     */
    Future<SubscriptionOptionsInfo> updateSubscriptionOptions(String setupId, String queueName, String groupName, SubscriptionOptionsRequest options);

    /**
     * Gets subscription options for a consumer group.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param groupName the consumer group name
     * @return future containing the subscription options
     */
    Future<SubscriptionOptionsInfo> getSubscriptionOptions(String setupId, String queueName, String groupName);

    /**
     * Deletes subscription options for a consumer group.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param groupName the consumer group name
     * @return future that completes when deletion is done
     */
    Future<Void> deleteSubscriptionOptions(String setupId, String queueName, String groupName);

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

    /**
     * Cleans up old dead letter messages.
     *
     * @param setupId the setup identifier
     * @param olderThanDays delete messages older than this many days
     * @return future containing number of messages deleted
     */
    Future<Long> cleanupDeadLetters(String setupId, int olderThanDays);

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

    /**
     * Updates the heartbeat for a subscription.
     *
     * @param setupId the setup identifier
     * @param topic the topic name
     * @param groupName the consumer group name
     * @return future that completes when heartbeat is updated
     */
    Future<Void> updateHeartbeat(String setupId, String topic, String groupName);

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

    /**
     * Gets an event as of a specific transaction time.
     *
     * @param setupId the setup identifier
     * @param storeName the event store name
     * @param eventId the event identifier
     * @param asOfTime the transaction time to query at
     * @return future containing the event as it was at that time
     */
    Future<BiTemporalEvent> getEventAsOf(String setupId, String storeName, String eventId, Instant asOfTime);

    /**
     * Gets statistics for an event store.
     *
     * @param setupId the setup identifier
     * @param storeName the event store name
     * @return future containing event store statistics
     */
    Future<EventStoreStats> getEventStoreStats(String setupId, String storeName);

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

    /**
     * Streams messages from a queue using SSE.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param options streaming options
     * @return a read stream of messages as JSON objects
     */
    ReadStream<JsonObject> streamMessages(String setupId, String queueName, StreamOptions options);

    // ========================================================================
    // Webhook Subscription Operations
    // ========================================================================

    /**
     * Creates a webhook subscription for a queue.
     *
     * @param setupId the setup identifier
     * @param queueName the queue name
     * @param request the webhook subscription request
     * @return future containing the subscription info
     */
    Future<WebhookSubscriptionInfo> createWebhookSubscription(String setupId, String queueName, WebhookSubscriptionRequest request);

    /**
     * Gets a webhook subscription by ID.
     *
     * @param subscriptionId the subscription identifier
     * @return future containing the subscription info
     */
    Future<WebhookSubscriptionInfo> getWebhookSubscription(String subscriptionId);

    /**
     * Deletes a webhook subscription.
     *
     * @param subscriptionId the subscription identifier
     * @return future that completes when deletion is done
     */
    Future<Void> deleteWebhookSubscription(String subscriptionId);

    // ========================================================================
    // Management API Operations
    // ========================================================================

    /**
     * Gets the global health status.
     *
     * @return future containing health status as JSON
     */
    Future<JsonObject> getGlobalHealth();

    /**
     * Gets system overview for management UI.
     *
     * @return future containing system overview
     */
    Future<SystemOverview> getSystemOverview();

    /**
     * Gets metrics for the system.
     *
     * @return future containing metrics as JSON
     */
    Future<JsonObject> getMetrics();

    /**
     * Gets all queues across all setups.
     *
     * @return future containing list of queue info
     */
    Future<List<QueueInfo>> getQueues();

    /**
     * Gets all event stores across all setups.
     *
     * @return future containing list of event store info
     */
    Future<List<EventStoreInfo>> getEventStores();

    /**
     * Gets all consumer groups across all setups.
     *
     * @return future containing list of consumer group info
     */
    Future<List<ConsumerGroupInfo>> getConsumerGroups();

    /**
     * Gets messages from a queue (for debugging/browsing).
     *
     * @param setupId the setup ID
     * @param queueName the queue name
     * @param count maximum number of messages to return
     * @return future containing list of messages as JSON
     */
    Future<List<JsonObject>> getMessages(String setupId, String queueName, int count);

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Closes the client and releases resources.
     */
    @Override
    void close();
}

