package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Durability control through SQL on the local socket. A policy is installed with
 * {@code ALTER SYSTEM}, which persists it in {@code postgresql.auto.conf}, followed by
 * {@code pg_ctl reload} (OD-9). The reload is confirmed when the configuration load time changes.
 * The setting in effect must then equal the generated value, and every required standby must
 * report quorum synchronous state. Peer identity is the authenticated replication role. Member
 * replication slots are created only when absent and never dropped or moved (plan §9.24).
 */
public final class PgSqlDurabilityControl implements PgDurabilityControl {
    @FunctionalInterface
    private interface Parser<T> { T parse(); }
    private static final Logger logger = LoggerFactory.getLogger(PgSqlDurabilityControl.class);
    private static final long POLL_MILLIS = 100;
    private static final String LOAD_TIME = "select extract(epoch from pg_conf_load_time())";
    private static final String WRITER = "select json_build_object('inRecovery', pg_is_in_recovery(), "
        + "'flushLsn', case when pg_is_in_recovery() then null else pg_current_wal_flush_lsn()::text end)";
    /**
     * A writer's position is the timeline of its WAL and its flush position; a standby's is its streaming receiver's
     * timeline and flushed position (plan §9.27). The writer's timeline is read from the current WAL file name.
     */
    private static final String POSITION = "select json_build_object('inRecovery', pg_is_in_recovery(), "
        + "'writer', case when pg_is_in_recovery() then null else json_build_object('timeline', "
        + "('x' || substr(pg_walfile_name(pg_current_wal_flush_lsn()), 1, 8))::bit(32)::int, "
        + "'lsn', pg_current_wal_flush_lsn()::text) end, "
        + "'receiver', (select json_build_object('timeline', received_tli, 'lsn', flushed_lsn::text) "
        + "from pg_stat_wal_receiver where status = 'streaming' and flushed_lsn is not null))";
    private static final String PEERS ="select coalesce(json_agg(json_build_object('role', usename, "
        + "'applicationName', application_name, 'state', state, 'syncState', sync_state, "
        + "'flushLsn', flush_lsn::text, 'replayLsn', replay_lsn::text)), '[]'::json) from pg_stat_replication";
    private final Vertx vertx;
    private final PgCommandRunner runner;
    private final List<String> commandPrefix;
    private final String pgCtl;
    private final String psql;
    private final String dataDirectory;
    private final Duration commandTimeout;

