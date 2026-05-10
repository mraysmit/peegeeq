package dev.mars.peegeeq.db.consumer;

import java.time.OffsetDateTime;

/**
 * Represents the offset state for a specific (topic, group, partition) combination
 * in OFFSET_WATERMARK completion tracking mode.
 *
 * @param topic The topic name
 * @param groupName The consumer group name
 * @param partitionKey The partition key (derived from message_group, or "__default__" for NULL)
 * @param committedOffset The outbox.id of the last committed message (0 = no progress)
 * @param committedAt When the offset was last committed
 * @param pendingOffset The outbox.id of the last fetched-but-uncommitted message, or null
 * @param pendingSince When the pending offset was set, or null
 * @param generation The rebalance generation (for fencing stale instances)
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
public record PartitionOffset(
    String topic,
    String groupName,
    String partitionKey,
    long committedOffset,
    OffsetDateTime committedAt,
    Long pendingOffset,
    OffsetDateTime pendingSince,
    int generation
) {}
