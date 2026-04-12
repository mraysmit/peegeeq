package dev.mars.peegeeq.api.subscription;

/**
 * API-level representation of a partition assignment within a consumer group.
 *
 * @param topic The topic name
 * @param groupName The consumer group name
 * @param partitionKey The partition key (derived from message_group)
 * @param instanceId The consumer instance that owns this partition
 * @param generation The rebalance generation this assignment belongs to
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
public record PartitionAssignmentInfo(
        String topic,
        String groupName,
        String partitionKey,
        String instanceId,
        int generation
) {}
