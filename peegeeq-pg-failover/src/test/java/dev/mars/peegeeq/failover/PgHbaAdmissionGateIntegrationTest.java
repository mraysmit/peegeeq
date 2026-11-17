package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
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
import org.junit.jupiter.api.parallel.Isolated;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure modes of the host-based-authentication admission gate against a real PostgreSQL that
 * only the test starts. The admission behaviour through the supervisor is in
 * {@code PgFailoverMonitorIntegrationTest}.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@io.vertx.junit5.Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
class PgHbaAdmissionGateIntegrationTest {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration BUDGET = Duration.ofSeconds(10);
    private static final String INVALID_RULES =
        "local all all trust\\nthis line is not a rule\\nhost all all 127.0.0.1/32 trust\\n";
    private static final PgSupervisedContainer POSTGRES = new PgSupervisedContainer();
    private Vertx vertx;
    private PgCommandRunner runner;
    private String dataDirectory;
    private PgProcessControl control;

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
            control = new PgCtlProcessControl(runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, created,
                POSTGRES.logFile(created), COMMAND_TIMEOUT);
            return POSTGRES.provisionAdmission(runner, created);
        }).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        POSTGRES.forceStop(runner, dataDirectory)
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void activeFileThatDoesNotHoldTheInstalledRulesFailsTheClose(VertxTestContext context) {
        // The active path is a directory. The copy exits 0 by copying into it. The comparison does not.
        String directory = "/var/lib/postgresql/" + UUID.randomUUID();
        var gate = gate(directory, POSTGRES.openHba(directory), POSTGRES.closedHba(directory));
        POSTGRES.exec(runner, "mkdir", "-p", directory + "/pg_hba.conf").compose(created -> {
            assertEquals(0, created.exitCode(), created.output());
            return POSTGRES.provisionAdmission(runner, directory);
        }).compose(ignored -> gate.close(BUDGET))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgProcessControlException.class, failure);
                assertTrue(failure.getMessage().startsWith("Verifying admission file"), failure.getMessage());
                context.completeNow();
            })));
    }

    @Test void processStateThatIsNeitherRunningNorStoppedFailsCloseAndOpen(VertxTestContext context) {
        // An existing directory that is not a database cluster: pg_ctl status exits 4.
        String directory = "/var/lib/postgresql/" + UUID.randomUUID();
        var gate = gate(directory, POSTGRES.openHba(directory), POSTGRES.closedHba(directory));
        POSTGRES.exec(runner, "mkdir", "-p", directory).compose(created -> {
            assertEquals(0, created.exitCode(), created.output());
            return POSTGRES.provisionAdmission(runner, directory);
        }).compose(ignored -> gate.close(BUDGET)).transform(close -> {
            assertTrue(close.failed(), "An unknown process state was treated as stopped");
            assertInstanceOf(PgProcessControlException.class, close.cause());
            assertTrue(close.cause().getMessage().startsWith("pg_ctl status exit 4"), close.cause().getMessage());
            return gate.open();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgProcessControlException.class, failure);
            assertTrue(failure.getMessage().startsWith("pg_ctl status exit 4"), failure.getMessage());
            context.completeNow();
        })));
    }

    @Test void openWithInvalidRulesFailsAndAdmitsNothing(VertxTestContext context) {
        String invalid = dataDirectory + "/pg_hba.invalid.conf";
        var gate = gate(dataDirectory, invalid, POSTGRES.closedHba(dataDirectory));
        POSTGRES.write(runner, invalid, INVALID_RULES).compose(ignored -> gate.close(BUDGET))
            .compose(ignored -> control.startPrimary())
            .compose(ignored -> gate.open()).transform(open -> {
                assertTrue(open.failed(), "Admission opened on rules that PostgreSQL rejected");
                assertInstanceOf(PgProcessControlException.class, open.cause());
                assertEquals("Admission file has invalid rules", open.cause().getMessage());
                return POSTGRES.applicationSql(runner, "select 1");
            }).onSuccess(application -> context.verify(() -> {
                assertNotEquals(0, application.exitCode(), "An application connected after a failed open");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void closeWithInvalidRulesFailsAndDoesNotCloseAdmission(VertxTestContext context) {
        String invalid = dataDirectory + "/pg_hba.invalid.conf";
        var valid = gate(dataDirectory, POSTGRES.openHba(dataDirectory), POSTGRES.closedHba(dataDirectory));
        var broken = gate(dataDirectory, POSTGRES.openHba(dataDirectory), invalid);
        POSTGRES.write(runner, invalid, INVALID_RULES).compose(ignored -> valid.close(BUDGET))
            .compose(ignored -> control.startPrimary())
            .compose(ignored -> valid.open())
            .compose(ignored -> broken.close(BUDGET)).transform(close -> {
                assertTrue(close.failed(), "A close on rules that PostgreSQL rejected was reported as success");
                assertInstanceOf(PgProcessControlException.class, close.cause());
                assertEquals("Admission file has invalid rules", close.cause().getMessage());
                return POSTGRES.applicationSql(runner, "select 1");
            }).onSuccess(application -> context.verify(() -> {
                // PostgreSQL kept the rules that admit applications. The caller must stop the writer.
                assertEquals(0, application.exitCode(), application.output());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void aRunnerThatReturnsNoResultFailsCloseAndOpen(VertxTestContext context) {
        var silent = new PgHbaAdmissionGate(vertx, new NoResultCommandRunner(runner), POSTGRES.prefix(),
            PgSupervisedContainer.PG_CTL, "psql", dataDirectory, POSTGRES.openHba(dataDirectory),
            POSTGRES.closedHba(dataDirectory), COMMAND_TIMEOUT);
        silent.close(BUDGET).transform(close -> {
            assertTrue(close.failed(), "A close succeeded although its command returned no result");
            assertInstanceOf(PgProcessControlException.class, close.cause());
            assertTrue(close.cause().getMessage().startsWith("Command returned no result"), close.cause().getMessage());
            return silent.open();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgProcessControlException.class, failure);
            assertTrue(failure.getMessage().startsWith("Command returned no result"), failure.getMessage());
            context.completeNow();
        })));
    }

    @Test void closeThatCannotFinishFailsWithinItsBudget(VertxTestContext context) {
        // A one-millisecond budget cannot cover one command. The failure must still be prompt.
        var gate = gate(dataDirectory, POSTGRES.openHba(dataDirectory), POSTGRES.closedHba(dataDirectory));
        long started = System.nanoTime();
        gate.close(Duration.ofMillis(1)).transform(close -> {
            assertTrue(close.failed(), "A close without time to run was reported as success");
            assertInstanceOf(PgProcessControlException.class, close.cause());
            assertTrue(close.cause().getMessage().startsWith("Admission budget exhausted"), close.cause().getMessage());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30));
            return gate.close(Duration.ZERO);
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgProcessControlException.class, failure);
            assertEquals("Closing admission requires a positive budget", failure.getMessage());
            context.completeNow();
        })));
    }

    @Test void openWhilePostgresIsStoppedIsRefusedAndInstallsNothing(VertxTestContext context) {
        var gate = gate(dataDirectory, POSTGRES.openHba(dataDirectory), POSTGRES.closedHba(dataDirectory));
        gate.close(BUDGET).compose(ignored -> gate.open()).transform(open -> {
            assertTrue(open.failed(), "Admission opened while PostgreSQL was stopped");
            assertInstanceOf(PgProcessControlException.class, open.cause());
            assertEquals("Admission cannot open while PostgreSQL is stopped", open.cause().getMessage());
            return POSTGRES.exec(runner, "cmp", "-s", POSTGRES.closedHba(dataDirectory),
                dataDirectory + "/pg_hba.conf");
        }).onSuccess(compared -> context.verify(() -> {
            assertEquals(0, compared.exitCode(), "The rules for the next start are no longer the closed rules");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void closeWithAConfigurationFileThatPostgreSqlRejectsEndsSessionsAndFailsWithoutWaitingOutItsBudget(
            VertxTestContext context) {
        // Plan §9.33, check 9: PostgreSQL then applies pg_hba.conf on a reload but leaves the load time unchanged, so a
        // wait for the load time ends only at the budget, before the sessions are ended.
        var gate = gate(dataDirectory, POSTGRES.openHba(dataDirectory), POSTGRES.closedHba(dataDirectory));
        var budget = Duration.ofSeconds(20);
        gate.close(BUDGET).compose(ignored -> control.startPrimary()).compose(ignored -> gate.open()).compose(open -> {
            Future<PgCommandResult> session = POSTGRES.applicationSql(runner, "select pg_sleep(90)");
            return waitForBackend("pg_sleep", System.nanoTime() + TimeUnit.SECONDS.toNanos(20))
                .compose(ignored -> breakConfiguration())
                .compose(ignored -> {
                    long started = System.nanoTime();
                    return gate.close(budget).transform(close -> {
                        assertTrue(close.failed(), "A close whose reload cannot be confirmed was reported as success");
                        assertInstanceOf(PgProcessControlException.class, close.cause());
                        assertTrue(close.cause().getMessage().contains("no_such_parameter"), close.cause().getMessage());
                        assertTrue(System.nanoTime() - started < budget.minusSeconds(5).toNanos(),
                            "The close waited out its budget");
                        return session;
                    });
                });
        }).compose(session -> {
            assertNotEquals(0, session.exitCode(), "An application session survived the close: " + session.output());
            return POSTGRES.applicationSql(runner, "select 1");
        }).onSuccess(application -> context.verify(() -> {
            assertNotEquals(0, application.exitCode(), "A new application connection was admitted after the close");
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void openWithAConfigurationFileThatPostgreSqlRejectsIsRefusedAndAdmitsNothing(VertxTestContext context) {
        // Check 9: a reload would apply the open rules although the load time never changes.
        var gate = gate(dataDirectory, POSTGRES.openHba(dataDirectory), POSTGRES.closedHba(dataDirectory));
        gate.close(BUDGET).compose(ignored -> control.startPrimary()).compose(ignored -> breakConfiguration())
            .compose(ignored -> {
                long started = System.nanoTime();
                return gate.open().transform(open -> {
                    assertTrue(open.failed(), "Admission opened with a configuration file that PostgreSQL rejects");
                    assertInstanceOf(PgProcessControlException.class, open.cause());
                    assertTrue(open.cause().getMessage().contains("no_such_parameter"), open.cause().getMessage());
                    assertTrue(System.nanoTime() - started < COMMAND_TIMEOUT.minusSeconds(5).toNanos(),
                        "The open waited out its budget");
                    return POSTGRES.applicationSql(runner, "select 1");
                });
            }).compose(application -> {
                assertNotEquals(0, application.exitCode(), "An application connected after a refused open");
                return POSTGRES.exec(runner, "cmp", "-s", POSTGRES.closedHba(dataDirectory), dataDirectory + "/pg_hba.conf");
            }).onSuccess(compared -> context.verify(() -> {
                assertEquals(0, compared.exitCode(), "A refused open installed the open rules");
                context.completeNow();
            })).onFailure(context::failNow);
    }

    /** An unknown parameter: PostgreSQL then applies no change of postgresql.conf (plan §9.33, check 6). */
    private Future<Void> breakConfiguration() {
        return POSTGRES.exec(runner, "sh", "-c", "printf 'no_such_parameter = 1\\n' >> " + dataDirectory
            + "/postgresql.conf").map(result -> {
                assertEquals(0, result.exitCode(), result.output());
                return null;
            });
    }

    /** Polls every 100 ms until a backend running the given text is visible, and fails on the deadline. */
    private Future<Void> waitForBackend(String text, long deadline) {
        return POSTGRES.sql(runner, "select count(*) from pg_stat_activity where query like '%" + text
                + "%' and pid <> pg_backend_pid()").compose(result -> {
            if (result.exitCode() == 0 && !"0".equals(result.output().strip())) return Future.succeededFuture();
            if (System.nanoTime() >= deadline) {
                return Future.failedFuture(new AssertionError("Backend did not appear: " + result.output()));
            }
            return vertx.timer(100).compose(ignored -> waitForBackend(text, deadline));
        });
    }

    private PgAdmissionGate gate(String directory, String openFile, String closedFile) {
        return new PgHbaAdmissionGate(vertx, runner, POSTGRES.prefix(), PgSupervisedContainer.PG_CTL, "psql",
            directory, openFile, closedFile, COMMAND_TIMEOUT);
    }
}
