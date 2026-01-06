package dev.mars.peegeeq.api.tracing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
public class TraceContextUtilTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void testSetMDCFromMessageHeaders_WithValidTraceparent() {
        // Arrange
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String spanId = "00f067aa0ba902b7";
        String traceparent = "00-" + traceId + "-" + spanId + "-01";
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", traceparent);

        // Act
        boolean result = TraceContextUtil.setMDCFromMessageHeaders(headers);

        // Assert
        assertTrue(result, "Should return true when traceparent is present");
        assertEquals(traceId, MDC.get(TraceContextUtil.MDC_TRACE_ID));
        assertEquals(spanId, MDC.get(TraceContextUtil.MDC_SPAN_ID));
    }

    @Test
    void testSetMDCFromMessageHeaders_WithMissingTraceparent() {
        // Arrange
        Map<String, String> headers = new HashMap<>();
        headers.put("some-other-header", "value");

        // Act
        boolean result = TraceContextUtil.setMDCFromMessageHeaders(headers);

        // Assert
        assertFalse(result, "Should return false when traceparent is missing");
        assertNull(MDC.get(TraceContextUtil.MDC_TRACE_ID));
    }

    @Test
    void testCorrelationIdIsSetIndependently() {
        // Arrange
        Map<String, String> headers = new HashMap<>();
        headers.put("correlationId", "corr-123");

        // Act
        boolean result = TraceContextUtil.setMDCFromMessageHeaders(headers);

        // Assert
        assertFalse(result, "Should return false because traceparent is missing");
        assertEquals("corr-123", MDC.get(TraceContextUtil.MDC_CORRELATION_ID), "Correlation ID should still be set");
        assertNull(MDC.get(TraceContextUtil.MDC_TRACE_ID));
    }

    @Test
    void testCaptureTraceContext() {
        // Arrange
        String traceId = "12345678901234567890123456789012";
        String spanId = "1234567890123456";
        MDC.put(TraceContextUtil.MDC_TRACE_ID, traceId);
        MDC.put(TraceContextUtil.MDC_SPAN_ID, spanId);

        // Act
        TraceCtx ctx = TraceContextUtil.captureTraceContext();

        // Assert
        assertNotNull(ctx);
        assertEquals(traceId, ctx.traceId());
        assertEquals(spanId, ctx.spanId());
    }

    @Test
    void testCaptureTraceContext_ReturnsNullWhenEmpty() {
        // Act
        TraceCtx ctx = TraceContextUtil.captureTraceContext();

        // Assert
        assertNull(ctx, "Should return null when MDC is empty");
    }

    @Test
    void testCreateNewTraceCtx() {
        // Act
        TraceCtx ctx = TraceCtx.createNew();

        // Assert
        assertNotNull(ctx);
        assertNotNull(ctx.traceId());
        assertEquals(32, ctx.traceId().length());
        assertNotNull(ctx.spanId());
        assertEquals(16, ctx.spanId().length());
        assertNotNull(ctx.traceparent());
    }

    @Test
    void testMdcScope_ClearsOnNormalClose() {
        // Arrange
        TraceCtx ctx = TraceCtx.createNew();
        
        // Act - normal close via try-with-resources
        try (var scope = TraceContextUtil.mdcScope(ctx)) {
            // Verify MDC is set during scope
            assertEquals(ctx.traceId(), MDC.get(TraceContextUtil.MDC_TRACE_ID));
            assertEquals(ctx.spanId(), MDC.get(TraceContextUtil.MDC_SPAN_ID));
        }
        
        // Assert - MDC should be cleared after scope closes
        assertNull(MDC.get(TraceContextUtil.MDC_TRACE_ID), "traceId should be cleared after scope closes");
        assertNull(MDC.get(TraceContextUtil.MDC_SPAN_ID), "spanId should be cleared after scope closes");
    }

    @Test
    void testMdcScope_ClearsOnException() {
        // Arrange
        TraceCtx ctx = TraceCtx.createNew();
        RuntimeException testException = new RuntimeException("Test exception");
        
        // Act - exception thrown inside scope
        try {
            try (var scope = TraceContextUtil.mdcScope(ctx)) {
                // Verify MDC is set during scope
                assertEquals(ctx.traceId(), MDC.get(TraceContextUtil.MDC_TRACE_ID));
                assertEquals(ctx.spanId(), MDC.get(TraceContextUtil.MDC_SPAN_ID));
                
                // Throw exception to trigger close
                throw testException;
            }
        } catch (RuntimeException e) {
            // Expected
            assertSame(testException, e);
        }
        
        // Assert - MDC should still be cleared even after exception
        assertNull(MDC.get(TraceContextUtil.MDC_TRACE_ID), 
            "traceId should be cleared after scope closes even on exception");
        assertNull(MDC.get(TraceContextUtil.MDC_SPAN_ID), 
            "spanId should be cleared after scope closes even on exception");
    }

    @Test
    void testMdcScope_HandlesNullTraceCtx() {
        // Act - scope with null TraceCtx should not throw
        try (var scope = TraceContextUtil.mdcScope(null)) {
            // Should not set MDC
            assertNull(MDC.get(TraceContextUtil.MDC_TRACE_ID));
            assertNull(MDC.get(TraceContextUtil.MDC_SPAN_ID));
        }
        
        // Assert - should complete without error and MDC should remain unset
        assertNull(MDC.get(TraceContextUtil.MDC_TRACE_ID));
        assertNull(MDC.get(TraceContextUtil.MDC_SPAN_ID));
    }

    @Test
    void testChildSpan_PreservesTraceId() {
        // Arrange
        TraceCtx parent = TraceCtx.createNew();
        
        // Act
        TraceCtx child = parent.childSpan("test-operation");
        
        // Assert
        assertEquals(parent.traceId(), child.traceId(), "Child should have same traceId as parent");
        assertNotEquals(parent.spanId(), child.spanId(), "Child should have different spanId");
        assertEquals(parent.spanId(), child.parentSpanId(), "Child's parentSpanId should be parent's spanId");
        assertNotNull(child.traceparent());
        assertTrue(child.traceparent().contains(child.traceId()));
        assertTrue(child.traceparent().contains(child.spanId()));
    }
}
