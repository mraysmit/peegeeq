package dev.mars.peegeeq.sidecar;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone launcher for {@link PgPrimaryCheckVerticle}.
 *
 * <p>Reads configuration from system properties. Deploy one instance per PostgreSQL node.
 *
 * <pre>
 * java -Dpg.host=db-node-1 \
 *      -Dpg.port=5432 \
 *      -Dpg.database=postgres \
 *      -Dpg.user=haproxy_check \
 *      -Dpg.password= \
 *      -Dhttp.port=8008 \
 *      -jar peegeeq-pg-sidecar.jar
 * </pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-10
 */
public class PgPrimaryCheckMain {

    private static final Logger logger = LoggerFactory.getLogger(PgPrimaryCheckMain.class);

    public static void main(String[] args) {
        JsonObject config = new JsonObject()
                .put("pg.host",     System.getProperty("pg.host", "localhost"))
                .put("pg.port",     Integer.getInteger("pg.port", 5432))
                .put("pg.database", System.getProperty("pg.database", "postgres"))
                .put("pg.user",     System.getProperty("pg.user", "haproxy_check"))
                .put("pg.password", System.getProperty("pg.password", ""))
                .put("http.port",   Integer.getInteger("http.port", 8008));

        logger.info("Starting PgPrimaryCheckVerticle with config: pg={}:{}, http.port={}",
                config.getString("pg.host"),
                config.getInteger("pg.port"),
                config.getInteger("http.port"));

        Vertx vertx = Vertx.vertx();

        vertx.deployVerticle(
                new PgPrimaryCheckVerticle(),
                new DeploymentOptions().setConfig(config)
        ).onFailure(err -> {
            logger.error("Failed to start PgPrimaryCheckVerticle", err);
            vertx.close();
            System.exit(1);
        });
    }
}
