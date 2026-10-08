package dev.mars.peegeeq.failover;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Authoritative node identity and timing inputs. Lease deadlines are derived at runtime. */
public record PgNodeConfig(String clusterId, String incarnation, String nodeId,
                           List<String> memberNodeIds, Duration sessionTtl,
                           Duration loopInterval, Duration requestTimeout,
                           Duration stopTimeout) {
    public PgNodeConfig {
        requireIdentity(clusterId);
        requireIdentity(incarnation);
        requireIdentity(nodeId);
        memberNodeIds = List.copyOf(memberNodeIds);
        memberNodeIds.forEach(PgNodeConfig::requireIdentity);
        if (!memberNodeIds.contains(nodeId) || memberNodeIds.size() < 2
                || memberNodeIds.stream().map(id -> id.toLowerCase(Locale.ROOT)).distinct().count()
                    != memberNodeIds.size()) {
            throw new IllegalArgumentException("Membership must contain this node and distinct peers");
        }
        Objects.requireNonNull(sessionTtl, "sessionTtl");
        if (sessionTtl.toSeconds() < 10 || sessionTtl.toSeconds() > 86400
                || sessionTtl.getNano() != 0) {
            throw new IllegalArgumentException("Consul TTL must be whole seconds between 10 and 86400");
        }
        positiveMillis(loopInterval);
        positiveMillis(requestTimeout);
        positiveMillis(stopTimeout);
        if (loopInterval.plus(requestTimeout.multipliedBy(2)).compareTo(sessionTtl) > 0
                || loopInterval.plus(requestTimeout).compareTo(watchdogTimeout(sessionTtl)) >= 0
                || stopTimeout.compareTo(watchdogTimeout(sessionTtl)) >= 0) {
            throw new IllegalArgumentException("Timing does not leave watchdog exclusion slack");
        }
    }

    public static PgNodeConfig defaults(String clusterId, String incarnation, String nodeId,
                                        List<String> memberNodeIds) {
        return new PgNodeConfig(clusterId, incarnation, nodeId, memberNodeIds,
            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    public String controlKey() {
        return "peegeeq/pg/" + clusterId + "/" + incarnation + "/primary-lock";
    }

    public Duration watchdogTimeout() {
        return watchdogTimeout(sessionTtl);
    }

    private static Duration watchdogTimeout(Duration ttl) {
        return Duration.ofSeconds(ttl.toSeconds() / 2);
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
