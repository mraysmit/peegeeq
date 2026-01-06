package dev.mars.peegeeq.api.tracing;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.slf4j.MDC;
import java.util.Map;
import java.util.HashMap;

/**
 * Utility class for managing W3C Trace Context and SLF4J MDC (Mapped Diagnostic Context).
 * 
 * This class provides methods to:
 * - Parse W3C traceparent headers
 * - Set/clear MDC values for distributed tracing
 * - Propagate trace context across async boundaries
 * 
 * W3C Trace Context Format:
 * traceparent: 00-{trace-id}-{parent-id}-{trace-flags}
 * Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-24
 * @version 1.0
 */
public class TraceContextUtil {
    
    // MDC keys
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_TOPIC = "topic";
    public static final String MDC_MESSAGE_ID = "messageId";
    public static final String MDC_SETUP_ID = "setupId";
    public static final String MDC_QUEUE_NAME = "queueName";
    
    // Vert.x Context keys
    public static final String CONTEXT_TRACE_KEY = "trace.ctx";

    /**     * Captures the current trace context from the MDC.
     * @return A TraceCtx representing the current MDC state, or null if no trace information is present.
     */
    public static TraceCtx captureTraceContext() {
        String traceId = MDC.get(MDC_TRACE_ID);
        String spanId = MDC.get(MDC_SPAN_ID);
        // Only valid if we have both IDs
        if (traceId != null && spanId != null) {
            // Reconstruct a traceparent if not available in MDC (we don't store the raw string usually)
            // Assuming version 00 and recorded flag 01 for continuity
            String traceparent = String.format("00-%s-%s-01", traceId, spanId);
            return new TraceCtx(traceId, spanId, null, traceparent);
        }
        return null;
    }

    /**     * Parses a traceparent header or creates a new root TraceCtx if missing/invalid.
     * Delegates to TraceCtx.parseOrCreate.
     *
     * @param traceparent The W3C traceparent header value.
     * @return A valid TraceCtx (never null).
     */
    public static TraceCtx parseOrCreate(String traceparent) {
        return TraceCtx.parseOrCreate(traceparent);
    }

    /**
     * Applies the given TraceCtx to the SLF4J MDC and returns an AutoCloseable
     * that removes these keys when closed.
     *
     * Usage:
     * try (var scope = TraceContextUtil.mdcScope(trace)) {
     *     // ... work ...
     * }
     *
     * @param trace The TraceCtx to apply.
     * @return An AutoCloseable that cleans up the MDC choices.
     */
    public static MDCScope mdcScope(TraceCtx trace) {
        if (trace == null) {
            return new MDCScope(false);
        }
        MDC.put(MDC_TRACE_ID, trace.traceId());
        MDC.put(MDC_SPAN_ID, trace.spanId());
        return new MDCScope(true);
    }

    /**
     * Safe AutoCloseable for MDC cleanup.
     */
    public static class MDCScope implements AutoCloseable {
        private final boolean active;
        
        public MDCScope(boolean active) {
            this.active = active;
        }
        
        @Override
        public void close() {
            if (active) {
                MDC.remove(MDC_TRACE_ID);
                MDC.remove(MDC_SPAN_ID);
            }
        }
    }
    
    /**
     * Parses a W3C traceparent header and extracts trace ID and span ID.
     * 
     * Format: 00-{trace-id}-{parent-id}-{trace-flags}
     * 
     * @param traceparent The traceparent header value
     * @return TraceContext object with parsed values, or null if invalid
     */
    public static TraceContext parseTraceparent(String traceparent) {
        if (traceparent == null || traceparent.trim().isEmpty()) {
            return null;
        }
        
        // W3C Trace Context format: 00-{trace-id}-{parent-id}-{trace-flags}
        String[] parts = traceparent.split("-");
        if (parts.length < 4 || !parts[0].equals("00")) {
            return null; // Invalid format or unsupported version
        }
        
        String traceId = parts[1];  // 32 hex characters
        String spanId = parts[2];   // 16 hex characters
        String traceFlags = parts[3]; // 2 hex characters
        
        // Basic validation
        if (traceId.length() != 32 || spanId.length() != 16) {
            return null;
        }
        
        return new TraceContext(traceId, spanId, traceFlags);
    }
    
    /**
     * Sets MDC values from a traceparent header.
     * 
     * @param traceparent The W3C traceparent header
     * @return true if MDC was set, false if traceparent was invalid
     */
    public static boolean setMDCFromTraceparent(String traceparent) {
        TraceContext context = parseTraceparent(traceparent);
        if (context == null) {
            return false;
        }
        
        MDC.put(MDC_TRACE_ID, context.getTraceId());
        MDC.put(MDC_SPAN_ID, context.getSpanId());
        return true;
    }
    
    /**
     * Sets MDC values from message headers.
     * Extracts traceparent, correlationId, and other relevant fields.
     * 
     * @param headers Message headers map
     * @return true if trace context was found and set, false otherwise
     */
    public static boolean setMDCFromMessageHeaders(Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        
        boolean traceFound = false;
        // Extract and parse traceparent
        String traceparent = headers.get("traceparent");
        if (traceparent != null) {
            traceFound = setMDCFromTraceparent(traceparent);
        }
        
        // Set correlation ID if present
        String correlationId = headers.get("correlationId");
        if (correlationId != null) {
            MDC.put(MDC_CORRELATION_ID, correlationId);
        }
        
        return traceFound;
    }
    
    /**
     * Sets a single MDC value if the value is not null.
     * 
     * @param key MDC key
     * @param value MDC value (can be null)
     */
    public static void setMDC(String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            MDC.put(key, value);
        }
    }
    
    /**
     * Clears all trace-related MDC values.
     */
    public static void clearTraceMDC() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
        MDC.remove(MDC_CORRELATION_ID);
        MDC.remove(MDC_TOPIC);
        MDC.remove(MDC_MESSAGE_ID);
        MDC.remove(MDC_SETUP_ID);
        MDC.remove(MDC_QUEUE_NAME);
    }
    
    /**
     * Clears all MDC values.
     */
    public static void clearAllMDC() {
        MDC.clear();
    }
    
    /**
     * Gets current MDC context as a map (for propagation across async boundaries).
     * 
     * @return Map of current MDC values
     */
    public static Map<String, String> getMDCContext() {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return contextMap != null ? contextMap : new HashMap<>();
    }
    
    /**
     * Restores MDC context from a map (for async context propagation).
     * 
     * @param contextMap Map of MDC values to restore
     */
    public static void restoreMDCContext(Map<String, String> contextMap) {
        if (contextMap != null && !contextMap.isEmpty()) {
            MDC.setContextMap(contextMap);
        }
    }
}

