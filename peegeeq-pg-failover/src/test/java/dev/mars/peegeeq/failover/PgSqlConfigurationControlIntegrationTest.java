package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The configuration adapter (plan §9.33, contract 1) against a real PostgreSQL that only the test starts and stops: the
 * settings observation, a reload that takes effect, and a reload refused before it is sent because a configuration file
 * holds an error that PostgreSQL would apply in part or not at all (checks 5 to 7).
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class PgSqlConfigurationControlIntegrationTest {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final String LOAD_TIME = "select extract(epoch from pg_conf_load_time())";
    private static final PgSupervisedContainer POSTGRES = new PgSupervisedContainer();
    @TempDir Path localDirectory;
    private Vertx vertx;
    private PgCommandRunner runner;
    private String dataDirectory;
    private PgProcessControl process;
    private PgConfigurationControl control;

    @BeforeAll static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll static void stopContainer() {
        POSTGRES.close();
    }

    @BeforeEach void setUp(Vertx vertx, VertxTestContext context) {
        this.vertx = vertx;
        runner = new LocalCommandRunner(vertx);
        POSTGRES.newDataDirectory(runner).compose(created -> {
            dataDirectory = created;
            process = new PgCtlProcessControl(runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, created,
                POSTGRES.logFile(created), COMMAND_TIMEOUT);
            control = control(runner, POSTGRES.prefix(), "psql", COMMAND_TIMEOUT);
            return process.startPrimary();
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        POSTGRES.forceStop(runner, dataDirectory).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- settings

    @Test void theSettingsReportTheRunningServer(VertxTestContext context) {
        sql("select (extract(epoch from pg_postmaster_start_time()) * 1000000)::bigint || '|' "
                + "|| current_setting('server_version_num')").compose(expected -> control.settings().map(settings -> {
            String[] parts = expected.split("\\|");
            assertEquals(Long.parseLong(parts[0]), settings.postmasterStartMicros(), "The postmaster start time differs");
            assertEquals(Integer.parseInt(parts[1]), settings.serverVersion(), "The server version differs");
            assertFalse(settings.inRecovery(), "A writable server was observed in recovery");
            assertEquals(List.of(), settings.pendingRestart(), "A new server has no parameter pending a restart");
            return null;
        })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aServerInRecoveryIsReportedInRecovery(VertxTestContext context) {
        process.stop(COMMAND_TIMEOUT).compose(ignored -> process.startInRecovery())
            .compose(ignored -> control.settings())
            .onSuccess(settings -> context.verify(() -> {
                assertTrue(settings.inRecovery(), "A server in recovery was observed writable");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aRestartParameterChangedByAlterSystemIsPendingAfterTheReload(VertxTestContext context) {
        // Plan §9.33, check 3: a postmaster parameter keeps its value until a restart and is reported pending.
        sql("alter system set max_connections = 150").compose(ignored -> control.reload())
            .compose(ignored -> control.settings()).compose(settings -> {
                assertEquals(List.of("max_connections"), settings.pendingRestart());
                return sql("show max_connections");
            }).onSuccess(effective -> context.verify(() -> {
                assertEquals("100", effective, "A restart parameter took effect without a restart");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- reload

    @Test void aReloadableParameterTakesEffectAndTheLoadTimeChanges(VertxTestContext context) {
        sql(LOAD_TIME).compose(before -> sql("alter system set work_mem = '8MB'")
            .compose(ignored -> control.reload())
            .compose(ignored -> sql("show work_mem"))
            .compose(workMem -> {
                assertEquals("8MB", workMem, "The reloaded setting is not in effect");
                return sql(LOAD_TIME);
            }).map(after -> {
                assertNotEquals(before, after, "The reload did not change the configuration load time");
                return null;
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aReloadCommandThatReportsSuccessButIsNotProcessedFailsWithinItsBudget(VertxTestContext context) {
        // The load time is the evidence of a reload, not the exit code of pg_ctl reload (plan §9.33, contract 1). The
        // budget leaves room for slow docker exec calls on the test host (3.7 s for one psql call was seen).
        var budget = Duration.ofSeconds(10);
        var unprocessed = control(new ReloadDoesNotReloadCommandRunner(runner), POSTGRES.prefix(), "psql", budget);
        long started = System.nanoTime();
        sql("alter system set work_mem = '8MB'").compose(ignored -> unprocessed.reload().transform(reload -> {
            refused(reload, "The configuration load time did not change within the budget");
            assertTrue(System.nanoTime() - started < budget.plusSeconds(10).toNanos(), "The wait overran its budget");
            return sql("show work_mem");
        })).onSuccess(workMem -> context.verify(() -> {
            assertEquals("4MB", workMem, "The reload was processed although its signal was not sent");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void anInvalidValueFailsTheReloadNamesItAndStatesThatTheOtherChangesWereApplied(VertxTestContext context) {
        // Check 5: PostgreSQL applies work_mem and skips checkpoint_timeout. Before a reload an invalid value cannot be
        // told apart from a pending restart (check 8), so the reload is sent, and its failure must say what happened.
        appendConfiguration("postgresql.conf", "work_mem = 8MB\\ncheckpoint_timeout = nonsense")
            .compose(ignored -> control.reload().transform(reload -> {
                assertTrue(reload.failed(), "A reload of a file with an invalid value succeeded");
                assertInstanceOf(PgConfigurationException.class, reload.cause());
                String message = reload.cause().getMessage();
                assertTrue(message.contains("checkpoint_timeout"), message);
                assertTrue(message.contains("applied the other changes"), message);
                return sql("show work_mem");
            }))
            .onSuccess(workMem -> context.verify(() -> {
                assertEquals("8MB", workMem, "The failure says the other changes were applied, and they were not");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void anInvalidValueOfARestartParameterFailsTheReloadAndIsNotReportedPending(VertxTestContext context) {
        // Check 8: shared_buffers = nonsense and a valid max_connections change carry the same error text. Only the
        // invalid one is an error; a restart with it would not start.
        appendConfiguration("postgresql.conf", "shared_buffers = nonsense")
            .compose(ignored -> sql("alter system set max_connections = 150"))
            .compose(ignored -> control.reload().transform(reload -> {
                assertTrue(reload.failed(), "A reload of a file with an invalid restart parameter succeeded");
                assertInstanceOf(PgConfigurationException.class, reload.cause());
                String message = reload.cause().getMessage();
                assertTrue(message.contains("shared_buffers"), message);
                assertFalse(message.contains("max_connections"), "A valid pending restart was reported as an error: "
                    + message);
                return control.settings();
            }))
            .onSuccess(settings -> context.verify(() -> {
                assertEquals(List.of("max_connections"), settings.pendingRestart());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void anUnknownParameterFailsTheReloadAtOnceInsteadOfAtTheEndOfTheBudget(VertxTestContext context) {
        // Check 6: PostgreSQL applies nothing and the load time never changes, so a wait for it would end only at the
        // budget. The failure must come at once and name the parameter.
        long started = System.nanoTime();
        appendConfiguration("postgresql.conf", "no_such_parameter = 1")
            .compose(ignored -> refusedWithoutReload("no_such_parameter"))
            .onSuccess(ignored -> context.verify(() -> {
                assertTrue(System.nanoTime() - started < COMMAND_TIMEOUT.toNanos() / 2,
                    "The refusal waited for the budget instead of failing at once");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void anInvalidAdmissionRuleFailsTheReloadBeforeItIsSent(VertxTestContext context) {
        // Check 7: PostgreSQL changes the load time and keeps its previous pg_hba.conf rules.
        appendConfiguration("pg_hba.conf", "notatype all all trust")
            .compose(ignored -> refusedWithoutReload("notatype"))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- dependency failures

    @Test void aStoppedServerFailsBothOperations(VertxTestContext context) {
        process.stop(COMMAND_TIMEOUT)
            .compose(ignored -> control.settings().transform(settings -> refused(settings, "Local SQL failed with exit")))
            .compose(ignored -> control.reload().transform(reload -> refused(reload, "Local SQL failed with exit")))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aRunnerThatReturnsNoResultFailsBothOperations(VertxTestContext context) {
        var silent = control(new NoResultCommandRunner(runner), POSTGRES.prefix(), "psql", COMMAND_TIMEOUT);
        String noResult = "Command returned no result";
        silent.settings().transform(settings -> refused(settings, noResult))
            .compose(ignored -> silent.reload().transform(reload -> refused(reload, noResult)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void outputThatIsNotAnObservationIsRejected(VertxTestContext context) {
        // echo exits 0 and prints its arguments, so the output is not a query result.
        var echoing = control(runner, POSTGRES.prefix(), "echo", COMMAND_TIMEOUT);
        String malformed = "Malformed configuration observation";
        echoing.settings().transform(settings -> refused(settings, malformed))
            .compose(ignored -> echoing.reload().transform(reload -> refused(reload, malformed)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.LocalCommandRunner",
        message = "Command could not run: ",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IOException.class,
        minOccurrences = 2,
        maxOccurrences = 2)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgSqlConfigurationControl",
        message = "Configuration command failed: psql",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgProcessControlException.class,
        minOccurrences = 2,
        maxOccurrences = 2)
    void aCommandThatCannotRunFailsBothOperationsAndIsLogged(VertxTestContext context) {
        // The command prefix names a program that does not exist on this host.
        var unreachable = control(runner, List.of(localDirectory.resolve("absent").toString()), "psql", COMMAND_TIMEOUT);
        String failed = "Command failed: psql";
        unreachable.settings().transform(settings -> refused(settings, failed))
            .compose(ignored -> unreachable.reload().transform(reload -> refused(reload, failed)))
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void aBudgetThatCannotCoverOneCommandFailsPromptly(VertxTestContext context) {
        var hurried = control(runner, POSTGRES.prefix(), "psql", Duration.ofMillis(1));
        String exhausted = "Configuration budget exhausted";
        long started = System.nanoTime();
        hurried.settings().transform(settings -> refused(settings, exhausted))
            .compose(ignored -> hurried.reload().transform(reload -> refused(reload, exhausted)))
            .onSuccess(ignored -> context.verify(() -> {
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "An exhausted budget did not fail promptly");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The reload fails with a {@link PgConfigurationException} that names the entry, and no reload was sent: the load
     * time is unchanged.
     */
    private Future<Void> refusedWithoutReload(String entry) {
        return sql(LOAD_TIME).compose(before -> control.reload().transform(reload -> {
            assertTrue(reload.failed(), "A reload of a configuration file with an error succeeded");
            assertInstanceOf(PgConfigurationException.class, reload.cause());
            assertTrue(reload.cause().getMessage().contains(entry), reload.cause().getMessage());
            return sql(LOAD_TIME);
        }).map(after -> {
            assertEquals(before, after, "A reload was sent for a configuration file with an error");
            return null;
        }));
    }

    private Future<Void> appendConfiguration(String file, String printfLines) {
        return POSTGRES.exec(runner, "sh", "-c", "printf '" + printfLines + "\\n' >> " + dataDirectory + "/" + file)
            .map(result -> {
                assertEquals(0, result.exitCode(), result.output());
                return null;
            });
    }

    /** One statement through the local socket; a non-zero exit fails the chain. The trimmed output is the result. */
    private Future<String> sql(String statement) {
        return POSTGRES.sql(runner, statement).map(result -> {
            assertEquals(0, result.exitCode(), result.output());
            return result.output().strip();
        });
    }

    private PgConfigurationControl control(PgCommandRunner commands, List<String> prefix, String psql, Duration timeout) {
        return new PgSqlConfigurationControl(vertx, commands, prefix, PgSupervisedContainer.PG_CTL, psql, dataDirectory,
            timeout);
    }

    /**
     * Asserts that the operation failed with a {@link PgConfigurationException} whose message starts with the prefix,
     * and succeeds, so that a failed assertion ends the chain with its own message.
     */
    private static Future<Void> refused(AsyncResult<?> outcome, String messagePrefix) {
        assertTrue(outcome.failed(), "The operation succeeded; expected a failure starting with: " + messagePrefix);
        assertInstanceOf(PgConfigurationException.class, outcome.cause());
        assertTrue(outcome.cause().getMessage().startsWith(messagePrefix), outcome.cause().getMessage());
        return Future.succeededFuture();
    }
}
