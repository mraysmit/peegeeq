package dev.mars.peegeeq.failover;

import dev.mars.peegeeq.test.categories.TestCategories;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
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

    @Test void missingExecutableFails(Vertx vertx, VertxTestContext context) {
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
        long started = System.nanoTime();
        new LocalCommandRunner(vertx).run(List.of(JAVA, source.toString()), Duration.ofSeconds(3))
            .onComplete(context.failing(failure -> context.verify(() -> {
                long elapsed = System.nanoTime() - started;
                assertInstanceOf(PgProcessControlException.class, failure);
                assertTrue(elapsed >= TimeUnit.SECONDS.toNanos(3), "The command was not given its timeout");
                assertTrue(elapsed < TimeUnit.SECONDS.toNanos(15), "The timeout did not bound the command");
                assertTrue(ProcessHandle.current().children().noneMatch(child -> child.info().commandLine()
                    .filter(line -> line.contains("Block.java")).isPresent()), "The child process survived");
                context.completeNow();
            })));
    }
}
