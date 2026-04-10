package dev.mars.peegeeq.api.tracing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TraceCtx including child span creation for fan-out trace propagation.
 */
@Tag("core")
class TraceCtxTest {

    // --- createNew ---

    @Test
    void createNew_generatesValidTraceparent() {
        TraceCtx trace = TraceCtx.createNew();

        assertNotNull(trace.traceId());
        assertNotNull(trace.spanId());
        assertNotNull(trace.traceparent());
        assertEquals(32, trace.traceId().length());
        assertEquals(16, trace.spanId().length());
        assertNull(trace.parentSpanId(), "Root trace should have null parentSpanId");
        assertTrue(trace.traceparent().startsWith("00-"));
    }

    @Test
    void createNew_eachCallProducesUniqueIds() {
        TraceCtx t1 = TraceCtx.createNew();
        TraceCtx t2 = TraceCtx.createNew();

        assertNotEquals(t1.traceId(), t2.traceId());
        assertNotEquals(t1.spanId(), t2.spanId());
    }

    // --- parseOrCreate ---

    @Test
    void parseOrCreate_validTraceparent_preservesIds() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx trace = TraceCtx.parseOrCreate(traceparent);

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", trace.traceId());
        assertEquals("00f067aa0ba902b7", trace.spanId());
        assertEquals(traceparent, trace.traceparent());
        assertNull(trace.parentSpanId());
    }

    @Test
    void parseOrCreate_nullInput_createsNewRoot() {
        TraceCtx trace = TraceCtx.parseOrCreate(null);

        assertNotNull(trace.traceId());
        assertEquals(32, trace.traceId().length());
        assertNull(trace.parentSpanId());
    }

    @Test
    void parseOrCreate_emptyInput_createsNewRoot() {
        TraceCtx trace = TraceCtx.parseOrCreate("");

        assertNotNull(trace.traceId());
        assertEquals(32, trace.traceId().length());
    }

    @Test
    void parseOrCreate_invalidFormat_createsNewRoot() {
        TraceCtx trace = TraceCtx.parseOrCreate("garbage");

        assertNotNull(trace.traceId());
        assertEquals(32, trace.traceId().length());
    }

    // --- childSpan: fan-out trace propagation ---

    @Test
    void childSpan_preservesTraceId() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx parent = TraceCtx.parseOrCreate(traceparent);

        TraceCtx child = parent.childSpan("consumer-group:payments/process");

        assertEquals(parent.traceId(), child.traceId(),
                "Child span must preserve parent traceId for fan-out correlation");
    }

    @Test
    void childSpan_setsParentSpanId() {
        TraceCtx parent = TraceCtx.createNew();

        TraceCtx child = parent.childSpan("consumer-group:payments/process");

        assertEquals(parent.spanId(), child.parentSpanId(),
                "Child span parentSpanId must be the parent's spanId");
    }

    @Test
    void childSpan_generatesNewSpanId() {
        TraceCtx parent = TraceCtx.createNew();

        TraceCtx child = parent.childSpan("consumer-group:payments/process");

        assertNotEquals(parent.spanId(), child.spanId(),
                "Child span must have a different spanId from parent");
        assertEquals(16, child.spanId().length());
    }

    @Test
    void childSpan_generatesValidTraceparent() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + traceId + "-00f067aa0ba902b7-01";
        TraceCtx parent = TraceCtx.parseOrCreate(traceparent);

        TraceCtx child = parent.childSpan("consumer-group:payments/process");

        String expectedPrefix = "00-" + traceId + "-" + child.spanId() + "-01";
        assertEquals(expectedPrefix, child.traceparent(),
                "Child traceparent must use same traceId with new spanId");
    }

    @Test
    void childSpan_multipleChildren_produceDifferentSpanIds() {
        TraceCtx parent = TraceCtx.createNew();

        TraceCtx childA = parent.childSpan("consumer-group:groupA/process");
        TraceCtx childB = parent.childSpan("consumer-group:groupB/process");
        TraceCtx childC = parent.childSpan("consumer-group:groupC/process");

        // All children share parent traceId
        assertEquals(parent.traceId(), childA.traceId());
        assertEquals(parent.traceId(), childB.traceId());
        assertEquals(parent.traceId(), childC.traceId());

        // All children point to same parent
        assertEquals(parent.spanId(), childA.parentSpanId());
        assertEquals(parent.spanId(), childB.parentSpanId());
        assertEquals(parent.spanId(), childC.parentSpanId());

        // All children have unique spanIds (the branching)
        assertNotEquals(childA.spanId(), childB.spanId());
        assertNotEquals(childA.spanId(), childC.spanId());
        assertNotEquals(childB.spanId(), childC.spanId());
    }

    @Test
    void childSpan_chainedChildren_formSpanTree() {
        TraceCtx root = TraceCtx.createNew();
        TraceCtx fetch = root.childSpan("consumer-group:payments/fetch");
        TraceCtx process = fetch.childSpan("consumer-group:payments/process");

        // All share the same traceId
        assertEquals(root.traceId(), fetch.traceId());
        assertEquals(root.traceId(), process.traceId());

        // Parent chain: root → fetch → process
        assertEquals(root.spanId(), fetch.parentSpanId());
        assertEquals(fetch.spanId(), process.parentSpanId());

        // All spanIds are unique
        assertNotEquals(root.spanId(), fetch.spanId());
        assertNotEquals(fetch.spanId(), process.spanId());
        assertNotEquals(root.spanId(), process.spanId());
    }
}
