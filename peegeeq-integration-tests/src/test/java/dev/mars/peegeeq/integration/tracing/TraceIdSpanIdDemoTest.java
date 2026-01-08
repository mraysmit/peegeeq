package dev.mars.peegeeq.integration.tracing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dev.mars.peegeeq.api.tracing.AsyncTraceUtils;
import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating the difference between traceId and spanId.
 * 
 * Key concepts demonstrated:
 * - traceId remains CONSTANT across all operations for a single request
 * - spanId CHANGES when entering new units of work (child spans)
 * 
 * This test provides visible log output showing:
 * 1. Root span on event loop
 * 2. Child span on worker thread (different spanId, same traceId)
 * 3. Child span via Event Bus (different spanId, same traceId)
 * 4. Nested child span (different spanId, same traceId)
 */
@ExtendWith(VertxExtension.class)
@Tag("smoke")
@DisplayName("TraceId vs SpanId Demonstration")
public class TraceIdSpanIdDemoTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(TraceIdSpanIdDemoTest.class);
    
    private TraceCapturingAppender traceAppender;
    private Logger rootLogger;
    private WorkerExecutor workerExecutor;

    // Pattern to extract trace and span from log output
    private static final Pattern TRACE_SPAN_PATTERN = Pattern.compile("trace=([a-f0-9-]+).*span=([a-f0-9-]+)");

    @BeforeEach
    void setup(Vertx vertx) {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        
        traceAppender = new TraceCapturingAppender();
        traceAppender.setContext(lc);
        
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(lc);
        // Include both trace and span in captured output
        encoder.setPattern("[trace=%vxTrace span=%vxSpan] %msg");
        encoder.start();
        
        traceAppender.setEncoder(encoder);
        traceAppender.start();
        
        rootLogger.addAppender(traceAppender);
        
        workerExecutor = vertx.createSharedWorkerExecutor("span-demo-worker", 4);
    }

    @AfterEach
    void tearDown() {
        if (rootLogger != null && traceAppender != null) {
            rootLogger.detachAppender(traceAppender);
            traceAppender.stop();
        }
        if (workerExecutor != null) {
            workerExecutor.close();
        }
    }

    @Test
    @DisplayName("TraceId stays constant while SpanId changes across worker threads")
    void testTraceIdConstantSpanIdChangesAcrossWorkerThread(Vertx vertx, VertxTestContext testContext) {
        vertx.runOnContext(v -> {
            // Create root trace
            TraceCtx rootSpan = TraceCtx.createNew();
            String expectedTraceId = rootSpan.traceId();
            String rootSpanId = rootSpan.spanId();
            
            // Store in Vert.x Context
            Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);
            
            // Also set MDC for immediate logging
            try (var ignored = TraceContextUtil.mdcScope(rootSpan)) {
                log.info("STEP 1: Root span on event loop");
            }
            
            // Execute on worker thread - this creates a child span
            AsyncTraceUtils.executeBlockingTraced(vertx, workerExecutor, true, () -> {
                // Inside worker, MDC should have same traceId but different spanId
                String workerTraceId = MDC.get("traceId");
                String workerSpanId = MDC.get("spanId");
                
                log.info("STEP 2: Child span on worker thread");
                
                // Verify traceId is the same
                assertEquals(expectedTraceId, workerTraceId, 
                    "TraceId should remain constant on worker thread");
                
                // Verify spanId is different (child span)
                assertNotEquals(rootSpanId, workerSpanId, 
                    "SpanId should be different on worker thread (child span)");
                
                return "done";
            }).onComplete(ar -> {
                if (ar.succeeded()) {
                    // Back on event loop
                    try (var ignored = TraceContextUtil.mdcScope(rootSpan)) {
                        log.info("STEP 3: Back to root span on event loop");
                    }
                    
                    // Verify captured logs
                    vertx.setTimer(100, id -> {
                        try {
                            List<String> logs = traceAppender.getCapturedLogs();
                            
                            // Print logs for demonstration
                            System.out.println("\n========== TRACE ID vs SPAN ID DEMONSTRATION ==========");
                            System.out.println("Expected TraceId: " + expectedTraceId);
                            System.out.println("Root SpanId: " + rootSpanId);
                            System.out.println();
                            
                            String step1SpanId = null;
                            String step2SpanId = null;
                            String step3SpanId = null;
                            
                            for (String logLine : logs) {
                                if (logLine.contains("STEP")) {
                                    System.out.println(logLine);
                                    
                                    Matcher m = TRACE_SPAN_PATTERN.matcher(logLine);
                                    if (m.find()) {
                                        String traceId = m.group(1);
                                        String spanId = m.group(2);
                                        
                                        // All logs should have the same traceId
                                        assertEquals(expectedTraceId, traceId, 
                                            "TraceId must be constant across all log lines");
                                        
                                        if (logLine.contains("STEP 1")) step1SpanId = spanId;
                                        if (logLine.contains("STEP 2")) step2SpanId = spanId;
                                        if (logLine.contains("STEP 3")) step3SpanId = spanId;
                                    }
                                }
                            }
                            
                            System.out.println();
                            System.out.println("Analysis:");
                            System.out.println("  - STEP 1 (event loop): spanId = " + step1SpanId);
                            System.out.println("  - STEP 2 (worker):     spanId = " + step2SpanId + " <-- DIFFERENT (child span)");
                            System.out.println("  - STEP 3 (event loop): spanId = " + step3SpanId + " <-- SAME as STEP 1 (root span)");
                            System.out.println("  - TraceId: SAME across all steps ✓");
                            System.out.println("=======================================================\n");
                            
                            // Verify step 1 and step 3 have the same spanId (root span)
                            assertEquals(step1SpanId, step3SpanId, 
                                "Event loop spans should have same spanId");
                            
                            // Verify step 2 has different spanId (worker/child span)
                            assertNotEquals(step1SpanId, step2SpanId, 
                                "Worker span should have different spanId");
                            
                            testContext.completeNow();
                        } catch (Throwable t) {
                            testContext.failNow(t);
                        }
                    });
                } else {
                    testContext.failNow(ar.cause());
                }
            });
        });
    }

    @Test
    @DisplayName("TraceId stays constant while SpanId changes across Event Bus")
    void testTraceIdConstantSpanIdChangesAcrossEventBus(Vertx vertx, VertxTestContext testContext) {
        String eventBusAddress = "demo.trace.test." + System.currentTimeMillis();
        
        AtomicReference<String> consumerTraceId = new AtomicReference<>();
        AtomicReference<String> consumerSpanId = new AtomicReference<>();
        CountDownLatch consumerLatch = new CountDownLatch(1);
        
        // Register Event Bus consumer
        vertx.eventBus().consumer(eventBusAddress, msg -> {
            // Extract traceparent from message headers
            String traceparent = msg.headers().get("traceparent");
            TraceCtx consumerTrace = TraceContextUtil.parseOrCreate(traceparent);
            
            try (var ignored = TraceContextUtil.mdcScope(consumerTrace)) {
                log.info("STEP 2: Event Bus consumer received message");
                
                consumerTraceId.set(consumerTrace.traceId());
                consumerSpanId.set(consumerTrace.spanId());
            }
            
            msg.reply("acknowledged");
            consumerLatch.countDown();
        });
        
        vertx.runOnContext(v -> {
            // Create root trace
            TraceCtx rootSpan = TraceCtx.createNew();
            String expectedTraceId = rootSpan.traceId();
            String rootSpanId = rootSpan.spanId();
            
            Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);
            
            try (var ignored = TraceContextUtil.mdcScope(rootSpan)) {
                log.info("STEP 1: Publishing to Event Bus");
            }
            
            // Create child span for the Event Bus message
            TraceCtx childSpan = rootSpan.childSpan("eventbus-publish");
            
            // Publish with trace context in headers
            DeliveryOptions opts = new DeliveryOptions()
                .addHeader("traceparent", childSpan.traceparent());
            
            vertx.eventBus().request(eventBusAddress, "test-message", opts)
                .onComplete(ar -> {
                    try {
                        assertTrue(consumerLatch.await(5, TimeUnit.SECONDS), 
                            "Consumer should have received message");
                        
                        try (var ignored = TraceContextUtil.mdcScope(rootSpan)) {
                            log.info("STEP 3: Back in publisher after Event Bus round-trip");
                        }
                        
                        // Print demonstration
                        System.out.println("\n========== EVENT BUS TRACE PROPAGATION ==========");
                        System.out.println("Publisher TraceId: " + expectedTraceId);
                        System.out.println("Publisher SpanId:  " + rootSpanId);
                        System.out.println("Consumer TraceId:  " + consumerTraceId.get());
                        System.out.println("Consumer SpanId:   " + consumerSpanId.get());
                        System.out.println();
                        System.out.println("Analysis:");
                        System.out.println("  - TraceId: " + 
                            (expectedTraceId.equals(consumerTraceId.get()) ? "SAME ✓" : "DIFFERENT ✗"));
                        System.out.println("  - SpanId:  " + 
                            (rootSpanId.equals(consumerSpanId.get()) ? "SAME" : "DIFFERENT ✓ (child span)"));
                        System.out.println("=================================================\n");
                        
                        // Verify traceId is propagated
                        assertEquals(expectedTraceId, consumerTraceId.get(), 
                            "TraceId should be propagated across Event Bus");
                        
                        // Verify spanId is different (child span)
                        assertNotEquals(rootSpanId, consumerSpanId.get(), 
                            "SpanId should be different (child span via Event Bus)");
                        
                        testContext.completeNow();
                    } catch (Throwable t) {
                        testContext.failNow(t);
                    }
                });
        });
    }

    @Test
    @DisplayName("Nested child spans maintain same traceId with unique spanIds")
    void testNestedChildSpans(Vertx vertx, VertxTestContext testContext) {
        vertx.runOnContext(v -> {
            // Create root trace
            TraceCtx rootSpan = TraceCtx.createNew();
            String traceId = rootSpan.traceId();
            
            Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);
            
            // Create hierarchy: root -> child1 -> child2 -> child3
            TraceCtx child1 = rootSpan.childSpan("child1");
            TraceCtx child2 = child1.childSpan("child2");
            TraceCtx child3 = child2.childSpan("child3");
            
            try (var ignored = TraceContextUtil.mdcScope(rootSpan)) {
                log.info("ROOT SPAN");
            }
            try (var ignored = TraceContextUtil.mdcScope(child1)) {
                log.info("CHILD 1 (parent: root)");
            }
            try (var ignored = TraceContextUtil.mdcScope(child2)) {
                log.info("CHILD 2 (parent: child1)");
            }
            try (var ignored = TraceContextUtil.mdcScope(child3)) {
                log.info("CHILD 3 (parent: child2)");
            }
            
            // Print demonstration
            System.out.println("\n========== NESTED SPAN HIERARCHY ==========");
            System.out.println("TraceId (same for all): " + traceId);
            System.out.println();
            System.out.println("Span Hierarchy:");
            System.out.println("  ROOT:    spanId=" + rootSpan.spanId() + " parentSpanId=" + rootSpan.parentSpanId());
            System.out.println("  CHILD1:  spanId=" + child1.spanId() + " parentSpanId=" + child1.parentSpanId());
            System.out.println("  CHILD2:  spanId=" + child2.spanId() + " parentSpanId=" + child2.parentSpanId());
            System.out.println("  CHILD3:  spanId=" + child3.spanId() + " parentSpanId=" + child3.parentSpanId());
            System.out.println();
            System.out.println("Parent-Child Verification:");
            System.out.println("  child1.parentSpanId == root.spanId:   " + 
                (child1.parentSpanId() != null && child1.parentSpanId().equals(rootSpan.spanId()) ? "✓" : "✗"));
            System.out.println("  child2.parentSpanId == child1.spanId: " + 
                (child2.parentSpanId() != null && child2.parentSpanId().equals(child1.spanId()) ? "✓" : "✗"));
            System.out.println("  child3.parentSpanId == child2.spanId: " + 
                (child3.parentSpanId() != null && child3.parentSpanId().equals(child2.spanId()) ? "✓" : "✗"));
            System.out.println("============================================\n");
            
            // All should have the same traceId
            assertEquals(traceId, rootSpan.traceId());
            assertEquals(traceId, child1.traceId());
            assertEquals(traceId, child2.traceId());
            assertEquals(traceId, child3.traceId());
            
            // All should have unique spanIds
            List<String> spanIds = List.of(
                rootSpan.spanId(), 
                child1.spanId(), 
                child2.spanId(), 
                child3.spanId()
            );
            assertEquals(4, spanIds.stream().distinct().count(), 
                "All spans should have unique spanIds");
            
            // Verify parent-child relationships
            assertEquals(rootSpan.spanId(), child1.parentSpanId(), 
                "child1's parent should be root");
            assertEquals(child1.spanId(), child2.parentSpanId(), 
                "child2's parent should be child1");
            assertEquals(child2.spanId(), child3.parentSpanId(), 
                "child3's parent should be child2");
            
            testContext.completeNow();
        });
    }

    /**
     * Custom Logback appender to capture log output for verification.
     */
    static class TraceCapturingAppender extends AppenderBase<ILoggingEvent> {
        private PatternLayoutEncoder encoder;
        private final List<String> capturedLogs = Collections.synchronizedList(new ArrayList<>());
        
        public void setEncoder(PatternLayoutEncoder encoder) {
            this.encoder = encoder;
        }

        @Override
        protected void append(ILoggingEvent eventObject) {
            byte[] encoded = encoder.encode(eventObject);
            capturedLogs.add(new String(encoded).trim());
        }
        
        public List<String> getCapturedLogs() {
            return new ArrayList<>(capturedLogs);
        }
    }
}
