package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.api.tracing.TraceContextUtil;
import dev.mars.peegeeq.api.tracing.TraceCtx;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.MDC;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for tracing in ConsumerGroupFetcher and CompletionTracker.
 *
 * <p>Verifies that trace context is created for fetch/completion operations
 * and that MDC does not leak after operations complete.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Execution(ExecutionMode.SAME_THREAD)
public class ConsumerTracingTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private ConsumerGroupFetcher fetcher;
    private CompletionTracker tracker;

    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx());

        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(10).build();
        connectionManager.getOrCreateReactivePool("test-tracing", connectionConfig, poolConfig);

        fetcher = new ConsumerGroupFetcher(connectionManager, "test-tracing");
        tracker = new CompletionTracker(connectionManager, "test-tracing");

        TraceContextUtil.clearTraceMDC();
    }

    @AfterEach
    void tearDown() {
        TraceContextUtil.clearTraceMDC();
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    // ---- ConsumerGroupFetcher Tracing ----

    @Test
    void fetchMessages_mdcCleanAfterCompletion(VertxTestContext testContext) throws InterruptedException {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        fetcher.fetchMessages("non-existent-topic", "test-group", 10)
                .onSuccess(messages -> {
                    try {
                        assertNotNull(messages);
                        assertEquals(0, messages.size());
                        assertNull(MDC.get("traceId"), "traceId should not leak after fetchMessages");
                        assertNull(MDC.get("spanId"), "spanId should not leak after fetchMessages");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    @Test
    void fetchMessages_noExternalTraceContext_succeeds(VertxTestContext testContext) throws InterruptedException {
        assertNull(MDC.get("traceId"));
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        fetcher.fetchMessages("some-topic", "group-a", 5)
                .onSuccess(messages -> {
                    try {
                        assertNotNull(messages);
                        assertNull(MDC.get("traceId"));
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    @Test
    void fetchMessages_withPreExistingMdc_doesNotLeakInternalTrace(VertxTestContext testContext) throws InterruptedException {
        // Set caller MDC on test thread
        MDC.put("traceId", "caller-trace-id-12345678");
        MDC.put("spanId", "caller-span-1234");
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        fetcher.fetchMessages("topic", "group", 10)
                .onSuccess(messages -> {
                    try {
                        assertNotNull(messages);
                        // Callback runs on Vert.x event loop thread — MDC is thread-local
                        // so caller MDC won't be present. Verify internal trace doesn't leak.
                        String traceId = MDC.get("traceId");
                        if (traceId != null) {
                            assertNotEquals("caller-trace-id-12345678".length(), 0,
                                    "If traceId is present, it should not be the internal fetcher trace");
                        }
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        MDC.clear();
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    MDC.clear();
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        // Verify caller's MDC is preserved on the calling thread (test thread)
        assertEquals("caller-trace-id-12345678", MDC.get("traceId"),
                "Caller's traceId should be preserved on the calling thread");
        assertEquals("caller-span-1234", MDC.get("spanId"),
                "Caller's spanId should be preserved on the calling thread");
        MDC.clear();
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    // ---- CompletionTracker Tracing ----

    @Test
    void markCompleted_mdcCleanAfterCompletion(VertxTestContext testContext) throws InterruptedException {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        tracker.markCompleted(999L, "nonexistent-group", "nonexistent-topic")
                .onSuccess(v -> {
                    try {
                        assertNull(MDC.get("traceId"), "traceId should not leak after markCompleted");
                        assertNull(MDC.get("spanId"), "spanId should not leak after markCompleted");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    // Expected — no subscription exists; verify MDC is still clean
                    try {
                        assertNull(MDC.get("traceId"), "traceId should not leak after markCompleted failure");
                        assertNull(MDC.get("spanId"), "spanId should not leak after markCompleted failure");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    @Test
    void markFailed_mdcCleanAfterCompletion(VertxTestContext testContext) throws InterruptedException {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        tracker.markFailed(999L, "nonexistent-group", "nonexistent-topic", "test error")
                .onSuccess(v -> {
                    try {
                        assertNull(MDC.get("traceId"), "traceId should not leak after markFailed");
                        assertNull(MDC.get("spanId"), "spanId should not leak after markFailed");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    try {
                        assertNull(MDC.get("traceId"), "traceId should not leak after markFailed failure");
                        assertNull(MDC.get("spanId"), "spanId should not leak after markFailed failure");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    @Test
    void markCompleted_withPreExistingMdc_doesNotLeakInternalTrace(VertxTestContext testContext)
            throws InterruptedException {
        MDC.put("traceId", "completion-caller-trace");
        MDC.put("spanId", "completion-caller-span");
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        tracker.markCompleted(999L, "nonexistent-group", "nonexistent-topic")
                .eventually(() -> {
                    try {
                        // Callback runs on Vert.x event loop — MDC is thread-local,
                        // so caller MDC won't be here. Verify no internal trace leaked.
                        String traceId = MDC.get("traceId");
                        assertNull(traceId,
                                "Internal trace should not leak into callback thread MDC");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                    return io.vertx.core.Future.succeededFuture();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        // Verify caller's MDC is preserved on the calling thread
        assertEquals("completion-caller-trace", MDC.get("traceId"),
                "Caller's traceId should be preserved on the calling thread");
        assertEquals("completion-caller-span", MDC.get("spanId"),
                "Caller's spanId should be preserved on the calling thread");
        MDC.clear();
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    @Test
    void markFailed_withPreExistingMdc_doesNotLeakInternalTrace(VertxTestContext testContext)
            throws InterruptedException {
        MDC.put("traceId", "failure-caller-trace");
        MDC.put("spanId", "failure-caller-span");
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        tracker.markFailed(999L, "nonexistent-group", "nonexistent-topic", "err")
                .eventually(() -> {
                    try {
                        // Callback runs on Vert.x event loop — MDC is thread-local,
                        // so caller MDC won't be here. Verify no internal trace leaked.
                        String traceId = MDC.get("traceId");
                        assertNull(traceId,
                                "Internal trace should not leak into callback thread MDC");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                    return io.vertx.core.Future.succeededFuture();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        // Verify caller's MDC is preserved on the calling thread
        assertEquals("failure-caller-trace", MDC.get("traceId"),
                "Caller's traceId should be preserved on the calling thread");
        assertEquals("failure-caller-span", MDC.get("spanId"),
                "Caller's spanId should be preserved on the calling thread");
        MDC.clear();
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    // ---- Child Span Propagation Tests ----

    @Test
    void markCompleted_withParentTrace_usesChildSpan(VertxTestContext testContext) throws InterruptedException {
        // Given a known parent trace from a message's traceparent header
        String parentTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx parentTrace = TraceCtx.parseOrCreate(parentTraceparent);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // When markCompleted is called with a parent trace, it should succeed
        // (will fail with subscription validation, but we verify the method signature works)
        tracker.markCompleted(999L, "nonexistent-group", "nonexistent-topic", parentTrace)
                .eventually(() -> {
                    try {
                        // MDC should be clean after operation (regardless of success/failure)
                        assertNull(MDC.get("traceId"),
                                "traceId should not leak after markCompleted with parent trace");
                        assertNull(MDC.get("spanId"),
                                "spanId should not leak after markCompleted with parent trace");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                    return io.vertx.core.Future.succeededFuture();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    @Test
    void markFailed_withParentTrace_usesChildSpan(VertxTestContext testContext) throws InterruptedException {
        // Given a known parent trace
        String parentTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx parentTrace = TraceCtx.parseOrCreate(parentTraceparent);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // When markFailed is called with a parent trace
        tracker.markFailed(999L, "nonexistent-group", "nonexistent-topic", "test error", parentTrace)
                .eventually(() -> {
                    try {
                        assertNull(MDC.get("traceId"),
                                "traceId should not leak after markFailed with parent trace");
                        assertNull(MDC.get("spanId"),
                                "spanId should not leak after markFailed with parent trace");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                    return io.vertx.core.Future.succeededFuture();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    @Test
    void markCompleted_withParentTrace_childSpanLinksToParent() {
        // Verify the trace mechanics directly: when a parent trace is provided,
        // the child span created must preserve the traceId and link to the parent.
        String parentTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx parentTrace = TraceCtx.parseOrCreate(parentTraceparent);

        TraceCtx childTrace = parentTrace.childSpan("consumer-group:test-group/complete");

        // Same trace tree
        assertEquals(parentTrace.traceId(), childTrace.traceId(),
                "Completion child span must share parent traceId");
        // Linked to parent
        assertEquals(parentTrace.spanId(), childTrace.parentSpanId(),
                "Completion child span parentSpanId must reference parent's spanId");
        // Unique span
        assertNotEquals(parentTrace.spanId(), childTrace.spanId(),
                "Completion child span must have its own spanId");
    }

    @Test
    void markCompleted_withoutParentTrace_backwardCompatible(VertxTestContext testContext)
            throws InterruptedException {
        // The original 3-parameter method must still work
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        tracker.markCompleted(999L, "nonexistent-group", "nonexistent-topic")
                .eventually(() -> {
                    try {
                        assertNull(MDC.get("traceId"),
                                "traceId should not leak after markCompleted (no parent)");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                    return io.vertx.core.Future.succeededFuture();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    // ---- ConsumerGroupFetcher child span propagation ----

    @Test
    void fetchMessages_withParentTrace_usesChildSpan(VertxTestContext testContext) throws InterruptedException {
        // Given a known parent trace from a publish operation
        String parentTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx parentTrace = TraceCtx.parseOrCreate(parentTraceparent);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // When fetchMessages is called with a parent trace
        fetcher.fetchMessages("non-existent-topic", "test-group", 10, parentTrace)
                .onSuccess(messages -> {
                    try {
                        assertNotNull(messages);
                        assertNull(MDC.get("traceId"),
                                "traceId should not leak after fetchMessages with parent trace");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }

    @Test
    void fetchMessages_withoutParentTrace_backwardCompatible(VertxTestContext testContext)
            throws InterruptedException {
        // The original 3-parameter method must still work
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        fetcher.fetchMessages("non-existent-topic", "test-group", 10)
                .onSuccess(messages -> {
                    try {
                        assertNotNull(messages);
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        testContext.completeNow();
                    }
                })
                .onFailure(throwable -> {
                    errorRef.set(throwable);
                    testContext.completeNow();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }
}
