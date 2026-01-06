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

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;

import java.util.concurrent.Callable;

/**
 * Utilities for strictly tracing async operations in Vert.x 5.
 * Prevents MDC context loss when switching threads.
 * <p>
 * This class provides wrapper APIs that enforce correct trace propagation:
 * <ul>
 *   <li>{@link #executeBlockingTraced} - For blocking operations on WorkerExecutor</li>
 *   <li>{@link #publishWithTrace} - For fire-and-forget Event Bus publish</li>
 *   <li>{@link #requestWithTrace} - For request/response Event Bus RPC</li>
 *   <li>{@link #tracedConsumer} - For Event Bus consumers with auto trace extraction</li>
 * </ul>
 * <p>
 * <b>Rule:</b> Never use raw Event Bus or executeBlocking. Always use these wrappers.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-01-06
 */
public class AsyncTraceUtils {
    
    private static final String TRACEPARENT_HEADER = "traceparent";

    /**
     * Executes blocking code on a WorkerExecutor with correct trace propagation.
     * <p>
     * 1. Captures current TraceCtx from Vert.x Context (source of truth).
     * 2. Creates a child span.
     * 3. Wraps the blocking call in an MDC scope.
     * </p>
     *
     * @param vertx The Vertx instance
     * @param worker The WorkerExecutor to use
     * @param ordered Whether execution should be ordered
     * @param blocking The blocking code to run
     * @return Future result
     * @param <T> Result type
     */
    public static <T> Future<T> executeBlockingTraced(
            Vertx vertx,
            WorkerExecutor worker,
            boolean ordered,
            Callable<T> blocking
    ) {
        Context ctx = vertx.getOrCreateContext();
        Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
        
        TraceCtx parent;
        if (traceObj instanceof TraceCtx) {
            parent = (TraceCtx) traceObj;
        } else {
            // Start a new trace if none exists, to ensure we have visibility
            parent = TraceCtx.createNew();
            // We should put it back in context if we want subsequent ops on this event loop to see it
            ctx.put(TraceContextUtil.CONTEXT_TRACE_KEY, parent);
        }
        
        // Create child span for the blocking operation
        TraceCtx span = parent.childSpan("executeBlocking");

        return worker.executeBlocking(() -> {
            // This runs on the worker thread
            try (var scope = TraceContextUtil.mdcScope(span)) {
                return blocking.call();
            }
        }, ordered);
    }

    /**
     * Traces an asynchronous action.
     * The action must return a Future.
     * 
     * @param vertx The Vertx instance
     * @param operationName The name of the operation for the span
     * @param action The supplier of the Future to trace
     * @return The result Future
     */
    public static <T> Future<T> traceAsyncAction(
            Vertx vertx,
            String operationName,
            java.util.function.Supplier<Future<T>> action
    ) {
        Context ctx = vertx.getOrCreateContext();
        Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
        
        TraceCtx parent;
        if (traceObj instanceof TraceCtx) {
            parent = (TraceCtx) traceObj;
        } else {
            parent = TraceCtx.createNew();
            ctx.put(TraceContextUtil.CONTEXT_TRACE_KEY, parent);
        }
        
        TraceCtx span = parent.childSpan(operationName);
        
        try (var scope = TraceContextUtil.mdcScope(span)) {
             return action.get();
        }
    }
    
    // ========================================
    // Event Bus Wrappers with Trace Propagation
    // ========================================
    
    /**
     * Publishes a message to the Event Bus with W3C trace context propagation.
     * <p>
     * Creates a child span from the current trace context and injects the
     * traceparent header into delivery options.
     * </p>
     * <p>
     * <b>Fire-and-forget:</b> Consumers are new roots of work and must be self-sufficient.
     * </p>
     *
     * @param vertx   The Vertx instance
     * @param address The Event Bus address
     * @param message The message payload
     */
    public static void publishWithTrace(Vertx vertx, String address, Object message) {
        publishWithTrace(vertx, address, message, null);
    }
    
    /**
     * Publishes a message to the Event Bus with W3C trace context propagation.
     * <p>
     * Creates a child span from the current trace context and injects the
     * traceparent header into delivery options.
     * </p>
     *
     * @param vertx   The Vertx instance
     * @param address The Event Bus address
     * @param message The message payload
     * @param options Additional delivery options (traceparent will be added)
     */
    public static void publishWithTrace(Vertx vertx, String address, Object message, DeliveryOptions options) {
        TraceCtx parent = getOrCreateTrace(vertx);
        TraceCtx childSpan = parent.childSpan("publish:" + address);
        
        DeliveryOptions opts = (options != null) ? options : new DeliveryOptions();
        opts.addHeader(TRACEPARENT_HEADER, childSpan.traceparent());
        
        vertx.eventBus().publish(address, message, opts);
    }
    
    /**
     * Sends a request to the Event Bus with W3C trace context propagation and awaits a reply.
     * <p>
     * Creates a child span from the current trace context, injects the traceparent header,
     * and re-scopes MDC inside the reply handler.
     * </p>
     *
     * @param vertx   The Vertx instance
     * @param address The Event Bus address
     * @param message The request payload
     * @param <T>     The expected reply type
     * @return Future containing the reply message
     */
    public static <T> Future<Message<T>> requestWithTrace(Vertx vertx, String address, Object message) {
        return requestWithTrace(vertx, address, message, null);
    }
    
