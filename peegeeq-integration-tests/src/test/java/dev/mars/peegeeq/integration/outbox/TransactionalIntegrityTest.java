package dev.mars.peegeeq.integration.outbox;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.db.setup.PeeGeeQDatabaseSetupService;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@DisplayName("Transactional Integrity Tests")
public class TransactionalIntegrityTest extends SmokeTestBase {

    @Test
    @DisplayName("Verify rollback prevents message publishing")
    void testRollbackPreventsPublishing(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "outbox_integrity_queue";
        
        // 1. Create Setup
        JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);
        setupRequest.getJsonArray("queues").getJsonObject(0).put("type", "outbox");

        // 2. Setup Webhook
        ConcurrentLinkedQueue<JsonObject> receivedMessages = new ConcurrentLinkedQueue<>();
        int webhookPort = 9092 + (int)(Math.random() * 100);
        String webhookPath = "/webhook-integrity-" + setupId;
        
        HttpServer webhookServer = vertx.createHttpServer()
            .requestHandler(req -> {
                if (req.path().equals(webhookPath)) {
                    req.bodyHandler(body -> receivedMessages.add(body.toJsonObject()));
                    req.response().setStatusCode(200).end();
                }
            });

        webhookServer.listen(webhookPort)
            .compose(server -> webClient.post( "/api/v1/database-setup/create").sendJsonObject(setupRequest))
            .compose(r -> {
                // 3. Register Webhook
                return webClient.post( 
                        "/api/v1/setups/" + setupId + "/queues/" + queueName + "/webhook-subscriptions")
                    .sendJsonObject(new JsonObject().put("webhookUrl", "http://localhost:" + webhookPort + webhookPath));
            })
            .compose(r -> {
                // 4. Get Database Config via Reflection
                try {
                    Object actualService = setupService;
                    
                    // Unwrap RuntimeDatabaseSetupService if needed
                    if (actualService.getClass().getSimpleName().equals("RuntimeDatabaseSetupService")) {
                        Field delegateField = actualService.getClass().getDeclaredField("delegate");
                        delegateField.setAccessible(true);
                        actualService = delegateField.get(actualService);
                    }

                    Field configMapField = PeeGeeQDatabaseSetupService.class.getDeclaredField("setupDatabaseConfigs");
                    configMapField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, DatabaseConfig> configs = (Map<String, DatabaseConfig>) configMapField.get(actualService);
                    DatabaseConfig dbConfig = configs.get(setupId);
                    
                    if (dbConfig == null) {
                        return Future.failedFuture("Database config not found for setup: " + setupId);
                    }

                    // 5. Connect and Rollback
                    PgConnectOptions connectOptions = new PgConnectOptions()
                        .setPort(dbConfig.getPort())
                        .setHost(dbConfig.getHost())
                        .setDatabase(dbConfig.getDatabaseName())
                        .setUser(dbConfig.getUsername())
                        .setPassword(dbConfig.getPassword());

                    Pool pool = Pool.pool(vertx, connectOptions, new PoolOptions().setMaxSize(1));

                    return pool.getConnection()
                        .compose(conn -> conn.begin()
                            .compose(tx -> {
                                // Insert message manually
                                // Note: The table schema for outbox queue usually has 'payload' (jsonb) and 'correlation_id' (text)
                                String sql = "INSERT INTO " + queueName + " (topic, payload, correlation_id) VALUES ($1, $2, $3)";
                                return conn.preparedQuery(sql)
                                    .execute(Tuple.of(queueName, new JsonObject().put("data", "should-not-exist"), "rollback-1"))
                                    .compose(res -> {
                                        logger.info("Inserted message in transaction, now rolling back...");
                                        return tx.rollback();
                                    });
                            })
                            .eventually(() -> conn.close())
                        )
                        .eventually(() -> pool.close());
                } catch (Exception e) {
                    return Future.failedFuture(e);
                }
            })
            .onComplete(testContext.succeeding(v -> {
                // 6. Verify NO message received
                // Wait for 3 seconds to ensure the poller would have picked it up if it was committed
                vertx.setTimer(3000, id -> {
                    testContext.verify(() -> {
                        assertTrue(receivedMessages.isEmpty(), "Message should not have been delivered! Received: " + receivedMessages.size());
                        webhookServer.close();
                        cleanupSetup(setupId);
                        testContext.completeNow();
                    });
                });
            }));
    }

    private void cleanupSetup(String setupId) {
        webClient.delete( "/api/v1/setups/" + setupId)
            .send()
            .onFailure(err -> logger.warn("Failed to cleanup setup {}", setupId, err));
    }
}
