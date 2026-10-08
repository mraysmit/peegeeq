package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonObject;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Durable evidence of one local action. Identity is generation, operation, action, and target
 * node. The parameters are immutable. A receipt never grants authority.
 */
public record PgActionReceipt(long generation, String operationId, String action, String targetNodeId,
                              JsonObject parameters, PgActionResult result, JsonObject effect) {
    public PgActionReceipt {
        if (generation < 1) throw new IllegalArgumentException("Receipt generation must be positive");
        UUID.fromString(Objects.requireNonNull(operationId, "operationId"));
        if (action == null || !action.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid action name");
        }
        PgNodeConfig.requireIdentity(targetNodeId);
        parameters = Objects.requireNonNull(parameters, "parameters").copy();
        Objects.requireNonNull(result, "result");
        effect = Objects.requireNonNull(effect, "effect").copy();
    }

    @Override
    public JsonObject parameters() {
        return parameters.copy();
    }

    @Override
    public JsonObject effect() {
        return effect.copy();
    }

    PgActionReceipt withResult(PgActionResult next, JsonObject observed) {
        return new PgActionReceipt(generation, operationId, action, targetNodeId, parameters, next, observed);
    }

    JsonObject toJson() {
        return new JsonObject().put("generation", generation).put("operationId", operationId)
            .put("action", action).put("targetNodeId", targetNodeId).put("parameters", parameters)
            .put("result", result.name()).put("effect", effect);
    }

    static PgActionReceipt fromJson(JsonObject source) {
        if (!Set.of("generation", "operationId", "action", "targetNodeId", "parameters", "result", "effect")
                .equals(source.fieldNames())) {
            throw new IllegalArgumentException("Unexpected receipt fields");
        }
        return new PgActionReceipt(source.getLong("generation"), source.getString("operationId"),
            source.getString("action"), source.getString("targetNodeId"), source.getJsonObject("parameters"),
            PgActionResult.valueOf(source.getString("result")), source.getJsonObject("effect"));
    }
}