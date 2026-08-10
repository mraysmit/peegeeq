/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
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
package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.error.PeeGeeQError;
import dev.mars.peegeeq.api.error.PeeGeeQErrorCodes;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.api.setup.SetupNotFoundException;
import dev.mars.peegeeq.rest.error.ErrorResponse;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;

/**
 * Handler for the database-level telemetry endpoint (telemetry requirements §4A, gap G7).
 *
 * <p>PeeGeeQ is Postgres-backed, so under load the limiting factor is usually the database:
 * enqueue/dequeue churn produces dead tuples, dead tuples produce bloat, bloat produces
 * autovacuum load and scan degradation. None of that is visible in app-level counters —
 * it must be read from the database's own statistics views. This handler exposes one
 * snapshot per call:
 *
 * <ul>
 *   <li>GET /api/v1/setups/:setupId/db-telemetry — per-table churn/vacuum/scan/IO/size
 *       stats for every table in the setup's schema, plus cluster-level signals
 *       (long-running transaction, {@code xmin} holders, locks, WAL, checkpoints,
 *       xid age, commit/rollback/deadlock counters).</li>
 * </ul>
 *
 * <p><b>Sampling contract.</b> The {@code pg_stat_*} counters are cumulative since the last
 * stats reset. This endpoint returns raw snapshots; the consumer baselines at run start and
 * reports deltas over the run window (telemetry §4A "Deltas"). These queries are heavier
 * than a counter read — sample at roughly 5 s intervals, not 1 Hz (telemetry §4A
 * "Cost/cadence").
 *
 * <p><b>Clock contract.</b> {@code sampledAt} is the REST server's clock at the moment the
 * sample was taken, for timeline plotting only. All counters are the database's own
 * cumulative statistics; {@code longestTxnSeconds} is computed on the database clock.
 *
 * <p><b>Absence contract.</b> Fields whose value is unknown are omitted, never zeroed:
 * {@code lastVacuum}/{@code lastAutovacuum}/{@code lastAutoanalyze} are absent when the
 * table has never been vacuumed/analyzed, {@code idxScan} is absent when the table has no
 * indexes, and {@code longestTxnSeconds} is absent when no transaction is in progress —
 * a zero would claim a transaction of age zero exists.
 *
 * <p><b>Error contract.</b> Failures surface as HTTP errors (404 unknown setup, 503 query
 * failure). No fabricated zero/empty payloads on failure.
 *
 * <p>Column set verified against PostgreSQL 15 ({@code pg_stat_wal} needs 14+;
 * checkpoint counters read from {@code pg_stat_bgwriter}, which PostgreSQL 17 moves to
 * {@code pg_stat_checkpointer}).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-06
 */
