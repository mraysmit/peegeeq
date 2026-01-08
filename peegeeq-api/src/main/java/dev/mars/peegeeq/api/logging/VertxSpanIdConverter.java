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

    @Override
    public String convert(ILoggingEvent event) {
        // 1. Try MDC first (fastest, and standard for blocking code)
        String mdcSpanId = event.getMDCPropertyMap().get("spanId");
        if (mdcSpanId != null && !mdcSpanId.isEmpty()) {
            return mdcSpanId;
        }

        // 2. Try Vert.x Context (if we are on a Vert.x thread)
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
        
        // Return indicator instead of empty string for clarity
        return NO_SPAN_INDICATOR;
    }
}
