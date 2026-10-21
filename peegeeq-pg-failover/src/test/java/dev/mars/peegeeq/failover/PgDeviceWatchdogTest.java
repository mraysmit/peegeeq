package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The watchdog device adapter where no watchdog device exists, against a real directory.
 * Activation, keepalive, and magic close on a real device need a Linux host with a watchdog
 * driver and are not covered here.
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PgDeviceWatchdogTest {
    @TempDir Path directory;

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgDeviceWatchdog",
        message = "Watchdog operation failed: activate",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = NoSuchFileException.class)
    void activationFailsWhenTheDeviceIsAbsent(Vertx vertx, VertxTestContext context) throws IOException {
        var watchdog = new PgDeviceWatchdog(vertx, directory.resolve("watchdog"), timeoutFile("10"));
        watchdog.activate().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgWatchdogException.class, failure);
            assertInstanceOf(NoSuchFileException.class, failure.getCause());
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgDeviceWatchdog",
        message = "Watchdog operation failed: activate",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = NoSuchFileException.class)
    void activationFailsWhenTheTimeoutCannotBeRead(Vertx vertx, VertxTestContext context) {
        var watchdog = new PgDeviceWatchdog(vertx, directory.resolve("watchdog"), directory.resolve("timeout"));
        watchdog.activate().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgWatchdogException.class, failure);
            assertInstanceOf(NoSuchFileException.class, failure.getCause());
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgDeviceWatchdog",
        message = "Watchdog operation failed: activate",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = NumberFormatException.class)
    void aMalformedTimeoutIsRejectedBeforeTheDeviceIsOpened(Vertx vertx, VertxTestContext context)
            throws IOException {
        // The device is absent too. The failure names the timeout, so the device was not opened.
        var watchdog = new PgDeviceWatchdog(vertx, directory.resolve("watchdog"), timeoutFile("ten"));
        watchdog.activate().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgWatchdogException.class, failure);
            assertInstanceOf(NumberFormatException.class, failure.getCause());
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgDeviceWatchdog",
        message = "Watchdog operation failed: activate",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgWatchdogException.class)
    void aTimeoutThatIsNotPositiveIsRejected(Vertx vertx, VertxTestContext context) throws IOException {
        var watchdog = new PgDeviceWatchdog(vertx, directory.resolve("watchdog"), timeoutFile("0"));
        watchdog.activate().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgWatchdogException.class, failure);
            assertNull(failure.getCause());
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgDeviceWatchdog",
        message = "Watchdog operation failed: keepalive",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgWatchdogException.class)
    void keepaliveWithoutAnActiveWatchdogFails(Vertx vertx, VertxTestContext context) throws IOException {
        var watchdog = new PgDeviceWatchdog(vertx, directory.resolve("watchdog"), timeoutFile("10"));
        watchdog.keepalive().onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgWatchdogException.class, failure);
            context.completeNow();
        })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgDeviceWatchdog",
        message = "Watchdog operation failed: activate",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = NoSuchFileException.class)
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.PgDeviceWatchdog",
        message = "Watchdog operation failed: keepalive",
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = PgWatchdogException.class)
    void aFailedActivationLeavesTheWatchdogInactive(Vertx vertx, VertxTestContext context) throws IOException {
        var watchdog = new PgDeviceWatchdog(vertx, directory.resolve("watchdog"), timeoutFile("10"));
        watchdog.activate().transform(activation -> {
            assertTrue(activation.failed());
            return watchdog.keepalive();
        }).onComplete(context.failing(failure -> context.verify(() -> {
            assertInstanceOf(PgWatchdogException.class, failure);
            context.completeNow();
        })));
    }

    @Test void disarmWithoutAnActiveWatchdogSucceeds(Vertx vertx, VertxTestContext context) throws IOException {
        var watchdog = new PgDeviceWatchdog(vertx, directory.resolve("watchdog"), timeoutFile("10"));
        watchdog.disarm().onComplete(context.succeedingThenComplete());
    }

    private Path timeoutFile(String content) throws IOException {
        return Files.writeString(directory.resolve("timeout"), content + "\n");
    }
}