    /**
     * Sends a request to the Event Bus with W3C trace context propagation and awaits a reply.
     * <p>
     * Creates a child span from the current trace context, injects the traceparent header,
     * and re-scopes MDC inside the reply handler.
     * </p>
     *
     * @param vertx   The Vertx instance
     * @param address The Event Bus address
     * @param message The request payload
     * @param options Additional delivery options (traceparent will be added)
     * @param <T>     The expected reply type
     * @return Future containing the reply message
     */
    public static <T> Future<Message<T>> requestWithTrace(Vertx vertx, String address, Object message, DeliveryOptions options) {
        TraceCtx parent = getOrCreateTrace(vertx);
        TraceCtx childSpan = parent.childSpan("request:" + address);
        
        DeliveryOptions opts = (options != null) ? options : new DeliveryOptions();
        opts.addHeader(TRACEPARENT_HEADER, childSpan.traceparent());
        
        // Store the span used for this request so we can scope MDC in the reply handler
        return vertx.eventBus().<T>request(address, message, opts)
            .map(reply -> {
                // Re-scope MDC when processing the reply (we're back on event loop)
                try (var scope = TraceContextUtil.mdcScope(childSpan)) {
                    return reply;
                }
            });
    }
    
    /**
     * Creates a traced Event Bus consumer that automatically extracts trace context
     * from incoming messages and scopes MDC for the handler duration.
     * <p>
     * The consumer:
     * <ol>
     *   <li>Extracts traceparent from message headers</li>
     *   <li>Stores TraceCtx in Vert.x Context (source of truth)</li>
     *   <li>Wraps handler execution in MDC scope</li>
     *   <li>Clears MDC after handler completes</li>
     * </ol>
     * </p>
     *
     * @param vertx   The Vertx instance
     * @param address The Event Bus address to consume
     * @param handler The message handler
     * @param <T>     The message body type
     * @return The MessageConsumer for lifecycle management
     */
    public static <T> MessageConsumer<T> tracedConsumer(Vertx vertx, String address, Handler<Message<T>> handler) {
        return vertx.eventBus().<T>consumer(address, msg -> {
            // Extract traceparent from message headers
            String traceparent = msg.headers().get(TRACEPARENT_HEADER);
            TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);
            
            // Store in Vert.x Context (source of truth)
            Context ctx = vertx.getOrCreateContext();
            ctx.put(TraceContextUtil.CONTEXT_TRACE_KEY, trace);
            
            // Execute handler with MDC scoped
            try (var scope = TraceContextUtil.mdcScope(trace)) {
                handler.handle(msg);
            }
        });
    }
    
    /**
     * Creates a traced Event Bus consumer for request/reply patterns.
     * <p>
     * Same as {@link #tracedConsumer} but the handler returns a Future for async processing.
     * Trace context is maintained throughout the async operation.
     * </p>
     *
     * @param vertx   The Vertx instance
     * @param address The Event Bus address to consume
     * @param handler The async message handler that returns a reply
     * @param <T>     The request message body type
     * @param <R>     The reply type
     * @return The MessageConsumer for lifecycle management
     */
    public static <T, R> MessageConsumer<T> tracedAsyncConsumer(
            Vertx vertx, 
            String address, 
            java.util.function.Function<Message<T>, Future<R>> handler
    ) {
        return vertx.eventBus().<T>consumer(address, msg -> {
            // Extract traceparent from message headers
            String traceparent = msg.headers().get(TRACEPARENT_HEADER);
            TraceCtx trace = TraceContextUtil.parseOrCreate(traceparent);
            
            // Store in Vert.x Context (source of truth)
            Context ctx = vertx.getOrCreateContext();
            ctx.put(TraceContextUtil.CONTEXT_TRACE_KEY, trace);
            
            // Execute handler with MDC scoped
            try (var scope = TraceContextUtil.mdcScope(trace)) {
                handler.apply(msg)
                    .onSuccess(result -> {
                        try (var replyScope = TraceContextUtil.mdcScope(trace)) {
                            msg.reply(result);
                        }
                    })
                    .onFailure(err -> {
                        try (var replyScope = TraceContextUtil.mdcScope(trace)) {
                            msg.fail(500, err.getMessage());
                        }
                    });
            }
        });
    }
    
    // ========================================
    // Internal Helpers
    // ========================================
    
    /**
     * Gets the current TraceCtx from Vert.x Context, or creates a new one if absent.
     * This ensures there's always a trace context available for propagation.
     *
     * @param vertx The Vertx instance
     * @return The current or new TraceCtx
     */
    public static TraceCtx getOrCreateTrace(Vertx vertx) {
        Context ctx = vertx.getOrCreateContext();
        Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
        
        if (traceObj instanceof TraceCtx) {
            return (TraceCtx) traceObj;
        }
        
        TraceCtx newTrace = TraceCtx.createNew();
        ctx.put(TraceContextUtil.CONTEXT_TRACE_KEY, newTrace);
        return newTrace;
    }
    
    /**
     * Gets the current TraceCtx from Vert.x Context, or null if not present.
     *
     * @param vertx The Vertx instance
     * @return The current TraceCtx or null
     */
    public static TraceCtx getCurrentTrace(Vertx vertx) {
        Context ctx = Vertx.currentContext();
        if (ctx == null) {
            return null;
        }
        Object traceObj = ctx.get(TraceContextUtil.CONTEXT_TRACE_KEY);
        return (traceObj instanceof TraceCtx) ? (TraceCtx) traceObj : null;
    }
}
