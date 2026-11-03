package dev.mars.peegeeq.failover;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * The serving sequence on a writer whose control record holds withdrawn intent with a pending policy:
 * install the pending policy, confirm it, prepare the grant for its revision, publish {@code SERVING}
 * intent, and activate the matching grant. First-start bootstrap and the takeover cutover both run it.
 * A failure at any step stops the sequence; admission stays closed and the control record stays as the
 * failed step left it. The caller logs the failure.
 */
final class PgPolicyCutover {
    private PgPolicyCutover() {
    }

    static Future<PgControlRecord> serve(PgPrimaryElector elector, PgFailoverMonitor monitor, PgControlRecord held,
                                         PgFailoverMode mode) {
        return monitor.installPendingPolicy(held)
            .compose(ignored -> elector.update(held, confirmedIntent(held.intent())))
            .compose(confirmed -> monitor.prepareWriter(confirmed, mode)
                .compose(prepared -> elector.update(confirmed, servingIntent(confirmed.intent()))
                    .compose(serving -> monitor.activateWriter(serving, prepared).map(open -> serving))));
    }

    /** The pending policy becomes the confirmed policy. The phase stays withdrawn. */
    private static JsonObject confirmedIntent(JsonObject intent) {
        JsonObject pending = (JsonObject) intent.remove("pendingDurabilityPolicy");
        return intent.put("durabilityPolicy", pending);
    }

    private static JsonObject servingIntent(JsonObject intent) {
        return intent.put("phase", "SERVING");
    }
}
