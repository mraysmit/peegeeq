package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A restart scheduled on this node (plan §9.33, contracts 4 and 5): the request, when it is due, and the postmaster start
 * time when it was scheduled. The operator's intent, held in the node-local store (OD-59), one per node. A postmaster that
 * started since then cancels it, because the restart already happened.
 */
public record PgScheduledRestart(String nodeId, PgRestartRequest request, Instant due, long postmasterStartMicros) {
    private static final Set<String> REQUIRED = Set.of("nodeId", "operationId", "onlyIfPending", "due",
        "postmasterStartMicros");
    private static final Set<String> ALLOWED = Set.of("nodeId", "operationId", "onlyIfPending", "role", "belowVersion",
        "due", "postmasterStartMicros");

    public PgScheduledRestart {
        PgNodeConfig.requireIdentity(nodeId);
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(due, "due");
        if (postmasterStartMicros <= 0) throw new IllegalArgumentException("A postmaster start time is required");
    }

    JsonObject toJson() {
        return request.conditions().put("nodeId", nodeId).put("operationId", request.operationId())
            .put("due", due.toString()).put("postmasterStartMicros", postmasterStartMicros);
    }

    static PgScheduledRestart fromJson(JsonObject source) {
        if (!source.fieldNames().containsAll(REQUIRED) || !ALLOWED.containsAll(source.fieldNames())) {
            throw new IllegalArgumentException("Unexpected scheduled restart fields");
        }
        Optional<PgRestartRole> role = source.containsKey("role")
            ? Optional.of(PgRestartRole.valueOf(source.getString("role"))) : Optional.empty();
        OptionalInt below = source.containsKey("belowVersion")
            ? OptionalInt.of(source.getInteger("belowVersion")) : OptionalInt.empty();
        var request = new PgRestartRequest(source.getString("operationId"),
            Objects.requireNonNull(source.getBoolean("onlyIfPending"), "onlyIfPending"), role, below);
        return new PgScheduledRestart(source.getString("nodeId"), request, Instant.parse(source.getString("due")),
            source.getLong("postmasterStartMicros"));
    }
}
