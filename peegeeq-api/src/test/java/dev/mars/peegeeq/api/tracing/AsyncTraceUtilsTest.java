package dev.mars.peegeeq.api.tracing;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
@ExtendWith(VertxExtension.class)
class AsyncTraceUtilsTest {

    @Test
    void traceAsyncAction_ScopesCompletionAndRestoresPreviousContextTrace(Vertx vertx, VertxTestContext testContext) {
        Checkpoint checkpoint = testContext.checkpoint();
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

            traced
                .onSuccess(result -> testContext.verify(() -> {
                    completionMdcTraceId.set(MDC.get(TraceContextUtil.MDC_TRACE_ID));
                    completionMdcSpanId.set(MDC.get(TraceContextUtil.MDC_SPAN_ID));
                    contextAfterCompletionRef.set(AsyncTraceUtils.getCurrentTrace(vertx));

                    TraceCtx parentCtx = parentRef.get();
                    TraceCtx spanCtx = spanRef.get();
                    TraceCtx restoredCtx = contextAfterCompletionRef.get();

                    assertNotNull(parentCtx);
                    assertNotNull(spanCtx);
                    assertNotNull(restoredCtx);
                    assertEquals(parentCtx.traceId(), spanCtx.traceId());
                    assertNotEquals(parentCtx.spanId(), spanCtx.spanId());

                    assertEquals(spanCtx.traceId(), completionMdcTraceId.get());
                    assertNotNull(completionMdcSpanId.get());
                    assertFalse(completionMdcSpanId.get().isBlank());

                    assertEquals(parentCtx.traceId(), restoredCtx.traceId());
                    assertEquals(parentCtx.spanId(), restoredCtx.spanId());
                    checkpoint.flag();
                }))
                .onFailure(testContext::failNow);
        });
    }
}
