package dev.mars.peegeeq.api.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;

public class VertxTraceIdConverter extends ClassicConverter {

    /**
     * Indicator shown when no trace context exists.
     * Using "-" is concise and clearly indicates "no trace" without being verbose.
     * This distinguishes intentional "no trace" scenarios (background jobs, startup)
     * from logging bugs where trace propagation failed.
     */
    private static final String NO_TRACE_INDICATOR = "-";

    @Override
    public String convert(ILoggingEvent event) {
        // 1. Try MDC first (fastest, and standard for blocking code)
        String mdcTraceId = event.getMDCPropertyMap().get("traceId");
        if (mdcTraceId != null && !mdcTraceId.isEmpty()) {
            return mdcTraceId;
        }

        // 2. Try Vert.x Context (if we are on a Vert.x thread)
        // Accessing static Vertx.currentContext() is safe here as logback
        // appenders run in the same thread that logged the event.
        Context ctx = Vertx.currentContext();
        if (ctx != null) {
            // Check for robust TraceCtx object
            Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
            if (traceObj instanceof TraceCtx) {
                 return ((TraceCtx) traceObj).traceId();
            }
            // Fallback: Check if it's stored as a plain string under "traceId"
            Object simpleTrace = ctx.get("traceId");
            if (simpleTrace != null) {
                return simpleTrace.toString();
            }
        }
        
        // Return indicator instead of empty string for clarity
        return NO_TRACE_INDICATOR;
    }
}
