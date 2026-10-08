package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonObject;
import java.util.Objects;

/**
 * Coordinator-neutral control record: name, generation, revision, lease holder, and intent.
 * The generation increases on each acquisition. The revision changes on every value change.
 * The lease holder is absent on retained, unowned history.
 */
public record PgControlRecord(String controlName, long generation, long revision,
                              String leaseHolder, JsonObject intent) {
    public PgControlRecord {
        Objects.requireNonNull(controlName, "controlName");
        if (generation < 1 || revision < 1) {
            throw new IllegalArgumentException("Control metadata must have a positive generation and revision");
        }
        if (leaseHolder != null && leaseHolder.isBlank()) {
            throw new IllegalArgumentException("An unowned record has no lease holder; a blank holder is invalid");
        }
        intent = Objects.requireNonNull(intent, "intent").copy();
    }

    @Override
    public JsonObject intent() {
        return intent.copy();
    }
}
