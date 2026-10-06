package dev.mars.peegeeq.db.consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Core contracts for how {@link WatermarkJob} reports sweep failures.
 *
 * The job follows the shared background-task policy of {@code BackgroundTaskFailureTracker}:
 * the first consecutive failure is a WARN with the full stack, persistent failure escalates to
 * one count-bearing ERROR summary without a stack, and a successful sweep restores health.
 * Each test drives one failure mode of {@code WatermarkCalculator.calculateAndSweep}: a failed
 * Future, a synchronous throw, and a null Future.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class WatermarkJobFailureHealthCoreTest {

    /** Long enough that only the run started by start() occurs unless a test needs the timer. */
    private static final long LONG_INTERVAL_MS = 60_000L;
    private static final long SHORT_INTERVAL_MS = 20L;

    private Vertx vertx;
    private PgConnectionManager connectionManager;
    private ScriptedCalculator calculator;
    private WatermarkJob job;
    private ch.qos.logback.classic.Logger jobLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp(Vertx vertx) {
        this.vertx = vertx;
        connectionManager = new PgConnectionManager(vertx, null);
        calculator = new ScriptedCalculator(connectionManager);
        jobLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WatermarkJob.class);
        appender = new ListAppender<>();
        appender.setContext(jobLogger.getLoggerContext());
        appender.start();
        jobLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        jobLogger.detachAppender(appender);
        appender.stop();
        Future.<Void>succeededFuture()
                .compose(v -> job != null ? job.stopAsync() : Future.succeededFuture())
                .compose(v -> connectionManager != null ? connectionManager.close() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void firstFailureWarnsWithStackAndHealthDegradesThenRecovers(VertxTestContext testContext) {
        String topic = "health-topic";
        calculator.thenFail(new IllegalStateException("forced sweep failure"));
        job = new WatermarkJob(vertx, calculator, topic, LONG_INTERVAL_MS);

        job.start();

        assertEquals("background-watermark-health-topic", job.getHealthComponentName());
        assertEquals(1L, job.getTotalFailures());
        List<ILoggingEvent> failureEvents = warnAndErrorEvents(topic);
        assertEquals(1, failureEvents.size(),
                "Exactly one WARN or ERROR is expected for the first failure, got: " + failureEvents);
        ILoggingEvent first = failureEvents.get(0);
        assertEquals(Level.WARN, first.getLevel(), "The first consecutive failure must be a WARN");
        assertTrue(first.getFormattedMessage().contains("first failure"), first.getFormattedMessage());
        assertTrue(first.getFormattedMessage().contains(topic), first.getFormattedMessage());
        assertNotNull(first.getThrowableProxy(), "The first failure must keep the full stack");
        assertEquals(IllegalStateException.class.getName(), first.getThrowableProxy().getClassName());

        job.checkHealth()
                .compose(status -> {
                    assertTrue(status.isDegraded(), "The first sweep failure should degrade health");
                    assertEquals(1L, status.getDetails().get("consecutiveFailures"));
                    return job.stopAsync();
                })
                .compose(v -> {
                    job.start();
                    return job.checkHealth();
                })
                .compose(status -> {
                    assertTrue(status.isHealthy(), "A successful sweep should restore health");
                    assertEquals(0L, status.getDetails().get("consecutiveFailures"));
                    assertEquals(1L, status.getDetails().get("totalFailures"));
                    return Future.<Void>succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.consumer.WatermarkJob",
            message = "Watermark sweep for topic escalation-topic is still failing "
                    + "(3 consecutive failures, 3 total failures): sweep failure 3",
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void persistentFailureEscalatesToOneCountedErrorSummaryThenRecovers(VertxTestContext testContext) {
        calculator.thenFail(new IllegalStateException("sweep failure 1"));
        calculator.thenFail(new IllegalStateException("sweep failure 2"));
        calculator.thenFail(new IllegalStateException("sweep failure 3"));
        job = new WatermarkJob(vertx, calculator, "escalation-topic", SHORT_INTERVAL_MS);

        job.start();

        // A fifth call can only start after the fourth settled, so the recovery is already recorded.
        awaitCondition(() -> calculator.calls() >= 5, System.currentTimeMillis() + 5_000)
                .compose(v -> job.checkHealth())
                .compose(status -> {
                    assertTrue(status.isHealthy(), "The successful fourth sweep should restore health");
                    assertEquals(0L, status.getDetails().get("consecutiveFailures"));
                    assertEquals(3L, status.getDetails().get("totalFailures"));
                    assertEquals(3L, job.getTotalFailures());

                    List<ILoggingEvent> events = warnAndErrorEvents("escalation-topic");
                    assertEquals(2, events.size(),
                            "Expected one first-failure WARN and one escalation ERROR, got: " + events);
                    assertEquals(Level.WARN, events.get(0).getLevel());
                    assertEquals(Level.ERROR, events.get(1).getLevel());
                    assertNull(events.get(1).getThrowableProxy(),
                            "The escalation summary must not repeat the stack trace");
                    return Future.<Void>succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void synchronousCalculatorThrowIsRecordedAndJobKeepsRunning(VertxTestContext testContext) {
        calculator.thenThrow(new IllegalStateException("calculator threw synchronously"));
        job = new WatermarkJob(vertx, calculator, "throwing-topic", LONG_INTERVAL_MS);

        assertDoesNotThrow(() -> job.start(),
                "A synchronous throw from the calculator must be recorded as a sweep failure, not propagate");

        assertEquals(1L, job.getTotalFailures());
        assertEquals(1L, job.getTotalRunCount(), "A thrown sweep still counts as a run");

        job.checkHealth()
                .compose(status -> {
                    assertTrue(status.isDegraded());
                    assertTrue(String.valueOf(status.getDetails().get("lastFailureMessage"))
                            .contains("calculator threw synchronously"), "details: " + status.getDetails());
                    return job.stopAsync();
                })
                .compose(v -> {
                    job.start();
                    return job.checkHealth();
                })
                .compose(status -> {
                    assertEquals(2L, job.getTotalRunCount(),
                            "The next run must execute, so the in-progress guard must have been released");
                    assertTrue(status.isHealthy(), "A successful sweep should restore health");
                    return Future.<Void>succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void nullFutureFromCalculatorIsRecordedAndJobKeepsRunning(VertxTestContext testContext) {
        calculator.thenReturnNull();
        job = new WatermarkJob(vertx, calculator, "null-future-topic", LONG_INTERVAL_MS);

        assertDoesNotThrow(() -> job.start(),
                "A null Future from the calculator must be recorded as a sweep failure, not propagate");

        assertEquals(1L, job.getTotalFailures());
        assertEquals(1L, job.getTotalRunCount(), "A null-Future sweep still counts as a run");

        job.checkHealth()
                .compose(status -> {
                    assertTrue(status.isDegraded());
                    assertTrue(String.valueOf(status.getDetails().get("lastFailureMessage"))
                            .contains("null Future"), "details: " + status.getDetails());
                    return job.stopAsync();
                })
                .compose(v -> {
                    job.start();
                    return job.checkHealth();
                })
                .compose(status -> {
                    assertEquals(2L, job.getTotalRunCount(),
                            "The next run must execute, so the in-progress guard must have been released");
                    assertTrue(status.isHealthy(), "A successful sweep should restore health");
                    return Future.<Void>succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    /**
     * WARN and ERROR events for one topic. The job logs through a logger shared by every test in
     * the JVM, and peegeeq-db runs test methods and classes concurrently, so events are selected by
     * the topic named in the tracker's task description.
     */
    private List<ILoggingEvent> warnAndErrorEvents(String topic) {
        String taskDescription = "for topic " + topic + " ";
        return List.copyOf(appender.list).stream()
                .filter(event -> event.getLevel() == Level.WARN || event.getLevel() == Level.ERROR)
                .filter(event -> event.getFormattedMessage().contains(taskDescription))
                .toList();
    }

    private Future<Void> awaitCondition(BooleanSupplier condition, long deadlineMs) {
        if (condition.getAsBoolean()) {
            return Future.succeededFuture();
        }
        if (System.currentTimeMillis() >= deadlineMs) {
            return Future.failedFuture(new AssertionError("Condition was not met before deadline"));
        }
        return vertx.timer(10).compose(ignored -> awaitCondition(condition, deadlineMs));
    }

    /** One scripted outcome of a calculator call. */
    private interface Step {
        Future<Integer> run();
    }

    /**
     * Calculator whose outcomes are scripted per call. Calls beyond the script succeed with zero
     * swept messages.
     */
    private static final class ScriptedCalculator extends WatermarkCalculator {
        private final Queue<Step> script = new ConcurrentLinkedQueue<>();
        private final AtomicInteger calls = new AtomicInteger();

        private ScriptedCalculator(PgConnectionManager connectionManager) {
            super(connectionManager, "scripted-service");
        }

        void thenFail(Throwable failure) {
            script.add(() -> Future.failedFuture(failure));
        }

        void thenThrow(RuntimeException failure) {
            script.add(() -> {
                throw failure;
            });
        }

        void thenReturnNull() {
            script.add(() -> null);
        }

        int calls() {
            return calls.getAcquire();
        }

        @Override
        public Future<Integer> calculateAndSweep(String topic) {
            calls.incrementAndGet();
            Step next = script.poll();
            return next == null ? Future.succeededFuture(0) : next.run();
        }
    }
}
