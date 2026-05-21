package dev.mars.peegeeq.api.messaging;

/**
 * Load balancing strategies for consumer groups.
 *
 * <p>Defines how messages are distributed across the members of a consumer group.
 * Implementations in peegeeq-native and peegeeq-outbox honour the configured strategy.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 * @version 1.0
 */
public enum LoadBalancingStrategy {

    /**
     * Round-robin assignment of partitions to members.
     */
    ROUND_ROBIN,

    /**
     * Range-based assignment of partitions to members.
     */
    RANGE,

    /**
     * Sticky assignment that tries to minimise partition reassignment.
     */
    STICKY,

    /**
     * Random assignment of partitions to members.
     */
    RANDOM
}
