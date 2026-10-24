package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import org.testcontainers.containers.Network;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An initial writer and two physical standbys, each in its own container on one network. Each
 * provisioning does what a deployment does before first-start bootstrap (OD-10): it creates the
 * writer's cluster, starts it once with application admission closed to create one replication
 * role per standby, takes each standby's base backup under that standby's own role with
 * {@code standby.signal} in place, and stops the writer. Nothing is left running. A standby's
 * replication role is its node identity (OD-11).
 */
public final class PgReplicationFixture implements AutoCloseable {
    public static final String WRITER = "pg-node-1";
    public static final List<String> STANDBYS = List.of("pg-node-2", "pg-node-3");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private final Network network = Network.newNetwork();
    private final Map<String, PgSupervisedContainer> nodes = new LinkedHashMap<>();
    private String dataDirectory;

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

    public PgProcessControl control(PgCommandRunner runner, String nodeId) {
        PgSupervisedContainer node = node(nodeId);
        return new PgCtlProcessControl(runner, node.prefix(), PgSupervisedContainer.PG_CTL, dataDirectory,
            node.logFile(dataDirectory), COMMAND_TIMEOUT);
    }

    /** Provisions a new cluster in a new data directory on every node. Every node is left stopped. */
    public Future<Void> provision(PgCommandRunner runner) {
        String directory = "/var/lib/postgresql/" + UUID.randomUUID();
        dataDirectory = directory;
        PgSupervisedContainer writer = node(WRITER);
        // No command argument contains a double quote: Windows does not pass one to a child process.
        StringBuilder replicationRules = new StringBuilder();
        STANDBYS.forEach(standby ->
            replicationRules.append("host replication ").append(standby).append(" samenet scram-sha-256\\n"));
        Future<Void> provisioned = checked(writer.exec(runner, "initdb", "-D", directory), "initdb")
            .compose(ignored -> checked(writer.exec(runner, "sh", "-c", "printf 'listen_addresses = \\047*\\047\\n"
                + "wal_keep_size = \\04764MB\\047\\n' >> " + directory + "/postgresql.conf"), "Configuring the writer"))
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
                    primaryConnectionInfo(standby, standby), "-D", directory, "-Fp", "-Xs", "-R"),
                    "Base backup for " + standby))
                .compose(ignored -> node.write(runner, node.closedHba(directory),
                    "local all all trust\\nhost all all all reject\\n"))
                .compose(ignored -> node.write(runner, node.openHba(directory),
                    "local all all trust\\nhost all all all trust\\n"));
        }
        return provisioned.compose(ignored -> checked(writer.exec(runner, PgSupervisedContainer.PG_CTL, "stop",
            "-D", directory, "-m", "fast", "-w"), "Stopping the writer after provisioning"));
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
