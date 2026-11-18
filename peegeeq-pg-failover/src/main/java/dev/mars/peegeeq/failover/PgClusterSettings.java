package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The cluster-wide lease timing that the coordinator stores (plan §9.34, contract 1): lease TTL, loop interval, retry
 * budget, and stop budget. Satisfies the lease and local-stop rule of system design §5.4 in every watchdog mode. A value
 * that breaks the rule is refused, never adjusted (OD-66). {@link PgNodeConfig} applies the same rule through this type.
 */
public record PgClusterSettings(Duration leaseTtl, Duration loopInterval, Duration requestTimeout,
                                Duration stopTimeout) {
    private static final String LEASE_TTL = "leaseTtlMillis";
    private static final String LOOP_INTERVAL = "loopIntervalMillis";
    private static final String REQUEST_TIMEOUT = "requestTimeoutMillis";
    private static final String STOP_TIMEOUT = "stopTimeoutMillis";
    private static final Set<String> FIELDS = Set.of(LEASE_TTL, LOOP_INTERVAL, REQUEST_TIMEOUT, STOP_TIMEOUT);

    public PgClusterSettings {
        // The range a coordinator accepts for a lease TTL is validated by its adapter.
        positiveMillis(Objects.requireNonNull(leaseTtl, "leaseTtl"));
        positiveMillis(loopInterval);
        positiveMillis(requestTimeout);
        positiveMillis(stopTimeout);
        // Lease and local-stop rule. It applies in every watchdog mode.
        if (loopInterval.plus(requestTimeout.multipliedBy(2)).compareTo(leaseTtl) > 0) {
            throw new IllegalArgumentException("Loop interval plus two retry budgets exceeds the lease TTL");
        }
        if (loopInterval.plus(requestTimeout).plus(stopTimeout).compareTo(leaseTtl) >= 0) {
            throw new IllegalArgumentException(
                "One loop-and-retry cycle plus the stop budget must finish before the lease TTL");
        }
    }

    /** The stored form: exactly the four fields, each a whole number of milliseconds. */
    public JsonObject toJson() {
        return new JsonObject().put(LEASE_TTL, leaseTtl.toMillis())
            .put(LOOP_INTERVAL, loopInterval.toMillis())
            .put(REQUEST_TIMEOUT, requestTimeout.toMillis())
            .put(STOP_TIMEOUT, stopTimeout.toMillis());
    }

    /** Reads the stored form. An unknown, missing, or non-integer field, or a value that breaks the rule, fails. */
    public static PgClusterSettings fromJson(JsonObject json) {
        Objects.requireNonNull(json, "json");
        if (!FIELDS.equals(json.fieldNames())) {
            throw new IllegalArgumentException("Cluster settings must have exactly the fields " + FIELDS + ": "
                + json.fieldNames());
        }
        return new PgClusterSettings(millis(json, LEASE_TTL), millis(json, LOOP_INTERVAL),
            millis(json, REQUEST_TIMEOUT), millis(json, STOP_TIMEOUT));
    }

    private static Duration millis(JsonObject json, String field) {
        Object value = json.getValue(field);
        if (!(value instanceof Long || value instanceof Integer)) {
            throw new IllegalArgumentException("Cluster setting " + field + " is not an integer: " + value);
        }
        return Duration.ofMillis(((Number) value).longValue());
    }

    private static void positiveMillis(Duration duration) {
        if (duration == null || duration.toMillis() <= 0
                || duration.toNanos() % 1_000_000 != 0) {
            throw new IllegalArgumentException("Timing must be a positive whole number of milliseconds");
        }
    }
}
