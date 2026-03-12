package dev.mars.peegeeq.api.tracing;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class AsyncTraceUtilsTest {

    @Test
    void traceAsyncAction_ScopesCompletionAndRestoresPreviousContextTrace() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            CompletableFuture<Void> done = new CompletableFuture<>();
            AtomicReference<TraceCtx> parentRef = new AtomicReference<>();
            AtomicReference<TraceCtx> spanRef = new AtomicReference<>();
            AtomicReference<String> completionMdcTraceId = new AtomicReference<>();
            AtomicReference<String> completionMdcSpanId = new AtomicReference<>();
            AtomicReference<TraceCtx> contextAfterCompletionRef = new AtomicReference<>();

            vertx.runOnContext(ignored -> {
                TraceCtx parent = TraceCtx.createNew();
                parentRef.set(parent);
                vertx.getOrCreateContext().put(TraceContextUtil.CONTEXT_TRACE_KEY, parent);

                Future<String> traced = AsyncTraceUtils.traceAsyncAction(vertx, "test-op", () -> {
                    spanRef.set(AsyncTraceUtils.getCurrentTrace(vertx));

                    Promise<String> promise = Promise.promise();
                    vertx.setTimer(10, id -> promise.complete("ok"));
                    return promise.future();
                });

                traced.onComplete(ar -> {
                    if (ar.failed()) {
                        done.completeExceptionally(ar.cause());
                        return;
                    }
                    completionMdcTraceId.set(MDC.get(TraceContextUtil.MDC_TRACE_ID));
                    completionMdcSpanId.set(MDC.get(TraceContextUtil.MDC_SPAN_ID));
                    contextAfterCompletionRef.set(AsyncTraceUtils.getCurrentTrace(vertx));
                    done.complete(null);
                });
            });

            done.get(3, TimeUnit.SECONDS);

            TraceCtx parent = parentRef.get();
            TraceCtx span = spanRef.get();
            TraceCtx restored = contextAfterCompletionRef.get();

            assertNotNull(parent);
            assertNotNull(span);
            assertNotNull(restored);
            assertEquals(parent.traceId(), span.traceId());
            assertNotEquals(parent.spanId(), span.spanId());

            // Completion handlers should execute with traced MDC values.
            assertEquals(span.traceId(), completionMdcTraceId.get());
            assertNotNull(completionMdcSpanId.get());
            assertFalse(completionMdcSpanId.get().isBlank());

            // Vert.x context trace should be restored to the parent after completion.
            assertEquals(parent.traceId(), restored.traceId());
            assertEquals(parent.spanId(), restored.spanId());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }
}
