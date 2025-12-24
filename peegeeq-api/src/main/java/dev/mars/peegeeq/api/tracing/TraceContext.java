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

/**
 * Represents a W3C Trace Context parsed from a traceparent header.
 * 
 * W3C Trace Context Format:
 * traceparent: 00-{trace-id}-{parent-id}-{trace-flags}
 * 
 * Example: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-12-24
 * @version 1.0
 */
public class TraceContext {
    
    private final String traceId;
    private final String spanId;
    private final String traceFlags;
    
    /**
     * Creates a new TraceContext.
     * 
     * @param traceId The trace ID (32 hex characters)
     * @param spanId The span/parent ID (16 hex characters)
     * @param traceFlags The trace flags (2 hex characters)
     */
    public TraceContext(String traceId, String spanId, String traceFlags) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.traceFlags = traceFlags;
    }
    
    /**
     * Gets the trace ID.
     * 
     * @return The trace ID (32 hex characters)
     */
    public String getTraceId() {
        return traceId;
    }
    
    /**
     * Gets the span/parent ID.
     * 
     * @return The span ID (16 hex characters)
     */
    public String getSpanId() {
        return spanId;
    }
    
    /**
     * Gets the trace flags.
     * 
     * @return The trace flags (2 hex characters)
     */
    public String getTraceFlags() {
        return traceFlags;
    }
    
    /**
     * Reconstructs the traceparent header value.
     * 
     * @return The traceparent header in W3C format
     */
    public String toTraceparent() {
        return String.format("00-%s-%s-%s", traceId, spanId, traceFlags);
    }
    
    @Override
    public String toString() {
        return String.format("TraceContext{traceId='%s', spanId='%s', traceFlags='%s'}", 
                traceId, spanId, traceFlags);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        TraceContext that = (TraceContext) o;
        
        if (!traceId.equals(that.traceId)) return false;
        if (!spanId.equals(that.spanId)) return false;
        return traceFlags.equals(that.traceFlags);
    }
    
    @Override
    public int hashCode() {
        int result = traceId.hashCode();
        result = 31 * result + spanId.hashCode();
        result = 31 * result + traceFlags.hashCode();
        return result;
    }
}

