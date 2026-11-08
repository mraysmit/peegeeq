package dev.mars.peegeeq.sidecar;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x verticle that exposes a single HTTP endpoint for HAProxy primary detection.
 *
 * <pre>
 * GET /primary
 *    HTTP 200 "primary"              when pg_is_in_recovery() = false (this node is the write primary)
 *    HTTP 503 "replica"              when pg_is_in_recovery() = true
 *    HTTP 503 "unavailable: cause"   when the check query fails, for example PostgreSQL is unreachable
 * </pre>
 *
 * HAProxy configuration:
 * <pre>
 * backend pg_write
 *     option httpchk GET /primary
 *     server pg1 pg1:5432 check port 8008 inter 500ms fall 2 rise 1
 *     server pg2 pg2:5432 check port 8008 backup inter 500ms fall 2 rise 1
 * </pre>
 *
 * Configuration keys (read from verticle config JsonObject):
 * <ul>
 *   <li>{@code pg.host}      PostgreSQL host (default: localhost)</li>
 *   <li>{@code pg.port}      PostgreSQL port (default: 5432)</li>
 *   <li>{@code pg.database}  database name (default: postgres)</li>
 *   <li>{@code pg.user}      PostgreSQL user (default: haproxy_check)</li>
 *   <li>{@code pg.password}  password (default: empty)</li>
 *   <li>{@code http.port}    HTTP listen port (default: 8008)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-10
 */
public class PgPrimaryCheckVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(PgPrimaryCheckVerticle.class);

    private Pool pool;

    @Override
    public void start(Promise<Void> start) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(config().getString("pg.host", "localhost"))
                .setPort(config().getInteger("pg.port", 5432))
                .setDatabase(config().getString("pg.database", "postgres"))
                .setUser(config().getString("pg.user", "haproxy_check"))
                .setPassword(config().getString("pg.password", ""));

        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(2))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();

        int httpPort = config().getInteger("http.port", 8008);

        vertx.createHttpServer()
                .requestHandler(req -> {
                    if ("/primary".equals(req.path())) {
                        handlePrimaryCheck(req.response());
                    } else {
                        req.response().setStatusCode(404).end();
                    }
                })
                .listen(httpPort)
                .<Void>mapEmpty()
                .onSuccess(v -> {
                    logger.info("PgPrimaryCheckVerticle listening on port {}, pg={}:{}",
                            httpPort,
                            connectOptions.getHost(),
                            connectOptions.getPort());
                    start.complete();
                })
                .onFailure(start::fail);
    }

    /**
     * HAProxy reads the status only: 200 routes writes here, 503 routes them away. A replica and a
     * node whose check failed both answer 503, so the body separates them for an operator:
     * {@code primary}, {@code replica}, or {@code unavailable: <cause>} (finding 8).
     */
    private void handlePrimaryCheck(io.vertx.core.http.HttpServerResponse response) {
        pool.query("SELECT pg_is_in_recovery()")
                .execute()
                .map(rows -> Boolean.TRUE.equals(rows.iterator().next().getBoolean(0)))
                .onSuccess(isReplica -> {
                    int statusCode = isReplica ? 503 : 200;
                    logger.debug("pg_is_in_recovery={}  HTTP {}", isReplica, statusCode);
                    end(response, statusCode, isReplica ? "replica" : "primary");
                })
                .onFailure(err -> {
                    logger.warn("pg_is_in_recovery() query failed: {}", err.getMessage());
                    end(response, 503, "unavailable: " + err.getMessage());
                });
    }

    private static void end(io.vertx.core.http.HttpServerResponse response, int statusCode, String body) {
        response.setStatusCode(statusCode)
                .putHeader("content-type", "text/plain")
                .end(body)
                .onFailure(err -> logger.debug("Primary check response not delivered: {}", err.getMessage()));
    }

    @Override
    public void stop(Promise<Void> stop) {
        if (pool != null) {
            // A pool that failed to close fails the undeploy; it was reported as a clean stop before.
            pool.close()
                    .onSuccess(v -> stop.complete())
                    .onFailure(err -> {
                        logger.error("Pool close failed during stop", err);
                        stop.fail(err);
                    });
        } else {
            stop.complete();
        }
    }
}
