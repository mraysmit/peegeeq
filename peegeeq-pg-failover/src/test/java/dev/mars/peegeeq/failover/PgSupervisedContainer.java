package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.vertx.core.Future;
import org.testcontainers.containers.GenericContainer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A PostgreSQL image whose entry point does not start PostgreSQL. The supervisor under test owns
 * the process. Commands run inside the container as the {@code postgres} user, through the
 * production command runner and the Docker command line.
 */
public final class PgSupervisedContainer implements AutoCloseable {
    public static final String PG_CTL = "pg_ctl";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private final GenericContainer<?> container = new GenericContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
        .withCreateContainerCmdModifier(command -> command.withEntrypoint("tail", "-f", "/dev/null"));

    public void start() {
        container.start();
    }

    @Override
    public void close() {
        container.stop();
    }

    /** Prefix that runs a command in the container as the PostgreSQL operating-system user. */
    public List<String> prefix() {
        return List.of("docker", "exec", "-u", "postgres", container.getContainerId());
    }

    public String logFile(String dataDirectory) {
        return dataDirectory + ".log";
    }

    /** Creates a new, stopped database cluster and returns its data directory. */
    public Future<String> newDataDirectory(PgCommandRunner runner) {
        String dataDirectory = "/var/lib/postgresql/" + UUID.randomUUID();
        return exec(runner, "initdb", "-D", dataDirectory).map(result -> {
            if (result.exitCode() != 0) throw new AssertionError("initdb failed: " + result.output());
            return dataDirectory;
        });
    }

    public Future<PgCommandResult> exec(PgCommandRunner runner, String... command) {
        List<String> full = new ArrayList<>(prefix());
        full.addAll(List.of(command));
        return runner.run(full, COMMAND_TIMEOUT);
    }

    /** Runs one SQL statement through the local socket. A non-zero exit is returned, not thrown. */
    public Future<PgCommandResult> sql(PgCommandRunner runner, String statement) {
        return exec(runner, "psql", "-X", "-tA", "-c", statement);
    }

    public String openHba(String dataDirectory) {
        return dataDirectory + "/pg_hba.open.conf";
    }

    public String closedHba(String dataDirectory) {
        return dataDirectory + "/pg_hba.closed.conf";
    }

    /**
     * Writes the two admission files a deployment provisions. Both keep the local socket for the
     * supervisor. The open file admits application connections over TCP; the closed file rejects them.
     */
    public Future<Void> provisionAdmission(PgCommandRunner runner, String dataDirectory) {
        return write(runner, openHba(dataDirectory), "local all all trust\\nhost all all 127.0.0.1/32 trust\\n")
            .compose(ignored -> write(runner, closedHba(dataDirectory),
                "local all all trust\\nhost all all all reject\\n"));
    }

    /** Runs one statement over TCP, the route an application uses. */
    public Future<PgCommandResult> applicationSql(PgCommandRunner runner, String statement) {
        return exec(runner, "psql", "-X", "-tA", "-h", "127.0.0.1", "-c", statement);
    }

    private Future<Void> write(PgCommandRunner runner, String file, String printfFormat) {
        return exec(runner, "sh", "-c", "printf '" + printfFormat + "' > " + file).map(result -> {
            if (result.exitCode() != 0) throw new AssertionError("Could not write " + file + ": " + result.output());
            return null;
        });
    }

    /** Stops whatever server uses this data directory. Exit 0 is stopped now; exit 1 is not running. */
    public Future<Void> forceStop(PgCommandRunner runner, String dataDirectory) {
        return exec(runner, PG_CTL, "stop", "-D", dataDirectory, "-m", "immediate", "-w", "-t", "20")
            .compose(stopped -> exec(runner, PG_CTL, "status", "-D", dataDirectory))
            .map(status -> {
                if (status.exitCode() != 3) throw new AssertionError("PostgreSQL is not stopped: " + status.output());
                return null;
            });
    }
}
