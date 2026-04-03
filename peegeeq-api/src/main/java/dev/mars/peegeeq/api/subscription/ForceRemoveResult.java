package dev.mars.peegeeq.api.subscription;

/**
 * Result of a force-remove operation on a consumer group.
 *
 * <p>Force-remove marks the subscription as DEAD, runs dead-group cleanup
 * (decrement required_consumer_groups, remove orphaned tracking rows,
 * auto-complete unblocked messages), then marks the subscription CANCELLED.</p>
 *
 * @param topic                  The topic the group was removed from
 * @param groupName              The consumer group that was removed
 * @param previousStatus         The subscription status before force-remove
 * @param messagesDecremented    Number of messages whose required_consumer_groups was decremented
 * @param orphanRowsRemoved      Number of orphaned tracking rows deleted
 * @param messagesAutoCompleted  Number of messages auto-completed after decrement
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
public record ForceRemoveResult(
        String topic,
        String groupName,
        String previousStatus,
        int messagesDecremented,
        int orphanRowsRemoved,
        int messagesAutoCompleted
) {
    /** Total cleanup actions performed. */
    public int totalActions() {
        return messagesDecremented + orphanRowsRemoved + messagesAutoCompleted;
    }
}
