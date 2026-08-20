package dev.mars.peegeeq.db.health;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
class BackgroundTaskFailureTrackerTest {

    private ch.qos.logback.classic.Logger testLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        testLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                BackgroundTaskFailureTrackerTest.class.getName() + ".captured");
        appender = new ListAppender<>();
        appender.setContext(testLogger.getLoggerContext());
        appender.start();
        testLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        testLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void firstFailureKeepsStackAndPersistentSummariesAreRateLimited() {
        BackgroundTaskFailureTracker tracker = tracker();
        RuntimeException failure = new RuntimeException("database unavailable");

        for (int attempt = 0; attempt < 6; attempt++) {
            tracker.recordFailure(failure);
        }

        List<ILoggingEvent> failureEvents = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN || event.getLevel() == Level.ERROR)
                .toList();
        assertEquals(3, failureEvents.size(), "Only failures 1, 3, and 6 should be logged");

        ILoggingEvent first = failureEvents.get(0);
        assertEquals(Level.WARN, first.getLevel());
        assertTrue(first.getFormattedMessage().contains("first failure"));
        assertEquals(RuntimeException.class.getName(), first.getThrowableProxy().getClassName());

        ILoggingEvent thresholdSummary = failureEvents.get(1);
        assertEquals(Level.ERROR, thresholdSummary.getLevel());
        assertTrue(thresholdSummary.getFormattedMessage().contains("3 consecutive failures"));
        assertNull(thresholdSummary.getThrowableProxy(), "Persistent summaries must not repeat the stack trace");

        ILoggingEvent intervalSummary = failureEvents.get(2);
        assertEquals(Level.ERROR, intervalSummary.getLevel());
        assertTrue(intervalSummary.getFormattedMessage().contains("6 consecutive failures"));
        assertNull(intervalSummary.getThrowableProxy(), "Persistent summaries must remain stack-free");
    }

    @Test
    void healthEscalatesAndSuccessfulRunRestoresHealthyState() {
        BackgroundTaskFailureTracker tracker = tracker();

        assertTrue(tracker.currentStatus().isHealthy());

        tracker.recordFailure(new IllegalStateException("first"));
        HealthStatus degraded = tracker.currentStatus();
        assertTrue(degraded.isDegraded());
        assertEquals(1L, degraded.getDetails().get("consecutiveFailures"));
        assertEquals(IllegalStateException.class.getName(), degraded.getDetails().get("lastFailureType"));

        tracker.recordFailure(new IllegalStateException("second"));
        tracker.recordFailure(new IllegalStateException("third"));
        HealthStatus unhealthy = tracker.currentStatus();
        assertTrue(unhealthy.isUnhealthy());
        assertEquals(3L, unhealthy.getDetails().get("consecutiveFailures"));
        assertEquals(3L, unhealthy.getDetails().get("totalFailures"));

        tracker.recordSuccess();
        HealthStatus recovered = tracker.currentStatus();
        assertTrue(recovered.isHealthy());
        assertEquals(0L, recovered.getDetails().get("consecutiveFailures"));
        assertEquals(3L, recovered.getDetails().get("totalFailures"));
        assertFalse(recovered.getDetails().containsKey("lastFailureMessage"));
    }

    @Test
    void constructorRejectsInvalidPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> new BackgroundTaskFailureTracker("component", "task", testLogger, 1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new BackgroundTaskFailureTracker("component", "task", testLogger, 3, 0));
    }

    private BackgroundTaskFailureTracker tracker() {
        return new BackgroundTaskFailureTracker("background-test", "Test background task", testLogger);
    }
}
