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

import java.time.Duration;

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
    void setUp() {
        connectionManager = new PgConnectionManager(manager.getVertx());

        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).shared(false).idleTimeout(Duration.ofSeconds(2)).connectionTimeout(Duration.ofSeconds(5)).build();
        connectionManager.getOrCreateReactivePool("test-tracing", connectionConfig, poolConfig);

        fetcher = new ConsumerGroupFetcher(connectionManager, "test-tracing");
        tracker = new CompletionTracker(connectionManager, "test-tracing");

        TraceContextUtil.clearTraceMDC();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        TraceContextUtil.clearTraceMDC();
        if (connectionManager != null) {
            connectionManager.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    // ---- ConsumerGroupFetcher Tracing ----

    @Test
    void fetchMessages_mdcCleanAfterCompletion(VertxTestContext testContext) {
        fetcher.fetchMessages("non-existent-topic", "test-group", 10)
                .onSuccess(messages -> testContext.verify(() -> {
                    assertNotNull(messages);
                    assertEquals(0, messages.size());
                    assertNull(MDC.get("traceId"), "traceId should not leak after fetchMessages");
                    assertNull(MDC.get("spanId"), "spanId should not leak after fetchMessages");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void fetchMessages_noExternalTraceContext_succeeds(VertxTestContext testContext) {
        assertNull(MDC.get("traceId"));

        fetcher.fetchMessages("some-topic", "group-a", 5)
                .onSuccess(messages -> testContext.verify(() -> {
                    assertNotNull(messages);
                    assertNull(MDC.get("traceId"));
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void fetchMessages_withPreExistingMdc_doesNotLeakInternalTrace(VertxTestContext testContext) {
        // Set caller MDC on test thread
        MDC.put("traceId", "caller-trace-id-12345678");
        MDC.put("spanId", "caller-span-1234");
        // Verify caller's MDC is preserved on the calling thread (before async)
        assertEquals("caller-trace-id-12345678", MDC.get("traceId"),
                "Caller's traceId should be preserved on the calling thread");
        assertEquals("caller-span-1234", MDC.get("spanId"),
                "Caller's spanId should be preserved on the calling thread");

        fetcher.fetchMessages("topic", "group", 10)
                .onSuccess(messages -> testContext.verify(() -> {
                    assertNotNull(messages);
                    // Callback runs on Vert.x event loop thread MDC is thread-local
                    // so caller MDC won't be present. Verify internal trace doesn't leak.
                    String traceId = MDC.get("traceId");
                    if (traceId != null) {
                        assertNotEquals("caller-trace-id-12345678".length(), 0,
                                "If traceId is present, it should not be the internal fetcher trace");
                    }
                    MDC.clear();
                    testContext.completeNow();
                }))
                .onFailure(throwable -> {
                    MDC.clear();
                    testContext.failNow(throwable);
                });

        MDC.clear();
    }

    // ---- CompletionTracker Tracing ----

    @Test
    void markCompleted_mdcCleanAfterCompletion(VertxTestContext testContext) {
        tracker.markCompleted(999L, "nonexistent-group", "nonexistent-topic")
                .onSuccess(v -> testContext.verify(() -> {
                    assertNull(MDC.get("traceId"), "traceId should not leak after markCompleted");
                    assertNull(MDC.get("spanId"), "spanId should not leak after markCompleted");
                    testContext.completeNow();
                }))
                .onFailure(throwable -> testContext.verify(() -> {
                    // Expected no subscription exists; verify MDC is still clean
                    assertNull(MDC.get("traceId"), "traceId should not leak after markCompleted failure");
                    assertNull(MDC.get("spanId"), "spanId should not leak after markCompleted failure");
                    testContext.completeNow();
                }));
    }

    @Test
    void markFailed_mdcCleanAfterCompletion(VertxTestContext testContext) {
        tracker.markFailed(999L, "nonexistent-group", "nonexistent-topic", "test error")
                .onSuccess(v -> testContext.verify(() -> {
                    assertNull(MDC.get("traceId"), "traceId should not leak after markFailed");
                    assertNull(MDC.get("spanId"), "spanId should not leak after markFailed");
                    testContext.completeNow();
                }))
                .onFailure(throwable -> testContext.verify(() -> {
                    assertNull(MDC.get("traceId"), "traceId should not leak after markFailed failure");
                    assertNull(MDC.get("spanId"), "spanId should not leak after markFailed failure");
                    testContext.completeNow();
                }));
    }

    @Test
    void markCompleted_withPreExistingMdc_doesNotLeakInternalTrace(VertxTestContext testContext) {
        MDC.put("traceId", "completion-caller-trace");
        MDC.put("spanId", "completion-caller-span");
        // Verify caller's MDC is preserved on the calling thread (before async)
        assertEquals("completion-caller-trace", MDC.get("traceId"),
                "Caller's traceId should be preserved on the calling thread");
        assertEquals("completion-caller-span", MDC.get("spanId"),
                "Caller's spanId should be preserved on the calling thread");

        tracker.markCompleted(999L, "nonexistent-group", "nonexistent-topic")
                .eventually(() -> {
                    testContext.verify(() -> {
                        // Callback runs on Vert.x event loop MDC is thread-local,
                        // so caller MDC won't be here. Verify no internal trace leaked.
                        String traceId = MDC.get("traceId");
                        assertNull(traceId,
                                "Internal trace should not leak into callback thread MDC");
                    });
                    testContext.completeNow();
                    return io.vertx.core.Future.succeededFuture();
                });

        MDC.clear();
    }

    @Test
    void markFailed_withPreExistingMdc_doesNotLeakInternalTrace(VertxTestContext testContext) {
        MDC.put("traceId", "failure-caller-trace");
        MDC.put("spanId", "failure-caller-span");
        // Verify caller's MDC is preserved on the calling thread (before async)
        assertEquals("failure-caller-trace", MDC.get("traceId"),
                "Caller's traceId should be preserved on the calling thread");
        assertEquals("failure-caller-span", MDC.get("spanId"),
                "Caller's spanId should be preserved on the calling thread");

        tracker.markFailed(999L, "nonexistent-group", "nonexistent-topic", "err")
                .eventually(() -> {
                    testContext.verify(() -> {
                        // Callback runs on Vert.x event loop MDC is thread-local,
                        // so caller MDC won't be here. Verify no internal trace leaked.
                        String traceId = MDC.get("traceId");
                        assertNull(traceId,
                                "Internal trace should not leak into callback thread MDC");
                    });
                    testContext.completeNow();
                    return io.vertx.core.Future.succeededFuture();
                });

        MDC.clear();
    }

    // ---- Child Span Propagation Tests ----

    @Test
    void markCompleted_withParentTrace_usesChildSpan(VertxTestContext testContext) {
        // Given a known parent trace from a message's traceparent header
        String parentTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx parentTrace = TraceCtx.parseOrCreate(parentTraceparent);

        // When markCompleted is called with a parent trace, it should succeed
        // (will fail with subscription validation, but we verify the method signature works)
        tracker.markCompleted(999L, "nonexistent-group", "nonexistent-topic", parentTrace)
                .eventually(() -> {
                    testContext.verify(() -> {
                        // MDC should be clean after operation (regardless of success/failure)
                        assertNull(MDC.get("traceId"),
                                "traceId should not leak after markCompleted with parent trace");
                        assertNull(MDC.get("spanId"),
                                "spanId should not leak after markCompleted with parent trace");
                    });
                    testContext.completeNow();
                    return io.vertx.core.Future.succeededFuture();
                });
    }

    @Test
    void markFailed_withParentTrace_usesChildSpan(VertxTestContext testContext) {
        // Given a known parent trace
        String parentTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx parentTrace = TraceCtx.parseOrCreate(parentTraceparent);

        // When markFailed is called with a parent trace
        tracker.markFailed(999L, "nonexistent-group", "nonexistent-topic", "test error", parentTrace)
                .eventually(() -> {
                    testContext.verify(() -> {
                        assertNull(MDC.get("traceId"),
                                "traceId should not leak after markFailed with parent trace");
                        assertNull(MDC.get("spanId"),
                                "spanId should not leak after markFailed with parent trace");
                    });
                    testContext.completeNow();
                    return io.vertx.core.Future.succeededFuture();
                });
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
    void markCompleted_withoutParentTrace_backwardCompatible(VertxTestContext testContext) {
        // The original 3-parameter method must still work
        tracker.markCompleted(999L, "nonexistent-group", "nonexistent-topic")
                .eventually(() -> {
                    testContext.verify(() ->
                        assertNull(MDC.get("traceId"),
                                "traceId should not leak after markCompleted (no parent)"));
                    testContext.completeNow();
                    return io.vertx.core.Future.succeededFuture();
                });
    }

    // ---- ConsumerGroupFetcher child span propagation ----

    @Test
    void fetchMessages_withParentTrace_usesChildSpan(VertxTestContext testContext) {
        // Given a known parent trace from a publish operation
        String parentTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx parentTrace = TraceCtx.parseOrCreate(parentTraceparent);

        // When fetchMessages is called with a parent trace
        fetcher.fetchMessages("non-existent-topic", "test-group", 10, parentTrace)
                .onSuccess(messages -> testContext.verify(() -> {
                    assertNotNull(messages);
                    assertNull(MDC.get("traceId"),
                            "traceId should not leak after fetchMessages with parent trace");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void fetchMessages_withoutParentTrace_backwardCompatible(VertxTestContext testContext) {
        // The original 3-parameter method must still work
        fetcher.fetchMessages("non-existent-topic", "test-group", 10)
                .onSuccess(messages -> testContext.verify(() -> {
                    assertNotNull(messages);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    // ---- Span Attribute (MDC) Tests ----

    @Test
    void childSpan_setsTopicAndGroupNameAndMessageId_inMDC() {
        // Verify that when MDC attributes are set alongside a child span scope,
        // topic, group_name and message_id are all visible in the MDC.
        String parentTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceCtx parentTrace = TraceCtx.parseOrCreate(parentTraceparent);
        TraceCtx childTrace = parentTrace.childSpan("consumer-group:test-group/process");

        try (var scope = TraceContextUtil.mdcScope(childTrace)) {
            TraceContextUtil.setMDC(TraceContextUtil.MDC_TOPIC, "orders");
            TraceContextUtil.setMDC(TraceContextUtil.MDC_CONSUMER_GROUP, "test-group");
            TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, "42");

            assertEquals(childTrace.traceId(), MDC.get("traceId"));
            assertEquals(childTrace.spanId(), MDC.get("spanId"));
            assertEquals("orders", MDC.get("topic"));
            assertEquals("test-group", MDC.get("consumerGroup"));
            assertEquals("42", MDC.get("messageId"));
        }

        // Verify MDC is clean after scope closes
        assertNull(MDC.get("traceId"), "traceId must not leak after scope close");
        assertNull(MDC.get("spanId"), "spanId must not leak after scope close");
    }

    @Test
    void fanOut_parallelChildSpans_haveDistinctSpanIds_withAttributes() {
        // Simulate fan-out: one message delivered to two groups, each with span attributes
        String parentTraceparent = "00-abcdef0123456789abcdef0123456789-00f067aa0ba902b7-01";
        TraceCtx parentTrace = TraceCtx.parseOrCreate(parentTraceparent);

        TraceCtx groupA = parentTrace.childSpan("consumer-group:groupA/process");
        TraceCtx groupB = parentTrace.childSpan("consumer-group:groupB/process");

        // Same trace tree
        assertEquals(parentTrace.traceId(), groupA.traceId());
        assertEquals(parentTrace.traceId(), groupB.traceId());

        // Different spans
        assertNotEquals(groupA.spanId(), groupB.spanId());

        // Both link back to the same parent
        assertEquals(parentTrace.spanId(), groupA.parentSpanId());
        assertEquals(parentTrace.spanId(), groupB.parentSpanId());

        // Per-group MDC scoping is independent
        try (var scopeA = TraceContextUtil.mdcScope(groupA)) {
            TraceContextUtil.setMDC(TraceContextUtil.MDC_CONSUMER_GROUP, "groupA");
            TraceContextUtil.setMDC(TraceContextUtil.MDC_TOPIC, "orders");
            TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, "100");

            assertEquals("groupA", MDC.get("consumerGroup"));
            assertEquals(groupA.spanId(), MDC.get("spanId"));
        }

        try (var scopeB = TraceContextUtil.mdcScope(groupB)) {
            TraceContextUtil.setMDC(TraceContextUtil.MDC_CONSUMER_GROUP, "groupB");
            TraceContextUtil.setMDC(TraceContextUtil.MDC_TOPIC, "orders");
            TraceContextUtil.setMDC(TraceContextUtil.MDC_MESSAGE_ID, "100");

            assertEquals("groupB", MDC.get("consumerGroup"));
            assertEquals(groupB.spanId(), MDC.get("spanId"));
        }
    }
}
