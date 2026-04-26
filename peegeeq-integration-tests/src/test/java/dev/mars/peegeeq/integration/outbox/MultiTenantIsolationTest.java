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
package dev.mars.peegeeq.integration.outbox;

import dev.mars.peegeeq.integration.SmokeTestBase;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

/**
 * Multi-tenant schema isolation integration tests.
 *
 * <p>PeeGeeQ's multi-tenant model assigns each setup its own dedicated database.
 * These tests verify that the isolation is absolute: a message published to one
 * tenant's queue must never appear in another tenant's database, regardless of
 * queue name collisions or shared database host.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("Multi-Tenant Schema Isolation Tests")
@Tag("integration")
public class MultiTenantIsolationTest extends SmokeTestBase {

    /**
     * Verifies that two setups backed by separate databases are fully isolated.
     *
     * <h3>What is being tested</h3>
     * Each PeeGeeQ setup is provisioned with its own isolated database
     * ({@code smoke_db_{setupId}}). A message published to setup A via the REST API
     * must be written to setup A's database and must never appear in setup B's
     * database, even if both setups declare a queue with the same name.
     *
     * <h3>Why this matters</h3>
     * Multi-tenant isolation is a load-bearing architectural guarantee. If the
     * server-side setup routing is misconfigured, a publish to setup A could
     * accidentally target setup B's database. This test catches that class of bug
     * deterministically at the DB level — no timing, no webhooks required.
     *
     * <h3>Test flow</h3>
     * <ol>
     *   <li>Create two setups (setup A and setup B) with the same queue name
     *       but different setup IDs and therefore different databases.</li>
     *   <li>Publish a message to setup A via the REST API with a unique
     *       {@code correlationId}.</li>
     *   <li>Query setup A's database directly: the row must be present
     *       (COUNT = 1).</li>
     *   <li>Query setup B's database directly: the row must be absent
     *       (COUNT = 0).</li>
     * </ol>
     */
    @Test
    @DisplayName("Message published to setup A must not appear in setup B database")
    void testPublishToOneSetupDoesNotLeakToAnother(VertxTestContext testContext) {
        String setupIdA = generateSetupId();
        String setupIdB = generateSetupId();
        // Intentionally the same queue name in both setups to maximise isolation stress
        String queueName = "isolation_test_queue";
        String correlationId = "iso-" + UUID.randomUUID().toString().substring(0, 8);

        JsonObject setupRequestA = createDatabaseSetupRequest(setupIdA, queueName);
        JsonObject setupRequestB = createDatabaseSetupRequest(setupIdB, queueName);

        webClient.post("/api/v1/database-setup/create").sendJsonObject(setupRequestA)
            .compose(r -> webClient.post("/api/v1/database-setup/create").sendJsonObject(setupRequestB))
            // Publish message to setup A only
            .compose(r -> webClient.post("/api/v1/queues/" + setupIdA + "/" + queueName + "/messages")
                .sendJsonObject(new JsonObject()
                    .put("payload", new JsonObject().put("data", "tenant-a-only"))
                    .put("correlationId", correlationId)))
            // Verify: setup A's database contains the row
            .compose(r -> {
                int statusCode = r.statusCode();
                if (statusCode != 200 && statusCode != 201) {
                    return Future.failedFuture(
                        "Publish to setup A failed with HTTP " + statusCode + ": " + r.bodyAsString());
                }
                return connectToSetupDb(setupRequestA)
                    .compose(pool -> pool.preparedQuery(
                        "SELECT COUNT(*) AS n FROM queue_messages WHERE topic = $1 AND correlation_id = $2")
                        .execute(Tuple.of(queueName, correlationId))
                        .compose(rows -> {
                            int n = rows.iterator().next().getInteger("n");
                            if (n < 1) {
                                return Future.failedFuture(
                                    "Setup A must contain the published message, count=" + n);
                            }
                            logger.info("Setup A: confirmed row present (count={})", n);
                            return Future.<Void>succeededFuture();
                        })
                        .eventually(() -> pool.close()));
            })
            // Verify: setup B's database does NOT contain the row
            .compose(v -> connectToSetupDb(setupRequestB)
                .compose(pool -> pool.preparedQuery(
                    "SELECT COUNT(*) AS n FROM queue_messages WHERE topic = $1 AND correlation_id = $2")
                    .execute(Tuple.of(queueName, correlationId))
                    .compose(rows -> {
                        int n = rows.iterator().next().getInteger("n");
                        if (n != 0) {
                            return Future.failedFuture(
                                "ISOLATION VIOLATION: setup B contains message from setup A "
                                    + "(correlation_id=" + correlationId + ", count=" + n + ")");
                        }
                        logger.info("Setup B: confirmed no cross-tenant contamination (count=0)");
                        return Future.<Void>succeededFuture();
                    })
                    .eventually(() -> pool.close())))
            .onSuccess(v -> {
                cleanupSetup(setupIdA);
                cleanupSetup(setupIdB);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Future<Pool> connectToSetupDb(JsonObject setupRequest) {
        JsonObject dbConfig = setupRequest.getJsonObject("databaseConfig");
        PgConnectOptions opts = new PgConnectOptions()
            .setHost(dbConfig.getString("host"))
            .setPort(dbConfig.getInteger("port"))
            .setDatabase(dbConfig.getString("databaseName"))
            .setUser(dbConfig.getString("username"))
            .setPassword(dbConfig.getString("password"));
        return Future.succeededFuture(Pool.pool(vertx, opts, new PoolOptions().setMaxSize(1)));
    }

    private void cleanupSetup(String setupId) {
        webClient.delete("/api/v1/setups/" + setupId)
            .send()
            .onFailure(err -> logger.warn("Failed to cleanup setup {}", setupId, err));
    }
}
