package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonObject;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Local execution permission for one writer generation. It matches the current lease and intent
 * by mode, generation, operation, policy revision, and node. A grant never proves ownership.
 */
public record PgWriterGrant(PgFailoverMode mode, long generation, String operationId,
                            long policyRevision, String nodeId, PgGrantState state) {
    public PgWriterGrant {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");
        if (generation < 1 || policyRevision < 1) {
            throw new IllegalArgumentException("Grant generation and policy revision must be positive");
        }
        UUID.fromString(Objects.requireNonNull(operationId, "operationId"));
        PgNodeConfig.requireIdentity(nodeId);
    }

    public PgWriterGrant withState(PgGrantState next) {
        return new PgWriterGrant(mode, generation, operationId, policyRevision, nodeId, next);
    }

    JsonObject toJson() {
        return new JsonObject().put("mode", mode.name()).put("generation", generation)
            .put("operationId", operationId).put("policyRevision", policyRevision)
            .put("nodeId", nodeId).put("state", state.name());
    }

    static PgWriterGrant fromJson(JsonObject source) {
        if (!Set.of("mode", "generation", "operationId", "policyRevision", "nodeId", "state")
                .equals(source.fieldNames())) {
            throw new IllegalArgumentException("Unexpected grant fields");
        }
        return new PgWriterGrant(PgFailoverMode.valueOf(source.getString("mode")), source.getLong("generation"),
            source.getString("operationId"), source.getLong("policyRevision"), source.getString("nodeId"),
            PgGrantState.valueOf(source.getString("state")));
    }
}