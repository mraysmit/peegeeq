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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Local process control against a real PostgreSQL that only the supervisor starts and stops. */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Isolated
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PgCtlProcessControlIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(PgCtlProcessControlIntegrationTest.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration STOP_BUDGET = Duration.ofSeconds(10);
    private static final PgSupervisedContainer POSTGRES = new PgSupervisedContainer();
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
        runner = new LocalCommandRunner(vertx);
        POSTGRES.newDataDirectory(runner).onSuccess(created -> context.verify(() -> {
            dataDirectory = created;
            control = control(PgSupervisedContainer.PG_CTL, created);
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @AfterEach void tearDown(VertxTestContext context) {
        POSTGRES.forceStop(runner, dataDirectory)
            .onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    @Test void statusReportsStoppedThenRunning(VertxTestContext context) {
        control.status().compose(before -> {
            assertEquals(PgProcessState.STOPPED, before);
            return control.startPrimary();
        }).compose(ignored -> control.status())
            .onSuccess(after -> context.verify(() -> {
                assertEquals(PgProcessState.RUNNING, after);
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void primaryStartIsWritable(VertxTestContext context) {
        control.startPrimary().compose(ignored -> POSTGRES.sql(runner, "create table started_writable(i int)"))
            .compose(created -> {
                assertEquals(0, created.exitCode(), created.output());
                return POSTGRES.sql(runner, "select pg_is_in_recovery()");
            }).onSuccess(recovery -> context.verify(() -> {
                assertEquals("f", recovery.output().strip());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void recoveryStartIsReadOnly(VertxTestContext context) {
        control.startInRecovery().compose(ignored -> POSTGRES.sql(runner, "select pg_is_in_recovery()"))
            .compose(recovery -> {
                assertEquals("t", recovery.output().strip());
                return POSTGRES.sql(runner, "create table rejected(i int)");
            }).onSuccess(rejected -> context.verify(() -> {
                assertNotEquals(0, rejected.exitCode());
                assertTrue(rejected.output().contains("read-only"), rejected.output());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void stopEndsTheProcessAndExistingSessions(VertxTestContext context) {
        control.startPrimary().compose(ignored -> {
            Future<PgCommandResult> session = POSTGRES.sql(runner, "select pg_sleep(90)");
            return waitForBackend("pg_sleep", System.nanoTime() + TimeUnit.SECONDS.toNanos(20))
                .compose(observed -> control.stop(STOP_BUDGET))
                .compose(stopped -> session);
        }).compose(session -> {
            assertNotEquals(0, session.exitCode(), "The session survived the stop: " + session.output());
            return control.status();
        }).onSuccess(status -> context.verify(() -> {
            assertEquals(PgProcessState.STOPPED, status);
            context.completeNow();
        })).onFailure(context::failNow);
    }

    @Test void aSecondEntryPointCannotStartAWriterAfterStop(VertxTestContext context) {
        control.startPrimary().compose(ignored -> control.stop(STOP_BUDGET))
            // Bypass the supervisor: start PostgreSQL directly, as an orchestrator or operator would.
            .compose(ignored -> POSTGRES.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", dataDirectory,
                "-w", "-t", "20", "-l", POSTGRES.logFile(dataDirectory)))
            .compose(started -> {
                assertEquals(0, started.exitCode(), started.output());
                return POSTGRES.sql(runner, "select pg_is_in_recovery()");
            }).compose(recovery -> {
                assertEquals("t", recovery.output().strip(), "The second entry point started a writable server");
                return POSTGRES.sql(runner, "create table second_entry(i int)");
            }).onSuccess(rejected -> context.verify(() -> {
                assertNotEquals(0, rejected.exitCode());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void stopWhenAlreadyStoppedSucceedsAndStillInhibitsAWriter(VertxTestContext context) {
        control.stop(STOP_BUDGET).compose(ignored -> control.status()).compose(status -> {
            assertEquals(PgProcessState.STOPPED, status);
            return POSTGRES.exec(runner, PgSupervisedContainer.PG_CTL, "start", "-D", dataDirectory,
                "-w", "-t", "20", "-l", POSTGRES.logFile(dataDirectory));
        }).compose(started -> POSTGRES.sql(runner, "select pg_is_in_recovery()"))
            .onSuccess(recovery -> context.verify(() -> {
                assertEquals("t", recovery.output().strip());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void startWhileRunningFails(VertxTestContext context) {
        control.startPrimary().compose(ignored -> control.startPrimary())
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgProcessControlException.class, failure);
                context.completeNow();
            })));
    }

    @Test void missingDataDirectoryFailsStatusStartAndStop(VertxTestContext context) {
        PgProcessControl absent = control(PgSupervisedContainer.PG_CTL, "/var/lib/postgresql/absent");
        absent.status().transform(status -> {
            assertTrue(status.failed());
            assertInstanceOf(PgProcessControlException.class, status.cause());
            return absent.startPrimary();
        }).transform(start -> {
            assertTrue(start.failed());
            assertInstanceOf(PgProcessControlException.class, start.cause());
            return absent.stop(STOP_BUDGET);
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgProcessControlException.class, failure);
            context.completeNow();
        })));
    }

    @Test void missingControlBinaryFailsEveryOperation(VertxTestContext context) {
        PgProcessControl broken = control("/nonexistent/pg_ctl", dataDirectory);
        broken.status().transform(status -> {
            assertTrue(status.failed());
            assertInstanceOf(PgProcessControlException.class, status.cause());
            return broken.startInRecovery();
        }).transform(start -> {
            assertTrue(start.failed());
            assertInstanceOf(PgProcessControlException.class, start.cause());
            return broken.stop(STOP_BUDGET);
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgProcessControlException.class, failure);
            context.completeNow();
        })));
    }

    @Test void stopThatCannotFinishFailsWithinItsBudget(VertxTestContext context) {
        // A one-millisecond budget cannot cover one command. The failure must still be prompt.
        long started = System.nanoTime();
        control.startPrimary().compose(ignored -> control.stop(Duration.ofMillis(1)))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgProcessControlException.class, failure);
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30));
                context.completeNow();
            })));
    }

    private PgProcessControl control(String pgCtl, String directory) {
        return new PgCtlProcessControl(runner, POSTGRES.prefix(), pgCtl, directory, POSTGRES.logFile(directory),
            COMMAND_TIMEOUT);
    }

    /** Polls until a backend running the given text is visible, and fails on the deadline. */
    private Future<Void> waitForBackend(String text, long deadline) {
        return POSTGRES.sql(runner, "select count(*) from pg_stat_activity where query like '%" + text
                + "%' and pid <> pg_backend_pid()").compose(result -> {
            if (result.exitCode() == 0 && !"0".equals(result.output().strip())) return Future.succeededFuture();
            if (System.nanoTime() >= deadline) {
                return Future.failedFuture(new AssertionError("Backend did not appear: " + result.output()));
            }
            logger.debug("Waiting for backend: {}", result.output());
            return Future.<Void>succeededFuture().compose(ignored -> waitForBackend(text, deadline));
        });
    }
}
