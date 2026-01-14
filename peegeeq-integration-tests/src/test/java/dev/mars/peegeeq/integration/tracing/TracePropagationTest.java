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
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ExtendWith(VertxExtension.class)
@Tag("tracing-verification")
public class TracePropagationTest {

    private TraceCapturingAppender traceAppender;
    private Logger rootLogger;
    private WorkerExecutor workerExecutor;

    @BeforeEach
    void setup(Vertx vertx) {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        
        // Ensure our converter is registered in the context if not already (it should be via logback-test.xml)
        // But to be self-contained within the test environment regarding formatting, let's explicit configure the encoder.
        
        traceAppender = new TraceCapturingAppender();
        traceAppender.setContext(lc);
        
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(lc);
        // Use the pattern that relies on the converter we configured in logback-test.xml
        // OR explicitely use the converter rule. 
        // Since logback-test.xml globally registers 'vxTrace', we can use it.
        encoder.setPattern("%msg [captured-trace=%vxTrace]");
        encoder.start();
        
        traceAppender.setEncoder(encoder);
        traceAppender.start();
        
        rootLogger.addAppender(traceAppender);
        
        workerExecutor = vertx.createSharedWorkerExecutor("trace-test-worker");
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
    void testAsyncTracePropagation(Vertx vertx, VertxTestContext testContext) {
        vertx.runOnContext(v -> {
            // 1. Create a trace context
            TraceCtx rootSpan = TraceCtx.createNew();
            String expectedTraceId = rootSpan.traceId();

            // 2. Put it in the Vert.x Context (Current context)
            Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);
            
            Logger log = (Logger) LoggerFactory.getLogger(TracePropagationTest.class);

            // 3. Log on EventLoop (Phase 1)
            log.info("Phase 1: EventLoop Start");

            // 4. Exec Blocking
            AsyncTraceUtils.executeBlockingTraced(vertx, workerExecutor, true, () -> {
                // 5. Log on Worker (Phase 2)
                log.info("Phase 2: Worker Thread");
                
                // Verify MDC inside worker manually (just to be sure JVM logic is fine)
                String mdcTrace = org.slf4j.MDC.get("traceId");
                if (mdcTrace == null) {
                    throw new AssertionError("MDC is null in worker!");
                }
                if (!expectedTraceId.equals(mdcTrace)) {
                    throw new AssertionError("MDC mismatch in worker! Expected: " + expectedTraceId + ", Found: " + mdcTrace);
                }
                return "done";
            }).onComplete(ar -> {
                if (ar.succeeded()) {
                    // 6. Log on EventLoop (Phase 3)
                    log.info("Phase 3: EventLoop End");
                    
                    // Assertions
                    vertx.setTimer(100, id -> { // Give appender a moment to flush if async (it's not, but safety)
                        try {
                            List<String> logs = traceAppender.getCapturedLogs();
                            
                            boolean foundPhase1 = false;
                            boolean foundPhase2 = false;
                            boolean foundPhase3 = false;
                            
                            for (String line : logs) {
                                if (line.contains("Phase 1") && line.contains("captured-trace=" + expectedTraceId)) foundPhase1 = true;
                                if (line.contains("Phase 2") && line.contains("captured-trace=" + expectedTraceId)) foundPhase2 = true;
                                if (line.contains("Phase 3") && line.contains("captured-trace=" + expectedTraceId)) foundPhase3 = true;
                            }
                            
                            if (!foundPhase1) System.err.println("FAILED: Phase 1 trace missing. Logs: " + logs);
                            if (!foundPhase2) System.err.println("FAILED: Phase 2 trace missing. Logs: " + logs);
                            if (!foundPhase3) System.err.println("FAILED: Phase 3 trace missing. Logs: " + logs);
                            
                            if (foundPhase1 && foundPhase2 && foundPhase3) {
                                testContext.completeNow();
                            } else {
                                testContext.failNow(new AssertionError("Trace ID propagation failed. See stderr."));
                            }
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
    
    /**
     * Tests that 100 parallel traces don't bleed into each other.
     * This verifies that MDC context isolation works correctly under concurrent load.
     * 
     * Each parallel operation must see only its own traceId - if any operation
     * sees another operation's traceId, the test fails.
     */
    @Test
    void testConcurrencyIsolation(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        final int PARALLEL_TRACES = 100;
        
        // Map to track: traceId -> list of observed traceIds in its worker
        java.util.concurrent.ConcurrentHashMap<String, List<String>> observedTraces = 
            new java.util.concurrent.ConcurrentHashMap<>();
        
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(PARALLEL_TRACES);
        java.util.concurrent.atomic.AtomicReference<Throwable> firstError = new java.util.concurrent.atomic.AtomicReference<>();
        
        Logger log = (Logger) LoggerFactory.getLogger(TracePropagationTest.class);
        
        for (int i = 0; i < PARALLEL_TRACES; i++) {
            final int index = i;
            
            vertx.runOnContext(v -> {
                // Create unique trace for this parallel operation
                TraceCtx rootSpan = TraceCtx.createNew();
                String expectedTraceId = rootSpan.traceId();
                observedTraces.put(expectedTraceId, Collections.synchronizedList(new ArrayList<>()));
                
                // Store in Vert.x Context
                Vertx.currentContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, rootSpan);
                
                // Execute blocking operation that captures its observed traceId
                AsyncTraceUtils.executeBlockingTraced(vertx, workerExecutor, false, () -> {
                    // Capture what MDC says our traceId is
                    String observedMdcTrace = org.slf4j.MDC.get("traceId");
                    
                    // Record observation
                    if (observedMdcTrace != null) {
                        List<String> observations = observedTraces.get(expectedTraceId);
                        if (observations != null) {
                            observations.add(observedMdcTrace);
                        }
                    }
                    
                    // Verify isolation - MDC must match expected
                    if (!expectedTraceId.equals(observedMdcTrace)) {
                        String error = String.format(
                            "BLEED DETECTED! Operation %d expected traceId=%s but found %s",
                            index, expectedTraceId, observedMdcTrace
                        );
                        firstError.compareAndSet(null, new AssertionError(error));
                    }
                    
                    // Simulate some work to increase chance of race conditions
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    return "done-" + index;
                }).onComplete(ar -> {
                    if (ar.failed() && firstError.get() == null) {
                        firstError.compareAndSet(null, ar.cause());
                    }
                    latch.countDown();
                });
            });
        }
        
        // Wait for all parallel operations to complete
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        
        if (!completed) {
            testContext.failNow(new AssertionError("Timeout waiting for parallel traces to complete"));
            return;
        }
        
        Throwable error = firstError.get();
        if (error != null) {
            log.error("Concurrency isolation test FAILED: {}", error.getMessage());
            testContext.failNow(error);
        } else {
            log.info("Concurrency isolation test PASSED: {} parallel traces with no bleed detected", PARALLEL_TRACES);
            testContext.completeNow();
        }
    }

    static class TraceCapturingAppender extends AppenderBase<ILoggingEvent> {
        private PatternLayoutEncoder encoder;
        private final List<String> capturedLogs = Collections.synchronizedList(new ArrayList<>());
        
        public void setEncoder(PatternLayoutEncoder encoder) {
            this.encoder = encoder;
        }

        @Override
        protected void append(ILoggingEvent eventObject) {
            // Apply layout immediately to capture the state of thread/context at logging time
            byte[] encoded = encoder.encode(eventObject);
            capturedLogs.add(new String(encoded).trim());
        }
        
        public List<String> getCapturedLogs() {
            return new ArrayList<>(capturedLogs);
        }
    }
}
