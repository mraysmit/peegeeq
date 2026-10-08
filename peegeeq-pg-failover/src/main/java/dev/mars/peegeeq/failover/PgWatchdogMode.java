package dev.mars.peegeeq.failover;

/**
 * Configured watchdog mode. The mode is authoritative configuration. Whether a watchdog is
 * active and healthy is a live observation and is never stored.
 */
public enum PgWatchdogMode {
    /** Use the watchdog when the device is available. Report its absence and continue. */
    AUTOMATIC("automatic"),
    /** Do not open or activate a watchdog. Lease-loss shutdown and admission checks remain. */
    OFF("off"),
    /** Refuse writer start and promotion when the watchdog cannot be activated or its timing is unsafe. */
    REQUIRED("required");

    private final String property;

    PgWatchdogMode(String property) {
        this.property = property;
    }

    /** The value of {@code peegeeq.pg.failover.watchdog.mode} that selects this mode. */
    public String property() {
        return property;
    }

    public static PgWatchdogMode fromProperty(String value) {
        for (PgWatchdogMode mode : values()) {
            if (mode.property.equals(value)) return mode;
        }
        throw new IllegalArgumentException("Watchdog mode must be automatic, off, or required");
    }
}
