package dev.mars.peegeeq.api.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;

/**
 * Logback converter for retrieving span ID from MDC or Vert.x Context.
 * 
 * Register in logback.xml:
 * <pre>
 * &lt;conversionRule conversionWord="vxSpan" 
 *                 converterClass="dev.mars.peegeeq.api.logging.VertxSpanIdConverter" /&gt;
 * </pre>
 * 
 * Then use %vxSpan in patterns:
 * <pre>
 * &lt;pattern&gt;[trace=%vxTrace span=%vxSpan] %msg%n&lt;/pattern&gt;
 * </pre>
 */
public class VertxSpanIdConverter extends ClassicConverter {

    /**
     * Indicator shown when no span context exists.
     */
    private static final String NO_SPAN_INDICATOR = "-";

    /**
     * Indicator shown when the span could not be read. It differs from {@link #NO_SPAN_INDICATOR}
     * so that a failed read is not mistaken for a line that has no span.
     */
    static final String READ_FAILED_INDICATOR = "error";

    @Override
    public String convert(ILoggingEvent event) {
        // 1. Try MDC first (fastest, and standard for blocking code)
        String mdcSpanId = event.getMDCPropertyMap().get("spanId");
        if (mdcSpanId != null && !mdcSpanId.isEmpty()) {
            return mdcSpanId;
        }

        // 2. Try Vert.x Context (if we are on a Vert.x thread)
        // Accessing static Vertx.currentContext() can trigger Vert.x static initialization.
        // If triggered from a concurrent non-Vertx thread (like ForkJoinPool during tests)
        // while the logging framework is initializing, it can cause ExceptionInInitializerError.
        // Therefore, we only query Vertx context if we are on a Vertx thread.
        Thread currentThread = Thread.currentThread();
        if (currentThread.getName().startsWith("vert.x-")) {
            try {
                Context ctx = Vertx.currentContext();
                if (ctx != null) {
                    // Check for robust TraceCtx object
                    Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
                    if (traceObj instanceof TraceCtx) {
                         return ((TraceCtx) traceObj).spanId();
                    }
                    // Fallback: Check if it's stored as a plain string under "spanId"
                    Object simpleSpan = ctx.get("spanId");
                    if (simpleSpan != null) {
                        return simpleSpan.toString();
                    }
                }
            } catch (Throwable t) {
                // A converter must not throw: the appender would drop the whole line. It must not
                // log either: that would re-enter the layout. Logback's status channel is the
                // reporting path for converter failures.
                addError("Could not read the span id from the Vert.x context", t);
                return READ_FAILED_INDICATOR;
            }
        }
        
        // Return indicator instead of empty string for clarity
        return NO_SPAN_INDICATOR;
    }
}