public class DatabaseTelemetryHandler {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseTelemetryHandler.class);

    /**
     * Per-table churn/vacuum/scan/IO/size stats for every table in the given schema ($1).
     * One row per table; the consumer filters to the tables it cares about
     * (queue_messages, outbox, dead_letter_queue, per-queue tables, processed_ledger).
     */
    private static final String TABLE_STATS_SQL =
            "SELECT s.relname, " +
            "       s.n_tup_ins, s.n_tup_upd, s.n_tup_del, s.n_tup_hot_upd, " +
            "       s.n_live_tup, s.n_dead_tup, " +
            "       s.seq_scan, s.idx_scan, " +
            "       s.vacuum_count, s.autovacuum_count, " +
            "       s.last_vacuum, s.last_autovacuum, s.last_autoanalyze, " +
            "       io.heap_blks_hit, io.heap_blks_read, " +
            "       pg_relation_size(s.relid)       AS heap_bytes, " +
            "       pg_indexes_size(s.relid)        AS index_bytes, " +
            "       pg_total_relation_size(s.relid) AS total_bytes " +
            "FROM pg_stat_user_tables s " +
            "JOIN pg_statio_user_tables io ON io.relid = s.relid " +
            "WHERE s.schemaname = $1 " +
            "ORDER BY s.relname";

    /**
     * Cluster/database-wide signals in one round trip. {@code longest_txn_seconds} covers
     * non-idle sessions on this database (including idle-in-transaction, the classic vacuum
     * blocker); lock counts are scoped to this database; WAL and checkpoint counters are
     * instance-wide single-row views.
     */
    private static final String CLUSTER_STATS_SQL =
            "SELECT " +
            "  (SELECT EXTRACT(EPOCH FROM (now() - MIN(xact_start)))::bigint " +
            "     FROM pg_stat_activity " +
            "     WHERE datname = current_database() AND state <> 'idle') AS longest_txn_seconds, " +
            "  (SELECT COUNT(*)::int FROM pg_stat_activity " +
            "     WHERE datname = current_database() AND backend_xmin IS NOT NULL) AS backends_holding_xmin, " +
            "  (SELECT COUNT(*)::int FROM pg_locks l JOIN pg_database db ON l.database = db.oid " +
            "     WHERE db.datname = current_database()) AS locks_total, " +
            "  (SELECT COUNT(*)::int FROM pg_locks l JOIN pg_database db ON l.database = db.oid " +
            "     WHERE db.datname = current_database() AND NOT l.granted) AS locks_waiting, " +
            "  (SELECT age(datfrozenxid) FROM pg_database WHERE datname = current_database()) AS xid_age, "
            // NOTIFY queue usage (telemetry G3, moved from system_stats 2026-08-09): the native
            // queue signals consumers with NOTIFY, and when this fixed-size INSTANCE-wide buffer
            // fills, NOTIFY blocks committing transactions. The fraction is 0..1 and always
            // reportable — PostgreSQL is the producer; this endpoint only reads.
            + "  pg_notification_queue_usage()::float8 AS notify_queue_usage, " +
            "  (SELECT wal_records FROM pg_stat_wal) AS wal_records, " +
            "  (SELECT wal_bytes::bigint FROM pg_stat_wal) AS wal_bytes, " +
            "  pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint AS wal_lsn_bytes, " +
            "  (SELECT checkpoints_timed FROM pg_stat_bgwriter) AS checkpoints_timed, " +
            "  (SELECT checkpoints_req FROM pg_stat_bgwriter) AS checkpoints_requested, " +
            "  (SELECT buffers_checkpoint FROM pg_stat_bgwriter) AS buffers_checkpoint, " +
            "  d.xact_commit, d.xact_rollback, d.deadlocks, " +
            "  d.tup_returned, d.tup_fetched, d.numbackends, " +
            "  d.blks_hit, d.blks_read " +
            "FROM pg_stat_database d " +
            "WHERE d.datname = current_database()";

    private final DatabaseSetupService setupService;
    private final Vertx vertx;

    public DatabaseTelemetryHandler(DatabaseSetupService setupService, Vertx vertx) {
        this.setupService = setupService;
        this.vertx = vertx;
    }

    /**
     * Returns one database-level telemetry snapshot for the setup's database.
     * GET /api/v1/setups/:setupId/db-telemetry
     */
    public void getDatabaseTelemetry(RoutingContext ctx) {
        String setupId = ctx.pathParam("setupId");

        setupService.getDatabaseConfig(setupId)
            .compose(dbConfig -> {
                PgConnectOptions connectOptions = new PgConnectOptions()
                        .setHost(dbConfig.getHost())
                        .setPort(dbConfig.getPort())
                        .setDatabase(dbConfig.getDatabaseName())
                        .setUser(dbConfig.getUsername())
                        .setPassword(dbConfig.getPassword());

                Pool tempPool = PgBuilder.pool()
                        .with(new PoolOptions().setMaxSize(1))
                        .connectingTo(connectOptions)
                        .using(vertx)
                        .build();

                long sampledAt = System.currentTimeMillis();
                String schema = dbConfig.getSchema();

                return tempPool.preparedQuery(TABLE_STATS_SQL)
                        .execute(Tuple.of(schema))
                        .compose(tableRows -> tempPool.preparedQuery(CLUSTER_STATS_SQL)
                                .execute()
                                .map(clusterRows -> buildSnapshot(
                                        setupId, dbConfig.getDatabaseName(), schema,
                                        sampledAt, tableRows, clusterRows)))
                        .eventually(() -> tempPool.close());
            })
            .onSuccess(snapshot -> ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(snapshot.encode()))
            .onFailure(error -> {
                if (error instanceof SetupNotFoundException) {
                    ErrorResponse.notFound(ctx, PeeGeeQError.setupNotFound(setupId));
                    return;
                }
                logger.error("Failed to collect database telemetry for setup: {}", setupId, error);
                ErrorResponse.send(ctx, 503,
                        PeeGeeQError.of(PeeGeeQErrorCodes.DATABASE_QUERY_FAILED,
                                "Failed to collect database telemetry: " + error.getMessage()));
            });
    }

    private JsonObject buildSnapshot(String setupId, String databaseName, String schema,
                                     long sampledAt, RowSet<Row> tableRows, RowSet<Row> clusterRows) {
        JsonArray tables = new JsonArray();
        for (Row row : tableRows) {
            tables.add(tableJson(row));
        }
        return new JsonObject()
                .put("setupId", setupId)
                .put("databaseName", databaseName)
                .put("schema", schema)
                .put("sampledAt", sampledAt)
                .put("tables", tables)
                .put("cluster", clusterJson(clusterRows.iterator().next()));
    }

    private JsonObject tableJson(Row row) {
        JsonObject table = new JsonObject()
                .put("tableName", row.getString("relname"))
                .put("nTupIns", row.getLong("n_tup_ins"))
                .put("nTupUpd", row.getLong("n_tup_upd"))
                .put("nTupDel", row.getLong("n_tup_del"))
                .put("nTupHotUpd", row.getLong("n_tup_hot_upd"))
                .put("nLiveTup", row.getLong("n_live_tup"))
                .put("nDeadTup", row.getLong("n_dead_tup"))
                .put("seqScan", row.getLong("seq_scan"))
                .put("vacuumCount", row.getLong("vacuum_count"))
                .put("autovacuumCount", row.getLong("autovacuum_count"))
                .put("heapBlksHit", row.getLong("heap_blks_hit"))
                .put("heapBlksRead", row.getLong("heap_blks_read"))
                .put("heapBytes", row.getLong("heap_bytes"))
                .put("indexBytes", row.getLong("index_bytes"))
                .put("totalBytes", row.getLong("total_bytes"));
        // Absent, not zeroed: idx_scan is NULL for tables without indexes; the last_* stamps
        // are NULL until the first (auto)vacuum/analyze has run.
        putIfNotNull(table, "idxScan", row.getLong("idx_scan"));
        putTimestampIfNotNull(table, "lastVacuum", row.getOffsetDateTime("last_vacuum"));
        putTimestampIfNotNull(table, "lastAutovacuum", row.getOffsetDateTime("last_autovacuum"));
        putTimestampIfNotNull(table, "lastAutoanalyze", row.getOffsetDateTime("last_autoanalyze"));
        return table;
    }

    private JsonObject clusterJson(Row row) {
        JsonObject cluster = new JsonObject()
                .put("backendsHoldingXmin", row.getInteger("backends_holding_xmin"))
                .put("locksTotal", row.getInteger("locks_total"))
                .put("locksWaiting", row.getInteger("locks_waiting"))
                .put("xidAge", row.getInteger("xid_age"))
                .put("notifyQueueUsage", row.getDouble("notify_queue_usage"))
                .put("walRecords", row.getLong("wal_records"))
                .put("walBytes", row.getLong("wal_bytes"))
                .put("walLsnBytes", row.getLong("wal_lsn_bytes"))
                .put("checkpointsTimed", row.getLong("checkpoints_timed"))
                .put("checkpointsRequested", row.getLong("checkpoints_requested"))
                .put("buffersCheckpoint", row.getLong("buffers_checkpoint"))
                .put("xactCommit", row.getLong("xact_commit"))
                .put("xactRollback", row.getLong("xact_rollback"))
                .put("deadlocks", row.getLong("deadlocks"))
                .put("tupReturned", row.getLong("tup_returned"))
                .put("tupFetched", row.getLong("tup_fetched"))
                .put("numbackends", row.getInteger("numbackends"))
                .put("blksHit", row.getLong("blks_hit"))
                .put("blksRead", row.getLong("blks_read"));
        // Absent when no transaction is in progress on this database.
        putIfNotNull(cluster, "longestTxnSeconds", row.getLong("longest_txn_seconds"));
        return cluster;
    }

    private static void putIfNotNull(JsonObject target, String key, Long value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putTimestampIfNotNull(JsonObject target, String key, OffsetDateTime value) {
        if (value != null) {
            target.put(key, value.toInstant().toString());
        }
    }
}
