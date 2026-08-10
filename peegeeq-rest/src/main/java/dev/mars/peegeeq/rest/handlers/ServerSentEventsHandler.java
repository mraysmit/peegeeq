package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.messaging.QueueBrowser;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-Sent Events (SSE) handler.
 *
 * <p>Serves the non-destructive queue-list update stream
 * ({@code GET /api/v1/sse/queues/{setupId}}): pushes a {@code queue-changed} event whenever a
 * queue is created, updated, or deleted in a setup. The frontend reacts by calling refetch();
 * it does not consume any messages.
 *
 * <p><b>Removed 2026-06-17 (data-loss hazard):</b> the consuming message stream
 * {@code GET /api/v1/queues/{setupId}/{queueName}/stream}. It created a real consumer and
 * subscribed, so it <i>drained</i> messages off the queue — observing a queue must never consume.
 * A non-destructive observe stream (LISTEN/NOTIFY + browser tail → plain {@code SELECT} → push)
 * is the replacement; consumption stays with the real consumer APIs.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-19
 */
public class ServerSentEventsHandler {

    private static final Logger logger = LoggerFactory.getLogger(ServerSentEventsHandler.class);

    private final Vertx vertx;
    private final DatabaseSetupService setupService;
    private final AtomicLong connectionIdCounter = new AtomicLong(0);

    public ServerSentEventsHandler(Vertx vertx, DatabaseSetupService setupService) {
        this.vertx = vertx;
        this.setupService = setupService;
    }

