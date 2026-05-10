package dev.mars.peegeeq.sidecar;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
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
 *   → HTTP 200  when pg_is_in_recovery() = false  (this node is the write primary)
 *   → HTTP 503  when pg_is_in_recovery() = true   (replica) or PostgreSQL is unreachable
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
 *   <li>{@code pg.host}     — PostgreSQL host (default: localhost)</li>
 *   <li>{@code pg.port}     — PostgreSQL port (default: 5432)</li>
 *   <li>{@code pg.database} — database name (default: postgres)</li>
 *   <li>{@code pg.user}     — PostgreSQL user (default: haproxy_check)</li>
 *   <li>{@code pg.password} — password (default: empty)</li>
 *   <li>{@code http.port}   — HTTP listen port (default: 8008)</li>
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

    private void handlePrimaryCheck(io.vertx.core.http.HttpServerResponse response) {
        pool.query("SELECT pg_is_in_recovery()")
                .execute()
                .compose(rows -> {
                    boolean isReplica = rows.iterator().next().getBoolean(0);
                    int statusCode = isReplica ? 503 : 200;
                    logger.debug("pg_is_in_recovery={} → HTTP {}", isReplica, statusCode);
                    return Future.succeededFuture(statusCode);
                })
                .onSuccess(statusCode -> response.setStatusCode(statusCode).end())
                .onFailure(err -> {
                    logger.warn("pg_is_in_recovery() query failed: {}", err.getMessage());
                    response.setStatusCode(503).end();
                });
    }

    @Override
    public void stop(Promise<Void> stop) {
        if (pool != null) {
            pool.close()
                    .onSuccess(v -> stop.complete())
                    .onFailure(err -> {
                        logger.warn("Pool close failed during stop", err);
                        stop.complete();
                    });
        } else {
            stop.complete();
        }
    }
}
