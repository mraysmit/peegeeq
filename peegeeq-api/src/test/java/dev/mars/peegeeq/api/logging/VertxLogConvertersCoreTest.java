package dev.mars.peegeeq.api.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.status.Status;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding 19: the trace and span converters must not hide a failed read of the Vert.x context.
 *
 * <p>Before the correction an empty {@code catch (Throwable)} printed the "no trace" indicator
 * {@code -} for a failed read, so a propagation defect looked like a line with no trace. The
 * converters now report the failure on Logback's status channel and print {@code error}. The log
 * line itself is kept: an exception that leaves a converter makes the appender drop the line.</p>
 */
@Tag("core")
@ExtendWith(VertxExtension.class)
class VertxLogConvertersCoreTest {

    /** A context value whose rendering fails, as a defective trace propagation would store. */
    private static final Object UNRENDERABLE = new Object() {
        @Override
        public String toString() {
            throw new IllegalStateException("unrenderable id");
        }
    };

    @Test
    void spanConverterReportsAFailedContextRead(Vertx vertx, VertxTestContext testContext) {
        assertFailedReadIsReported(vertx, testContext, new VertxSpanIdConverter(), "spanId");
    }

    @Test
    void traceConverterReportsAFailedContextRead(Vertx vertx, VertxTestContext testContext) {
        assertFailedReadIsReported(vertx, testContext, new VertxTraceIdConverter(), "traceId");
    }

    @Test
    void spanConverterPrintsTheContextValue(Vertx vertx, VertxTestContext testContext) {
        assertContextValueIsPrinted(vertx, testContext, new VertxSpanIdConverter(), "spanId");
    }

    @Test
    void traceConverterPrintsTheContextValue(Vertx vertx, VertxTestContext testContext) {
        assertContextValueIsPrinted(vertx, testContext, new VertxTraceIdConverter(), "traceId");
    }

    @Test
    void convertersPrintTheNoTraceIndicatorOffAVertxThreadAndReportNothing() {
        LoggerContext logbackContext = new LoggerContext();
        ClassicConverter span = started(new VertxSpanIdConverter(), logbackContext);
        ClassicConverter trace = started(new VertxTraceIdConverter(), logbackContext);

        assertEquals("-", span.convert(eventWithEmptyMdc()));
        assertEquals("-", trace.convert(eventWithEmptyMdc()));
        assertEquals(List.of(), errors(logbackContext), "no failure happened, so none may be reported");
    }

    private void assertFailedReadIsReported(Vertx vertx, VertxTestContext testContext,
                                            ClassicConverter converter, String key) {
        LoggerContext logbackContext = new LoggerContext();
        started(converter, logbackContext);
        vertx.runOnContext(ignored -> {
            vertx.getOrCreateContext().put(key, UNRENDERABLE);
            String rendered = converter.convert(eventWithEmptyMdc());
            testContext.verify(() -> {
                assertEquals("error", rendered,
                        "a failed read must not print the no-trace indicator '-'");
                List<Status> reported = errors(logbackContext);
                assertEquals(1, reported.size(), "the failure must be reported once: " + reported);
                assertInstanceOf(IllegalStateException.class, reported.get(0).getThrowable(),
                        "the report must carry the cause");
            });
            testContext.completeNow();
        });
    }

    private void assertContextValueIsPrinted(Vertx vertx, VertxTestContext testContext,
                                             ClassicConverter converter, String key) {
        LoggerContext logbackContext = new LoggerContext();
        started(converter, logbackContext);
        vertx.runOnContext(ignored -> {
            vertx.getOrCreateContext().put(key, "id-from-context");
            String rendered = converter.convert(eventWithEmptyMdc());
            testContext.verify(() -> {
                assertEquals("id-from-context", rendered);
                assertEquals(List.of(), errors(logbackContext));
            });
            testContext.completeNow();
        });
    }

    private static ClassicConverter started(ClassicConverter converter, LoggerContext logbackContext) {
        converter.setContext(logbackContext);
        converter.start();
        return converter;
    }

    private static LoggingEvent eventWithEmptyMdc() {
        LoggingEvent event = new LoggingEvent();
        event.setMDCPropertyMap(Map.of());
        return event;
    }

    private static List<Status> errors(LoggerContext logbackContext) {
        return logbackContext.getStatusManager().getCopyOfStatusList().stream()
                .filter(status -> status.getLevel() == Status.ERROR)
                .toList();
    }
}
