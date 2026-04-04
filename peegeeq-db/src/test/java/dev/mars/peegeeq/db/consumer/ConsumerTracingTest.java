package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.api.tracing.TraceContextUtil;
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
            connectionManager.closeAsync();
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
    void fetchMessages_withPreExistingMdc_preservesCallerTrace(VertxTestContext testContext) throws InterruptedException {
        MDC.put("traceId", "caller-trace-id-12345678");
        MDC.put("spanId", "caller-span-1234");
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        fetcher.fetchMessages("topic", "group", 10)
                .onSuccess(messages -> {
                    try {
                        assertNotNull(messages);
                        assertEquals("caller-trace-id-12345678", MDC.get("traceId"),
                                "Caller's traceId should be preserved after fetchMessages");
                        assertEquals("caller-span-1234", MDC.get("spanId"),
                                "Caller's spanId should be preserved after fetchMessages");
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
    void markCompleted_withPreExistingMdc_preservesCallerTrace(VertxTestContext testContext)
            throws InterruptedException {
        MDC.put("traceId", "completion-caller-trace");
        MDC.put("spanId", "completion-caller-span");
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        tracker.markCompleted(999L, "nonexistent-group", "nonexistent-topic")
                .eventually(() -> {
                    try {
                        assertEquals("completion-caller-trace", MDC.get("traceId"),
                                "Caller's traceId should be preserved");
                        assertEquals("completion-caller-span", MDC.get("spanId"),
                                "Caller's spanId should be preserved");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        MDC.clear();
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
    void markFailed_withPreExistingMdc_preservesCallerTrace(VertxTestContext testContext)
            throws InterruptedException {
        MDC.put("traceId", "failure-caller-trace");
        MDC.put("spanId", "failure-caller-span");
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        tracker.markFailed(999L, "nonexistent-group", "nonexistent-topic", "err")
                .eventually(() -> {
                    try {
                        assertEquals("failure-caller-trace", MDC.get("traceId"),
                                "Caller's traceId should be preserved");
                        assertEquals("failure-caller-span", MDC.get("spanId"),
                                "Caller's spanId should be preserved");
                    } catch (Throwable t) {
                        errorRef.set(t);
                    } finally {
                        MDC.clear();
                        testContext.completeNow();
                    }
                    return io.vertx.core.Future.succeededFuture();
                });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
        if (errorRef.get() != null) {
            fail("Test failed: " + errorRef.get().getMessage(), errorRef.get());
        }
    }
}
