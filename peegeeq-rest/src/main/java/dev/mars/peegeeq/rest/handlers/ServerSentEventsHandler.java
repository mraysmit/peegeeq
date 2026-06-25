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
                        writeSSEEvent(response, "message", new JsonObject()
                                .put("type", "message")
                                .put("connectionId", connectionId)
                                .put("messageId", message.getId())
                                .put("payload", message.getPayload())
                                .put("timestamp", System.currentTimeMillis()));
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
