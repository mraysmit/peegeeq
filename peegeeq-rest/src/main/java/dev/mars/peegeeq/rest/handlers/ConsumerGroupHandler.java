package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.ConsumerGroupStats;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageFilter;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Handler for Consumer Group Management API.
 *
 * Provides REST endpoints for creating, managing, and monitoring consumer groups
 * with load balancing and member coordination capabilities.
 *
 * <p>This handler integrates with the actual queue implementations via
 * {@link QueueFactory#createConsumerGroup(String, String, Class)} to create
 * real consumer groups backed by PostgreSQL (either native or outbox pattern).</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 2.0
 */
public class ConsumerGroupHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupHandler.class);

    private final DatabaseSetupService setupService;
    private final SubscriptionManagerFactory subscriptionManagerFactory;

    /**
     * Stores real ConsumerGroup instances created via QueueFactory.
     * Key format: "setupId:queueName:groupName"
     */
    private final Map<String, ConsumerGroup<Object>> consumerGroups = new ConcurrentHashMap<>();

    /**
     * Stores metadata about consumer groups for REST API responses.
     * Key format: "setupId:queueName:groupName"
     */
    private final Map<String, ConsumerGroupMetadata> consumerGroupMetadata = new ConcurrentHashMap<>();

    private final AtomicLong memberIdCounter = new AtomicLong(0);

    public ConsumerGroupHandler(DatabaseSetupService setupService, ObjectMapper objectMapper, SubscriptionManagerFactory subscriptionManagerFactory) {
        this.setupService = setupService;
        this.subscriptionManagerFactory = subscriptionManagerFactory;
    }

    /**
     * Metadata for consumer groups to support REST API responses.
     * Stores additional information not available from the ConsumerGroup interface.
     */
    private static class ConsumerGroupMetadata {
        final String groupName;
        final String setupId;
        final String queueName;
        final long createdAt;
        volatile long lastActivity;
        volatile int maxMembers = 10;
        volatile LoadBalancingStrategy loadBalancingStrategy = LoadBalancingStrategy.ROUND_ROBIN;
        volatile long sessionTimeout = 30000L;

        ConsumerGroupMetadata(String groupName, String setupId, String queueName) {
            this.groupName = groupName;
            this.setupId = setupId;
            this.queueName = queueName;
            this.createdAt = System.currentTimeMillis();
            this.lastActivity = this.createdAt;
        }

        void updateActivity() {
            this.lastActivity = System.currentTimeMillis();
        }
    }
    
    /**
     * Creates a new consumer group.
     * POST /api/v1/queues/{setupId}/{queueName}/consumer-groups
     *
     * <p>This method creates a real consumer group via {@link QueueFactory#createConsumerGroup(String, String, Class)},
     * which is backed by PostgreSQL (either native or outbox pattern depending on the queue configuration).</p>
     *
     * <p>Optionally accepts subscriptionOptions in the request body to configure the consumer group's
     * subscription settings in a single call. If provided, the subscription will be created via
     * SubscriptionService before starting the consumer group.</p>
     *
     * <p>Optionally accepts groupFilter in the request body to filter messages at the group level.
     * The group filter is applied before messages are distributed to individual consumers.</p>
     */
    @SuppressWarnings("unchecked")
    public void createConsumerGroup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.info("Creating consumer group for queue {} in setup {}", queueName, setupId);

        try {
            JsonObject requestBody = ctx.body().asJsonObject();
            String groupName = requestBody.getString("groupName");

            if (groupName == null || groupName.trim().isEmpty()) {
                sendError(ctx, 400, "Consumer group name is required");
                return;
            }

            String groupKey = createGroupKey(setupId, queueName, groupName);

            if (consumerGroups.containsKey(groupKey)) {
                sendError(ctx, 409, "Consumer group already exists: " + groupName);
                return;
            }

            // Parse optional subscription options from request
            SubscriptionOptions subscriptionOptions = null;
            if (requestBody.containsKey("subscriptionOptions")) {
                try {
                    subscriptionOptions = parseSubscriptionOptions(requestBody.getJsonObject("subscriptionOptions"));
                    logger.info("Consumer group '{}' will be created with subscription options: {}",
                               groupName, subscriptionOptions);
                } catch (Exception e) {
                    sendError(ctx, 400, "Invalid subscription options: " + e.getMessage());
                    return;
                }
            }

            // Parse optional group filter from request
            Predicate<Message<Object>> groupFilter = null;
            if (requestBody.containsKey("groupFilter")) {
                try {
                    groupFilter = parseMessageFilter(requestBody.getJsonObject("groupFilter"));
                    logger.info("Consumer group '{}' will be created with group filter: {}",
                               groupName, requestBody.getJsonObject("groupFilter").encode());
                } catch (Exception e) {
                    sendError(ctx, 400, "Invalid group filter: " + e.getMessage());
                    return;
                }
            }

            // Capture options for use in async callback
            final SubscriptionOptions finalSubscriptionOptions = subscriptionOptions;
            final Predicate<Message<Object>> finalGroupFilter = groupFilter;

            // Validate setup and queue exist, then create real consumer group
            setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }

                    QueueFactory queueFactory = setupResult.getQueueFactories().get(queueName);
                    if (queueFactory == null) {
                        sendError(ctx, 404, "Queue not found: " + queueName);
                        return;
                    }

                    try {
                        // Create real consumer group via QueueFactory
                        // Using Object.class as the payload type for generic REST API usage
                        ConsumerGroup<Object> realConsumerGroup = (ConsumerGroup<Object>)
                            queueFactory.createConsumerGroup(groupName, queueName, Object.class);

                        // Apply group filter if provided
                        if (finalGroupFilter != null) {
                            realConsumerGroup.setGroupFilter(finalGroupFilter);
                            logger.info("Applied group filter to consumer group '{}'", groupName);
                        }

                        // If subscription options provided, create subscription before starting
                        if (finalSubscriptionOptions != null) {
                            String topic = setupId + "-" + queueName;
                            logger.info("Creating subscription for group '{}' on topic '{}' with options: {}",
                                       groupName, topic, finalSubscriptionOptions);

                            try {
                                SubscriptionService subscriptionService = subscriptionManagerFactory.getManager(setupId);
                                subscriptionService.subscribe(topic, groupName, finalSubscriptionOptions)
                                    .toCompletionStage()
                                    .toCompletableFuture()
                                    .get(5, java.util.concurrent.TimeUnit.SECONDS);

                                logger.info("Subscription created successfully for group '{}' on topic '{}'",
                                           groupName, topic);
                            } catch (Exception e) {
                                logger.error("Failed to create subscription for group '{}': {}", groupName, e.getMessage());
                                // Don't fail the consumer group creation - subscription can be set later
                                logger.warn("Consumer group '{}' created without subscription options due to error", groupName);
                            }
                        }

                        // Store the real consumer group
                        consumerGroups.put(groupKey, realConsumerGroup);

                        // Create and store metadata for REST API responses
                        ConsumerGroupMetadata metadata = new ConsumerGroupMetadata(groupName, setupId, queueName);

                        // Apply configuration from request
                        if (requestBody.containsKey("maxMembers")) {
                            metadata.maxMembers = Math.max(1, Math.min(100, requestBody.getInteger("maxMembers", 10)));
                        }

                        if (requestBody.containsKey("loadBalancingStrategy")) {
                            String strategy = requestBody.getString("loadBalancingStrategy", "ROUND_ROBIN");
                            metadata.loadBalancingStrategy = LoadBalancingStrategy.valueOf(strategy);
                        }

                        if (requestBody.containsKey("sessionTimeout")) {
                            long timeout = requestBody.getLong("sessionTimeout", 30000L);
                            metadata.sessionTimeout = Math.max(5000L, Math.min(300000L, timeout));
                        }

                        consumerGroupMetadata.put(groupKey, metadata);

                        JsonObject response = new JsonObject()
                            .put("message", "Consumer group created successfully")
                            .put("groupName", groupName)
                            .put("setupId", setupId)
                            .put("queueName", queueName)
                            .put("groupId", groupKey) // Use groupKey as ID
                            .put("maxMembers", metadata.maxMembers)
                            .put("loadBalancingStrategy", metadata.loadBalancingStrategy.name())
                            .put("sessionTimeout", metadata.sessionTimeout)
                            .put("implementationType", queueFactory.getImplementationType())
                            .put("subscriptionConfigured", finalSubscriptionOptions != null)
                            .put("timestamp", System.currentTimeMillis());

                        ctx.response()
                            .setStatusCode(201)
                            .putHeader("content-type", "application/json")
                            .end(response.encode());

                        logger.info("Consumer group created via QueueFactory: {} for queue {} in setup {} (type: {})",
                                   groupName, queueName, setupId, queueFactory.getImplementationType());

                    } catch (Exception e) {
                        logger.error("Failed to create consumer group via QueueFactory: {}", e.getMessage(), e);
                        sendError(ctx, 500, "Failed to create consumer group: " + e.getMessage());
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("Error creating consumer group {}: {}", groupName, throwable.getMessage(), throwable);
                    sendError(ctx, 500, "Failed to create consumer group: " + throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            logger.error("Error processing create consumer group request: {}", e.getMessage(), e);
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
        }
    }
    
    /**
     * Lists all consumer groups for a queue.
     * GET /api/v1/queues/{setupId}/{queueName}/consumer-groups
     *
     * <p>Returns information from real consumer groups created via QueueFactory.</p>
     */
    public void listConsumerGroups(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        logger.debug("Listing consumer groups for queue {} in setup {}", queueName, setupId);

        JsonArray groups = new JsonArray();
        String queuePrefix = setupId + ":" + queueName + ":";

        consumerGroups.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(queuePrefix))
            .forEach(entry -> {
                String groupKey = entry.getKey();
                ConsumerGroup<Object> realGroup = entry.getValue();
                ConsumerGroupMetadata metadata = consumerGroupMetadata.get(groupKey);

                JsonObject groupInfo = new JsonObject()
                    .put("groupName", realGroup.getGroupName())
                    .put("groupId", groupKey)
                    .put("setupId", metadata != null ? metadata.setupId : setupId)
                    .put("queueName", metadata != null ? metadata.queueName : queueName)
                    .put("topic", realGroup.getTopic())
                    .put("memberCount", realGroup.getActiveConsumerCount())
                    .put("consumerIds", new JsonArray(realGroup.getConsumerIds().stream().toList()))
                    .put("isActive", realGroup.isActive())
                    .put("maxMembers", metadata != null ? metadata.maxMembers : 10)
                    .put("loadBalancingStrategy", metadata != null ? metadata.loadBalancingStrategy.name() : "ROUND_ROBIN")
                    .put("sessionTimeout", metadata != null ? metadata.sessionTimeout : 30000L)
                    .put("createdAt", metadata != null ? metadata.createdAt : 0L)
                    .put("lastActivity", metadata != null ? metadata.lastActivity : 0L);

                // Add stats if available
                try {
                    ConsumerGroupStats stats = realGroup.getStats();
                    if (stats != null) {
                        groupInfo.put("stats", new JsonObject()
                            .put("messagesProcessed", stats.getTotalMessagesProcessed())
                            .put("messagesFiltered", stats.getTotalMessagesFiltered())
                            .put("messagesFailed", stats.getTotalMessagesFailed()));
                    }
                } catch (Exception e) {
                    logger.debug("Could not get stats for consumer group {}: {}", groupKey, e.getMessage());
                }

                groups.add(groupInfo);
            });

        JsonObject response = new JsonObject()
            .put("message", "Consumer groups retrieved successfully")
            .put("setupId", setupId)
            .put("queueName", queueName)
            .put("groupCount", groups.size())
            .put("groups", groups)
            .put("timestamp", System.currentTimeMillis());

        ctx.response()
            .putHeader("content-type", "application/json")
            .end(response.encode());
    }

    /**
     * Gets details of a specific consumer group.
     * GET /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}
     *
     * <p>Returns detailed information from the real consumer group created via QueueFactory,
     * including all consumer members and their states.</p>
     */
    public void getConsumerGroup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");

        logger.debug("Getting consumer group {} for queue {} in setup {}", groupName, queueName, setupId);

        String groupKey = createGroupKey(setupId, queueName, groupName);
        ConsumerGroup<Object> realGroup = consumerGroups.get(groupKey);

        if (realGroup == null) {
            sendError(ctx, 404, "Consumer group not found: " + groupName);
            return;
        }

        ConsumerGroupMetadata metadata = consumerGroupMetadata.get(groupKey);

        // Build member information from real consumer group
        JsonArray members = new JsonArray();
        Set<String> consumerIds = realGroup.getConsumerIds();
        for (String consumerId : consumerIds) {
            JsonObject memberInfo = new JsonObject()
                .put("consumerId", consumerId)
                .put("groupName", realGroup.getGroupName())
                .put("topic", realGroup.getTopic());
            members.add(memberInfo);
        }

        JsonObject response = new JsonObject()
            .put("message", "Consumer group retrieved successfully")
            .put("groupName", realGroup.getGroupName())
            .put("groupId", groupKey)
            .put("setupId", metadata != null ? metadata.setupId : setupId)
            .put("queueName", metadata != null ? metadata.queueName : queueName)
            .put("topic", realGroup.getTopic())
            .put("memberCount", realGroup.getActiveConsumerCount())
            .put("consumerIds", new JsonArray(consumerIds.stream().toList()))
            .put("isActive", realGroup.isActive())
            .put("maxMembers", metadata != null ? metadata.maxMembers : 10)
            .put("loadBalancingStrategy", metadata != null ? metadata.loadBalancingStrategy.name() : "ROUND_ROBIN")
            .put("sessionTimeout", metadata != null ? metadata.sessionTimeout : 30000L)
            .put("createdAt", metadata != null ? metadata.createdAt : 0L)
            .put("lastActivity", metadata != null ? metadata.lastActivity : 0L)
            .put("members", members)
            .put("timestamp", System.currentTimeMillis());

        // Add stats if available
        try {
            ConsumerGroupStats stats = realGroup.getStats();
            if (stats != null) {
                response.put("stats", new JsonObject()
                    .put("messagesProcessed", stats.getTotalMessagesProcessed())
                    .put("messagesFiltered", stats.getTotalMessagesFiltered())
                    .put("messagesFailed", stats.getTotalMessagesFailed())
                    .put("averageProcessingTimeMs", stats.getAverageProcessingTimeMs()));
            }
        } catch (Exception e) {
            logger.debug("Could not get stats for consumer group {}: {}", groupKey, e.getMessage());
        }

        ctx.response()
            .putHeader("content-type", "application/json")
            .end(response.encode());
    }
    
    /**
     * Joins a consumer group by adding a consumer.
     * POST /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}/members
     *
     * <p>This method calls {@link ConsumerGroup#addConsumer(String, MessageHandler)} on the real
     * consumer group to add a new consumer member. The consumer is created with a placeholder
     * handler that logs messages (actual message processing is handled by the consumer group's
     * internal distribution mechanism).</p>
     *
     * <p>Optionally accepts a messageFilter in the request body to filter messages for this specific consumer.
     * The filter is a JSON object with the following structure:</p>
     * <pre>{@code
     * {
     *   "memberName": "my-consumer",
     *   "messageFilter": {
     *     "type": "header",           // Filter type: "header", "headerIn", "region", "priority", "and"
     *     "headerKey": "region",      // For "header" and "headerIn" types
     *     "headerValue": "US",        // For "header" type
     *     "allowedValues": ["US", "EU"], // For "headerIn" and "region" types
     *     "minPriority": "HIGH",      // For "priority" type
     *     "filters": [...]            // For "and" type (array of nested filters)
     *   }
     * }
     * }</pre>
     */
    public void joinConsumerGroup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");

        logger.info("Member joining consumer group {} for queue {} in setup {}", groupName, queueName, setupId);

        try {
            JsonObject requestBody = ctx.body().asJsonObject();
            String memberName = requestBody.getString("memberName", "member-" + memberIdCounter.incrementAndGet());

            String groupKey = createGroupKey(setupId, queueName, groupName);
            ConsumerGroup<Object> realGroup = consumerGroups.get(groupKey);

            if (realGroup == null) {
                sendError(ctx, 404, "Consumer group not found: " + groupName);
                return;
            }

            ConsumerGroupMetadata metadata = consumerGroupMetadata.get(groupKey);
            if (metadata != null && realGroup.getActiveConsumerCount() >= metadata.maxMembers) {
                sendError(ctx, 409, "Consumer group is full (max " + metadata.maxMembers + " members)");
                return;
            }

            // Generate unique consumer ID
            String consumerId = memberName + "-" + memberIdCounter.incrementAndGet();

            // Create a placeholder message handler for REST API consumers
            // In a real scenario, the consumer would have its own handler for processing messages
            MessageHandler<Object> placeholderHandler = message -> {
                logger.debug("REST consumer {} received message: {}", consumerId, message.getId());
                return CompletableFuture.completedFuture(null);
            };

            // Parse message filter if provided
            Predicate<Message<Object>> messageFilter = null;
            if (requestBody.containsKey("messageFilter")) {
                try {
                    messageFilter = parseMessageFilter(requestBody.getJsonObject("messageFilter"));
                    logger.info("Parsed message filter for consumer {}: {}", consumerId, requestBody.getJsonObject("messageFilter").encode());
                } catch (Exception e) {
                    sendError(ctx, 400, "Invalid message filter: " + e.getMessage());
                    return;
                }
            }

            // Add consumer to the real consumer group via ConsumerGroup.addConsumer()
            ConsumerGroupMember<Object> member;
            if (messageFilter != null) {
                member = realGroup.addConsumer(consumerId, placeholderHandler, messageFilter);
            } else {
                member = realGroup.addConsumer(consumerId, placeholderHandler);
            }

            // Update metadata
            if (metadata != null) {
                metadata.updateActivity();
            }

            JsonObject response = new JsonObject()
                .put("message", "Successfully joined consumer group")
                .put("groupName", realGroup.getGroupName())
                .put("consumerId", consumerId)
                .put("memberName", memberName)
                .put("topic", realGroup.getTopic())
                .put("isActive", member.isActive())
                .put("joinedAt", member.getJoinedAt() != null ? member.getJoinedAt().toString() : null)
                .put("memberCount", realGroup.getActiveConsumerCount())
                .put("timestamp", System.currentTimeMillis());

            ctx.response()
                .setStatusCode(201)
                .putHeader("content-type", "application/json")
                .end(response.encode());

            logger.info("Consumer {} joined consumer group {} via ConsumerGroup.addConsumer()", consumerId, groupName);

        } catch (IllegalArgumentException e) {
            logger.warn("Consumer already exists in group: {}", e.getMessage());
            sendError(ctx, 409, "Consumer already exists: " + e.getMessage());
        } catch (IllegalStateException e) {
            logger.warn("Consumer group is closed: {}", e.getMessage());
            sendError(ctx, 400, "Consumer group is closed: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error processing join consumer group request: {}", e.getMessage(), e);
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
        }
    }

    /**
     * Leaves a consumer group by removing a consumer.
     * DELETE /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}/members/{memberId}
     *
     * <p>This method calls {@link ConsumerGroup#removeConsumer(String)} on the real consumer group
     * to remove the specified consumer member.</p>
     */
    public void leaveConsumerGroup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");
        String consumerId = ctx.pathParam("memberId"); // memberId in URL maps to consumerId

        logger.info("Consumer {} leaving consumer group {} for queue {} in setup {}", consumerId, groupName, queueName, setupId);

        String groupKey = createGroupKey(setupId, queueName, groupName);
        ConsumerGroup<Object> realGroup = consumerGroups.get(groupKey);

        if (realGroup == null) {
            sendError(ctx, 404, "Consumer group not found: " + groupName);
            return;
        }

        // Remove consumer from the real consumer group via ConsumerGroup.removeConsumer()
        boolean removed = realGroup.removeConsumer(consumerId);

        if (!removed) {
            sendError(ctx, 404, "Consumer not found in consumer group: " + consumerId);
            return;
        }

        // Update metadata
        ConsumerGroupMetadata metadata = consumerGroupMetadata.get(groupKey);
        if (metadata != null) {
            metadata.updateActivity();
        }

        JsonObject response = new JsonObject()
            .put("message", "Successfully left consumer group")
            .put("groupName", realGroup.getGroupName())
            .put("consumerId", consumerId)
            .put("memberCount", realGroup.getActiveConsumerCount())
            .put("timestamp", System.currentTimeMillis());

        ctx.response()
            .putHeader("content-type", "application/json")
            .end(response.encode());

        logger.info("Consumer {} left consumer group {} via ConsumerGroup.removeConsumer()", consumerId, groupName);
    }

    /**
     * Deletes a consumer group.
     * DELETE /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}
     *
     * <p>This method calls {@link ConsumerGroup#close()} on the real consumer group
     * to properly release all resources and stop all consumers.</p>
     */
    public void deleteConsumerGroup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");

        logger.info("Deleting consumer group {} for queue {} in setup {}", groupName, queueName, setupId);

        String groupKey = createGroupKey(setupId, queueName, groupName);
        ConsumerGroup<Object> realGroup = consumerGroups.remove(groupKey);
        consumerGroupMetadata.remove(groupKey);

        if (realGroup == null) {
            sendError(ctx, 404, "Consumer group not found: " + groupName);
            return;
        }

        // Close the real consumer group via ConsumerGroup.close()
        try {
            realGroup.close();
            logger.info("Consumer group {} closed successfully via ConsumerGroup.close()", groupName);
        } catch (Exception e) {
            logger.warn("Error closing consumer group {}: {}", groupName, e.getMessage());
            // Continue with response - group is already removed from our tracking
        }

        JsonObject response = new JsonObject()
            .put("message", "Consumer group deleted successfully")
            .put("groupName", groupName)
            .put("setupId", setupId)
            .put("queueName", queueName)
            .put("timestamp", System.currentTimeMillis());

        ctx.response()
            .putHeader("content-type", "application/json")
            .end(response.encode());

        logger.info("Consumer group deleted: {}", groupName);
    }
    
    /**
     * Creates a unique key for a consumer group.
     */
    private String createGroupKey(String setupId, String queueName, String groupName) {
        return setupId + ":" + queueName + ":" + groupName;
    }
    
    /**
     * Sends an error response.
     */
    private void sendError(RoutingContext ctx, int statusCode, String message) {
        JsonObject error = new JsonObject()
                .put("error", message)
                .put("timestamp", System.currentTimeMillis());
        
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("content-type", "application/json")
                .end(error.encode());
    }
    
    /**
     * Gets statistics about all consumer groups.
     *
     * <p>Returns aggregate statistics from all real consumer groups.</p>
     */
    public JsonObject getStatistics() {
        int totalGroups = consumerGroups.size();
        int totalMembers = consumerGroups.values().stream()
            .mapToInt(ConsumerGroup::getActiveConsumerCount)
            .sum();

        long totalMessagesProcessed = 0;
        long totalMessagesFailed = 0;

        for (ConsumerGroup<Object> group : consumerGroups.values()) {
            try {
                ConsumerGroupStats stats = group.getStats();
                if (stats != null) {
                    totalMessagesProcessed += stats.getTotalMessagesProcessed();
                    totalMessagesFailed += stats.getTotalMessagesFailed();
                }
            } catch (Exception e) {
                logger.debug("Could not get stats for consumer group: {}", e.getMessage());
            }
        }

        return new JsonObject()
            .put("totalGroups", totalGroups)
            .put("totalMembers", totalMembers)
            .put("totalMessagesProcessed", totalMessagesProcessed)
            .put("totalMessagesFailed", totalMessagesFailed)
            .put("timestamp", System.currentTimeMillis());
    }

    /**
     * Updates subscription options for a consumer group.
     * POST /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription
     */
    public void updateSubscriptionOptions(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");

        logger.info("Updating subscription options for consumer group '{}' on queue '{}' in setup '{}'",
                   groupName, queueName, setupId);

        try {
            // Validate consumer group exists
            String key = createGroupKey(setupId, queueName, groupName);
            ConsumerGroup<Object> group = consumerGroups.get(key);
            if (group == null) {
                sendError(ctx, 404, "Consumer group not found: " + groupName);
                return;
            }
            
            JsonObject body = ctx.body().asJsonObject();
            
            // Parse subscription options from request
            SubscriptionOptions options = parseSubscriptionOptions(body);
            
            // Use topic naming convention: setupId-queueName
            String topic = setupId + "-" + queueName;
            
            // Get SubscriptionService for this setup
            SubscriptionService subscriptionService = subscriptionManagerFactory.getManager(setupId);

            // Subscribe via SubscriptionService (database-backed)
            subscriptionService.subscribe(topic, groupName, options)
                .onSuccess(v -> {
                    logger.info("Successfully updated subscription options for group '{}' on topic '{}'", groupName, topic);
                    
                    // Return success response
                    JsonObject response = new JsonObject()
                        .put("setupId", setupId)
                        .put("queueName", queueName)
                        .put("groupName", groupName)
                        .put("subscriptionOptions", toJsonObject(options))
                        .put("message", "Subscription options updated successfully")
                        .put("timestamp", System.currentTimeMillis());
                    
                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(response.encode());
                })
                .onFailure(throwable -> {
                    logger.error("Failed to update subscription options in database", throwable);
                    sendError(ctx, 500, "Failed to update subscription: " + throwable.getMessage());
                });
                
        } catch (IllegalArgumentException e) {
            logger.error("Invalid subscription options for consumer group '{}'", groupName, e);
            sendError(ctx, 400, "Invalid subscription options: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to update subscription options for consumer group '{}'", groupName, e);
            sendError(ctx, 500, "Internal server error: " + e.getMessage());
        }
    }
    
    /**
     * Gets subscription options for a consumer group.
     * GET /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription
     */
    public void getSubscriptionOptions(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");
        
        // Use topic naming convention: setupId-queueName
        String topic = setupId + "-" + queueName;
        
        // Get SubscriptionService for this setup
        SubscriptionService subscriptionService = subscriptionManagerFactory.getManager(setupId);

        // Fetch from SubscriptionService (database-backed)
        subscriptionService.getSubscription(topic, groupName)
            .onSuccess(subscriptionInfo -> {
                if (subscriptionInfo == null) {
                    // If subscription not found, return defaults
                    logger.debug("Subscription not found for group '{}' on topic '{}', returning defaults", groupName, topic);
                    SubscriptionOptions options = SubscriptionOptions.defaults();

                    JsonObject response = new JsonObject()
                        .put("setupId", setupId)
                        .put("queueName", queueName)
                        .put("groupName", groupName)
                        .put("status", "NOT_CONFIGURED")
                        .put("subscriptionOptions", toJsonObject(options))
                        .put("timestamp", System.currentTimeMillis());

                    ctx.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json")
                        .end(response.encode());
                    return;
                }

                // Convert SubscriptionInfo to SubscriptionOptions
                SubscriptionOptions options = subscriptionInfoToOptions(subscriptionInfo);

                JsonObject response = new JsonObject()
                    .put("setupId", setupId)
                    .put("queueName", queueName)
                    .put("groupName", groupName)
                    .put("status", subscriptionInfo.state().name())
                    .put("subscriptionOptions", toJsonObject(options))
                    .put("lastHeartbeat", subscriptionInfo.lastHeartbeatAt() != null ? subscriptionInfo.lastHeartbeatAt().toString() : null)
                    .put("createdAt", subscriptionInfo.subscribedAt().toString())
                    .put("timestamp", System.currentTimeMillis());

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(response.encode());
            })
            .onFailure(throwable -> {
                // If subscription not found, return defaults
                logger.debug("Subscription not found for group '{}' on topic '{}', returning defaults", groupName, topic);
                SubscriptionOptions options = SubscriptionOptions.defaults();
                
                JsonObject response = new JsonObject()
                    .put("setupId", setupId)
                    .put("queueName", queueName)
                    .put("groupName", groupName)
                    .put("status", "NOT_CONFIGURED")
                    .put("subscriptionOptions", toJsonObject(options))
                    .put("timestamp", System.currentTimeMillis());
                
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(response.encode());
            });
    }
    
    /**
     * Converts a SubscriptionInfo to SubscriptionOptions.
     */
    private SubscriptionOptions subscriptionInfoToOptions(SubscriptionInfo subscriptionInfo) {
        logger.debug("Converting SubscriptionInfo to SubscriptionOptions: startFromMessageId={}, startFromTimestamp={}",
                    subscriptionInfo.startFromMessageId(), subscriptionInfo.startFromTimestamp());

        SubscriptionOptions.Builder builder = SubscriptionOptions.builder()
            .heartbeatIntervalSeconds(subscriptionInfo.heartbeatIntervalSeconds())
            .heartbeatTimeoutSeconds(subscriptionInfo.heartbeatTimeoutSeconds());

        // Determine start position from subscription data
        if (subscriptionInfo.startFromMessageId() != null) {
            // Special case: start_from_message_id = 1 means FROM_BEGINNING
            if (subscriptionInfo.startFromMessageId() == 1L) {
                logger.debug("Detected FROM_BEGINNING (start_from_message_id=1)");
                builder.startPosition(StartPosition.FROM_BEGINNING);
            } else {
                logger.debug("Using FROM_MESSAGE_ID with id={}", subscriptionInfo.startFromMessageId());
                builder.startPosition(StartPosition.FROM_MESSAGE_ID)
                       .startFromMessageId(subscriptionInfo.startFromMessageId());
            }
        } else if (subscriptionInfo.startFromTimestamp() != null) {
            logger.debug("Using FROM_TIMESTAMP with timestamp={}", subscriptionInfo.startFromTimestamp());
            builder.startPosition(StartPosition.FROM_TIMESTAMP)
                   .startFromTimestamp(subscriptionInfo.startFromTimestamp());
        } else {
            // Default to FROM_NOW
            logger.debug("No start position specified, defaulting to FROM_NOW");
            builder.startPosition(StartPosition.FROM_NOW);
        }

        SubscriptionOptions options = builder.build();
        logger.debug("Converted to SubscriptionOptions with startPosition={}", options.getStartPosition());
        return options;
    }
    
    /**
     * Deletes a consumer group subscription configuration.
     * DELETE /api/v1/consumer-groups/:setupId/:queueName/:groupName/subscription
     */
    public void deleteSubscriptionOptions(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");
        
        logger.info("Deleting subscription options for consumer group '{}' on queue '{}' in setup '{}'",
                   groupName, queueName, setupId);
        
        // Use topic naming convention: setupId-queueName
        String topic = setupId + "-" + queueName;
        
        // Get SubscriptionService for this setup
        SubscriptionService subscriptionService = subscriptionManagerFactory.getManager(setupId);

        // Cancel subscription via SubscriptionService (database-backed)
        subscriptionService.cancel(topic, groupName)
            .onSuccess(v -> {
                logger.info("Successfully deleted subscription for group '{}' on topic '{}'", groupName, topic);
                ctx.response().setStatusCode(204).end();
            })
            .onFailure(throwable -> {
                logger.warn("Failed to delete subscription (may not exist): {}", throwable.getMessage());
                // Still return 204 - idempotent delete
                ctx.response().setStatusCode(204).end();
            });
    }
    
    /**
     * Gets subscription options for a consumer group (internal use).
     * Returns null if consumer group doesn't exist or has no subscription options configured.
     * Caller should handle null by using defaults if appropriate.
     * 
     * Note: This is a BLOCKING call that waits for the database Future to complete.
     * Only use this from non-event-loop contexts.
     */
    public SubscriptionOptions getSubscriptionOptionsInternal(String setupId, String queueName, String groupName) {
        // Use topic naming convention: setupId-queueName
        String topic = setupId + "-" + queueName;

        try {
            // Get SubscriptionService for this setup
            SubscriptionService subscriptionService = subscriptionManagerFactory.getManager(setupId);

            // Block and wait for the database future to complete
            // This is acceptable in SSE handler context as it's already async
            SubscriptionInfo subscriptionInfo = subscriptionService.getSubscription(topic, groupName)
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, java.util.concurrent.TimeUnit.SECONDS);

            // If no subscription found, return null (caller will use defaults)
            if (subscriptionInfo == null) {
                return null;
            }

            return subscriptionInfoToOptions(subscriptionInfo);
        } catch (Exception e) {
            logger.debug("No subscription options found for consumer group '{}' on topic '{}', caller should use defaults: {}",
                       groupName, topic, e.getMessage());
            return null;
        }
    }
    
    /**
     * Parses subscription options from JSON.
     */
    private SubscriptionOptions parseSubscriptionOptions(JsonObject json) {
        SubscriptionOptions.Builder builder = SubscriptionOptions.builder();
        
        // Parse start position
        String startPositionStr = json.getString("startPosition");
        if (startPositionStr != null) {
            try {
                StartPosition startPosition = StartPosition.valueOf(startPositionStr);
                builder.startPosition(startPosition);
                
                // Parse position-specific fields
                switch (startPosition) {
                    case FROM_MESSAGE_ID:
                        Long messageId = json.getLong("startFromMessageId");
                        if (messageId != null) {
                            builder.startFromMessageId(messageId);
                        }
                        break;
                    case FROM_TIMESTAMP:
                        String timestampStr = json.getString("startFromTimestamp");
                        if (timestampStr != null) {
                            Instant timestamp = Instant.parse(timestampStr);
                            builder.startFromTimestamp(timestamp);
                        }
                        break;
                    default:
                        // FROM_NOW and FROM_BEGINNING don't need additional fields
                        break;
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid start position: " + startPositionStr);
            }
        }
        
        // Parse heartbeat settings
        Integer heartbeatInterval = json.getInteger("heartbeatIntervalSeconds");
        if (heartbeatInterval != null) {
            builder.heartbeatIntervalSeconds(heartbeatInterval);
        }
        
        Integer heartbeatTimeout = json.getInteger("heartbeatTimeoutSeconds");
        if (heartbeatTimeout != null) {
            builder.heartbeatTimeoutSeconds(heartbeatTimeout);
        }
        
        return builder.build();
    }
    
    /**
     * Converts SubscriptionOptions to JSON.
     */
    private JsonObject toJsonObject(SubscriptionOptions options) {
        JsonObject json = new JsonObject()
            .put("startPosition", options.getStartPosition().name())
            .put("heartbeatIntervalSeconds", options.getHeartbeatIntervalSeconds())
            .put("heartbeatTimeoutSeconds", options.getHeartbeatTimeoutSeconds());

        if (options.getStartFromMessageId() != null) {
            json.put("startFromMessageId", options.getStartFromMessageId());
        }

        if (options.getStartFromTimestamp() != null) {
            json.put("startFromTimestamp", options.getStartFromTimestamp().toString());
        }

        return json;
    }

    /**
     * Parses a message filter from JSON.
     *
     * <p>Supported filter types:</p>
     * <ul>
     *   <li><b>header</b>: Filter by exact header value - requires "headerKey" and "headerValue"</li>
     *   <li><b>headerIn</b>: Filter by header value in set - requires "headerKey" and "allowedValues" (array)</li>
     *   <li><b>region</b>: Filter by region header - requires "allowedValues" (array)</li>
     *   <li><b>priority</b>: Filter by minimum priority - requires "minPriority" (HIGH, NORMAL, or LOW)</li>
     *   <li><b>and</b>: Combine multiple filters with AND logic - requires "filters" (array of filter objects)</li>
     * </ul>
     *
     * @param filterJson The JSON object representing the filter
     * @return A predicate that filters messages
     * @throws IllegalArgumentException if the filter JSON is invalid
     */
    private Predicate<Message<Object>> parseMessageFilter(JsonObject filterJson) {
        String type = filterJson.getString("type");
        if (type == null) {
            throw new IllegalArgumentException("Filter type is required");
        }

        return switch (type.toLowerCase()) {
            case "header" -> {
                String headerKey = filterJson.getString("headerKey");
                String headerValue = filterJson.getString("headerValue");
                if (headerKey == null || headerValue == null) {
                    throw new IllegalArgumentException("Filter type 'header' requires 'headerKey' and 'headerValue'");
                }
                yield MessageFilter.byHeader(headerKey, headerValue);
            }
            case "headerin" -> {
                String headerKey = filterJson.getString("headerKey");
                JsonArray allowedValuesArray = filterJson.getJsonArray("allowedValues");
                if (headerKey == null || allowedValuesArray == null) {
                    throw new IllegalArgumentException("Filter type 'headerIn' requires 'headerKey' and 'allowedValues'");
                }
                Set<String> allowedValues = new HashSet<>();
                for (int i = 0; i < allowedValuesArray.size(); i++) {
                    allowedValues.add(allowedValuesArray.getString(i));
                }
                yield MessageFilter.byHeaderIn(headerKey, allowedValues);
            }
            case "region" -> {
                JsonArray allowedRegionsArray = filterJson.getJsonArray("allowedValues");
                if (allowedRegionsArray == null) {
                    throw new IllegalArgumentException("Filter type 'region' requires 'allowedValues'");
                }
                Set<String> allowedRegions = new HashSet<>();
                for (int i = 0; i < allowedRegionsArray.size(); i++) {
                    allowedRegions.add(allowedRegionsArray.getString(i));
                }
                yield MessageFilter.byRegion(allowedRegions);
            }
            case "priority" -> {
                String minPriority = filterJson.getString("minPriority");
                if (minPriority == null) {
                    throw new IllegalArgumentException("Filter type 'priority' requires 'minPriority'");
                }
                yield MessageFilter.byPriority(minPriority);
            }
            case "and" -> {
                JsonArray filtersArray = filterJson.getJsonArray("filters");
                if (filtersArray == null || filtersArray.isEmpty()) {
                    throw new IllegalArgumentException("Filter type 'and' requires 'filters' array with at least one filter");
                }
                List<Predicate<Message<Object>>> filters = new ArrayList<>();
                for (int i = 0; i < filtersArray.size(); i++) {
                    JsonObject nestedFilter = filtersArray.getJsonObject(i);
                    filters.add(parseMessageFilter(nestedFilter));
                }
                yield MessageFilter.and(filters.toArray(new Predicate[0]));
            }
            default -> throw new IllegalArgumentException("Unknown filter type: " + type);
        };
    }


}
