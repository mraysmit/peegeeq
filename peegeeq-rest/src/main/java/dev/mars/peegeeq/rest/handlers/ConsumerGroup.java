package dev.mars.peegeeq.rest.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a consumer group with members and partition management.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public class ConsumerGroup {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroup.class);
    
    private final String groupId;
    private final String groupName;
    private final String setupId;
    private final String queueName;
    private final long createdAt;
    
    private volatile int maxMembers = 10;
    private volatile LoadBalancingStrategy loadBalancingStrategy = LoadBalancingStrategy.ROUND_ROBIN;
    private volatile long sessionTimeout = 30000L; // 30 seconds
    private volatile long lastActivity;
    
    // Member management
    private final Map<String, ConsumerGroupMember> members = new ConcurrentHashMap<>();
    
    // Partition management (simplified - in a real implementation this would be more complex)
    private final int totalPartitions = 16; // Default number of partitions
    
    public ConsumerGroup(String groupName, String setupId, String queueName) {
        this.groupId = UUID.randomUUID().toString();
        this.groupName = groupName;
        this.setupId = setupId;
        this.queueName = queueName;
        this.createdAt = System.currentTimeMillis();
        this.lastActivity = this.createdAt;
        
        logger.debug("Created consumer group: {} (ID: {}) for queue {} in setup {}", 
                    groupName, groupId, queueName, setupId);
    }
    
    /**
     * Adds a member to the consumer group.
     */
    public synchronized void addMember(ConsumerGroupMember member) {
        if (members.size() >= maxMembers) {
            throw new IllegalStateException("Consumer group is full (max " + maxMembers + " members)");
        }
        
        members.put(member.getMemberId(), member);
        updateActivity();
        
        logger.info("Member {} added to consumer group {}: {}", 
                   member.getMemberId(), groupName, member.getMemberName());
    }
    
    /**
     * Removes a member from the consumer group.
     */
    public synchronized ConsumerGroupMember removeMember(String memberId) {
        ConsumerGroupMember member = members.remove(memberId);
        if (member != null) {
            updateActivity();
            logger.info("Member {} removed from consumer group {}", memberId, groupName);
        }
        return member;
    }
    
    /**
     * Gets a member by ID.
     */
    public ConsumerGroupMember getMember(String memberId) {
        return members.get(memberId);
    }
    
    /**
     * Gets all members.
     */
    public Map<String, ConsumerGroupMember> getMembers() {
        return new HashMap<>(members);
    }
    
    /**
     * Gets the number of active members.
     */
    public int getMemberCount() {
        return members.size();
    }
    
    /**
     * Rebalances partitions among active members.
     */
    public synchronized void rebalancePartitions() {
        logger.info("Rebalancing partitions for consumer group {} with {} members", groupName, members.size());
        
        // Clear existing assignments
        members.values().forEach(ConsumerGroupMember::clearPartitions);
        
        if (members.isEmpty()) {
            logger.debug("No members in consumer group {}, skipping rebalance", groupName);
            return;
        }
        
        List<ConsumerGroupMember> activeMembers = new ArrayList<>(members.values());
        List<Integer> partitions = new ArrayList<>();
        for (int i = 0; i < totalPartitions; i++) {
            partitions.add(i);
        }
        
        switch (loadBalancingStrategy) {
            case ROUND_ROBIN:
                rebalanceRoundRobin(activeMembers, partitions);
                break;
            case RANGE:
                rebalanceRange(activeMembers, partitions);
                break;
            case STICKY:
                rebalanceSticky(activeMembers, partitions);
                break;
            case RANDOM:
                rebalanceRandom(activeMembers, partitions);
                break;
        }
        
        updateActivity();
        
        logger.info("Partition rebalancing completed for consumer group {}", groupName);
    }
    
    /**
     * Round-robin partition assignment.
     */
    private void rebalanceRoundRobin(List<ConsumerGroupMember> members, List<Integer> partitions) {
        for (int i = 0; i < partitions.size(); i++) {
            ConsumerGroupMember member = members.get(i % members.size());
            List<Integer> memberPartitions = new ArrayList<>(member.getAssignedPartitions());
            memberPartitions.add(partitions.get(i));
            member.assignPartitions(memberPartitions);
        }
    }
    
    /**
     * Range-based partition assignment.
     */
    private void rebalanceRange(List<ConsumerGroupMember> members, List<Integer> partitions) {
        int partitionsPerMember = partitions.size() / members.size();
        int remainder = partitions.size() % members.size();
        
        int startIndex = 0;
        for (int i = 0; i < members.size(); i++) {
            int endIndex = startIndex + partitionsPerMember + (i < remainder ? 1 : 0);
            List<Integer> memberPartitions = partitions.subList(startIndex, endIndex);
            members.get(i).assignPartitions(memberPartitions);
            startIndex = endIndex;
        }
    }
    
    /**
     * Sticky partition assignment (simplified - tries to keep existing assignments).
     */
    private void rebalanceSticky(List<ConsumerGroupMember> members, List<Integer> partitions) {
        // For simplicity, fall back to round-robin
        // In a real implementation, this would try to preserve existing assignments
        rebalanceRoundRobin(members, partitions);
    }
    
    /**
     * Random partition assignment.
     */
    private void rebalanceRandom(List<ConsumerGroupMember> members, List<Integer> partitions) {
        Collections.shuffle(partitions);
        rebalanceRoundRobin(members, partitions);
    }
    
    /**
     * Removes inactive members based on session timeout.
     */
    public synchronized void removeInactiveMembers() {
        List<String> inactiveMembers = new ArrayList<>();
        
        members.forEach((memberId, member) -> {
            if (!member.isAlive(sessionTimeout)) {
                inactiveMembers.add(memberId);
            }
        });
        
        if (!inactiveMembers.isEmpty()) {
            logger.info("Removing {} inactive members from consumer group {}", inactiveMembers.size(), groupName);
            
            inactiveMembers.forEach(this::removeMember);
            
            // Rebalance after removing inactive members
            if (!members.isEmpty()) {
                rebalancePartitions();
            }
        }
    }
    
    /**
     * Updates the last activity timestamp.
     */
    public void updateActivity() {
        this.lastActivity = System.currentTimeMillis();
    }
    
    /**
     * Cleans up resources associated with this consumer group.
     */
    public void cleanup() {
        logger.info("Cleaning up consumer group: {}", groupName);
        members.clear();
    }
    
    // Getters and setters
    
    public String getGroupId() {
        return groupId;
    }
    
    public String getGroupName() {
        return groupName;
    }
    
    public String getSetupId() {
        return setupId;
    }
    
    public String getQueueName() {
        return queueName;
    }
    
    public long getCreatedAt() {
        return createdAt;
    }
    
    public int getMaxMembers() {
        return maxMembers;
    }
    
    public void setMaxMembers(int maxMembers) {
        this.maxMembers = Math.max(1, Math.min(100, maxMembers)); // Clamp between 1 and 100
    }
    
    public LoadBalancingStrategy getLoadBalancingStrategy() {
        return loadBalancingStrategy;
    }
    
    public void setLoadBalancingStrategy(LoadBalancingStrategy loadBalancingStrategy) {
        this.loadBalancingStrategy = loadBalancingStrategy;
    }
    
    public long getSessionTimeout() {
        return sessionTimeout;
    }
    
    public void setSessionTimeout(long sessionTimeout) {
        this.sessionTimeout = Math.max(5000L, Math.min(300000L, sessionTimeout)); // Clamp between 5s and 5min
    }
    
    public long getLastActivity() {
        return lastActivity;
    }
    
    public int getTotalPartitions() {
        return totalPartitions;
    }
}
