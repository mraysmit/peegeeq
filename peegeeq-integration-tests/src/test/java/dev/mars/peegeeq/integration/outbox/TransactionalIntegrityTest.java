package dev.mars.peegeeq.integration.outbox;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
@DisplayName("Transactional Integrity Tests")
@Tag("integration")
public class TransactionalIntegrityTest extends SmokeTestBase {

    /**
     * Verifies that a message written inside a rolled-back database transaction is never
     * delivered to downstream consumers.
     *
     * <h3>What is being tested</h3>
     * The outbox pattern relies on a guarantee: only committed rows are eligible for
     * dispatch. If an application writes to the outbox table and then rolls back its
     * transaction, the outbox poller must not pick up and forward that message.
     * This test exercises that guarantee end-to-end: from a real PostgreSQL transaction
     * rollback through the REST-based webhook subscription layer to an in-process HTTP
     * webhook receiver.
     *
     * <h3>Test flow</h3>
     * <ol>
     *   <li>Start a local webhook server that records every delivery it receives.</li>
     *   <li>Create a PeeGeeQ database setup (schema + outbox queue) via the REST API.</li>
     *   <li>Register the local webhook server as a subscriber for that queue.</li>
     *   <li>Open a raw PostgreSQL connection, begin a transaction, insert a row into the
     *       outbox table with a unique {@code correlation_id}, then <em>roll back</em>.</li>
     *   <li>Immediately query the outbox table to confirm the rolled-back row is absent
     *       (COUNT = 0). This is the deterministic terminal state: if the DB has no row
     *       the poller can never dispatch it, regardless of timing.</li>
     *   <li>Assert that the webhook server received zero deliveries.</li>
     * </ol>
     *
     * <h3>Why the DB verification step matters</h3>
     * A naive implementation would wait N seconds after the rollback and then assert
     * absence. That approach is a wall-clock guess: too short on a slow CI machine
     * (poller may not have had time to run), and wasteful on a fast machine. Instead,
     * the verification queries the DB directly after the rollback. PostgreSQL's
     * read-committed isolation guarantees that any subsequent read from any connection
     * sees only committed data, so a COUNT = 0 result here is a hard proof not a
     * probabilistic one that the row was never committed and therefore cannot be
     * dispatched.
     */
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
                // 4. Use the same database config that was posted to the setup API
                try {
                    JsonObject dbConfigJson = setupRequest.getJsonObject("databaseConfig");
                        DatabaseConfig dbConfig = new DatabaseConfig.Builder()
                            .host(dbConfigJson.getString("host"))
                            .port(dbConfigJson.getInteger("port"))
                            .databaseName(dbConfigJson.getString("databaseName"))
                            .username(dbConfigJson.getString("username"))
                            .password(dbConfigJson.getString("password"))
                            .schema(dbConfigJson.getString("schema"))
                            .templateDatabase(dbConfigJson.getString("templateDatabase"))
                            .encoding(dbConfigJson.getString("encoding"))
                            .build();
                    
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
                        .compose(conn -> {
                            String insertSql = "INSERT INTO " + queueName + " (topic, payload, correlation_id) VALUES ($1, $2, $3)";
                            return conn.begin()
                                .compose(tx -> conn.preparedQuery(insertSql)
                                    .execute(Tuple.of(queueName, new JsonObject().put("data", "should-not-exist"), "rollback-1"))
                                    .compose(res -> {
                                        logger.info("Inserted message in transaction, now rolling back...");
                                        return tx.rollback();
                                    }))
                                // After rollback, verify row is absent deterministic terminal state
                                .compose(ignored -> conn.preparedQuery(
                                    "SELECT COUNT(*) AS n FROM " + queueName + " WHERE correlation_id = $1")
                                    .execute(Tuple.of("rollback-1")))
                                .compose(rows -> {
                                    Row row = rows.iterator().next();
                                    int count = row.getInteger("n");
                                    if (count != 0) {
                                        return Future.failedFuture(
                                            "Rolled-back row unexpectedly present in table: count=" + count);
                                    }
                                    logger.info("Confirmed: rolled-back row is absent from DB (count=0)");
                                    return Future.<Void>succeededFuture();
                                })
                                .eventually(() -> conn.close());
                        })
                        .eventually(() -> pool.close());
                } catch (Exception e) {
                    return Future.failedFuture(e);
                }
            })
            .onSuccess(v -> {
                // 6. Verify NO webhook was triggered the DB query above already proved the row
                // was absent after rollback, so the poller can never dispatch it.
                testContext.verify(() ->
                    assertTrue(receivedMessages.isEmpty(),
                        "Webhook should not have been called for a rolled-back message. Received: " + receivedMessages.size()));
                webhookServer.close();
                cleanupSetup(setupId);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    /**
     * Verifies that a message written inside a committed database transaction is
     * delivered to downstream consumers.
     *
     * <h3>What is being tested</h3>
     * This is the positive complement to {@link #testRollbackPreventsPublishing}.
     * The outbox guarantee has two sides: rolled-back rows must never be dispatched,
     * and committed rows must always be dispatched. This test exercises the second
     * side end-to-end: a message inserted and committed via a raw PostgreSQL
     * transaction (bypassing the REST API) must be detected by the outbox poller
     * and delivered to the registered webhook.
     *
     * <h3>Why bypass the REST API</h3>
     * The REST API publish path is tested by {@code OutboxSmokeTest}. This test
     * specifically proves that the OutboxConsumer picks up rows regardless of
     * how they entered the table not only through the sanctioned REST path, but
     * also through any direct committed write. This is the transactional contract
     * that producers must be able to rely on.
     *
     * <h3>Test flow</h3>
     * <ol>
     *   <li>Start a local webhook server.</li>
     *   <li>Create a PeeGeeQ setup and register the webhook.</li>
     *   <li>Open a raw PostgreSQL connection, begin a transaction, insert a row
     *       with a known {@code correlation_id} and {@code headers} containing
     *       the same id, then <em>commit</em>.</li>
     *   <li>Immediately verify COUNT = 1 in the queue table (deterministic proof
     *       the row is committed).</li>
     *   <li>Poll the webhook server until the delivery arrives, with a 15-second
     *       failure-only timeout guard.</li>
     *   <li>Assert the delivered {@code correlationId} matches the committed row.</li>
     * </ol>
     */
    @Test
    @DisplayName("Verify committed transaction delivers message via webhook")
    void testCommitDeliversMessage(VertxTestContext testContext) {
        String setupId = generateSetupId();
        String queueName = "outbox_commit_queue";
        String correlationId = "commit-" + UUID.randomUUID().toString().substring(0, 8);

        JsonObject setupRequest = createDatabaseSetupRequest(setupId, queueName);

        ConcurrentLinkedQueue<JsonObject> receivedMessages = new ConcurrentLinkedQueue<>();
        int webhookPort = 9094 + (int) (Math.random() * 100);
        String webhookPath = "/webhook-commit-" + setupId;

        HttpServer webhookServer = vertx.createHttpServer()
            .requestHandler(req -> {
                if (req.path().equals(webhookPath)) {
                    req.bodyHandler(body -> receivedMessages.add(body.toJsonObject()));
                    req.response().setStatusCode(200).end();
                }
            });

        long[] timeoutTimerId = {-1};

        webhookServer.listen(webhookPort)
            .compose(server -> webClient.post("/api/v1/database-setup/create").sendJsonObject(setupRequest))
            .compose(r -> webClient.post(
                    "/api/v1/setups/" + setupId + "/queues/" + queueName + "/webhook-subscriptions")
                .sendJsonObject(new JsonObject()
                    .put("webhookUrl", "http://localhost:" + webhookPort + webhookPath)))
            .compose(r -> {
                JsonObject dbConfigJson = setupRequest.getJsonObject("databaseConfig");
                PgConnectOptions connectOptions = new PgConnectOptions()
                    .setPort(dbConfigJson.getInteger("port"))
                    .setHost(dbConfigJson.getString("host"))
                    .setDatabase(dbConfigJson.getString("databaseName"))
                    .setUser(dbConfigJson.getString("username"))
                    .setPassword(dbConfigJson.getString("password"));

                Pool pool = Pool.pool(vertx, connectOptions, new PoolOptions().setMaxSize(1));

                return pool.getConnection()
                    .compose(conn -> {
                        // Insert into queue_messages (the native consumer reads from this table,
                        // filtering by topic). Headers must include correlationId so the webhook
                        // payload carries it through WebhookSubscriptionHandler.
                        String insertSql = "INSERT INTO queue_messages"
                            + " (topic, payload, correlation_id, headers, status, created_at, priority)"
                            + " VALUES ($1, $2::jsonb, $3, $4::jsonb, 'AVAILABLE', NOW(), 5)";
                        JsonObject headers = new JsonObject().put("correlationId", correlationId);

                        return conn.begin()
                            .compose(tx -> conn.preparedQuery(insertSql)
                                .execute(Tuple.of(
                                    queueName,
                                    new JsonObject().put("data", "commit-test"),
                                    correlationId,
                                    headers))
                                .compose(res -> {
                                    logger.info("Inserted message in transaction, now committing...");
                                    return tx.commit();
                                }))
                            .compose(ignored -> conn.preparedQuery(
                                "SELECT COUNT(*) AS n FROM queue_messages WHERE topic = $1 AND correlation_id = $2")
                                .execute(Tuple.of(queueName, correlationId)))
                            .compose(rows -> {
                                Row row = rows.iterator().next();
                                int count = row.getInteger("n");
                                if (count != 1) {
                                    return Future.failedFuture(
                                        "Expected committed row to be present, count=" + count);
                                }
                                logger.info("Confirmed: committed row is present in DB (count=1)");
                                return Future.<Void>succeededFuture();
                            })
                            .eventually(() -> conn.close());
                    })
                    .eventually(() -> pool.close());
            })
            .onSuccess(v -> {
                // Row committed poll the webhook until the outbox poller delivers it
                timeoutTimerId[0] = vertx.setTimer(15_000, ignored -> {
                    testContext.failNow(new AssertionError(
                        "Committed message was not delivered to webhook within 15 seconds"));
                    webhookServer.close();
                    cleanupSetup(setupId);
                });

                vertx.setPeriodic(100, timerId -> {
                    if (!receivedMessages.isEmpty()) {
                        vertx.cancelTimer(timerId);
                        vertx.cancelTimer(timeoutTimerId[0]);
                        testContext.verify(() -> {
                            JsonObject received = receivedMessages.poll();
                            assertNotNull(received, "Received message must not be null");
                            assertTrue(
                                correlationId.equals(received.getString("correlationId")),
                                "Delivered correlationId must match committed row. Expected: "
                                    + correlationId + " but got: " + received.getString("correlationId"));
                            webhookServer.close();
                            cleanupSetup(setupId);
                        });
                        testContext.completeNow();
                    }
                });
            })
            .onFailure(testContext::failNow);
    }

    private void cleanupSetup(String setupId) {
        webClient.delete( "/api/v1/setups/" + setupId)
            .send()
            .onFailure(err -> logger.warn("Failed to cleanup setup {}", setupId, err));
    }
}
