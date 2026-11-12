package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.testcontainers.containers.Network;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * An initial writer and two physical standbys, each in its own container on one network. Each
 * provisioning does what a deployment does before first-start bootstrap (OD-10): it creates the
 * writer's cluster, starts it once with application admission closed to create one replication
 * role per standby, takes each standby's base backup under that standby's own role with
 * {@code standby.signal} in place, and stops the writer. Nothing is left running. A standby's
 * replication role is its node identity (OD-11). Each base backup creates the standby's own
 * physical slot on the writer and writes {@code primary_slot_name}, so the writer holds a slot for
 * every standby from provisioning; the retention of every slot is bounded (plan §9.24, contract 7).
 */
public final class PgReplicationFixture implements AutoCloseable {
    public static final String WRITER = "pg-node-1";
    public static final List<String> STANDBYS = List.of("pg-node-2", "pg-node-3");
    /**
     * What provisioning may take. The {@code @BeforeEach} of a test that provisions has a Vert.x limit of
     * 120 seconds; the rest of its setup needs the remainder. A contract test asserts this budget.
     */
    public static final Duration PROVISIONING_BUDGET = Duration.ofSeconds(90);
    /** The deployment's bound on the WAL a slot keeps (OD-27 refuses an unbounded one). Check 1 of plan §9.24 used it. */
    public static final String SLOT_RETENTION = "256MB";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private final Network network = Network.newNetwork();
    private final Map<String, PgSupervisedContainer> nodes = new LinkedHashMap<>();
    private String dataDirectory;
    private volatile Duration lastProvisioning;

    public PgReplicationFixture() {
        nodes.put(WRITER, new PgSupervisedContainer(network, WRITER));
        STANDBYS.forEach(standby -> nodes.put(standby, new PgSupervisedContainer(network, standby)));
    }

    public void start() {
        nodes.values().forEach(PgSupervisedContainer::start);
    }

    @Override
    public void close() {
        nodes.values().forEach(PgSupervisedContainer::close);
        network.close();
    }

    public PgSupervisedContainer node(String nodeId) {
        PgSupervisedContainer node = nodes.get(nodeId);
        if (node == null) throw new IllegalArgumentException("No node " + nodeId);
        return node;
    }

    /** The data directory of the current provisioning. Every node uses the same path. */
    public String dataDirectory() {
        return dataDirectory;
    }

    /** The password file of a standby's replication role, in that standby's container. */
    public String passfile(String standby) {
        return dataDirectory + "." + standby + ".pgpass";
    }

    /** Where and how a standby connects to the node of the given identity, on the shared network. */
    public PgWriterEndpoint endpoint(String writerNode, String standby) {
        return new PgWriterEndpoint(writerNode, 5432, passfile(standby));
    }

    public PgProcessControl control(PgCommandRunner runner, String nodeId) {
        PgSupervisedContainer node = node(nodeId);
        return new PgCtlProcessControl(runner, node.prefix(), PgSupervisedContainer.PG_CTL, dataDirectory,
            node.logFile(dataDirectory), COMMAND_TIMEOUT);
    }

    /**
     * Provisions a new cluster in a new data directory on every node. Every node is left stopped. A
     * cluster that an earlier test left running holds the ports of this one, because vertx-junit5 does
     * not await an asynchronous teardown after a failed test. It is stopped first.
     */
    public Future<Void> provision(PgCommandRunner runner) {
        long started = System.nanoTime();
        return forceStopAll(runner).compose(ignored -> provisionNew(runner)).map(provisioned -> {
            lastProvisioning = Duration.ofNanos(System.nanoTime() - started);
            return provisioned;
        });
    }

    /** How long the last {@link #provision} took, including the stop of an earlier cluster. */
    public Duration lastProvisioningDuration() {
        return lastProvisioning;
    }