    /**
     * Handles SSE connections for a NON-DESTRUCTIVE live message stream.
     * SSE URL: GET /api/v1/queues/{setupId}/{queueName}/messages/stream
     *
     * <p>Backed by {@code QueueBrowser.tail(...)} — it <strong>observes</strong> new messages and
     * pushes them, but never consumes (no {@code subscribe}, no ack, no delete). Observed messages
     * remain in the queue and are still delivered to the application's real consumers. The tail is
     * torn down ({@code browser.close()}) when the client disconnects.
     *
     * <p><b>Message frame fields, and their two different clocks (telemetry G6, Phase T.3):</b>
     * <ul>
     *   <li>{@code enqueuedAt} — when the message was ENQUEUED (its {@code created_at}). This is
     *       the one to measure end-to-end latency against, and to verify delay/FIFO ordering.
     *       Absent if unknown; never zeroed.</li>
     *   <li>{@code headers} — the message's own headers, carrying any client correlation or
     *       send-time header (e.g. {@code x-send-ts}) needed to join client and server timings.
     *       Absent when the message has none.</li>
     *   <li>{@code timestamp} — the moment THIS FRAME WAS WRITTEN by the server. It is emit time,
     *       not enqueue time. It predates T.3 and is kept so existing consumers keep working, but
     *       a latency computed from it measures only how long the server took to emit the frame —
     *       a small, plausible-looking number that means almost nothing. Use {@code enqueuedAt}.</li>
     * </ul>
     */
    public void handleQueueMessageStream(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");
        String connectionId = "msg-stream-" + connectionIdCounter.incrementAndGet();

        logger.info("SSE message stream established: {} for queue '{}' in setup '{}' (non-destructive observe)",
                connectionId, queueName, setupId);

        HttpServerResponse response = ctx.response();
        response.putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("Access-Control-Allow-Headers", "Cache-Control")
                .setChunked(true);

        AtomicReference<QueueBrowser<Object>> browserRef = new AtomicReference<>();
        AtomicLong observed = new AtomicLong(0);
        long[] heartbeatTimer = {-1};

        // Tear down the tail (browser.close()) and heartbeat when the client disconnects.
        ctx.request().connection().closeHandler(v -> {
            logger.info("SSE message stream closed: {} (observed {} message(s))", connectionId, observed.get());
            if (heartbeatTimer[0] != -1) {
                vertx.cancelTimer(heartbeatTimer[0]);
            }
            QueueBrowser<Object> b = browserRef.get();
            if (b != null) {
                try { b.close(); }
                catch (Exception e) { logger.warn("Error closing tail browser for {}: {}", connectionId, e.getMessage()); }
            }
        });

        writeSSEEvent(response, "connected", new JsonObject()
                .put("type", "connected")
                .put("connectionId", connectionId)
                .put("setupId", setupId)
                .put("queueName", queueName)
                .put("timestamp", System.currentTimeMillis()));

        heartbeatTimer[0] = vertx.setPeriodic(30000L, id -> {
            if (response.closed()) {
                vertx.cancelTimer(heartbeatTimer[0]);
            } else {
                writeSSEEvent(response, "heartbeat", new JsonObject()
                        .put("type", "heartbeat")
                        .put("connectionId", connectionId)
                        .put("timestamp", System.currentTimeMillis()));
            }
        });

        setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.<QueueBrowser<Object>>failedFuture(
                                new IllegalStateException("Setup '" + setupId + "' is not active"));
                    }
                    QueueFactory factory = setupResult.getQueueFactories().get(queueName);
                    if (factory == null) {
                        return Future.<QueueBrowser<Object>>failedFuture(
                                new IllegalArgumentException("Queue '" + queueName + "' not found in setup '" + setupId + "'"));
                    }
                    return factory.createBrowser(queueName, Object.class);
                })
                .compose(browser -> {
                    browserRef.set(browser);
                    if (response.closed()) {
                        // Client disconnected before the tail was established — release the browser.
                        try { browser.close(); }
                        catch (Exception e) { logger.warn("Error closing tail browser for {}: {}", connectionId, e.getMessage()); }
                        return Future.<Void>succeededFuture();
                    }
                    // Non-destructive live observe: tail pushes each new message; it never consumes.
                    return browser.tail(message -> {
                        long n = observed.incrementAndGet();
                        logger.info("SSE message stream {}: pushing message #{} (id={}) to client",
                                connectionId, n, message.getId());
                        JsonObject frame = new JsonObject()
                                .put("type", "message")
                                .put("connectionId", connectionId)
                                .put("messageId", message.getId())
                                .put("payload", message.getPayload())
                                // EMIT time — the moment this frame was written. Kept for
                                // compatibility; it is NOT the enqueue time. See enqueuedAt.
                                .put("timestamp", System.currentTimeMillis());

                        // Telemetry G6: the ENQUEUE timestamp, so a consumer can compute true
                        // end-to-end latency and verify delay/FIFO ordering. Absent rather than
                        // zeroed when unknown — a fabricated instant would be worse than none.
                        if (message.getCreatedAt() != null) {
                            frame.put("enqueuedAt", message.getCreatedAt().toString());
                        }
                        // Telemetry G6: the message's own headers, which carry any client
                        // correlation/send-time header (e.g. x-send-ts) for the latency join.
                        if (message.getHeaders() != null && !message.getHeaders().isEmpty()) {
                            JsonObject headers = new JsonObject();
                            message.getHeaders().forEach(headers::put);
                            frame.put("headers", headers);
                        }

                        writeSSEEvent(response, "message", frame);
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> {
                    // Signal readiness: the tail is live and the FROM_NOW watermark is seeded, so a
                    // client can now publish and be sure those messages will be observed.
                    logger.info("SSE message stream {} observing queue '{}' (tail established)",
                            connectionId, queueName);
                    writeSSEEvent(response, "subscribed", new JsonObject()
                            .put("type", "subscribed")
                            .put("connectionId", connectionId)
                            .put("queueName", queueName)
                            .put("timestamp", System.currentTimeMillis()));
                })
                .onFailure(err -> {
                    logger.error("SSE message stream {} failed for queue '{}': {}",
                            connectionId, queueName, err.getMessage());
                    writeSSEEvent(response, "error", new JsonObject()
                            .put("type", "error")
                            .put("connectionId", connectionId)
                            .put("error", err.getMessage())
                            .put("timestamp", System.currentTimeMillis()));
                });
    }

    /**
     * Handles SSE connections for real-time queue list updates.
     * SSE URL: GET /api/v1/sse/queues/{setupId}
     *
     * <p>Pushes a {@code queue-changed} event whenever a queue is created, updated, or deleted in
     * the given setup. The frontend reacts by calling refetch() — it does not use the event payload
     * directly. This stream is purely observational: it reads nothing off the queue and consumes
     * no messages.
     */
    public void handleQueueUpdates(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String connectionId = "queue-updates-" + connectionIdCounter.incrementAndGet();

        logger.info("Queue updates SSE connection established: {} for setup {}", connectionId, setupId);

        HttpServerResponse response = ctx.response();
        response.putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("Access-Control-Allow-Headers", "Cache-Control")
                .setChunked(true);

        writeSSEEvent(response, "connected", new JsonObject()
                .put("type", "connected")
                .put("connectionId", connectionId)
                .put("setupId", setupId)
                .put("timestamp", System.currentTimeMillis()));

        String address = ManagementApiHandler.QUEUES_CHANGED_ADDRESS_PREFIX + setupId;
        io.vertx.core.eventbus.MessageConsumer<JsonObject> busConsumer =
                vertx.eventBus().consumer(address, msg -> {
                    if (!response.closed()) {
                        writeSSEEvent(response, "queue-changed", msg.body());
                    }
                });

        long[] timerIdRef = {0};
        timerIdRef[0] = vertx.setPeriodic(30000L, id -> {
            if (response.closed()) {
                vertx.cancelTimer(timerIdRef[0]);
                busConsumer.unregister();
            } else {
                writeSSEEvent(response, "heartbeat", new JsonObject()
                        .put("type", "heartbeat")
                        .put("connectionId", connectionId)
                        .put("timestamp", System.currentTimeMillis()));
            }
        });

        ctx.request().connection().closeHandler(v -> {
            logger.info("Queue updates SSE connection closed: {}", connectionId);
            vertx.cancelTimer(timerIdRef[0]);
            busConsumer.unregister();
        });
    }

    // ── Fast per-queue stats stream (telemetry G4 — metrics-stack remediation step 6) ────────
    //
    // §5 of the telemetry requirements wants ≥ 1 Hz per-queue figures for G.1b's breaking-point
    // attribution. /sse/metrics cannot honestly provide that: it aggregates every setup behind
    // a 5 s system-stats cache. This stream is the decided alternative — per-QUEUE, built on
    // the same typed core seam as GET .../stats (QueueFactory.getStats → QueueStats →
    // QueueHandler.queueStatsJson), so the frame shape is identical to /stats by construction.
    // The collector holds no metric state: every tick is a fresh idempotent read of
    // core-produced numbers, and rate/delta derivation stays with the consumer (§4A).

    /** Interval clamp: ≥ 1 Hz is the requirement; 200 ms is the floor so a typo cannot turn a stats stream into load. */
    private static final long STATS_STREAM_MIN_INTERVAL_MS = 200L;
    private static final long STATS_STREAM_MAX_INTERVAL_MS = 10_000L;
    private static final long STATS_STREAM_DEFAULT_INTERVAL_MS = 1_000L;

    /**
     * SSE URL: GET /api/v1/queues/{setupId}/{queueName}/stats/stream?intervalMs=1000
     *
     * <p>Emits a {@code stats} event per tick carrying exactly the GET .../stats payload.
     * Non-destructive: getStats is a read. A tick that FAILS ends the stream with an
     * {@code error} event carrying the reason — a stream that silently stopped sampling would
     * read as a healthy queue with frozen numbers.
     */
    public void handleQueueStatsStream(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");
        String queueName = ctx.pathParam("queueName");

        if (!dev.mars.peegeeq.api.messaging.TopicNameValidator.isValid(queueName)) {
            ctx.response().setStatusCode(400)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("error", "Invalid queue name").encode());
            return;
        }

        long intervalMs = parseIntervalMs(ctx.request().getParam("intervalMs"));

        setupService.getSetupResult(setupId)
                .compose(setupResult -> {
                    if (setupResult.getStatus() != DatabaseSetupStatus.ACTIVE) {
                        return Future.failedFuture(new IllegalStateException(
                                "Setup not found or not active: " + setupId));
                    }
                    QueueFactory factory = setupResult.getQueueFactories().get(queueName);
                    if (factory == null) {
                        return Future.failedFuture(new IllegalStateException(
                                "Queue not found: " + queueName));
                    }
                    return Future.succeededFuture(factory);
                })
                .onSuccess(factory -> startQueueStatsStream(ctx, setupId, queueName, factory, intervalMs))
                .onFailure(err -> ctx.response().setStatusCode(404)
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject().put("error", err.getMessage()).encode()));
    }

    private void startQueueStatsStream(RoutingContext ctx, String setupId, String queueName,
            QueueFactory factory, long intervalMs) {
        String connectionId = "stats-stream-" + connectionIdCounter.incrementAndGet();
        HttpServerResponse response = ctx.response();
        response.putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .setChunked(true);

        logger.info("Queue stats stream connected: {} for {}/{} at {} ms", connectionId, setupId,
                queueName, intervalMs);

        writeSSEEvent(response, "connected", new JsonObject()
                .put("connectionId", connectionId)
                .put("setupId", setupId)
                .put("queueName", queueName)
                .put("intervalMs", intervalMs)
                .put("timestamp", System.currentTimeMillis()));

        long[] timerIdRef = {0};
        Runnable stop = () -> {
            if (timerIdRef[0] != 0) {
                vertx.cancelTimer(timerIdRef[0]);
                timerIdRef[0] = 0;
            }
        };

        // The in-flight guard: at 200 ms ticks a slow database can out-wait the interval, and
        // stacking reads would turn the observer into load (the same rule as the manager's
        // acquisition canary).
        java.util.concurrent.atomic.AtomicBoolean tickRunning =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Runnable tick = () -> {
            if (response.closed()) {
                stop.run();
                return;
            }
            if (!tickRunning.compareAndSet(false, true)) {
                return;
            }
            factory.isHealthy()
                    .compose(healthy -> factory.getStats(queueName)
                            .map(stats -> QueueHandler.queueStatsJson(
                                    setupId, queueName, factory.getImplementationType(), healthy, stats)))
                    .onSuccess(json -> {
                        tickRunning.set(false);
                        writeSSEEvent(response, "stats", json);
                    })
                    .onFailure(err -> {
                        tickRunning.set(false);
                        // The stream ENDS on a failed read: continuing would emit nothing while
                        // looking connected, and a frozen "healthy" frame is worse than a
                        // stated failure. The client reconnects if it still wants the stream.
                        logger.warn("Queue stats stream {} ending after failed read for {}/{}: {}",
                                connectionId, setupId, queueName, err.getMessage());
                        stop.run();
                        writeSSEEvent(response, "error", new JsonObject()
                                .put("error", "Stats read failed: " + err.getMessage())
                                .put("timestamp", System.currentTimeMillis()));
                        if (!response.closed()) {
                            response.end();
                        }
                    });
        };

        // First sample immediately, then the periodic cadence.
        tick.run();
        timerIdRef[0] = vertx.setPeriodic(intervalMs, id -> tick.run());

        ctx.request().connection().closeHandler(v -> {
            logger.info("Queue stats stream closed: {}", connectionId);
            stop.run();
        });
    }

    private static long parseIntervalMs(String param) {
        if (param == null) {
            return STATS_STREAM_DEFAULT_INTERVAL_MS;
        }
        try {
            long requested = Long.parseLong(param);
            return Math.max(STATS_STREAM_MIN_INTERVAL_MS,
                    Math.min(STATS_STREAM_MAX_INTERVAL_MS, requested));
        } catch (NumberFormatException e) {
            return STATS_STREAM_DEFAULT_INTERVAL_MS;
        }
    }

    /**
     * Writes a single SSE event directly to an HTTP response.
     */
    private void writeSSEEvent(HttpServerResponse response, String eventType, JsonObject data) {
        if (response.closed()) return;
        try {
            response.write("event: " + eventType + "\ndata: " + data.encode() + "\n\n");
        } catch (Exception e) {
            logger.error("Error writing SSE event: {}", e.getMessage());
        }
    }

    /**
     * Number of tracked, server-held SSE connections. The consuming message stream — the only thing
     * that ever held server-side state per connection — was removed, and the queue-updates stream
     * manages its own per-request lifecycle, so this is always 0.
     */
    public int getActiveConnectionCount() {
        return 0;
    }

    /**
     * Information about active SSE connections.
     */
    public JsonObject getConnectionInfo() {
        return new JsonObject()
                .put("activeConnections", 0)
                .put("timestamp", System.currentTimeMillis());
    }

    /**
     * Lifecycle close hook (invoked at server shutdown). The consuming message stream — the only
     * thing that held server-side consumers — was removed, so there is nothing to tear down here.
     */
    public void close() {
        logger.info("Closing ServerSentEventsHandler");
    }
}
