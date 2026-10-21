package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Linux watchdog device adapter in plain Java. Opening the device arms it, any written byte
 * other than {@code V} is a keepalive, and writing {@code V} before closing disarms it (magic
 * close). The adapter does not set the timeout; the deployment does, for example with the
 * {@code softdog} {@code soft_margin} parameter. The adapter reads the actual timeout from the
 * device's sysfs {@code timeout} file before it opens the device.
 *
 * <p>A kernel built with {@code nowayout} ignores magic close: the device then stays armed after
 * {@link #disarm()} and resets the host unless it is fed. Every failure is logged at ERROR.
 */
public final class PgDeviceWatchdog implements PgWatchdog {
    @FunctionalInterface
    private interface DeviceAction<T> { T run() throws IOException; }
    private static final Logger logger = LoggerFactory.getLogger(PgDeviceWatchdog.class);
    private static final byte[] KEEPALIVE = {0};
    private static final byte[] MAGIC_CLOSE = {'V'};
    private final Vertx vertx;
    private final Path device;
    private final Path timeoutFile;
    private FileChannel channel;

    /**
     * @param device      the watchdog device, for example {@code /dev/watchdog}
     * @param timeoutFile the device's sysfs timeout, for example {@code /sys/class/watchdog/watchdog0/timeout}
     */
    public PgDeviceWatchdog(Vertx vertx, Path device, Path timeoutFile) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.device = Objects.requireNonNull(device, "device");
        this.timeoutFile = Objects.requireNonNull(timeoutFile, "timeoutFile");
    }

    @Override public Future<Duration> activate() {
        return blocking("activate", () -> {
            synchronized (this) {
                if (channel != null) throw new PgWatchdogException("The watchdog is already active");
                // Read first: a device whose timeout is unknown is never armed.
                Duration timeout = readTimeout();
                channel = FileChannel.open(device, StandardOpenOption.WRITE);
                return timeout;
            }
        });
    }

    @Override public Future<Void> keepalive() {
        return blocking("keepalive", () -> {
            synchronized (this) {
                if (channel == null) throw new PgWatchdogException("The watchdog is not active");
                write(KEEPALIVE);
                return null;
            }
        });
    }

    @Override public Future<Void> disarm() {
        return blocking("disarm", () -> {
            synchronized (this) {
                if (channel == null) return null;
                // A failed write leaves the device open, so the caller can still feed it.
                write(MAGIC_CLOSE);
                FileChannel open = channel;
                channel = null;
                open.close();
                return null;
            }
        });
    }

    private Duration readTimeout() throws IOException {
        String text = Files.readString(timeoutFile, StandardCharsets.US_ASCII).strip();
        long seconds;
        try {
            seconds = Long.parseLong(text);
        } catch (NumberFormatException malformed) {
            throw new PgWatchdogException("The watchdog timeout is not a number: " + text, malformed);
        }
        if (seconds <= 0) throw new PgWatchdogException("The watchdog timeout is not positive: " + seconds);
        return Duration.ofSeconds(seconds);
    }

    private void write(byte[] bytes) throws IOException {
        int written = channel.write(ByteBuffer.wrap(bytes));
        if (written != bytes.length) throw new PgWatchdogException("The watchdog accepted " + written + " bytes");
    }

    private <T> Future<T> blocking(String operation, DeviceAction<T> action) {
        Callable<T> call = () -> {
            try {
                return action.run();
            } catch (IOException failure) {
                throw new PgWatchdogException("Watchdog device I/O failed", failure);
            }
        };
        return vertx.executeBlocking(call).onFailure(failure ->
            logger.error("Watchdog operation failed: {}", operation, failure));
    }
}
