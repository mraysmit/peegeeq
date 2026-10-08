package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonObject;
import java.util.Set;

/** Node quarantine. While it exists the node cannot prepare or activate a writer grant. */
public record PgQuarantine(String nodeId, String reason) {
    public PgQuarantine {
        PgNodeConfig.requireIdentity(nodeId);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Quarantine requires a reason");
    }

    JsonObject toJson() {
        return new JsonObject().put("nodeId", nodeId).put("reason", reason);
    }

    static PgQuarantine fromJson(JsonObject source) {
        if (!Set.of("nodeId", "reason").equals(source.fieldNames())) {
            throw new IllegalArgumentException("Unexpected quarantine fields");
        }
        return new PgQuarantine(source.getString("nodeId"), source.getString("reason"));
    }
}