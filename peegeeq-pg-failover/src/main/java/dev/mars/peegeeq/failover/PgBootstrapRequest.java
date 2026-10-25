package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The first-start bootstrap request: deployment evidence that names a new cluster incarnation, its
 * membership, its initial writer, and the standbys of the first durability policy. The deployment
 * places it in the supervisor's state directory. Filesystem permissions are its only
 * authentication. Its fields are immutable bootstrap parameters; the pending policy revision is
 * always 1 and is not part of the request.
 */
public record PgBootstrapRequest(String clusterId, String incarnation, List<String> memberNodeIds,
                                 String initialWriterNodeId, String operationId,
                                 List<String> requiredStandbyNodeIds) {
    private static final Logger logger = LoggerFactory.getLogger(PgBootstrapRequest.class);
    private static final Set<String> FIELDS = Set.of("clusterId", "incarnation", "memberNodeIds",
        "initialWriterNodeId", "operationId", "requiredStandbyNodeIds");

    public PgBootstrapRequest {
        memberNodeIds = List.copyOf(memberNodeIds);
        requiredStandbyNodeIds = List.copyOf(requiredStandbyNodeIds);
    }

    /**
     * Reads and verifies the request against this node's configuration. Fails unless every field is
     * present and known, the request names this cluster, incarnation, and membership, this node is
     * the initial writer, and the standbys are distinct members other than the writer.
     */
    public static Future<PgBootstrapRequest> load(Vertx vertx, Path file, PgNodeConfig config) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(config, "config");
        return vertx.fileSystem().readFile(file.toString())
            .transform(read -> {
                if (read.failed()) {
                    return Future.<PgBootstrapRequest>failedFuture(
                        new PgBootstrapException("The bootstrap request cannot be read: " + file, read.cause()));
                }
                try {
                    return Future.succeededFuture(verified(parse(read.result().toString()), config));
                } catch (RuntimeException invalid) {
                    return Future.<PgBootstrapRequest>failedFuture(invalid instanceof PgBootstrapException known
                        ? known : new PgBootstrapException("The bootstrap request is invalid", invalid));
                }
            }).andThen(outcome -> {
                if (outcome.failed()) logger.error("Bootstrap request rejected", outcome.cause());
            });
    }

    /** Withdrawn intent that names this request's writer and operation, with pending policy revision 1. */
    public JsonObject initialIntent() {
        return new JsonObject().put("writerNodeId", initialWriterNodeId).put("phase", "WITHDRAWN")
            .put("operationId", operationId)
            .put("pendingDurabilityPolicy", new JsonObject().put("revision", 1L)
                .put("requiredStandbyNodeIds", new JsonArray(requiredStandbyNodeIds)));
    }

    /** The request as immutable receipt parameters. */
    public JsonObject toJson() {
        return new JsonObject().put("clusterId", clusterId).put("incarnation", incarnation)
            .put("memberNodeIds", new JsonArray(memberNodeIds)).put("initialWriterNodeId", initialWriterNodeId)
            .put("operationId", operationId).put("requiredStandbyNodeIds", new JsonArray(requiredStandbyNodeIds));
    }

    private static PgBootstrapRequest parse(String content) {
        JsonObject source;
        try {
            source = new JsonObject(content);
        } catch (RuntimeException malformed) {
            throw new PgBootstrapException("The bootstrap request is not a JSON object", malformed);
        }
        if (!FIELDS.equals(source.fieldNames())) {
            throw new PgBootstrapException("The bootstrap request must have exactly the fields " + FIELDS);
        }
        return new PgBootstrapRequest(source.getString("clusterId"), source.getString("incarnation"),
            strings(source.getJsonArray("memberNodeIds")), source.getString("initialWriterNodeId"),
            source.getString("operationId"), strings(source.getJsonArray("requiredStandbyNodeIds")));
    }

    private static PgBootstrapRequest verified(PgBootstrapRequest request, PgNodeConfig config) {
        if (!config.clusterId().equals(request.clusterId()) || !config.incarnation().equals(request.incarnation())) {
            throw new PgBootstrapException("The bootstrap request names another cluster or incarnation");
        }
        if (request.memberNodeIds().size() != config.memberNodeIds().size()
                || !new HashSet<>(request.memberNodeIds()).equals(new HashSet<>(config.memberNodeIds()))) {
            throw new PgBootstrapException("The bootstrap request names another membership");
        }
        if (!config.nodeId().equals(request.initialWriterNodeId())) {
            throw new PgBootstrapException("The bootstrap request names another initial writer");
        }
        try {
            UUID.fromString(request.operationId());
        } catch (IllegalArgumentException invalid) {
            throw new PgBootstrapException("The bootstrap operation is not a UUID", invalid);
        }
        List<String> standbys = request.requiredStandbyNodeIds();
        if (standbys.isEmpty() || new HashSet<>(standbys).size() != standbys.size()
                || standbys.contains(request.initialWriterNodeId())
                || !config.memberNodeIds().containsAll(standbys)) {
            throw new PgBootstrapException("The required standbys must be distinct members other than the writer");
        }
        return request;
    }

    private static List<String> strings(JsonArray values) {
        if (values == null || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw new PgBootstrapException("A node list must be an array of node identities");
        }
        return values.stream().map(String.class::cast).toList();
    }
}
