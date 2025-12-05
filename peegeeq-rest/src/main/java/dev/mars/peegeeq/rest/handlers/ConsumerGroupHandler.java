package dev.mars.peegeeq.rest.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.api.subscription.SubscriptionService;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handler for Consumer Group Management API.
 * 
 * Provides REST endpoints for creating, managing, and monitoring consumer groups
 * with load balancing and member coordination capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public class ConsumerGroupHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupHandler.class);
    
    private final DatabaseSetupService setupService;
    private final SubscriptionManagerFactory subscriptionManagerFactory;
    // Consumer group management
    private final Map<String, ConsumerGroup> consumerGroups = new ConcurrentHashMap<>();
    private final AtomicLong memberIdCounter = new AtomicLong(0);
    
    // Track consumer group subscription options (DEPRECATED - now using SubscriptionManager)
    private final Map<String, SubscriptionOptions> consumerGroupSubscriptions = new ConcurrentHashMap<>();
    
    public ConsumerGroupHandler(DatabaseSetupService setupService, ObjectMapper objectMapper, SubscriptionManagerFactory subscriptionManagerFactory) {
        this.setupService = setupService;
        this.subscriptionManagerFactory = subscriptionManagerFactory;
    }
    
    /**
     * Creates a new consumer group.
     * POST /api/v1/queues/{setupId}/{queueName}/consumer-groups
     */
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
            
            // Validate setup and queue exist
            setupService.getSetupResult(setupId)
                .thenAccept(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        sendError(ctx, 404, "Setup not found or not active: " + setupId);
                        return;
                    }
                    
                    if (!setupResult.getQueueFactories().containsKey(queueName)) {
                        sendError(ctx, 404, "Queue not found: " + queueName);
                        return;
                    }
                    
                    // Create consumer group
                    ConsumerGroup group = new ConsumerGroup(groupName, setupId, queueName);
                    
                    // Apply configuration from request
                    if (requestBody.containsKey("maxMembers")) {
                        group.setMaxMembers(requestBody.getInteger("maxMembers", 10));
                    }
                    
                    if (requestBody.containsKey("loadBalancingStrategy")) {
                        String strategy = requestBody.getString("loadBalancingStrategy", "ROUND_ROBIN");
                        group.setLoadBalancingStrategy(LoadBalancingStrategy.valueOf(strategy));
                    }
                    
                    if (requestBody.containsKey("sessionTimeout")) {
                        group.setSessionTimeout(requestBody.getLong("sessionTimeout", 30000L));
                    }
                    
                    consumerGroups.put(groupKey, group);
                    
                    JsonObject response = new JsonObject()
                        .put("message", "Consumer group created successfully")
                        .put("groupName", groupName)
                        .put("setupId", setupId)
                        .put("queueName", queueName)
                        .put("groupId", group.getGroupId())
                        .put("maxMembers", group.getMaxMembers())
                        .put("loadBalancingStrategy", group.getLoadBalancingStrategy().name())
                        .put("sessionTimeout", group.getSessionTimeout())
                        .put("timestamp", System.currentTimeMillis());
                    
                    ctx.response()
                        .setStatusCode(201)
                        .putHeader("content-type", "application/json")
                        .end(response.encode());
                    
                    logger.info("Consumer group created: {} for queue {} in setup {}", groupName, queueName, setupId);
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
                ConsumerGroup group = entry.getValue();
                JsonObject groupInfo = new JsonObject()
                    .put("groupName", group.getGroupName())
                    .put("groupId", group.getGroupId())
                    .put("setupId", group.getSetupId())
                    .put("queueName", group.getQueueName())
                    .put("memberCount", group.getMemberCount())
                    .put("maxMembers", group.getMaxMembers())
                    .put("loadBalancingStrategy", group.getLoadBalancingStrategy().name())
                    .put("sessionTimeout", group.getSessionTimeout())
                    .put("createdAt", group.getCreatedAt())
                    .put("lastActivity", group.getLastActivity());
                
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
     */
    public void getConsumerGroup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");
        
        logger.debug("Getting consumer group {} for queue {} in setup {}", groupName, queueName, setupId);
        
        String groupKey = createGroupKey(setupId, queueName, groupName);
        ConsumerGroup group = consumerGroups.get(groupKey);
        
        if (group == null) {
            sendError(ctx, 404, "Consumer group not found: " + groupName);
            return;
        }
        
        JsonArray members = new JsonArray();
        group.getMembers().forEach((memberId, member) -> {
            JsonObject memberInfo = new JsonObject()
                .put("memberId", member.getMemberId())
                .put("memberName", member.getMemberName())
                .put("joinedAt", member.getJoinedAt())
                .put("lastHeartbeat", member.getLastHeartbeat())
                .put("assignedPartitions", member.getAssignedPartitions())
                .put("status", member.getStatus().name());
            
            members.add(memberInfo);
        });
        
        JsonObject response = new JsonObject()
            .put("message", "Consumer group retrieved successfully")
            .put("groupName", group.getGroupName())
            .put("groupId", group.getGroupId())
            .put("setupId", group.getSetupId())
            .put("queueName", group.getQueueName())
            .put("memberCount", group.getMemberCount())
            .put("maxMembers", group.getMaxMembers())
            .put("loadBalancingStrategy", group.getLoadBalancingStrategy().name())
            .put("sessionTimeout", group.getSessionTimeout())
            .put("createdAt", group.getCreatedAt())
            .put("lastActivity", group.getLastActivity())
            .put("members", members)
            .put("timestamp", System.currentTimeMillis());
        
        ctx.response()
            .putHeader("content-type", "application/json")
            .end(response.encode());
    }
    
    /**
     * Joins a consumer group.
     * POST /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}/members
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
            ConsumerGroup group = consumerGroups.get(groupKey);
            
            if (group == null) {
                sendError(ctx, 404, "Consumer group not found: " + groupName);
                return;
            }
            
            if (group.getMemberCount() >= group.getMaxMembers()) {
                sendError(ctx, 409, "Consumer group is full (max " + group.getMaxMembers() + " members)");
                return;
            }
            
            // Create new member
            String memberId = "member-" + memberIdCounter.incrementAndGet();
            ConsumerGroupMember member = new ConsumerGroupMember(memberId, memberName, groupName);
            
            // Add member to group
            group.addMember(member);
            
            // Rebalance partitions
            group.rebalancePartitions();
            
            JsonObject response = new JsonObject()
                .put("message", "Successfully joined consumer group")
                .put("groupName", groupName)
                .put("memberId", memberId)
                .put("memberName", memberName)
                .put("assignedPartitions", member.getAssignedPartitions())
                .put("memberCount", group.getMemberCount())
                .put("timestamp", System.currentTimeMillis());
            
            ctx.response()
                .setStatusCode(201)
                .putHeader("content-type", "application/json")
                .end(response.encode());
            
            logger.info("Member {} joined consumer group {}: {}", memberId, groupName, memberName);
            
        } catch (Exception e) {
            logger.error("Error processing join consumer group request: {}", e.getMessage(), e);
            sendError(ctx, 400, "Invalid request: " + e.getMessage());
        }
    }
    
    /**
     * Leaves a consumer group.
     * DELETE /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}/members/{memberId}
     */
    public void leaveConsumerGroup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");
        String memberId = ctx.pathParam("memberId");
        
        logger.info("Member {} leaving consumer group {} for queue {} in setup {}", memberId, groupName, queueName, setupId);
        
        String groupKey = createGroupKey(setupId, queueName, groupName);
        ConsumerGroup group = consumerGroups.get(groupKey);
        
        if (group == null) {
            sendError(ctx, 404, "Consumer group not found: " + groupName);
            return;
        }
        
        ConsumerGroupMember member = group.removeMember(memberId);
        if (member == null) {
            sendError(ctx, 404, "Member not found in consumer group: " + memberId);
            return;
        }
        
        // Rebalance partitions after member leaves
        group.rebalancePartitions();
        
        JsonObject response = new JsonObject()
            .put("message", "Successfully left consumer group")
            .put("groupName", groupName)
            .put("memberId", memberId)
            .put("memberCount", group.getMemberCount())
            .put("timestamp", System.currentTimeMillis());
        
        ctx.response()
            .putHeader("content-type", "application/json")
            .end(response.encode());
        
        logger.info("Member {} left consumer group {}", memberId, groupName);
    }
    
    /**
     * Deletes a consumer group.
     * DELETE /api/v1/queues/{setupId}/{queueName}/consumer-groups/{groupName}
     */
    public void deleteConsumerGroup(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String groupName = ctx.pathParam("groupName");
        
        logger.info("Deleting consumer group {} for queue {} in setup {}", groupName, queueName, setupId);
        
        String groupKey = createGroupKey(setupId, queueName, groupName);
        ConsumerGroup group = consumerGroups.remove(groupKey);
        
        if (group == null) {
            sendError(ctx, 404, "Consumer group not found: " + groupName);
            return;
        }
        
        // Clean up group resources
        group.cleanup();
        
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
     */
    public JsonObject getStatistics() {
        int totalGroups = consumerGroups.size();
        int totalMembers = consumerGroups.values().stream()
            .mapToInt(ConsumerGroup::getMemberCount)
            .sum();
        
        return new JsonObject()
            .put("totalGroups", totalGroups)
            .put("totalMembers", totalMembers)
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
            ConsumerGroup group = consumerGroups.get(key);
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


}
