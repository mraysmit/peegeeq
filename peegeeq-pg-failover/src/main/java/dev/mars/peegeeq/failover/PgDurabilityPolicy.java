package dev.mars.peegeeq.failover;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A durability policy as the control record holds it: a revision and the standbys every required
 * commit waits for. The control record is the source of truth; this is its typed form. The
 * acknowledgement count and the PostgreSQL setting are derived from the standby set.
 */
public record PgDurabilityPolicy(long revision, List<String> requiredStandbyNodeIds) {
    private static final Set<String> FIELDS = Set.of("revision", "requiredStandbyNodeIds");

    public PgDurabilityPolicy {
        if (revision < 1) throw new IllegalArgumentException("Policy revision must be positive");
        requiredStandbyNodeIds = List.copyOf(requiredStandbyNodeIds);
        if (requiredStandbyNodeIds.isEmpty()) throw new IllegalArgumentException("A policy requires a standby");
        requiredStandbyNodeIds.forEach(PgNodeConfig::requireIdentity);
        if (requiredStandbyNodeIds.stream().map(id -> id.toLowerCase(Locale.ROOT)).distinct().count()
                != requiredStandbyNodeIds.size()) {
            throw new IllegalArgumentException("Required standbys must be distinct, ignoring case");
        }
    }

    /** Reads the control record form. Fails unless it has exactly a revision and a standby list. */
    public static PgDurabilityPolicy fromJson(JsonObject source) {
        if (source == null || !FIELDS.equals(source.fieldNames())) {
            throw new IllegalArgumentException("A durability policy must have exactly the fields " + FIELDS);
        }
        Object revision = source.getValue("revision");
        Object standbys = source.getValue("requiredStandbyNodeIds");
        if (!(revision instanceof Long || revision instanceof Integer) || !(standbys instanceof JsonArray list)
                || list.stream().anyMatch(standby -> !(standby instanceof String))) {
            throw new IllegalArgumentException("A durability policy needs an integer revision and a list of node identities");
        }
        return new PgDurabilityPolicy(((Number) revision).longValue(),
            list.stream().map(String.class::cast).toList());
    }

    public JsonObject toJson() {
        return new JsonObject().put("revision", revision)
            .put("requiredStandbyNodeIds", new JsonArray(requiredStandbyNodeIds));
    }

    /**
     * The generated {@code synchronous_standby_names}: every required standby, each name quoted.
     * Node identities cannot contain a quote, so quoting cannot be broken.
     */
    public String synchronousStandbyNames() {
        return "ANY " + requiredStandbyNodeIds.size() + " ("
            + requiredStandbyNodeIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", ")) + ")";
    }

    /**
     * Fails unless every required standby has exactly one replication connection, authenticated
     * under the role named by its node identity, streaming, with flush and replay at or after the
     * boundary. A connection that uses a standby's name under another role, or a standby's role
     * under another name, fails the check with a {@link PgDurabilityException}. A standby that is
     * not connected, has several connections, is not streaming, or is behind the boundary fails it
     * with a {@link PgStandbyNotCoveredException}, which can change by waiting. Connections that
     * name no required standby are ignored.
     */
    public void requireCoveredAt(PgLsn boundary, List<PgReplicationPeer> peers) {
        Objects.requireNonNull(boundary, "boundary");
        for (PgReplicationPeer peer : peers) {
            for (String standby : requiredStandbyNodeIds) {
                if (peer.applicationName().equalsIgnoreCase(standby)
                        && !(peer.applicationName().equals(standby) && peer.role().equals(standby))) {
                    throw new PgDurabilityException("Replication client " + peer.applicationName() + " under role "
                        + peer.role() + " does not authenticate as node " + standby);
                }
                if (peer.role().equals(standby) && !peer.applicationName().equals(standby)) {
                    throw new PgDurabilityException("Role " + standby + " replicates as " + peer.applicationName()
                        + "; a standby role replicates only under its own node identity");
                }
            }
        }
        for (String standby : requiredStandbyNodeIds) {
            List<PgReplicationPeer> named = peers.stream()
                .filter(peer -> standby.equals(peer.applicationName())).toList();
            if (named.isEmpty()) {
                throw new PgStandbyNotCoveredException("Required standby " + standby + " is not connected");
            }
            if (named.size() > 1) {
                throw new PgStandbyNotCoveredException("Required standby " + standby + " has " + named.size()
                    + " replication connections");
            }
            PgReplicationPeer peer = named.get(0);
            if (!"streaming".equals(peer.state())) {
                throw new PgStandbyNotCoveredException("Required standby " + standby + " is not streaming: "
                    + peer.state());
            }
            if (peer.flushLsn() == null || !peer.flushLsn().reached(boundary)) {
                throw new PgStandbyNotCoveredException("Required standby " + standby + " has not flushed WAL to "
                    + boundary);
            }
            if (peer.replayLsn() == null || !peer.replayLsn().reached(boundary)) {
                throw new PgStandbyNotCoveredException("Required standby " + standby + " has not replayed WAL to "
                    + boundary);
            }
        }
    }
}
