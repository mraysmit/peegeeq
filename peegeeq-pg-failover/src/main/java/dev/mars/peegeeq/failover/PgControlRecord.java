package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonObject;
import java.util.Objects;

/** Consul metadata and intent. The session may be absent on retained, unowned history. */
public record PgControlRecord(String key, long lockIndex, long modifyIndex,
                              String sessionId, JsonObject intent) {
    public PgControlRecord {
        Objects.requireNonNull(key, "key");
        if (lockIndex < 1 || modifyIndex < 1) {
            throw new IllegalArgumentException("Control metadata must have positive revisions");
        }
        intent = Objects.requireNonNull(intent, "intent").copy();
    }

    @Override
    public JsonObject intent() {
        return intent.copy();
    }
}
