package dev.mars.peegeeq.pgqueue;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Live, <strong>non-destructive</strong> observer of new messages on a native queue topic — the
 * push counterpart to {@link PgNativeQueueBrowser#browse(int, int)}. Backs
 * {@code QueueBrowser.tail(...)}.
 *
 * <p>Mirrors the established {@link PgNativeQueueConsumer} LISTEN pattern: a dedicated non-pooled
 * connection holds the LISTEN; the notification/close/exception handlers are registered inline on
 * that connection's context; {@code closeHandler}-driven reconnect with bounded backoff. Because
 * notification delivery and the drain both run on the connection's single context, the watermark
 * and drain flags are naturally serialized — no cross-context hopping.
 *
 * <p><b>Watermark drain (never miss, never duplicate).</b> The NOTIFY payload (the new message id)
 * is treated only as a <em>wake</em> signal. On each wake — and on reconnect — the observer drains
 * {@code SELECT … WHERE id > highWaterId ORDER BY id ASC} (a plain, non-destructive read: no
 * {@code FOR UPDATE}, no status change, no delete) and advances the watermark. This is immune to
 * out-of-order commits (id 5 can commit before id 4) and to NOTIFYs lost while disconnected (the
 * reconnect drain catches the gap), neither of which a read-by-single-id approach survives.
 *
 * <p>Start position is FROM_NOW: the watermark is seeded to {@code MAX(id)} at start, so only
 * messages arriving after subscription are observed (history is {@code browse}).
 *
 * <p>Full design: {@code docs-design/dev/non-destructive-queue-observer-design-18-Jun-2026.md}.
 *
 * @param <T> the message payload type
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-18
 */
final class PgNativeQueueObserver<T> {

    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueObserver.class);

    private static final int  MAX_RECONNECT_ATTEMPTS = 5;
    private static final long BASE_RECONNECT_DELAY_MS = 1000;     // exponential backoff, capped ~32s
    private static final int  DRAIN_BATCH_LIMIT = 200;
    private static final long HANDLER_FAILURE_ESCALATION_THRESHOLD = 3;

    private final String topic;
    private final Class<T> payloadType;
    private final String schema;
    private final Pool pool;                       // non-destructive drain/seed SELECTs
    private final VertxPoolAdapter poolAdapter;    // dedicated LISTEN connection + Vert.x
    private final ObjectMapper objectMapper;
    private final MessageHandler<T> onMessage;
    private final String channel;

    private volatile PgConnection listenConn;
    private volatile boolean active;
    private volatile boolean shutdown;
    private volatile long highWaterId;             // highest id pushed; advanced only within a drain

    // Touched only on the LISTEN connection's context (notification handler, drain, finishDrain).
    private boolean draining;
    private boolean rescanPending;
    private int reconnectAttempts;

    private final AtomicLong handlerFailures = new AtomicLong(0);

    PgNativeQueueObserver(String topic, Class<T> payloadType, String schema, Pool pool,
            VertxPoolAdapter poolAdapter, ObjectMapper objectMapper, MessageHandler<T> onMessage) {
        this.topic = topic;
        this.payloadType = payloadType;
        this.schema = schema;
        this.pool = pool;
        this.poolAdapter = poolAdapter;
        this.objectMapper = objectMapper;
        this.onMessage = onMessage;
        this.channel = NativeQueueChannels.channelFor(schema, topic);
    }

    /**
     * Establishes the live tail: opens the dedicated LISTEN connection, seeds the watermark to
     * {@code MAX(id)} (FROM_NOW), and begins observing. The returned Future resolves once the
     * subscription is in place; a connect/LISTEN/seed failure propagates to the caller (it is
     * never swallowed into a silent hang).
     */
    Future<Void> start() {
        if (active) {
            return Future.succeededFuture();
        }
        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null) {
            return Future.failedFuture(new IllegalStateException(
                    "No Vert.x instance available from pool adapter for tail observer on topic: " + topic));
        }
        this.shutdown = false;
        return establish(true);
    }

    boolean isActive() {
        return active;
    }

    /**
     * Stops the tail: UNLISTEN (best-effort) then close the dedicated connection. Idempotent.
     */
    Future<Void> stop() {
        this.shutdown = true;
        this.active = false;
        PgConnection conn = this.listenConn;
        this.listenConn = null;
        if (conn == null) {
            return Future.succeededFuture();
        }
        return conn.query("UNLISTEN \"" + channel + "\"").execute()
                .transform(ar -> {
                    if (ar.failed()) {
                        // Expected when the connection is already gone (e.g. terminated by the server).
                        logger.debug("tail: UNLISTEN failed on channel {} (connection may be closed): {}",
                                channel, ar.cause().getMessage());
                    }
                    return conn.close();
                })
                .onFailure(err -> logger.warn("tail: failed to close LISTEN connection for channel {}: {}",
                        channel, err.getMessage()))
                .mapEmpty();
    }

    // ------------------------------------------------------------------------
    // Connection lifecycle (handlers registered inline on the connection's context)
    // ------------------------------------------------------------------------

    /**
     * (Re)establishes the LISTEN connection. {@code seedWatermark} is true on the initial start
     * (FROM_NOW) and false on reconnect (the existing watermark is retained so the catch-up drain
     * recovers messages whose NOTIFY was lost during the outage).
     */
    private Future<Void> establish(boolean seedWatermark) {
        return poolAdapter.connectDedicated().compose(conn ->
                conn.query("LISTEN \"" + channel + "\"").execute()
                        .compose(rs -> seedWatermark ? readMaxId() : Future.<Long>succeededFuture(highWaterId))
                        .map(seededHighWater -> {
                            // Runs on the connection's context — same place notifications are delivered.
                            this.listenConn = conn;
                            this.highWaterId = seededHighWater;
                            this.reconnectAttempts = 0;
                            this.active = true;

                            conn.notificationHandler(notification -> {
                                // NOTIFY payload is ignored as a value (see class javadoc) — it only
                                // triggers a drain. Same context as this registration → serialized.
                                if (channel.equals(notification.getChannel())) {
                                    logger.debug("tail: NOTIFY on channel {} (payload '{}')",
                                            channel, notification.getPayload());
                                    drain();
                                }
                            });
                            conn.exceptionHandler(err -> logger.error(
                                    "tail: LISTEN connection error on channel {}: {}", channel, err.getMessage()));
                            conn.closeHandler(v -> onConnectionClosed());

                            logger.info("tail: observing topic {} via LISTEN/NOTIFY (non-destructive), seeded highWater={}",
                                    topic, seededHighWater);
                            drain();   // initial (start) / catch-up (reconnect) drain
                            return (Void) null;
                        })
                        .onFailure(err -> {
                            logger.error("tail: failed to start LISTEN on channel {}: {}",
                                    channel, err.getMessage());
                            conn.close().onFailure(closeErr -> logger.warn(
                                    "tail: failed to close connection after LISTEN error: {}",
                                    closeErr.getMessage()));
                        }));
    }

    private void onConnectionClosed() {
        this.listenConn = null;
        this.active = false;
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (shutdown || active) {
            return;
        }
        Vertx vertx = poolAdapter.getVertx();
        if (vertx == null) {
            return;
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logger.error("tail: exhausted {} reconnect attempts for channel {}; live tail stopped",
                    MAX_RECONNECT_ATTEMPTS, channel);
            return;
        }
        long delay = BASE_RECONNECT_DELAY_MS << Math.min(reconnectAttempts, 5);
        int attempt = ++reconnectAttempts;
        logger.warn("tail: LISTEN connection for channel {} lost; reconnecting in {} ms (attempt {}/{})",
                channel, delay, attempt, MAX_RECONNECT_ATTEMPTS);
        vertx.setTimer(delay, id -> {
            if (shutdown || active) {
                return;
            }
            establish(false)   // keep watermark; the catch-up drain finds the gap
                    .onSuccess(v -> logger.info("tail: reconnected on channel {} (attempt {})", channel, attempt))
                    .onFailure(err -> {
                        logger.error("tail: reconnect attempt {} failed for channel {}: {}",
                                attempt, channel, err.getMessage());
                        scheduleReconnect();
                    });
        });
    }

    // ------------------------------------------------------------------------
    // Watermark drain (runs on the connection's context)
    // ------------------------------------------------------------------------

    private void drain() {
        if (!active || shutdown) {
            return;
        }
        if (draining) {
            rescanPending = true;   // coalesce concurrent wakes
            return;
        }
        draining = true;
        long from = highWaterId;
        String sql = String.format("""
                SELECT id, payload, headers, created_at, status
                FROM %s.queue_messages
                WHERE topic = $1 AND id > $2
                ORDER BY id ASC
                LIMIT $3
                """, schema);
        pool.preparedQuery(sql)
                .execute(Tuple.of(topic, from, DRAIN_BATCH_LIMIT))
                .compose(this::pushAll)
                .onSuccess(pushed -> finishDrain(pushed == DRAIN_BATCH_LIMIT))
                .onFailure(err -> {
                    logger.error("tail: drain failed for topic {} (will retry on next wake): {}",
                            topic, err.getMessage());
                    finishDrain(false);
                });
    }

    /** Clears the single-flight guard and re-drains if the batch was full or a wake was coalesced. */
    private void finishDrain(boolean more) {
        draining = false;
        if (more || rescanPending) {
            rescanPending = false;
            drain();
        }
    }

    /** Pushes rows sequentially (backpressure-respecting); returns the number pushed. */
    private Future<Integer> pushAll(RowSet<Row> rows) {
        Future<Void> chain = Future.succeededFuture();
        int count = 0;
        for (Row row : rows) {
            long id = row.getLong("id");
            Message<T> message = PgNativeMessages.map(row, objectMapper, payloadType);
            if (message == null) {
                // Unparseable row: skip it but advance past it so the drain makes progress and
                // does not re-read it on every wake.
                advanceWatermark(id);
                continue;
            }
            count++;
            chain = chain.compose(v -> pushOne(message, id));
        }
        int total = count;
        return chain.map(total);
    }

    private Future<Void> pushOne(Message<T> message, long id) {
        logger.debug("tail: pushing message id={} on topic {}", id, topic);
        return onMessage.handle(message).transform(ar -> {
            advanceWatermark(id);   // advance even on handler failure: the observer is a best-effort
                                    // viewer, not a delivery guarantee; never wedge the drain on one row
            if (ar.failed()) {
                logHandlerFailure(id, ar.cause());
            } else {
                handlerFailures.set(0);
            }
            return Future.<Void>succeededFuture();
        });
    }

    private void advanceWatermark(long id) {
        if (id > highWaterId) {
            highWaterId = id;
        }
    }

    private void logHandlerFailure(long id, Throwable err) {
        long failures = handlerFailures.incrementAndGet();
        if (failures >= HANDLER_FAILURE_ESCALATION_THRESHOLD) {
            logger.error("tail: handler failed for message {} on channel {} ({} consecutive failures): {}",
                    id, channel, failures, err.getMessage());
        } else {
            logger.warn("tail: handler failed for message {} on channel {}: {}",
                    id, channel, err.getMessage());
        }
    }

    private Future<Long> readMaxId() {
        String sql = String.format(
                "SELECT COALESCE(MAX(id), 0) AS hw FROM %s.queue_messages WHERE topic = $1", schema);
        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getLong("hw"));
    }
}