    private Future<Void> provisionNew(PgCommandRunner runner) {
        String directory = "/var/lib/postgresql/" + UUID.randomUUID();
        dataDirectory = directory;
        PgSupervisedContainer writer = node(WRITER);
        // No command argument contains a double quote: Windows does not pass one to a child process.
        StringBuilder replicationRules = new StringBuilder();
        STANDBYS.forEach(standby ->
            replicationRules.append("host replication ").append(standby).append(" samenet scram-sha-256\\n"));
        Future<Void> provisioned = checked(writer.exec(runner, "initdb", "-D", directory), "initdb")
            .compose(ignored -> checked(writer.exec(runner, "sh", "-c", "printf 'listen_addresses = \\047*\\047\\n"
                + "wal_keep_size = \\04764MB\\047\\nmax_slot_wal_keep_size = \\047" + SLOT_RETENTION + "\\047\\n' >> "
                + directory + "/postgresql.conf"), "Configuring the writer"))
            .compose(ignored -> writer.write(runner, writer.closedHba(directory),
                "local all all trust\\n" + replicationRules + "host all all all reject\\n"))
            .compose(ignored -> writer.write(runner, writer.openHba(directory),
                "local all all trust\\n" + replicationRules + "host all all all trust\\n"))
            .compose(ignored -> checked(writer.exec(runner, "cp", writer.closedHba(directory),
                directory + "/pg_hba.conf"), "Installing the closed admission file"))
            .compose(ignored -> checked(writer.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", directory,
                "-w", "-l", writer.logFile(directory)), "Starting the writer for provisioning"));
        for (String standby : STANDBYS) {
            provisioned = provisioned.compose(ignored -> checked(writer.sql(runner, "do $$ begin execute format("
                + "'create role %I with replication login password %L', '" + standby + "', '" + password(standby)
                + "'); end $$"), "Creating role " + standby));
        }
        for (String standby : STANDBYS) {
            PgSupervisedContainer node = node(standby);
            provisioned = provisioned
                .compose(ignored -> checked(node.exec(runner, "pg_basebackup", "-d",
                    primaryConnectionInfo(standby, standby), "-D", directory, "-Fp", "-Xs", "-R",
                    "-C", "-S", PgNodeConfig.slotName(standby)), "Base backup for " + standby))
                // Every node keeps the replication rules in both admission files, as the gate requires: a
                // standby that is promoted closes its gate, and its surviving peer must still replicate from it.
                .compose(ignored -> node.write(runner, node.closedHba(directory),
                    "local all all trust\\n" + replicationRules + "host all all all reject\\n"))
                .compose(ignored -> node.write(runner, node.openHba(directory),
                    "local all all trust\\n" + replicationRules + "host all all all trust\\n"))
                // The password file a deployment provisions for a follower. libpq requires mode 0600.
                .compose(ignored -> checked(node.exec(runner, "sh", "-c", "printf '*:*:*:" + standby + ":"
                    + password(standby) + "\\n' > " + passfile(standby) + " && chmod 600 " + passfile(standby)),
                    "Password file for " + standby));
        }
        return provisioned.compose(ignored -> checked(writer.exec(runner, PgSupervisedContainer.PG_CTL, "stop",
            "-D", directory, "-m", "fast", "-w"), "Stopping the writer after provisioning"));
    }

    /**
     * Makes a standby's slot on the writer lost, as a member that stays away past the bound does: stops the
     * standby, restarts the writer with a 32 MB bound and no {@code wal_keep_size}, writes 96 MB of WAL, and
     * checkpoints until the writer reports the slot as lost. The writer is left running with that bound.
     */
    public Future<Void> loseSlot(Vertx vertx, PgCommandRunner runner, String standby) {
        PgSupervisedContainer writer = node(WRITER);
        String directory = dataDirectory;
        return node(standby).forceStop(runner, directory)
            .compose(ignored -> writer.forceStop(runner, directory))
            .compose(ignored -> checked(writer.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", directory, "-w",
                "-l", writer.logFile(directory), "-o", "-c max_slot_wal_keep_size=32MB -c wal_keep_size=0"),
                "Starting the writer with a small slot bound"))
            .compose(ignored -> writeWal(runner, 96))
            .compose(ignored -> awaitSlotLost(vertx, runner, PgNodeConfig.slotName(standby),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(30)));
    }

    /** Writes at least this many megabytes of WAL on the writer in one committed transaction, then checkpoints it. */
    public Future<Void> writeWal(PgCommandRunner runner, int megabytes) {
        PgSupervisedContainer writer = node(WRITER);
        return checked(writer.sql(runner, "create table if not exists wal_probe (id int); do $$ declare "
            + "started pg_lsn := pg_current_wal_insert_lsn(); begin while pg_wal_lsn_diff(pg_current_wal_insert_lsn(), "
            + "started) < " + megabytes + " * 1024 * 1024 loop insert into wal_probe select generate_series(1, 100000); "
            + "end loop; end $$"), "Writing WAL")
            .compose(ignored -> checked(writer.sql(runner, "checkpoint"), "Checkpoint after writing WAL"));
    }

    /** Checkpoints until the writer reports the slot as lost. A checkpoint removes the WAL that nothing keeps. */
    private Future<Void> awaitSlotLost(Vertx vertx, PgCommandRunner runner, String slot, long deadline) {
        PgSupervisedContainer writer = node(WRITER);
        return checked(writer.sql(runner, "checkpoint"), "Checkpoint").compose(ignored -> writer.sql(runner,
            "select coalesce(wal_status, 'none') from pg_replication_slots where slot_name = '" + slot + "'"))
            .compose(status -> {
                if ("lost".equals(status.output().strip())) return Future.<Void>succeededFuture();
                if (System.nanoTime() >= deadline) {
                    return Future.<Void>failedFuture(new AssertionError("Slot " + slot + " was not lost: " + status.output()));
                }
                return vertx.timer(200).compose(ignored -> awaitSlotLost(vertx, runner, slot, deadline));
            });
    }

    /**
     * Rebuilds a standby as a deployment does once the writer holds a replaced slot for it (plan §9.26, contract 3):
     * stops it, removes its data directory, and takes a new base backup through that existing slot, without
     * {@code -C}. The standby is left stopped. The admission files come from the writer, which keeps the same
     * replication rules; the password file lies outside the data directory and is kept.
     */
    public Future<Void> rebuildThroughSlot(PgCommandRunner runner, String standby) {
        PgSupervisedContainer node = node(standby);
        String directory = dataDirectory;
        return node.forceStop(runner, directory)
            .compose(ignored -> checked(node.exec(runner, "rm", "-rf", directory), "Removing the data of " + standby))
            .compose(ignored -> checked(node.exec(runner, "pg_basebackup", "-d", primaryConnectionInfo(standby, standby),
                "-D", directory, "-Fp", "-Xs", "-R", "-S", PgNodeConfig.slotName(standby)), "Rebuild of " + standby));
    }

    /**
     * Stops every node's server, each even when an earlier one fails. A node that cannot be
     * confirmed stopped fails the result.
     */
    public Future<Void> forceStopAll(PgCommandRunner runner) {
        if (dataDirectory == null) return Future.succeededFuture();
        List<Throwable> failures = new ArrayList<>();
        Future<Void> stopped = Future.succeededFuture();
        for (PgSupervisedContainer node : nodes.values()) {
            stopped = stopped.compose(ignored -> node.forceStop(runner, dataDirectory).transform(stop -> {
                if (stop.failed()) failures.add(stop.cause());
                return Future.<Void>succeededFuture();
            }));
        }
        return stopped.compose(ignored -> {
            if (failures.isEmpty()) return Future.<Void>succeededFuture();
            Throwable first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            return Future.<Void>failedFuture(first);
        });
    }

    /**
     * The connection string with which a standby reaches the writer under its own role. Its base
     * backup uses it with the standby's identity as application name. A test passes another name to
     * make the standby replicate under an identity that is not its own.
     */
    public String primaryConnectionInfo(String standby, String applicationName) {
        return "host=" + WRITER + " port=5432 user=" + standby + " password=" + password(standby)
            + " application_name=" + applicationName;
    }

    private static String password(String standby) {
        return "replication-secret-" + standby;
    }

    private static Future<Void> checked(Future<PgCommandResult> command, String step) {
        return command.map(result -> {
            if (result.exitCode() != 0) throw new AssertionError(step + " failed: " + result.output());
            return null;
        });
    }
}
