package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.EventStoreFactory;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.BiTemporalSubscriptionInfo;
import dev.mars.peegeeq.api.subscription.BiTemporalSubscriptionService;
import dev.mars.peegeeq.api.subscription.DurableSubscriptionCoordinator;
import dev.mars.peegeeq.api.subscription.SubscriptionState;
import dev.mars.peegeeq.db.util.PostgreSqlIdentifierValidator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Tenant-local durable definition, ordered replay, and typed live delivery.
 * Registration remains metadata-only. Live hints and periodic reconciliation use the same
 * committed cursor; the existing non-durable event-store path is unchanged.
 *
 * <p>The database is authoritative; no cursor cache survives or overrides committed state.
 * All writes use transactions and row locks. The manager owns the pool; close drains this
 * service's in-flight operations without closing that pool.</p>
 */
public final class DurableBiTemporalSubscriptionCoordinator implements BiTemporalSubscriptionService,
        DurableSubscriptionCoordinator<DurableBiTemporalSubscriptionCoordinator.SubscriptionKey> {
    private final Pool pool;
    private final String schema;
    private final String subscriptions;
    private final EventStoreFactory eventStoreFactory;
    private final Vertx vertx;
    private final Map<SubscriptionKey, DurableBiTemporalDelivery<?>> deliveries = new HashMap<>();
    private final Set<Future<?>> pending = new HashSet<>();
    private Future<Void> closing;

    private record Lease(UUID owner, long generation, int heartbeatSeconds) {}

    private static final class LeaseOwnedException extends IllegalStateException {
        LeaseOwnedException() { super("Subscription is owned by another replay or is not active"); }
    }

    /** One expiring owner per finite scan. Idle live consumers may safely remain standbys. */
    private Future<Lease> acquireLease(SubscriptionKey key) {
        UUID owner = UUID.randomUUID();
        return pool.withTransaction(connection -> connection.preparedQuery("UPDATE " + subscriptions
            + " SET lease_owner=$4, lease_generation=lease_generation+1, "
            + "lease_until=clock_timestamp()+make_interval(secs=>heartbeat_timeout_seconds), "
            + "last_heartbeat_at=clock_timestamp() WHERE " + predicate()
            + " AND subscription_status='ACTIVE' AND (lease_owner IS NULL OR lease_until<=clock_timestamp()) "
            + "RETURNING lease_generation, heartbeat_interval_seconds")
            .execute(key.parameters().addUUID(owner)).map(rows -> {
                if (rows.size() == 0) throw new LeaseOwnedException();
                Row row = rows.iterator().next();
                return new Lease(owner, row.getLong("lease_generation"), row.getInteger("heartbeat_interval_seconds"));
            }));
    }

    private Future<Void> renewLease(SubscriptionKey key, Lease lease) {
        return pool.withTransaction(connection -> connection.preparedQuery("UPDATE " + subscriptions
            + " SET lease_until=clock_timestamp()+make_interval(secs=>heartbeat_timeout_seconds), "
            + "last_heartbeat_at=clock_timestamp() WHERE " + predicate()
            + " AND lease_owner=$4 AND lease_generation=$5 AND lease_until>clock_timestamp() "
            + "AND subscription_status='ACTIVE'").execute(key.parameters().addUUID(lease.owner()).addLong(lease.generation()))
            .map(rows -> {
                if (rows.rowCount() != 1) throw new IllegalStateException("Durable replay lease lost");
                return (Void) null;
            }));
    }

    private Future<Void> releaseLease(SubscriptionKey key, Lease lease) {
        return pool.withTransaction(connection -> connection.preparedQuery("UPDATE " + subscriptions
            + " SET lease_owner=NULL, lease_until=NULL WHERE " + predicate()
            + " AND lease_owner=$4 AND lease_generation=$5")
            .execute(key.parameters().addUUID(lease.owner()).addLong(lease.generation())).mapEmpty());
    }

    private final class Renewal {
        private Future<Void> current = Future.succeededFuture();
        private final long timer;
        private boolean stopped;

        Renewal(SubscriptionKey key, Lease lease) {
            timer = vertx.setPeriodic(lease.heartbeatSeconds() * 1000L, id -> {
                synchronized (this) {
                    if (!stopped && current.isComplete() && current.succeeded()) {
                        current = renewLease(key, lease).onFailure(error -> {
                            vertx.cancelTimer(id);
                            org.slf4j.LoggerFactory.getLogger(DurableBiTemporalSubscriptionCoordinator.class)
                                .error("Durable lease renewal failed for {}", key, error);
                        });
                    }
                }
            });
        }

        synchronized Future<Void> stop() {
            stopped = true;
            vertx.cancelTimer(timer);
            return current;
        }
    }

    private Future<Void> withLease(SubscriptionKey key, Function<Lease, Future<Void>> action) {
        return acquireLease(key).compose(lease -> {
            Renewal renewal = new Renewal(key, lease);
            return Future.<Void>succeededFuture().compose(v -> action.apply(lease))
                .transform(result -> renewal.stop().transform(renewed -> releaseLease(key, lease).transform(released -> {
                    Throwable error = result.failed() ? result.cause() : renewed.failed() ? renewed.cause()
                        : released.failed() ? released.cause() : null;
                    if (error != null) {
                        if (renewed.failed() && renewed.cause() != error) error.addSuppressed(renewed.cause());
                        if (released.failed() && released.cause() != error) error.addSuppressed(released.cause());
                        return Future.failedFuture(error);
                    }
                    return Future.succeededFuture();
                })));
        });
    }

    private Future<Void> verifyLease(SqlConnection connection, SubscriptionKey key, Lease lease, boolean reset) {
        return connection.preparedQuery("SELECT lease_owner, lease_generation, "
            + "lease_until>clock_timestamp() AS valid FROM " + subscriptions + " WHERE " + predicate()
            + " FOR UPDATE").execute(key.parameters()).map(rows -> {
                if (rows.size() == 0) throw new NoSuchElementException("Subscription not found: " + key);
                Row row = rows.iterator().next();
                boolean valid = Boolean.TRUE.equals(row.getBoolean("valid"));
                if (lease != null && (!valid || !lease.owner().equals(row.getUUID("lease_owner"))
                        || lease.generation() != row.getLong("lease_generation"))) {
                    throw new IllegalStateException("Durable replay lease lost; stale owner cannot acknowledge");
                }
                if (lease == null && !reset && valid && row.getUUID("lease_owner") != null) throw new LeaseOwnedException();
                return (Void) null;
            });
    }

    /** Durable identity; unlike a delivery filter, all three components are mandatory. */
    public record SubscriptionKey(String tableName, String subscriptionName, String consumerGroup) {
        public SubscriptionKey {
            PostgreSqlIdentifierValidator.validate(tableName, "Event table");
            if (!tableName.equals(tableName.trim())) {
                throw new IllegalArgumentException("Event table must not contain surrounding whitespace");
            }
            requireName(subscriptionName, "Subscription name");
            requireName(consumerGroup, "Consumer group");
        }

        Tuple parameters() { return Tuple.of(tableName, subscriptionName, consumerGroup); }
    }

    DurableBiTemporalSubscriptionCoordinator(Pool pool, String schema, EventStoreFactory eventStoreFactory, Vertx vertx) {
        this.pool = Objects.requireNonNull(pool, "Pool is required");
        this.vertx = Objects.requireNonNull(vertx, "Vertx is required");
        this.eventStoreFactory = Objects.requireNonNull(eventStoreFactory, "Event store factory is required");
        PostgreSqlIdentifierValidator.validate(schema, "Tenant schema");
        this.schema = PostgreSqlIdentifierValidator.quote(schema.trim());
        this.subscriptions = this.schema + ".bitemporal_subscriptions";
    }

    @Override
    public <T> Future<Void> subscribe(String table, String name, String group, String eventType,
            String aggregateId, Class<T> payloadType, MessageHandler<BiTemporalEvent<T>> handler,
            SubscriptionOptions options) {
        return operation(v -> {
            var key = new SubscriptionKey(table, name, group);
            Objects.requireNonNull(payloadType, "Payload type is required");
            Objects.requireNonNull(handler, "Handler is required");
            Objects.requireNonNull(options, "Options are required");
            if (options.getReplayBatchSize() > 10_000) throw new IllegalArgumentException("Replay batch size exceeds 10000");
            synchronized (this) {
                if (deliveries.containsKey(key)) throw new IllegalStateException("Handler already registered for " + key);
                var delivery = new DurableBiTemporalDelivery<>(vertx, this, key,
                    eventStoreFactory.createEventStore(payloadType, table), handler, options.getReplayBatchSize());
                deliveries.put(key, delivery);
                return registerDefinition(table, name, group, eventType, aggregateId, options)
                    .compose(ignored -> delivery.start()).transform(started -> {
                        if (started.succeeded()) return Future.succeededFuture();
                        synchronized (this) { deliveries.remove(key, delivery); }
                        return delivery.close().transform(closed -> {
                            if (closed.failed()) started.cause().addSuppressed(closed.cause());
                            return Future.failedFuture(started.cause());
                        });
                    });
            }
        });
    }

    @Override
    public Future<Void> deliveryCompletion(String table, String name, String group) {
        synchronized (this) {
            var delivery = deliveries.get(new SubscriptionKey(table, name, group));
            return delivery == null ? Future.failedFuture(new NoSuchElementException("No registered handler"))
                : delivery.completion();
        }
    }

    <T> Future<Void> replay(SubscriptionKey key, EventStore<T> events,
            MessageHandler<BiTemporalEvent<T>> handler, int batchSize) {
        return find(pool, key, false).compose(info -> info.isActive()
            ? withLease(key, lease -> pool.withTransaction(connection -> stableMaximumId(connection, key))
                .compose(boundary -> replayPage(key, events, handler, batchSize, boundary, lease)))
                .transform(result -> result.cause() instanceof LeaseOwnedException ? Future.succeededFuture()
                    : result.failed() ? Future.failedFuture(result.cause()) : Future.succeededFuture())
            : Future.succeededFuture());
    }

    @Override
    public <T> Future<Void> catchUp(String table, String name, String group, Class<T> payloadType,
            MessageHandler<BiTemporalEvent<T>> handler, int batchSize) {
        return operation(v -> {
            var key = new SubscriptionKey(table, name, group);
            Objects.requireNonNull(payloadType, "Payload type is required");
            Objects.requireNonNull(handler, "Handler is required");
            if (batchSize < 1 || batchSize > 10_000) {
                throw new IllegalArgumentException("Replay batch size must be between 1 and 10000");
            }
            EventStore<T> events = eventStoreFactory.createEventStore(payloadType, table);
            return withLease(key, lease -> pool.withTransaction(connection -> stableMaximumId(connection, key))
                .compose(boundary -> replayPage(key, events, handler, batchSize, boundary, lease)))
                .transform(result -> events.close().transform(closed -> {
                    if (result.failed()) {
                        if (closed.failed()) result.cause().addSuppressed(closed.cause());
                        return Future.failedFuture(result.cause());
                    }
                    return closed.failed() ? Future.failedFuture(closed.cause()) : Future.succeededFuture();
                }));
        });
    }

    private <T> Future<Void> replayPage(SubscriptionKey key, EventStore<T> events,
            MessageHandler<BiTemporalEvent<T>> handler, int batchSize, long boundary, Lease lease) {
        return find(pool, key, false).compose(info -> {
            if (!info.isActive()) return Future.failedFuture(new IllegalStateException("Subscription is not active"));
            validatePosition(cursor(info), boundary);
            return pool.preparedQuery("SELECT id, event_id, event_type, aggregate_id FROM " + eventTable(key)
                + " WHERE id > $1 AND id <= $2 ORDER BY id LIMIT $3")
                .execute(Tuple.of(cursor(info), boundary, batchSize)).compose(rows -> {
                    Future<Void> batch = Future.succeededFuture();
                    for (Row row : rows) {
                        batch = batch.compose(v -> pool.withTransaction(connection -> verifyLease(connection, key, lease, false)))
                            .compose(v -> {
                            Future<Void> delivered = Future.succeededFuture();
                            if (matches(info, row)) {
                                delivered = events.getById(row.getString("event_id")).compose(event -> {
                                    Objects.requireNonNull(event, "Persisted replay event is missing");
                                    return Objects.requireNonNull(handler.handle(new SimpleMessage<>(event.getEventId(),
                                        "bitemporal_events", event, event.getHeaders(), event.getCorrelationId(),
                                        key.consumerGroup(), event.getTransactionTime())), "Handler returned a null Future");
                                });
                            }
                            return delivered.compose(ignored -> pool.withTransaction(connection ->
                                writeCursor(connection, key, row.getLong("id"), false, lease)));
                        });
                    }
                    return batch.compose(v -> rows.size() == batchSize
                        ? replayPage(key, events, handler, batchSize, boundary, lease) : Future.succeededFuture());
                });
        });
    }

    private String eventTable(SubscriptionKey key) {
        return schema + "." + PostgreSqlIdentifierValidator.quote(key.tableName());
    }

    /**
     * A short READ COMMITTED writer barrier, released before fetching/handling the backlog.
     * Supports the standard append-only table: ascending CACHE 1 sequence, IDs allocated by
     * INSERT. Explicit/preallocated IDs and sequence resets are not supported for durable replay.
     * Waiting for writers is bounded; failure is propagated, never treated as an empty history.
     */
    private Future<Long> stableMaximumId(SqlConnection connection, SubscriptionKey key) {
        return connection.query("SET TRANSACTION ISOLATION LEVEL READ COMMITTED").execute()
            .compose(v -> connection.query("SET LOCAL lock_timeout='5s'").execute())
            .compose(v -> connection.query("LOCK TABLE " + eventTable(key) + " IN SHARE MODE").execute())
            .compose(v -> connection.preparedQuery("SELECT seqcache, seqincrement, seqcycle FROM pg_sequence "
                + "WHERE seqrelid=pg_get_serial_sequence($1, 'id')::regclass")
                .execute(Tuple.of(eventTable(key))))
            .compose(rows -> {
                if (rows.size() != 1) throw new IllegalStateException("Durable replay requires the event ID sequence");
                Row sequence = rows.iterator().next();
                if (sequence.getLong("seqcache") != 1 || sequence.getLong("seqincrement") <= 0
                        || sequence.getBoolean("seqcycle")) {
                    throw new IllegalStateException("Durable replay requires an ascending, non-cycling CACHE 1 sequence");
                }
                return maximumId(connection, key);
            });
    }

    /** Same dot-segment wildcard semantics as ReactiveNotificationHandler. */
    private static boolean matches(BiTemporalSubscriptionInfo info, Row row) {
        if (info.aggregateId() != null && !info.aggregateId().equals(row.getString("aggregate_id"))) return false;
        if (info.eventType() == null) return true;
        String[] pattern = info.eventType().split("\\.");
        String[] actual = row.getString("event_type").split("\\.");
        if (pattern.length != actual.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (!"*".equals(pattern[i]) && !pattern[i].equals(actual[i])) return false;
        }
        return true;
    }

    @Override
    public <T> Future<Void> subscribe(String table, String name, String group, String eventType,
            String aggregateId, MessageHandler<BiTemporalEvent<T>> handler, SubscriptionOptions options) {
        return operation(v -> Future.failedFuture(new UnsupportedOperationException(
            "Use the typed subscribe overload with an explicit payload Class; registerDefinition is metadata only")));
    }

    @Override
    public Future<Void> registerDefinition(String table, String name, String group, String eventType,
            String aggregateId, SubscriptionOptions options) {
        return operation(v -> {
            var key = new SubscriptionKey(table, name, group);
            Objects.requireNonNull(options, "Durable subscription options are required");
            if (!options.isDurableEnabled() || !name.equals(options.getSubscriptionName())) {
                throw new IllegalArgumentException("Durable options must name the same subscription");
            }
            if ((eventType != null && eventType.length() > 255)
                    || (aggregateId != null && aggregateId.length() > 255)) {
                throw new IllegalArgumentException("Subscription filters must not exceed 255 characters");
            }
            return pool.withTransaction(connection -> (options.getStartPosition()
                    == dev.mars.peegeeq.api.messaging.StartPosition.FROM_NOW
                ? stableMaximumId(connection, key) : maximumId(connection, key)).compose(max -> {
                long initial = switch (options.getStartPosition()) {
                    case FROM_BEGINNING -> 0;
                    case FROM_NOW -> max;
                    case FROM_MESSAGE_ID -> options.getStartFromMessageId();
                    case FROM_TIMESTAMP -> throw new IllegalArgumentException(
                        "Durable bitemporal persistence requires an event ID, not a timestamp");
                };
                validatePosition(initial, max);
                var parameters = key.parameters().addString(eventType).addString(aggregateId)
                    .addLong(initial).addInteger(options.getHeartbeatIntervalSeconds())
                    .addInteger(options.getHeartbeatTimeoutSeconds());
                return connection.preparedQuery("INSERT INTO " + subscriptions + " (table_name, "
                    + "subscription_name, consumer_group, event_type, aggregate_id, start_from_event_id, "
                    + "last_processed_id, heartbeat_interval_seconds, heartbeat_timeout_seconds) "
                    + "VALUES ($1,$2,$3,$4,$5,$6,$6,$7,$8) "
                    + "ON CONFLICT (table_name, subscription_name, consumer_group) DO NOTHING")
                    .execute(parameters).compose(ignored -> find(connection, key, true))
                    .compose(info -> {
                        requireResumable(info);
                        if (!Objects.equals(eventType, info.eventType())
                                || !Objects.equals(aggregateId, info.aggregateId())) {
                            throw new IllegalArgumentException("Existing subscription filters cannot be changed");
                        }
                        return connection.preparedQuery("UPDATE " + subscriptions
                            + " SET subscription_status='ACTIVE', last_active_at=clock_timestamp(), "
                            + "last_heartbeat_at=clock_timestamp(), heartbeat_interval_seconds=$4, "
                            + "heartbeat_timeout_seconds=$5 WHERE " + predicate())
                            .execute(key.parameters().addInteger(options.getHeartbeatIntervalSeconds())
                                .addInteger(options.getHeartbeatTimeoutSeconds())).mapEmpty();
                    });
            }));
        });
    }

    @Override
    public Future<BiTemporalSubscriptionInfo> getSubscription(String table, String name, String group) {
        return operation(v -> find(pool, new SubscriptionKey(table, name, group), false));
    }

    @Override
    public Future<List<BiTemporalSubscriptionInfo>> listSubscriptions(String table) {
        return operation(v -> {
            PostgreSqlIdentifierValidator.validate(table, "Event table");
            return pool.preparedQuery("SELECT * FROM " + subscriptions + " WHERE table_name=$1 ORDER BY id")
                .execute(Tuple.of(table)).map(rows -> {
                    List<BiTemporalSubscriptionInfo> result = new ArrayList<>();
                    rows.forEach(row -> result.add(metadata(row)));
                    return List.copyOf(result);
                });
        });
    }

    @Override
    public Future<Void> loadActiveSubscriptionDefinitions() {
        return operation(v -> pool.query("SELECT * FROM " + subscriptions
            + " WHERE subscription_status='ACTIVE' ORDER BY id").execute().compose(rows -> {
                Future<Void> validated = Future.succeededFuture();
                for (Row row : rows) {
                    var info = metadata(row);
                    var key = new SubscriptionKey(info.tableName(), info.subscriptionName(), info.consumerGroup());
                    validated = validated.compose(ignored -> maximumId(pool, key).map(max -> {
                        validatePosition(cursor(info), max);
                        return (Void) null;
                    }));
                }
                return validated;
            }));
    }

    @Override
    public Future<Long> getCurrentCursor(SubscriptionKey key) {
        return operation(v -> find(pool, key, false).map(DurableBiTemporalSubscriptionCoordinator::cursor));
    }

    @Override
    public Future<Void> advanceCursor(SubscriptionKey key, long value) {
        return operation(v -> pool.withTransaction(connection -> writeCursor(connection, key, value, false)));
    }

    /**
     * Enlists cursor advancement in a caller-owned transaction. The caller must use a
     * connection belonging to this tenant's database and observe its commit/rollback.
     */
    public Future<Void> advanceCursor(SqlConnection connection, SubscriptionKey key, long value) {
        return operation(v -> {
            if (connection.transaction() == null) {
                throw new IllegalStateException("Cursor advancement requires an active transaction");
            }
            return writeCursor(connection, key, value, false);
        });
    }

    @Override
    public Future<Void> resetCursor(SubscriptionKey key, long value) {
        return operation(v -> pool.withTransaction(connection -> writeCursor(connection, key, value, true)));
    }

    @Override
    public Future<Void> resetCursor(String table, String name, String group, long value) {
        return operation(v -> pool.withTransaction(connection ->
            writeCursor(connection, new SubscriptionKey(table, name, group), value, true)));
    }

    private Future<Void> writeCursor(SqlConnection connection, SubscriptionKey key, long value, boolean reset) {
        return writeCursor(connection, key, value, reset, null);
    }

    private Future<Void> writeCursor(SqlConnection connection, SubscriptionKey key, long value, boolean reset, Lease lease) {
        return verifyLease(connection, key, lease, reset).compose(v -> find(connection, key, true)).compose(info -> {
            requireResumable(info);
            if (!reset && !info.isActive()) {
                throw new IllegalStateException("Cannot acknowledge a paused subscription");
            }
            if (!reset && value < cursor(info)) {
                throw new IllegalArgumentException("Cursor cannot advance backwards");
            }
            return maximumId(connection, key).compose(max -> {
                validatePosition(value, max);
                return connection.preparedQuery("UPDATE " + subscriptions
                    + " SET last_processed_id=$4, last_processed_at=clock_timestamp()"
                    + (reset ? ", lease_owner=NULL, lease_until=NULL, lease_generation=lease_generation+1" : "")
                    + " WHERE " + predicate())
                    .execute(key.parameters().addLong(value)).mapEmpty();
            });
        });
    }

    @Override
    public Future<Void> pause(String table, String name, String group) {
        return operation(v -> changeState(new SubscriptionKey(table, name, group), SubscriptionState.PAUSED));
    }

    @Override
    public Future<Void> resume(String table, String name, String group) {
        return operation(v -> changeState(new SubscriptionKey(table, name, group), SubscriptionState.ACTIVE));
    }

    @Override
    public Future<Void> cancel(String table, String name, String group) {
        return operation(v -> changeState(new SubscriptionKey(table, name, group), SubscriptionState.CANCELLED));
    }

    @Override
    public Future<Void> pauseSubscription(SubscriptionKey key) {
        return operation(v -> changeState(key, SubscriptionState.PAUSED));
    }

    @Override
    public Future<Void> resumeSubscription(SubscriptionKey key) {
        return operation(v -> changeState(key, SubscriptionState.ACTIVE));
    }

    private Future<Void> changeState(SubscriptionKey key, SubscriptionState target) {
        DurableBiTemporalDelivery<?> delivery;
        synchronized (this) { delivery = deliveries.get(key); }
        Future<Void> quiesced = delivery == null || target == SubscriptionState.ACTIVE
            ? Future.succeededFuture() : delivery.suspend();
        return quiesced.compose(v -> pool.withTransaction(connection -> find(connection, key, true).compose(info -> {
            if (target != SubscriptionState.CANCELLED) {
                requireResumable(info);
            }
            return connection.preparedQuery("UPDATE " + subscriptions
                + " SET subscription_status=$4::varchar, last_active_at=CASE WHEN $4::varchar='ACTIVE' "
                + "THEN clock_timestamp() ELSE last_active_at END"
                + (target == SubscriptionState.ACTIVE ? "" : ", lease_owner=NULL, lease_until=NULL, lease_generation=lease_generation+1")
                + " WHERE " + predicate())
                .execute(key.parameters().addString(target.name())).mapEmpty();
        }))).compose(v -> delivery == null ? Future.succeededFuture()
            : target == SubscriptionState.ACTIVE ? delivery.resume()
            : target == SubscriptionState.CANCELLED ? delivery.close() : Future.succeededFuture());
    }

    @Override
    public Future<Void> updateHeartbeat(String table, String name, String group) {
        return operation(v -> {
            var key = new SubscriptionKey(table, name, group);
            return pool.withTransaction(connection -> find(connection, key, true).compose(info -> {
                requireResumable(info);
                return connection.preparedQuery("UPDATE " + subscriptions
                    + " SET last_heartbeat_at=clock_timestamp() WHERE " + predicate())
                    .execute(key.parameters()).mapEmpty();
            }));
        });
    }

    private Future<BiTemporalSubscriptionInfo> find(SqlClient client, SubscriptionKey key, boolean lock) {
        Objects.requireNonNull(key, "Subscription key is required");
        return client.preparedQuery("SELECT * FROM " + subscriptions + " WHERE " + predicate()
            + (lock ? " FOR UPDATE" : "")).execute(key.parameters()).map(rows -> {
                if (!rows.iterator().hasNext()) {
                    throw new NoSuchElementException("Subscription not found: " + key);
                }
                return metadata(rows.iterator().next());
            });
    }

    private Future<Long> maximumId(SqlClient client, SubscriptionKey key) {
        return client.query("SELECT COALESCE(MAX(id),0) AS maximum FROM " + schema + "."
            + PostgreSqlIdentifierValidator.quote(key.tableName())).execute()
            .map(rows -> rows.iterator().next().getLong("maximum"));
    }

    private static String predicate() {
        return "table_name=$1 AND subscription_name=$2 AND consumer_group=$3";
    }

    private static void requireName(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException(label + " must contain 1 to 255 characters");
        }
    }

    private static void requireResumable(BiTemporalSubscriptionInfo info) {
        if (info.state() == SubscriptionState.CANCELLED || info.state() == SubscriptionState.DEAD) {
            throw new IllegalStateException("Subscription is " + info.state());
        }
    }

    private static void validatePosition(long value, long maximum) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException("Cursor must be between zero and the event table maximum ID");
        }
    }

    private static long cursor(BiTemporalSubscriptionInfo info) {
        return info.lastProcessedId() != null ? info.lastProcessedId()
            : info.startFromEventId() != null ? info.startFromEventId() : 0L;
    }

    private static Instant instant(Row row, String column) {
        var value = row.getOffsetDateTime(column);
        return value == null ? null : value.toInstant();
    }

    private static BiTemporalSubscriptionInfo metadata(Row row) {
        return new BiTemporalSubscriptionInfo(row.getLong("id"), row.getString("table_name"),
            row.getString("subscription_name"), row.getString("consumer_group"), row.getString("event_type"),
            row.getString("aggregate_id"), SubscriptionState.valueOf(row.getString("subscription_status")),
            row.getLong("start_from_event_id"), row.getLong("last_processed_id"), instant(row, "subscribed_at"),
            instant(row, "last_active_at"), instant(row, "last_processed_at"),
            row.getInteger("heartbeat_interval_seconds"), row.getInteger("heartbeat_timeout_seconds"),
            instant(row, "last_heartbeat_at"));
    }

    private synchronized <T> Future<T> operation(Function<Void, Future<T>> action) {
        if (closing != null) {
            return Future.failedFuture(new IllegalStateException("Subscription service is closed"));
        }
        Future<T> result = Future.<Void>succeededFuture().compose(action);
        pending.add(result);
        return result.andThen(ignored -> {
            synchronized (this) { pending.remove(result); }
        });
    }

    @Override
    public synchronized Future<Void> close() {
        if (closing == null) {
            List<Future<?>> operations = new ArrayList<>(pending);
            deliveries.values().forEach(delivery -> operations.add(delivery.close()));
            closing = Future.join(operations).mapEmpty();
        }
        return closing;
    }
}
