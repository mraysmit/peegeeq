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

package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 11: the {@code system_stats} connection metrics, split from the old
 * meaningless {@code activeConnections} composite into three distinct dimensions:
 * <ul>
 *   <li>{@code monitoringSessions} — browser sessions watching the live feed,</li>
 *   <li>{@code activeSubscriptions} — registered consumer-group subscriptions,</li>
 *   <li>{@code dbPool} — live PostgreSQL connection breakdown ({@code active}/{@code idle}/{@code pending}/
 *       {@code total}) per setup (from {@code pg_stat_activity}) and aggregated, with a {@code perSetup} array.</li>
 * </ul>
 *
 * <p>NOTE: {@code dbPool.max} (pool-saturation reference) is intentionally not emitted yet — it needs
 * per-setup {@code PgPoolConfig} access not exposed by the current setup facade; tracked as a follow-up.
 * These tests therefore assert {@code active <= total} rather than {@code active <= max}.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-25
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemMonitoringHandlerConnectionMetricsTest {

    private static final Logger logger = LoggerFactory.getLogger(SystemMonitoringHandlerConnectionMetricsTest.class);
    private static final int TEST_PORT = 18116;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private String deploymentId;
    private WebClient webClient;
    private WebSocketClient wsClient;
    private String testSetupId;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        testSetupId = "conn-metrics-" + System.currentTimeMillis();
        webClient = WebClient.create(vertx);
        wsClient = vertx.createWebSocketClient();

        DatabaseSetupService setupService = PeeGeeQRuntime.createDatabaseSetupService();
        RestServerConfig testConfig = new RestServerConfig(
                TEST_PORT, RestServerConfig.MonitoringConfig.defaults(), java.util.List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(testConfig, setupService))
            .compose(id -> {
                deploymentId = id;
                return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
                    .putHeader("content-type", "application/json")
                    .timeout(60000)
                    .sendJsonObject(new JsonObject()
                        .put("setupId", testSetupId)
                        .put("databaseConfig", new JsonObject()
                            .put("host", postgres.getHost())
                            .put("port", postgres.getFirstMappedPort())
                            .put("databaseName", "conn_metrics_db_" + System.currentTimeMillis())
                            .put("username", postgres.getUsername())
                            .put("password", postgres.getPassword())
                            .put("schema", PostgreSQLTestConstants.TEST_SCHEMA)
                            .put("templateDatabase", "template0")
                            .put("encoding", "UTF8"))
                        .put("queues", new JsonArray())
                        .put("eventStores", new JsonArray()))
                    .compose(r -> (r.statusCode() == 201 || r.statusCode() == 200)
                        ? Future.succeededFuture()
                        : Future.failedFuture("Setup failed: " + r.statusCode() + " " + r.bodyAsString()));
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (wsClient != null) wsClient.close();
        if (webClient != null) webClient.close();
        if (deploymentId != null) {
            vertx.undeploy(deploymentId)
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    private static WebSocketConnectOptions monitoringOpts() {
        return new WebSocketConnectOptions().setHost("localhost").setPort(TEST_PORT).setURI("/ws/monitoring");
    }

    @Test
    @Order(1)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSystemStatsHasSplitConnectionFields(Vertx vertx, VertxTestContext testContext) {
        wsClient.connect(monitoringOpts())
            .onSuccess(ws -> {
                ws.exceptionHandler(testContext::failNow);
                ws.textMessageHandler(message -> testContext.verify(() -> {
                    JsonObject msg = new JsonObject(message);
                    if (!"system_stats".equals(msg.getString("type"))) {
                        return;
                    }
                    JsonObject data = msg.getJsonObject("data");
                    assertNotNull(data, "system_stats frame must carry a data object");

                    // Old meaningless composite is gone
                    assertNull(data.getInteger("activeConnections"),
                            "activeConnections must be absent after the Phase 11 split");

                    // Three distinct dimensions present
                    assertNotNull(data.getInteger("monitoringSessions"), "monitoringSessions must be present");
                    assertTrue(data.getInteger("monitoringSessions") >= 1,
                            "monitoringSessions must be >= 1 while this observer WS is open");
                    assertNotNull(data.getInteger("activeSubscriptions"), "activeSubscriptions must be present");
                    assertTrue(data.getInteger("activeSubscriptions") >= 0);

                    JsonObject dbPool = data.getJsonObject("dbPool");
                    assertNotNull(dbPool, "dbPool object must be present");
                    assertNotNull(dbPool.getInteger("active"), "dbPool.active must be present");
                    assertNotNull(dbPool.getInteger("idle"), "dbPool.idle must be present");
                    assertNotNull(dbPool.getInteger("pending"), "dbPool.pending must be present");
                    assertNotNull(dbPool.getInteger("total"), "dbPool.total must be present");
                    assertTrue(dbPool.getInteger("active") >= 0 && dbPool.getInteger("idle") >= 0
                                    && dbPool.getInteger("pending") >= 0 && dbPool.getInteger("total") >= 0,
                            "dbPool counts must be non-negative");
                    assertTrue(dbPool.getInteger("active") <= dbPool.getInteger("total"),
                            "dbPool.active must not exceed dbPool.total");
                    assertNotNull(dbPool.getJsonArray("perSetup"), "dbPool.perSetup must be present");

                    ws.close();
                    testContext.completeNow();
                }));

                vertx.setTimer(25000, id -> {
                    if (!testContext.completed()) {
                        ws.close();
                        testContext.failNow(new AssertionError("system_stats frame not received within 25s"));
                    }
                });
            })
            .onFailure(testContext::failNow);
    }

    @Test
    @Order(2)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testMonitoringSessionsReflectsOpenConnections(Vertx vertx, VertxTestContext testContext) {
        List<WebSocket> extras = new ArrayList<>();
        AtomicBoolean observerDone = new AtomicBoolean(false);
        WebSocketConnectOptions opts = monitoringOpts();

        // Open 3 extra monitoring connections sequentially, then an observer that must see
        // monitoringSessions >= 4 (the 3 extras + itself).
        wsClient.connect(opts)
            .compose(a -> { extras.add(a); return wsClient.connect(opts); })
            .compose(b -> { extras.add(b); return wsClient.connect(opts); })
            .compose(c -> { extras.add(c); return wsClient.connect(opts); })
            .onSuccess(observer -> {
                observer.exceptionHandler(testContext::failNow);
                observer.textMessageHandler(message -> testContext.verify(() -> {
                    JsonObject msg = new JsonObject(message);
                    if (!"system_stats".equals(msg.getString("type")) || observerDone.get()) {
                        return;
                    }
                    int sessions = msg.getJsonObject("data").getInteger("monitoringSessions", 0);
                    if (sessions >= 4) { // 3 extras + observer
                        observerDone.set(true);
                        extras.forEach(WebSocket::close);
                        observer.close();
                        testContext.completeNow();
                    }
                }));

                vertx.setTimer(25000, id -> {
                    if (!testContext.completed()) {
                        extras.forEach(WebSocket::close);
                        observer.close();
                        testContext.failNow(new AssertionError(
                                "monitoringSessions did not reach 4 within 25s"));
                    }
                });
            })
            .onFailure(testContext::failNow);
    }
}
