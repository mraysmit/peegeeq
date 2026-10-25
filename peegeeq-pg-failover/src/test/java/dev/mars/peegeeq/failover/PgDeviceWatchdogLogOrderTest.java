package dev.mars.peegeeq.failover;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A failed watchdog operation is logged before the caller sees the failure. A test that declares the
 * ERROR it expects closes its scope when the caller's handler completes the test, so a log line that
 * follows the caller's handler is attributed to no test. Vert.x gives no order between two listeners
 * of one future, so the logging must be part of the returned future, not a second listener of it.
 *
 * <p>The class is isolated and repeated: the appender sees every watchdog event of the JVM, and the
 * race it guards against shows only in a fraction of the runs.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class PgDeviceWatchdogLogOrderTest {
    @TempDir Path directory;
    private ListAppender<ILoggingEvent> appender;
    private Logger watchdogLogger;

    @BeforeEach void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        watchdogLogger = (Logger) LoggerFactory.getLogger(PgDeviceWatchdog.class);
        watchdogLogger.addAppender(appender);
    }

    @AfterEach void detachAppender() {
        watchdogLogger.detachAppender(appender);
        appender.stop();
    }

    @RepeatedTest(300)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgDeviceWatchdog",
        message = "Watchdog operation failed: keepalive",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgWatchdogException.class)
    void theFailureIsLoggedBeforeTheCallerSeesIt(Vertx vertx, VertxTestContext context) throws IOException {
        Path timeout = directory.resolve("timeout");
        Files.writeString(timeout, "10");
        var watchdog = new PgDeviceWatchdog(vertx, directory.resolve("watchdog"), timeout);
        watchdog.keepalive().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgWatchdogException.class, failure);
            assertEquals(1, appender.list.size(), "The caller saw the failure before it was logged");
            context.completeNow();
        })));
    }
}
