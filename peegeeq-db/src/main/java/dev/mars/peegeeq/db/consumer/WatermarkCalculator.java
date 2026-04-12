package dev.mars.peegeeq.db.consumer;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Computes and advances per-topic watermarks, then sweeps messages below the
 * watermark boundary to COMPLETED status.
 *
 * <p>The watermark for a topic is defined as MIN(committed_offset) across all
 * ACTIVE subscriptions. Messages with id &lt;= watermark are safe to mark as
 * COMPLETED because every active consumer group has committed past them.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-12
 * @version 1.0
 */
public class WatermarkCalculator {

    private static final Logger logger = LoggerFactory.getLogger(WatermarkCalculator.class);

    private final PgConnectionManager connectionManager;
    private final String serviceId;

    public WatermarkCalculator(PgConnectionManager connectionManager, String serviceId) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager cannot be null");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId cannot be null");
    }

    /**
     * Calculates the current watermark for a topic: MIN(committed_offset) across
     * all active subscriptions with offset records.
     *
     * @param topic the topic to calculate watermark for
     * @return the watermark value (0 if no active groups)
     */
    public Future<Long> calculateWatermark(String topic) {
        String sql = """
            SELECT COALESCE(MIN(o.committed_offset), 0) AS watermark
            FROM outbox_partition_offsets o
            INNER JOIN outbox_topic_subscriptions s
                ON o.topic = s.topic AND o.group_name = s.group_name
            WHERE o.topic = $1
              AND s.subscription_status = 'ACTIVE'
            """;
        return connectionManager.withConnection(serviceId, connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic))
                        .map(rows -> rows.iterator().next().getLong("watermark"))
        );
    }

    /**
     * Advances the stored watermark for a topic if the new value is higher.
     * Watermark only moves forward — never retreats.
     *
     * @param topic the topic to advance watermark for
     * @param newWatermark the candidate watermark value
     * @return the effective watermark after advance (may be unchanged if newWatermark &lt; current)
     */
    public Future<Long> advanceWatermark(String topic, long newWatermark) {
        // Upsert watermark row, only advancing forward
        String sql = """
            INSERT INTO outbox_topic_watermarks (topic, watermark_id, watermark_updated_at)
            VALUES ($1, $2, NOW())
            ON CONFLICT (topic) DO UPDATE
                SET watermark_id = GREATEST(outbox_topic_watermarks.watermark_id, EXCLUDED.watermark_id),
                    watermark_updated_at = CASE
                        WHEN EXCLUDED.watermark_id > outbox_topic_watermarks.watermark_id THEN NOW()
                        ELSE outbox_topic_watermarks.watermark_updated_at
                    END
            RETURNING watermark_id
            """;
        return connectionManager.withConnection(serviceId, connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, newWatermark))
                        .map(rows -> rows.iterator().next().getLong("watermark_id"))
        );
    }

    /**
     * Marks all PENDING/PROCESSING messages with id &lt;= watermark as COMPLETED.
     *
     * @param topic the topic to sweep
     * @param watermark the watermark boundary
     * @return the number of messages swept to COMPLETED
     */
    public Future<Integer> sweep(String topic, long watermark) {
        String sql = """
            UPDATE outbox SET status = 'COMPLETED'
            WHERE topic = $1
              AND id <= $2
              AND status IN ('PENDING', 'PROCESSING')
            """;
        return connectionManager.withConnection(serviceId, connection ->
                connection.preparedQuery(sql)
                        .execute(Tuple.of(topic, watermark))
                        .map(rows -> rows.rowCount())
        );
    }

    /**
     * Convenience method: calculates watermark, advances it, then sweeps.
     *
     * @param topic the topic to process
     * @return the number of messages swept to COMPLETED
     */
    public Future<Integer> calculateAndSweep(String topic) {
        return calculateWatermark(topic)
                .compose(wm -> advanceWatermark(topic, wm))
                .compose(effectiveWm -> sweep(topic, effectiveWm));
    }
}
