package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps the real command runner. A SQL command that reads {@code pg_stat_wal_receiver} is replaced by a command on the
 * same server that runs the original query repeatedly until its result starts with
 * {@code streaming|<host>|<port>|<slot>}, and returns that result. The query runs against the real server; only the
 * moment of the observation is chosen. Every other command reaches the real runner unchanged.
 *
 * <p>When the writer has no slot for the receiver, PostgreSQL 15 reports {@code streaming} with the writer's address
 * and the slot as soon as the receiver has connected, before {@code START_REPLICATION} fails (walreceiver.c). That
 * window lasts milliseconds and recurs with {@code wal_retrieve_retry_interval}. This wrapper returns the observation
 * from inside the window, so a follow confirmation that accepts it is caught on every run. If the window does not
 * occur within the command's time, the last observation is returned.
 */
public final class ReceiverWindowCommandRunner implements PgCommandRunner {
    private static final long MARGIN_MILLIS = 1_000;
    private final PgCommandRunner real;
    private final String prefix;
    private final AtomicInteger windowObservations = new AtomicInteger();

    public ReceiverWindowCommandRunner(PgCommandRunner real, String host, int port, String slot) {
        this.real = Objects.requireNonNull(real, "real");
        this.prefix = "streaming|" + Objects.requireNonNull(host, "host") + "|" + port + "|"
            + Objects.requireNonNull(slot, "slot");
    }

    @Override
    public Future<PgCommandResult> run(List<String> command, Duration timeout) {
        int option = command.indexOf("-c");
        if (option < 0 || option + 1 >= command.size() || !command.get(option + 1).contains("pg_stat_wal_receiver")) {
            return real.run(command, timeout);
        }
        long waitMillis = Math.max(0, timeout.toMillis() - MARGIN_MILLIS);
        // A standby is read-only, so the loop is an anonymous block; it hands the observation to the next statement in
        // a session setting.
        String windowed = "do $f$ declare observed text; ends timestamptz := clock_timestamp() + interval '"
            + waitMillis + " milliseconds'; begin loop perform pg_stat_clear_snapshot(); execute $q$"
            + command.get(option + 1) + "$q$ into observed; exit when observed like '" + prefix
            + "%' or clock_timestamp() > ends; end loop; perform set_config('peegeeq.receiver_window', "
            + "coalesce(observed, ''), false); end $f$; select current_setting('peegeeq.receiver_window')";
        List<String> replaced = new ArrayList<>(command.subList(0, option));
        // -q keeps the DO status line out of the output, so the output is the observation alone.
        replaced.add("-q");
        replaced.add("-c");
        replaced.add(windowed);
        replaced.addAll(command.subList(option + 2, command.size()));
        return real.run(replaced, timeout).map(result -> {
            if (result != null && result.exitCode() == 0 && result.output().strip().startsWith(prefix)) {
                windowObservations.incrementAndGet();
            }
            return result;
        });
    }

    /** How many observations from inside the window were returned, so a test can show the window was reached. */
    public int windowObservations() {
        return windowObservations.get();
    }
}
