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

import java.util.concurrent.ThreadLocalRandom;

/**
 * Immutable W3C Trace Context Record.
 * <p>
 * This record is the single source of truth for tracing information within the Vert.x Context.
 * It adheres to the W3C Trace Context specification.
 * </p>
 *
 * @param traceId The W3C trace-id (32 hex characters)
 * @param spanId The W3C parent-id / span-id (16 hex characters)
 * @param parentSpanId The parent span ID if this is a child span, or null for root/remote
 * @param traceparent The full W3C traceparent header string
 */
public record TraceCtx(String traceId, String spanId, String parentSpanId, String traceparent) {

    private static final String VERSION = "00";
    private static final String FLAGS = "01"; // Recorded

    /**
     * Creates a new root TraceCtx with a generated traceId and spanId.
     *
     * @return a new TraceCtx
     */
    public static TraceCtx createNew() {
        String traceId = generateHex(32);
        String spanId = generateHex(16);
        String traceparent = String.format("%s-%s-%s-%s", VERSION, traceId, spanId, FLAGS);
        return new TraceCtx(traceId, spanId, null, traceparent);
    }

    /**
     * Parses a W3C traceparent header or creates a new root if invalid.
     * @param traceparent The header value
     * @return TraceCtx
     */
    public static TraceCtx parseOrCreate(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return createNew();
        }
        String[] parts = traceparent.split("-");
        // 00-traceId-spanId-flags
        if (parts.length < 4 || !parts[0].equals("00")) {
             return createNew();
        }
        String traceId = parts[1];
        String spanId = parts[2];
        if (traceId.length() != 32 || spanId.length() != 16) {
             return createNew();
        }
        return new TraceCtx(traceId, spanId, null, traceparent);
    }
    
    /**
     * Creates a TraceCtx representing an incoming request/message.
     */
    public static TraceCtx createFromRemote(String traceId, String spanId, String traceparent) {
         return new TraceCtx(traceId, spanId, null, traceparent);
    }

    /**
     * Creates a child span from this context.
     * 
     * @param operationName Optional operation name (currently unused in ID generation but useful for API consistency)
     * @return a new TraceCtx with the same traceId, this spanId as parent, and a new spanId.
     */
    public TraceCtx childSpan(String operationName) {
        String newSpanId = generateHex(16);
        String newTraceparent = String.format("%s-%s-%s-%s", VERSION, traceId, newSpanId, FLAGS);
        return new TraceCtx(traceId, newSpanId, this.spanId, newTraceparent);
    }

    private static String generateHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(Integer.toHexString(random.nextInt(16)));
        }
        return sb.toString();
    }
}
