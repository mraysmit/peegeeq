package dev.mars.peegeeq.db.consumer;

import java.time.OffsetDateTime;

/**
 * Represents a partition assigned to a consumer instance within a group.
 *
 * @param topic The topic name
 * @param groupName The consumer group name
 * @param partitionKey The partition key (derived from message_group)
 * @param assignedInstanceId The consumer instance that owns this partition
 * @param assignedAt When this assignment was created
 * @param lastHeartbeatAt Last heartbeat timestamp for this assignment
 * @param generation The rebalance generation this assignment belongs to
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
public record PartitionAssignment(
        String topic,
        String groupName,
        String partitionKey,
        String assignedInstanceId,
        OffsetDateTime assignedAt,
        OffsetDateTime lastHeartbeatAt,
        int generation
) {}