    /** @param commandTimeout the budget of one operation, covering every command it runs */
    public PgSqlDurabilityControl(Vertx vertx, PgCommandRunner runner, List<String> commandPrefix, String pgCtl,
                                  String psql, String dataDirectory, Duration commandTimeout) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.commandPrefix = List.copyOf(commandPrefix);
        this.pgCtl = requireText(pgCtl, "pgCtl");
        this.psql = requireText(psql, "psql");
        this.dataDirectory = requireText(dataDirectory, "dataDirectory");
        if (commandTimeout == null || commandTimeout.toMillis() <= 0) {
            throw new IllegalArgumentException("Command timeout must be positive");
        }
        this.commandTimeout = commandTimeout;
    }

    @Override
    public Future<PgLsn> flushBoundary() {
        return flushBoundary(deadline());
    }

    private Future<PgLsn> flushBoundary(long deadline) {
        return sql(deadline, WRITER).map(output -> {
            JsonObject observed = observation(output, () -> new JsonObject(output));
            Object inRecovery = observed.getValue("inRecovery");
            if (!(inRecovery instanceof Boolean)) throw new PgDurabilityException("Malformed durability observation: " + output);
            if (Boolean.TRUE.equals(inRecovery)) {
                throw new PgDurabilityException("PostgreSQL is in recovery; it has no writer flush position");
            }
            return observation(output, () -> PgLsn.parse(observed.getString("flushLsn")));
        });
    }

    @Override
    public Future<List<PgReplicationPeer>> peers() {
        return peers(deadline());
    }

    @Override
    public Future<Void> confirmCovered(PgDurabilityPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        long deadline = deadline();
        return flushBoundary(deadline).compose(boundary -> awaitCovered(deadline, policy, boundary));
    }

    @Override
    public Future<Void> install(PgDurabilityPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        long deadline = deadline();
        String names = policy.synchronousStandbyNames();
        return sql(deadline, LOAD_TIME).compose(before ->
                sql(deadline, "alter system set synchronous_standby_names = " + escapeString(names))
                    .compose(ignored -> run(deadline, pgCtl, "reload", "-D", dataDirectory))
                    .map(result -> requireSuccess(result, "pg_ctl reload"))
                    .compose(ignored -> awaitChange(deadline, before)))
            .compose(ignored -> sql(deadline, "show synchronous_standby_names"))
            .map(effective -> {
                if (!names.equals(effective)) {
                    throw new PgDurabilityException("The setting in effect is " + effective + ", not the installed " + names);
                }
                return null;
            }).compose(ignored -> awaitQuorum(deadline, policy));
    }

    @Override
    public Future<Void> ensureSlots(List<String> nodeIds) {
        Objects.requireNonNull(nodeIds, "nodeIds");
        List<String> names = nodeIds.stream().map(PgNodeConfig::slotName).toList();
        long deadline = deadline();
        // Creating a slot that exists fails, so only the slots that the observation does not show are created.
        // Every node holds the member slots, a standby as well as the writer (OD-35).
        return slots(deadline, names).compose(existing -> {
            Future<Void> created = Future.succeededFuture();
            for (String name : names) {
                if (existing.statuses().containsKey(name)) continue;
                created = created.compose(ignored ->
                    sql(deadline, "select pg_create_physical_replication_slot('" + name + "', true)").mapEmpty());
            }
            return created;
        }).compose(ignored -> slots(deadline, names)).map(observed -> {
            List<String> missing = names.stream().filter(name -> !observed.statuses().containsKey(name)).toList();
            if (!missing.isEmpty()) {
                throw new PgDurabilityException("Replication slots were not observed after creation: " + missing);
            }
            return null;
        });
    }

    @Override
    public Future<PgMemberPosition> position(String nodeId) {
        PgNodeConfig.requireIdentity(nodeId);
        return sql(deadline(), POSITION).map(output -> {
            JsonObject observed = observation(output, () -> new JsonObject(output));
            if (!(observed.getValue("inRecovery") instanceof Boolean inRecovery)) {
                throw new PgDurabilityException("Malformed durability observation: " + output);
            }
            Object source = observed.getValue(inRecovery ? "receiver" : "writer");
            if (source == null && inRecovery) {
                throw new PgDurabilityException("PostgreSQL is in recovery without a streaming WAL receiver; a standby "
                    + "has a position only while it streams");
            }
            if (!(source instanceof JsonObject position) || !(position.getValue("timeline") instanceof Number timeline)) {
                throw new PgDurabilityException("Malformed durability observation: " + output);
            }
            return observation(output, () -> new PgMemberPosition(nodeId, timeline.longValue(),
                PgLsn.parse(position.getString("lsn"))));
        });
    }

    @Override
    public Future<Set<String>> advanceSlots(Map<String, PgMemberPosition> positions) {
        Objects.requireNonNull(positions, "positions");
        List<PgMemberPosition> published = List.copyOf(positions.values());
        String list = published.stream().map(position -> "'" + PgNodeConfig.slotName(position.nodeId()) + "'")
            .collect(Collectors.joining(", "));
        long deadline = deadline();
        String statement = "select json_build_object('inRecovery', pg_is_in_recovery(), "
            + "'timeline', (select received_tli from pg_stat_wal_receiver where status = 'streaming'), "
            + "'replay', pg_last_wal_replay_lsn()::text, "
            + "'slots', coalesce((select json_object_agg(slot_name, json_build_object('type', slot_type, "
            + "'walStatus', coalesce(wal_status, ''), 'restart', restart_lsn::text)) from pg_replication_slots "
            + "where slot_name::text = any(array[" + list + "]::text[])), '{}'::json))";
        return sql(deadline, statement).compose(output -> {
            JsonObject observed = observation(output, () -> new JsonObject(output));
            Object inRecovery = observed.getValue("inRecovery");
            Object slots = observed.getValue("slots");
            if (!(inRecovery instanceof Boolean) || !(slots instanceof JsonObject)) {
                throw new PgDurabilityException("Malformed durability observation: " + output);
            }
            if (Boolean.FALSE.equals(inRecovery)) {
                throw new PgDurabilityException("PostgreSQL is not in recovery; a writer's slots advance by streaming");
            }
            // Without a streaming receiver the local timeline is unknown, so no published position can be matched.
            if (!(observed.getValue("timeline") instanceof Number timeline)) return Future.succeededFuture(Set.<String>of());
            PgLsn replay = observation(output, () -> PgLsn.parse(observed.getString("replay")));
            Set<String> advanced = new HashSet<>();
            Future<Void> chain = Future.succeededFuture();
            for (PgMemberPosition position : published) {
                String name = PgNodeConfig.slotName(position.nodeId());
                if (!(((JsonObject) slots).getValue(name) instanceof JsonObject slot)) continue;
                if (!"physical".equals(slot.getValue("type"))) {
                    throw new PgDurabilityException("Replication slot " + name + " is a " + slot.getValue("type")
                        + " slot, not the physical slot of a member");
                }
                String status = slot.getString("walStatus");
                if (status == null || status.isEmpty() || "lost".equals(status)) continue;
                if (position.timeline() != timeline.longValue()) continue;
                PgLsn restart = observation(output, () -> PgLsn.parse(slot.getString("restart")));
                // The smaller of the published and the replay position, and only forward: the slot still keeps the WAL
                // the member needs, because a member's position never decreases within a timeline.
                PgLsn target = position.lsn().compareTo(replay) < 0 ? position.lsn() : replay;
                if (target.compareTo(restart) <= 0) continue;
                chain = chain.compose(ignored -> sql(deadline, "select pg_replication_slot_advance('" + name + "', '"
                    + target + "')").map(moved -> {
                        advanced.add(position.nodeId());
                        return null;
                    }));
            }
            return chain.map(ignored -> Set.copyOf(advanced));
        });
    }

    @Override
    public Future<Void> replaceLostSlot(String nodeId) {
        String name = PgNodeConfig.slotName(nodeId);
        long deadline = deadline();
        return slots(deadline, List.of(name)).compose(existing -> {
            if (existing.inRecovery()) {
                throw new PgDurabilityException("PostgreSQL is in recovery; a lost member slot is replaced on the writer");
            }
            if (!existing.statuses().containsKey(name)) {
                throw new PgDurabilityException("Replication slot " + name + " does not exist; only a lost slot is replaced");
            }
            if (!"lost".equals(existing.statuses().get(name))) {
                throw new PgDurabilityException("Replication slot " + name + " is not lost (wal_status '"
                    + existing.statuses().get(name) + "'); a slot that keeps WAL is never replaced");
            }
            return sql(deadline, "select pg_drop_replication_slot('" + name + "')")
                .compose(dropped -> sql(deadline, "select pg_create_physical_replication_slot('" + name + "', true)"));
        }).compose(ignored -> slots(deadline, List.of(name))).map(observed -> {
            // A slot with no reserved position has no wal_status, so a status other than lost proves the reservation.
            String status = observed.statuses().get(name);
            if (status == null || status.isEmpty() || "lost".equals(status)) {
                throw new PgDurabilityException("Replication slot " + name
                    + " was not observed with a reserved position after its replacement: wal_status '" + status + "'");
            }
            return null;
        });
    }

    /** Whether the server is in recovery, and each named slot that exists with its {@code wal_status}. Observed live. */
    private record SlotObservation(boolean inRecovery, Map<String, String> statuses) { }

    /**
     * One observation of the server role, the slot retention bound, and the named slots with their types and WAL
     * status. Returns the role and the named slots that exist, each with its {@code wal_status}, empty when no position
     * is reserved. An unbounded retention and a slot under one of the names that is not physical are refused, on a
     * standby as on the writer. The names are derived slot names, which need no escaping.
     */
    private Future<SlotObservation> slots(long deadline, List<String> names) {
        String list = names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
        String statement = "select json_build_object('inRecovery', pg_is_in_recovery(), "
            + "'retention', current_setting('max_slot_wal_keep_size'), "
            + "'slots', coalesce((select json_object_agg(slot_name, json_build_object('type', slot_type, "
            + "'walStatus', coalesce(wal_status, ''))) from pg_replication_slots "
            + "where slot_name::text = any(array[" + list + "]::text[])), '{}'::json))";
        return sql(deadline, statement).map(output -> {
            JsonObject observed = observation(output, () -> new JsonObject(output));
            Object inRecovery = observed.getValue("inRecovery");
            Object retention = observed.getValue("retention");
            Object slots = observed.getValue("slots");
            if (!(inRecovery instanceof Boolean) || !(retention instanceof String) || !(slots instanceof JsonObject)) {
                throw new PgDurabilityException("Malformed durability observation: " + output);
            }
            if ("-1".equals(retention)) {
                throw new PgDurabilityException("max_slot_wal_keep_size is -1; an unbounded slot retention is refused, "
                    + "because a member that stays away would keep WAL on the writer until pg_wal fills (OD-27)");
            }
            Map<String, String> existing = new HashMap<>();
            for (String name : ((JsonObject) slots).fieldNames()) {
                Object slot = ((JsonObject) slots).getValue(name);
                if (!(slot instanceof JsonObject described) || !(described.getValue("walStatus") instanceof String status)) {
                    throw new PgDurabilityException("Malformed durability observation: " + output);
                }
                Object type = described.getValue("type");
                if (!"physical".equals(type)) {
                    throw new PgDurabilityException("Replication slot " + name + " is a " + type
                        + " slot, not the physical slot of a member");
                }
                existing.put(name, status);
            }
            return new SlotObservation((Boolean) inRecovery, existing);
        });
    }

    private Future<List<PgReplicationPeer>> peers(long deadline) {
        return sql(deadline, PEERS).map(output -> observation(output, () -> {
            List<PgReplicationPeer> peers = new ArrayList<>();
            for (Object element : new JsonArray(output)) {
                JsonObject peer = (JsonObject) element;
                peers.add(new PgReplicationPeer(peer.getString("role"), peer.getString("applicationName"),
                    peer.getString("state"), peer.getString("syncState"), lsn(peer.getString("flushLsn")),
                    lsn(peer.getString("replayLsn"))));
            }
            return List.copyOf(peers);
        }));
    }

    /**
     * A standby catches up on its own, so coverage of the fixed boundary is observed until it holds.
     * Only a standby that is not covered yet is observed again. A failed observation, an identity
     * mismatch, and a required standby whose slot is lost end the wait at once, because waiting does not
     * change them (OD-33). The wait ends with the last coverage failure when another observation would not
     * fit in the budget.
     */
    private Future<Void> awaitCovered(long deadline, PgDurabilityPolicy policy, PgLsn boundary) {
        long started = System.nanoTime();
        return peers(deadline).compose(peers -> requireNoLostSlot(deadline, policy).map(peers)).compose(peers -> {
            PgStandbyNotCoveredException uncovered;
            try {
                policy.requireCoveredAt(boundary, peers);
                return Future.<Void>succeededFuture();
            } catch (PgStandbyNotCoveredException failure) {
                uncovered = failure;
            }
            long observation = System.nanoTime() - started;
            if (deadline - System.nanoTime() < 2 * observation + TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)) {
                return Future.<Void>failedFuture(new PgDurabilityException("Required standbys were not covered at "
                    + boundary + " within the budget: " + uncovered.getMessage(), uncovered));
            }
            return vertx.timer(POLL_MILLIS).compose(ignored -> awaitCovered(deadline, policy, boundary));
        });
    }

    /**
     * A lost slot no longer keeps WAL, and the WAL its standby needs is gone, so that standby must be rebuilt.
     * A required standby whose member slot is missing, or reserves no position, is left to the coverage check.
     */
    private Future<Void> requireNoLostSlot(long deadline, PgDurabilityPolicy policy) {
        String list = policy.requiredStandbyNodeIds().stream().map(standby -> "'" + PgNodeConfig.slotName(standby) + "'")
            .collect(Collectors.joining(", "));
        return sql(deadline, "select coalesce(json_object_agg(slot_name, coalesce(wal_status, '')), '{}'::json) "
            + "from pg_replication_slots where slot_name::text = any(array[" + list + "]::text[])").map(output -> {
                JsonObject statuses = observation(output, () -> new JsonObject(output));
                for (String standby : policy.requiredStandbyNodeIds()) {
                    String slot = PgNodeConfig.slotName(standby);
                    if ("lost".equals(statuses.getValue(slot))) {
                        throw new PgDurabilityException("Replication slot " + slot + " of required standby " + standby
                            + " is lost; the WAL the standby needs was removed, so it must be rebuilt (OD-33)");
                    }
                }
                return null;
            });
    }

    /** A reload is asynchronous. It is confirmed when the configuration load time has changed. */
    private Future<Void> awaitChange(long deadline, String before) {
        return sql(deadline, LOAD_TIME).compose(current -> current.equals(before)
            ? vertx.timer(POLL_MILLIS).compose(ignored -> awaitChange(deadline, before))
            : Future.<Void>succeededFuture());
    }

    /**
     * Each walsender applies the reloaded setting on its own, so quorum state is observed until it
     * holds. The wait ends with the last observation when another one would not fit in the budget.
     */
    private Future<Void> awaitQuorum(long deadline, PgDurabilityPolicy policy) {
        long started = System.nanoTime();
        return peers(deadline).compose(peers -> {
            List<String> missing = policy.requiredStandbyNodeIds().stream()
                .filter(standby -> peers.stream().noneMatch(peer -> standby.equals(peer.role())
                    && standby.equals(peer.applicationName()) && "quorum".equals(peer.syncState())))
                .toList();
            if (missing.isEmpty()) return Future.<Void>succeededFuture();
            long observation = System.nanoTime() - started;
            if (deadline - System.nanoTime() < 2 * observation + TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS)) {
                return Future.<Void>failedFuture(new PgDurabilityException(
                    "Required standbys did not report quorum synchronous state within the budget: " + missing
                        + "; observed " + peers));
            }
            return vertx.timer(POLL_MILLIS).compose(ignored -> awaitQuorum(deadline, policy));
        });
    }

    /** One statement through the local socket. The trimmed output is the result. */
    private Future<String> sql(long deadline, String statement) {
        return run(deadline, psql, "-X", "-tA", "-v", "ON_ERROR_STOP=1", "-c", statement).map(result -> {
            requireSuccess(result, "Local SQL");
            return result.output().strip();
        });
    }

    /** Every command is bounded by the time that remains. An exhausted budget is a failure. */
    private Future<PgCommandResult> run(long deadline, String... command) {
        long remaining = deadline - System.nanoTime();
        if (remaining < 1_000_000) {
            return Future.failedFuture(new PgDurabilityException(
                "Durability budget exhausted before " + command[0] + " could run"));
        }
        List<String> full = new ArrayList<>(commandPrefix);
        full.addAll(List.of(command));
        return runner.run(full, Duration.ofNanos(remaining)).transform(outcome -> {
            if (outcome.failed()) {
                PgDurabilityException failure = new PgDurabilityException("Command failed: " + command[0], outcome.cause());
                logger.error("Durability command failed: {}", command[0], failure);
                return Future.failedFuture(failure);
            }
            if (outcome.result() == null) {
                return Future.failedFuture(new PgDurabilityException("Command returned no result: " + command[0]));
            }
            return Future.succeededFuture(outcome.result());
        });
    }

    private long deadline() {
        return System.nanoTime() + commandTimeout.toNanos();
    }

    private static <T> T observation(String output, Parser<T> parser) {
        try {
            return parser.parse();
        } catch (RuntimeException malformed) {
            throw new PgDurabilityException("Malformed durability observation: " + output, malformed);
        }
    }

    /**
     * An escape-string literal with no double quote in the command line. Windows does not pass an
     * embedded double quote through a child process's arguments; PostgreSQL decodes {@code \x22}.
     */
    private static String escapeString(String value) {
        return "E'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\x22") + "'";
    }

    private static PgLsn lsn(String text) {
        return text == null ? null : PgLsn.parse(text);
    }

    private static Void requireSuccess(PgCommandResult result, String action) {
        if (result.exitCode() != 0) {
            throw new PgDurabilityException(action + " failed with exit " + result.exitCode() + ": " + result.output());
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
