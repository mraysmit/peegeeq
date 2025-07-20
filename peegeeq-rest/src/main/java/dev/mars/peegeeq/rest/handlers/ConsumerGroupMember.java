package dev.mars.peegeeq.rest.handlers;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a member of a consumer group.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public class ConsumerGroupMember {
    
    public enum MemberStatus {
        ACTIVE,
        INACTIVE,
        REBALANCING
    }
    
    private final String memberId;
    private final String memberName;
    private final String groupName;
    private final long joinedAt;
    
    private volatile long lastHeartbeat;
    private volatile MemberStatus status;
    private volatile List<Integer> assignedPartitions;
    
    public ConsumerGroupMember(String memberId, String memberName, String groupName) {
        this.memberId = memberId;
        this.memberName = memberName;
        this.groupName = groupName;
        this.joinedAt = System.currentTimeMillis();
        this.lastHeartbeat = this.joinedAt;
        this.status = MemberStatus.ACTIVE;
        this.assignedPartitions = new ArrayList<>();
    }
    
    /**
     * Updates the last heartbeat timestamp.
     */
    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }
    
    /**
     * Checks if the member is considered alive based on heartbeat.
     */
    public boolean isAlive(long sessionTimeout) {
        return (System.currentTimeMillis() - lastHeartbeat) < sessionTimeout;
    }
    
    /**
     * Assigns partitions to this member.
     */
    public void assignPartitions(List<Integer> partitions) {
        this.assignedPartitions = new ArrayList<>(partitions);
    }
    
    /**
     * Clears all assigned partitions.
     */
    public void clearPartitions() {
        this.assignedPartitions.clear();
    }
    
    // Getters and setters
    
    public String getMemberId() {
        return memberId;
    }
    
    public String getMemberName() {
        return memberName;
    }
    
    public String getGroupName() {
        return groupName;
    }
    
    public long getJoinedAt() {
        return joinedAt;
    }
    
    public long getLastHeartbeat() {
        return lastHeartbeat;
    }
    
    public MemberStatus getStatus() {
        return status;
    }
    
    public void setStatus(MemberStatus status) {
        this.status = status;
    }
    
    public List<Integer> getAssignedPartitions() {
        return new ArrayList<>(assignedPartitions);
    }
}
