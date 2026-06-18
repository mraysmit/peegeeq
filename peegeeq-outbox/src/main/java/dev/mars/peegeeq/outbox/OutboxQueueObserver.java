package dev.mars.peegeeq.outbox;

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
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Live, <strong>non-destructive</strong> observer of new messages on an outbox topic — the push
 * counterpart to {@link OutboxQueueBrowser#browse(int, int)}. Backs {@code QueueBrowser.tail(...)}.
 *
 * <p>The outbox has no insert {@code NOTIFY} (it is a poll-based queue; {@code OutboxConsumer} uses
 * {@code vertx.setPeriodic}, not LISTEN), so this observer is a <em>browse-poll</em>: a periodic
 * timer drains {@code SELECT … WHERE id > highWaterId ORDER BY id ASC} (a plain, non-destructive
 * read — no status change, no delete) and advances the watermark. Identical watermark semantics to
 * the native {@code PgNativeQueueObserver}, but the wake is a timer tick instead of a NOTIFY.
 *
 * <p>Start position is FROM_NOW: the watermark is seeded to {@code MAX(id)} at start, so only
 * messages arriving after subscription are observed (history is {@code browse}).
 *
 * <p>All state (watermark, drain flags) is touched only on the Vert.x context that owns the
 * periodic timer, and a context runs its handlers one at a time, so no locking is needed.
 *
 * @param <T> the message payload type
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-18
 */
final class OutboxQueueObserver<T> {

    private static final Logger logger = LoggerFactory.getLogger(OutboxQueueObserver.class);

    private static final long DEFAULT_POLL_INTERVAL_MS = 1000;
    private static final int  DRAIN_BATCH_LIMIT = 200;
    private static final long HANDLER_FAILURE_ESCALATION_THRESHOLD = 3;

    private final String topic;
    private final Class<T> payloadType;
    private final Pool pool;
    private final Vertx vertx;
    private final ObjectMapper objectMapper;
    private final MessageHandler<T> onMessage;
    private final String tableRef;

    private volatile boolean active;
    private volatile boolean shutdown;
    private volatile long highWaterId;             // highest id pushed; advanced only within a drain
    private volatile long pollTimerId = -1;

    // Touched only on the timer's Vert.x context (drain / finishDrain).
    private boolean draining;
    private boolean rescanPending;

    private final AtomicLong handlerFailures = new AtomicLong(0);

    OutboxQueueObserver(String topic, Class<T> payloadType, String schema, Pool pool, Vertx vertx,
            ObjectMapper objectMapper, MessageHandler<T> onMessage) {
        this.topic = topic;
        this.payloadType = payloadType;
        this.pool = pool;
        this.vertx = vertx;
        this.objectMapper = objectMapper;
        this.onMessage = onMessage;
        // null schema -> unqualified (search_path); non-null -> schema-qualified, matching browse().
        this.tableRef = schema != null ? OutboxFactory.quoteIdentifier(schema) + ".outbox" : "outbox";
    }

    /**
     * Establishes the live tail: seeds the watermark to {@code MAX(id)} (FROM_NOW) and starts the
     * periodic poll. The returned Future resolves once the watermark is seeded and the timer is
     * scheduled; a seed-query failure propagates to the caller (never a silent hang).
     */
    Future<Void> start() {
        if (active) {
            return Future.succeededFuture();
        }
        if (vertx == null) {
            return Future.failedFuture(new IllegalStateException(
                    "No Vert.x instance available for outbox tail observer on topic: " + topic));
        }
        this.shutdown = false;
        return readMaxId().map(seededHighWater -> {
            // Runs on the seed-query's completion context; the periodic timer is bound to it too,
            // so every drain runs on this one context and the watermark/flags need no locking.
            this.highWaterId = seededHighWater;
            this.active = true;
            this.pollTimerId = vertx.setPeriodic(DEFAULT_POLL_INTERVAL_MS, id -> drain());
            logger.info("tail: observing topic {} via {} ms poll (non-destructive), seeded highWater={}",
                    topic, DEFAULT_POLL_INTERVAL_MS, seededHighWater);
            return (Void) null;
        });
    }

    boolean isActive() {
        return active;
    }

    /** Stops the tail: cancels the poll timer. Idempotent. No connection to release (uses the pool). */
    Future<Void> stop() {
        this.shutdown = true;
        this.active = false;
        long timer = this.pollTimerId;
        this.pollTimerId = -1;
        if (timer != -1 && vertx != null) {
            vertx.cancelTimer(timer);
        }
        return Future.succeededFuture();
    }

    // ------------------------------------------------------------------------
    // Watermark drain (runs on the timer's Vert.x context)
    // ------------------------------------------------------------------------

    private void drain() {
        if (!active || shutdown) {
            return;
        }
        if (draining) {
            rescanPending = true;   // coalesce overlapping poll ticks
            return;
        }
        draining = true;
        long from = highWaterId;
        String sql = String.format("""
                SELECT id, payload, headers, created_at, status, correlation_id
                FROM %s
                WHERE topic = $1 AND id > $2
                ORDER BY id ASC
                LIMIT $3
                """, tableRef);
        pool.preparedQuery(sql)
                .execute(Tuple.of(topic, from, DRAIN_BATCH_LIMIT))
                .compose(this::pushAll)
                .onSuccess(pushed -> finishDrain(pushed == DRAIN_BATCH_LIMIT))
                .onFailure(err -> {
                    logger.error("tail: outbox drain failed for topic {} (will retry next poll): {}",
                            topic, err.getMessage());
                    finishDrain(false);
                });
    }

    /** Clears the single-flight guard and re-drains if the batch was full or a tick was coalesced. */
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
            Message<T> message = OutboxMessages.map(row, objectMapper, payloadType);
            if (message == null) {
                // Unparseable row: skip it but advance past it so the poll makes progress.
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
            advanceWatermark(id);   // advance even on handler failure: best-effort viewer, not delivery
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
            logger.error("tail: handler failed for outbox message {} on topic {} ({} consecutive failures): {}",
                    id, topic, failures, err.getMessage());
        } else {
            logger.warn("tail: handler failed for outbox message {} on topic {}: {}",
                    id, topic, err.getMessage());
        }
    }

    private Future<Long> readMaxId() {
        String sql = String.format("SELECT COALESCE(MAX(id), 0) AS hw FROM %s WHERE topic = $1", tableRef);
        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getLong("hw"));
    }
}
