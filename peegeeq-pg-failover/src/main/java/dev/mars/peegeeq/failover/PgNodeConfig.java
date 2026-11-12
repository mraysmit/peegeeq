package dev.mars.peegeeq.failover;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Authoritative node identity, timing inputs, and watchdog mode. Lease deadlines, the watchdog
 * timeout, and the ownership budget are derived.
 */
public record PgNodeConfig(String clusterId, String incarnation, String nodeId,
                           List<String> memberNodeIds, Duration leaseTtl,
                           Duration loopInterval, Duration requestTimeout,
                           Duration stopTimeout, PgWatchdogMode watchdogMode) {
    public PgNodeConfig {
        requireIdentity(clusterId);
        requireIdentity(incarnation);
        requireIdentity(nodeId);
        memberNodeIds = List.copyOf(memberNodeIds);
        memberNodeIds.forEach(PgNodeConfig::requireIdentity);
        if (!memberNodeIds.contains(nodeId) || memberNodeIds.size() < 2) {
            throw new IllegalArgumentException("Membership must contain this node and at least one peer");
        }
        // Equal identities, and identities equal apart from case, derive one slot name, so this also keeps
        // every member distinct (OD-31).
        if (memberNodeIds.stream().map(PgNodeConfig::slotName).distinct().count() != memberNodeIds.size()) {
            throw new IllegalArgumentException("Members must derive distinct replication slot names");
        }
        // The range a coordinator accepts for a lease TTL is validated by its adapter.
        positiveMillis(Objects.requireNonNull(leaseTtl, "leaseTtl"));
        positiveMillis(loopInterval);
        positiveMillis(requestTimeout);
        positiveMillis(stopTimeout);
        Objects.requireNonNull(watchdogMode, "watchdogMode");
        // Lease and local-stop rule. It applies in every watchdog mode.
        if (loopInterval.plus(requestTimeout.multipliedBy(2)).compareTo(leaseTtl) > 0) {
            throw new IllegalArgumentException("Loop interval plus two retry budgets exceeds the lease TTL");
        }
        if (loopInterval.plus(requestTimeout).plus(stopTimeout).compareTo(leaseTtl) >= 0) {
            throw new IllegalArgumentException(
                "One loop-and-retry cycle plus the stop budget must finish before the lease TTL");
        }
        // Watchdog timing is separate. Only required mode refuses a configuration that lacks it.
        if (watchdogMode == PgWatchdogMode.REQUIRED
                && !watchdogTimingUsable(leaseTtl, loopInterval, requestTimeout, stopTimeout)) {
            throw new IllegalArgumentException("Timing does not leave watchdog exclusion slack");
        }
    }

    public static PgNodeConfig defaults(String clusterId, String incarnation, String nodeId,
                                        List<String> memberNodeIds) {
        return new PgNodeConfig(clusterId, incarnation, nodeId, memberNodeIds,
            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
            PgWatchdogMode.AUTOMATIC);
    }

    /** Name of the one control record for this cluster incarnation. */
    public String controlName() {
        return "peegeeq/pg/" + clusterId + "/" + incarnation + "/primary-lock";
    }

    /**
     * The physical replication slot that a writer keeps for the member with this identity: {@code peegeeq_},
     * then the identity in lower case with {@code -} and {@code .} replaced by {@code _}. Derived, never
     * stored (plan §9.24).
     */
    public static String slotName(String nodeId) {
        requireIdentity(nodeId);
        // An identity is ASCII, so the length in characters is the length in bytes.
        String name = "peegeeq_" + nodeId.toLowerCase(Locale.ROOT).replace('-', '_').replace('.', '_');
        if (name.length() > 63) {
            throw new IllegalArgumentException("The replication slot name of " + nodeId + " exceeds 63 bytes");
        }
        return name;
    }

    /** Timeout requested from a watchdog device when one is used: half the lease TTL. */
    public Duration watchdogTimeout() {
        return watchdogTimeout(leaseTtl);
    }

    /**
     * Whether one loop-and-retry cycle and the stop budget each finish inside the watchdog
     * timeout. A watchdog cannot be activated safely when this is false.
     */
    public boolean watchdogTimingUsable() {
        return watchdogTimingUsable(leaseTtl, loopInterval, requestTimeout, stopTimeout);
    }

    /**
     * How long local ownership stays fresh after the start of a successful acquisition or
     * renewal. The stop budget always fits between the end of this budget and the lease TTL.
     * A mode that may use a watchdog also keeps the watchdog timeout before the lease TTL.
     */
    public Duration ownershipBudget() {
        Duration leaseBudget = leaseTtl.minus(stopTimeout);
        if (watchdogMode == PgWatchdogMode.OFF || !watchdogTimingUsable()) return leaseBudget;
        Duration watchdogBudget = leaseTtl.minus(watchdogTimeout());
        return watchdogBudget.compareTo(leaseBudget) < 0 ? watchdogBudget : leaseBudget;
    }

    private static Duration watchdogTimeout(Duration ttl) {
        return ttl.dividedBy(2);
    }

    private static boolean watchdogTimingUsable(Duration ttl, Duration loop, Duration request, Duration stop) {
        return loop.plus(request).compareTo(watchdogTimeout(ttl)) < 0
            && stop.compareTo(watchdogTimeout(ttl)) < 0;
    }

    static void requireIdentity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Invalid cluster, incarnation, or node identity");
        }
    }

    private static void positiveMillis(Duration duration) {
        if (duration == null || duration.toMillis() <= 0
                || duration.toNanos() % 1_000_000 != 0) {
            throw new IllegalArgumentException("Timing must be a positive whole number of milliseconds");
        }
    }
}
