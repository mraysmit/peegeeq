package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonObject;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * A request to restart the local PostgreSQL (plan §9.33, contract 3), supplied by the caller. Its own operation identity
 * keys the {@code restart} receipt, so two restarts under one control record do not share a receipt (OD-56). The
 * conditions are checked live when the restart runs: {@code onlyIfPending}, a parameter waits for a restart; {@code role},
 * the node has that role; {@code belowVersion}, the server version number is below it. Stored only as receipt parameters
 * and inside a schedule.
 */
public record PgRestartRequest(String operationId, boolean onlyIfPending, Optional<PgRestartRole> role,
                               OptionalInt belowVersion) {
    public PgRestartRequest {
        UUID.fromString(Objects.requireNonNull(operationId, "operationId"));
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(belowVersion, "belowVersion");
        if (belowVersion.isPresent() && belowVersion.getAsInt() <= 0) {
            throw new IllegalArgumentException("A version bound is a positive server version number");
        }
    }

    /** An unconditional restart under a new operation. */
    public static PgRestartRequest now() {
        return new PgRestartRequest(UUID.randomUUID().toString(), false, Optional.empty(), OptionalInt.empty());
    }

    /** The conditions as the receipt's immutable parameters. The operation is part of the receipt's identity. */
    public JsonObject conditions() {
        JsonObject conditions = new JsonObject().put("onlyIfPending", onlyIfPending);
        role.ifPresent(required -> conditions.put("role", required.name()));
        belowVersion.ifPresent(bound -> conditions.put("belowVersion", bound));
        return conditions;
    }
}
