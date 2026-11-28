package dev.mars.peegeeq.api.subscription;

/**
 * Result of a force-remove operation on a consumer group.
 *
 * <p>Force-remove ends a subscription from any status except CANCELLED by setting it
 * CANCELLED. It changes no message, so the result names only the subscription and the status
 * it had before.</p>
 *
 * @param topic          The topic the group was removed from
 * @param groupName      The consumer group that was removed
 * @param previousStatus The subscription status before force-remove
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
public record ForceRemoveResult(
        String topic,
        String groupName,
        String previousStatus
) {
}
