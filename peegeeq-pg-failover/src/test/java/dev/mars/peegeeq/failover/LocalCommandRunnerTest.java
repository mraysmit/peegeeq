package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** The production command runner against real child processes of this JVM's own launcher. */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LocalCommandRunnerTest {
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    @TempDir Path directory;

    @Test void successfulCommandReturnsExitZeroAndItsOutput(Vertx vertx, VertxTestContext context) {
        new LocalCommandRunner(vertx).run(List.of(JAVA, "-version"), Duration.ofSeconds(30))
            .onSuccess(result -> context.verify(() -> {
                assertEquals(0, result.exitCode());
                assertTrue(result.output().toLowerCase().contains("version"), result.output());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test void nonZeroExitIsAResultNotAFailure(Vertx vertx, VertxTestContext context) {
        new LocalCommandRunner(vertx).run(List.of(JAVA, "--no-such-option"), Duration.ofSeconds(30))
            .onSuccess(result -> context.verify(() -> {
                assertNotEquals(0, result.exitCode());
                assertFalse(result.output().isBlank());
                context.completeNow();
            })).onFailure(context::failNow);
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.LocalCommandRunner",
        message = "Command could not run: ",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = IOException.class)
    void missingExecutableFails(Vertx vertx, VertxTestContext context) {
        new LocalCommandRunner(vertx).run(List.of(directory.resolve("absent-binary").toString()), Duration.ofSeconds(5))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgProcessControlException.class, failure);
                context.completeNow();
            })));
    }

    @Test void emptyCommandFails(Vertx vertx, VertxTestContext context) {
        new LocalCommandRunner(vertx).run(List.of(), Duration.ofSeconds(5))
            .onComplete(context.failing(failure -> context.verify(() -> {
                assertInstanceOf(PgProcessControlException.class, failure);
                context.completeNow();
            })));
    }

    @Test void commandThatDoesNotExitFailsAtItsTimeoutAndIsKilled(Vertx vertx, VertxTestContext context)
            throws IOException {
        // The child blocks reading standard input, which the runner leaves open and unwritten.
        Path source = Files.writeString(directory.resolve("Block.java"),
            "class Block { public static void main(String[] a) throws Exception { System.in.read(); } }");
        Set<Long> before = childProcesses();
        long started = System.nanoTime();
        new LocalCommandRunner(vertx).run(List.of(JAVA, source.toString()), Duration.ofSeconds(3))
            .onComplete(context.failing(failure -> context.verify(() -> {
                long elapsed = System.nanoTime() - started;
                assertInstanceOf(PgProcessControlException.class, failure);
                assertTrue(elapsed >= TimeUnit.SECONDS.toNanos(3), "The command was not given its timeout");
                assertTrue(elapsed < TimeUnit.SECONDS.toNanos(15), "The timeout did not bound the command");
                // Process identifiers, not command lines: Windows reports no command line for a child.
                Set<Long> survivors = childProcesses();
                survivors.removeAll(before);
                assertTrue(survivors.isEmpty(), "The child process survived: " + survivors);
                context.completeNow();
            })));
    }

    @Test
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.failover.LocalCommandRunner",
        message = "Interrupted while waiting for command: ",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
        throwableType = InterruptedException.class)
    void interruptedWaitFailsAndKillsTheCommand(Vertx vertx, VertxTestContext context) throws IOException {
        Path source = Files.writeString(directory.resolve("Block.java"),
            "class Block { public static void main(String[] a) throws Exception { System.in.read(); } }");
        Set<Long> before = childProcesses();
        Future<PgCommandResult> command = new LocalCommandRunner(vertx)
            .run(List.of(JAVA, source.toString()), Duration.ofSeconds(40));
        interruptWaitingWorker(vertx, System.nanoTime() + TimeUnit.SECONDS.toNanos(20))
            .compose(ignored -> command.transform(outcome -> {
                assertTrue(outcome.failed(), "An interrupted command was reported as a result");
                assertInstanceOf(PgProcessControlException.class, outcome.cause());
                assertInstanceOf(InterruptedException.class, outcome.cause().getCause());
                // The runner waits for the kill, so the child is gone when the failure is reported.
                Set<Long> survivors = childProcesses();
                survivors.removeAll(before);
                assertTrue(survivors.isEmpty(), "The child process survived: " + survivors);
                return Future.<Void>succeededFuture();
            })).onSuccess(ignored -> context.completeNow()).onFailure(context::failNow);
    }

    private static Set<Long> childProcesses() {
        return ProcessHandle.current().children().map(ProcessHandle::pid)
            .collect(Collectors.toCollection(HashSet::new));
    }

    /** Interrupts the worker thread that waits for the child process, and fails on the deadline. */
    private static Future<Void> interruptWaitingWorker(Vertx vertx, long deadline) {
        for (Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces().entrySet()) {
            boolean inRunner = Arrays.stream(thread.getValue()).anyMatch(frame ->
                frame.getClassName().equals(LocalCommandRunner.class.getName())
                    && frame.getMethodName().equals("execute"));
            boolean waiting = Arrays.stream(thread.getValue())
                .anyMatch(frame -> frame.getMethodName().equals("waitFor"));
            if (inRunner && waiting) {
                thread.getKey().interrupt();
                return Future.succeededFuture();
            }
        }
        if (System.nanoTime() >= deadline) {
            return Future.failedFuture(new AssertionError("No worker thread waited for the command"));
        }
        return vertx.timer(50).compose(ignored -> interruptWaitingWorker(vertx, deadline));
    }
}
