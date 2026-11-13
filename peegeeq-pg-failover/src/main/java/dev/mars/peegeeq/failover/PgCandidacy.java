package dev.mars.peegeeq.failover;

import java.util.Objects;

/**
 * Whether this covered standby is a preferred takeover candidate (plan §9.28, P7d), and why. Derived on each call and
 * never stored. It never makes a node eligible: eligibility is membership in the last confirmed policy. A node that is
 * not preferred defers to a covered member that published a position ahead of it.
 */
public record PgCandidacy(boolean preferred, String reason) {
    public PgCandidacy {
        Objects.requireNonNull(reason, "reason");
    }
}
