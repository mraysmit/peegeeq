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
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for backend management-event publishing (Phase 8).
 *
 * <p>Resource lifecycle mutations (queue/event-store/consumer-group create/delete) publish a
 * transient event on the {@code peegeeq.management.events} bus address; {@code SystemMonitoringHandler}
 * forwards each to connected {@code /ws/monitoring} clients as a
 * {@code {"type":"management_event","data":{eventName,entity,action,state,name,setupId,timestamp}}}
 * frame that drives the notification bell. Nothing is persisted.</p>
 *
 * <p>Event names follow the {@code {entity}.{action}.{state}} convention from the PeeGeeQ Financial
 * Services Event Catalogue (kebab-case entities, e.g. {@code event-store.creation.completed}).</p>
 *
 * <p>Verified against a real PostgreSQL via TestContainers:</p>
 * <ul>
 *   <li>each lifecycle mutation (queue/event-store/consumer-group create &amp; delete) emits the
 *       correctly-named, correctly-structured {@code management_event};</li>
 *   <li>publishing a <em>message</em> (not a lifecycle change) does NOT emit a {@code management_event};</li>
 *   <li>the event is broadcast to every connected monitoring WebSocket, not just one.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-23
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementEventPublishingIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ManagementEventPublishingIntegrationTest.class);
    private static final int TEST_PORT = 18114;

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("peegeeq_mgmt_event_test");
        container.withUsername("peegeeq_test");
        container.withPassword("peegeeq_test");
        container.withSharedMemorySize(PostgreSQLTestConstants.DEFAULT_SHARED_MEMORY_SIZE);
        container.withReuse(false);
        return container;
    }

    private String deploymentId;
    private WebClient webClient;
    private WebSocketClient wsClient;
    private String testSetupId;

    @BeforeAll
    void setUp(Vertx vertx, VertxTestContext testContext) {
        testSetupId = "mgmt-event-" + System.currentTimeMillis();
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
                            .put("databaseName", "mgmt_event_db_" + System.currentTimeMillis())
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

    // ── REST mutation helpers (mirror ManagementApiIntegrationTest request shapes) ───────────────

    private Future<Void> createQueue(String name) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .putHeader("content-type", "application/json").timeout(10000)
            .sendJsonObject(new JsonObject().put("setupId", testSetupId).put("name", name).put("type", "native"))
            .compose(r -> ok(r.statusCode()) ? Future.<Void>succeededFuture()
                : Future.failedFuture("create queue failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    /** Standard REST delete — the path the management UI actually uses. */
    private Future<Void> deleteQueue(String name) {
        return webClient.delete(TEST_PORT, "localhost", "/api/v1/queues/" + testSetupId + "/" + name)
            .timeout(10000).send()
            .compose(r -> ok(r.statusCode()) ? Future.<Void>succeededFuture()
                : Future.failedFuture("delete queue failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private Future<Void> createEventStore(String name) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/management/event-stores")
            .putHeader("content-type", "application/json").timeout(20000)
            .sendJsonObject(new JsonObject().put("name", name).put("setup", testSetupId))
            .compose(r -> ok(r.statusCode()) ? Future.<Void>succeededFuture()
                : Future.failedFuture("create event store failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private Future<Void> deleteEventStore(String name) {
        // Composite storeId = {setupId}-{storeName}
        return webClient.delete(TEST_PORT, "localhost", "/api/v1/management/event-stores/" + testSetupId + "-" + name)
            .timeout(10000).send()
            .compose(r -> ok(r.statusCode()) ? Future.<Void>succeededFuture()
                : Future.failedFuture("delete event store failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private Future<Void> createConsumerGroup(String group, String queue) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/management/consumer-groups")
            .putHeader("content-type", "application/json").timeout(10000)
            .sendJsonObject(new JsonObject().put("name", group).put("setup", testSetupId).put("queueName", queue))
            .compose(r -> ok(r.statusCode()) ? Future.<Void>succeededFuture()
                : Future.failedFuture("create consumer group failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private Future<Void> deleteConsumerGroup(String group, String queue) {
        return webClient.delete(TEST_PORT, "localhost",
                "/api/v1/management/consumer-groups/" + testSetupId + "/" + queue + "/" + group)
            .timeout(10000).send()
            .compose(r -> ok(r.statusCode()) ? Future.<Void>succeededFuture()
                : Future.failedFuture("delete consumer group failed: " + r.statusCode() + " " + r.bodyAsString()));
    }

    private static boolean ok(int status) {
        return status == 200 || status == 201 || status == 204;
    }

    /**
     * Connects a monitoring WebSocket, runs {@code mutation} once the welcome frame arrives, and
     * applies {@code dataAssertions} to the {@code data} object of the first {@code management_event}
     * frame received. Any precondition data must be created BEFORE calling this (so its own event
     * fires while no WS is attached and cannot be mistaken for the event under test).
     */
    private void assertManagementEventOnMutation(
            Vertx vertx, VertxTestContext testContext,
            Supplier<Future<Void>> mutation, Consumer<JsonObject> dataAssertions) {

        AtomicBoolean triggered = new AtomicBoolean(false);
        WebSocketConnectOptions opts = new WebSocketConnectOptions()
                .setHost("localhost").setPort(TEST_PORT).setURI("/ws/monitoring");

        wsClient.connect(opts)
            .onSuccess(ws -> {
                ws.exceptionHandler(testContext::failNow);
                ws.textMessageHandler(message -> testContext.verify(() -> {
                    JsonObject msg = new JsonObject(message);
                    if ("welcome".equals(msg.getString("type")) && !triggered.getAndSet(true)) {
                        mutation.get().onFailure(testContext::failNow);
                    }
                    if ("management_event".equals(msg.getString("type"))) {
                        JsonObject data = msg.getJsonObject("data");
                        assertNotNull(data, "management_event must carry a data object");
                        dataAssertions.accept(data);
                        ws.close();
                        testContext.completeNow();
                    }
                }));
                vertx.setTimer(25000, id -> {
                    if (!testContext.completed()) {
                        ws.close();
                        testContext.failNow(new AssertionError("management_event not received within 25s"));
                    }
                });
            })
            .onFailure(testContext::failNow);
    }

    /** Asserts the catalogue-aligned envelope for a single resource lifecycle event. */
    private void assertEvent(JsonObject data, String entity, String action, String name) {
        assertEquals(entity + "." + action + ".completed", data.getString("eventName"));
        assertEquals(entity, data.getString("entity"));
        assertEquals(action, data.getString("action"));
        assertEquals("completed", data.getString("state"));
        assertEquals(name, data.getString("name"));
        assertEquals(testSetupId, data.getString("setupId"));
        assertNotNull(data.getLong("timestamp"), "management_event data must carry a timestamp");
    }

    // ── tests ───────────────────────────────────────────────────────────────────────────────────

    @Test @Order(1) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testQueueCreationEmitsEvent(Vertx vertx, VertxTestContext testContext) {
        String q = "mgmt_q_create_" + System.currentTimeMillis();
        assertManagementEventOnMutation(vertx, testContext,
            () -> createQueue(q),
            data -> assertEvent(data, "queue", "creation", q));
    }

    @Test @Order(2) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testQueueDeletionEmitsEvent(Vertx vertx, VertxTestContext testContext) {
        String q = "mgmt_q_delete_" + System.currentTimeMillis();
        createQueue(q)
            .onSuccess(v -> assertManagementEventOnMutation(vertx, testContext,
                () -> deleteQueue(q),
                data -> assertEvent(data, "queue", "deletion", q)))
            .onFailure(testContext::failNow);
    }

    @Test @Order(3) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testEventStoreCreationEmitsEvent(Vertx vertx, VertxTestContext testContext) {
        String store = "mgmt_es_create_" + System.currentTimeMillis();
        assertManagementEventOnMutation(vertx, testContext,
            () -> createEventStore(store),
            data -> assertEvent(data, "event-store", "creation", store));
    }

    @Test @Order(4) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testEventStoreDeletionEmitsEvent(Vertx vertx, VertxTestContext testContext) {
        String store = "mgmt_es_delete_" + System.currentTimeMillis();
        createEventStore(store)
            .onSuccess(v -> assertManagementEventOnMutation(vertx, testContext,
                () -> deleteEventStore(store),
                data -> assertEvent(data, "event-store", "deletion", store)))
            .onFailure(testContext::failNow);
    }

    @Test @Order(5) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testConsumerGroupCreationEmitsEvent(Vertx vertx, VertxTestContext testContext) {
        String q = "mgmt_cg_create_q_" + System.currentTimeMillis();
        String group = "mgmt_cg_create_g_" + System.currentTimeMillis();
        createQueue(q)
            .onSuccess(v -> assertManagementEventOnMutation(vertx, testContext,
                () -> createConsumerGroup(group, q),
                data -> assertEvent(data, "consumer-group", "creation", group)))
            .onFailure(testContext::failNow);
    }

    @Test @Order(6) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testConsumerGroupDeletionEmitsEvent(Vertx vertx, VertxTestContext testContext) {
        String q = "mgmt_cg_delete_q_" + System.currentTimeMillis();
        String group = "mgmt_cg_delete_g_" + System.currentTimeMillis();
        createQueue(q)
            .compose(v -> createConsumerGroup(group, q))
            .onSuccess(v -> assertManagementEventOnMutation(vertx, testContext,
                () -> deleteConsumerGroup(group, q),
                data -> assertEvent(data, "consumer-group", "deletion", group)))
            .onFailure(testContext::failNow);
    }

    @Test @Order(7) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testNoManagementEventForMessagePublish(Vertx vertx, VertxTestContext testContext) {
        String queueName = "mgmt_msg_" + System.currentTimeMillis();
        AtomicBoolean welcomeSeen = new AtomicBoolean(false);
        AtomicBoolean managementEventReceived = new AtomicBoolean(false);

        WebSocketConnectOptions opts = new WebSocketConnectOptions()
                .setHost("localhost").setPort(TEST_PORT).setURI("/ws/monitoring");

        // Create the queue first (its event fires before any WS is attached), then connect and
        // publish a MESSAGE — which must NOT emit a management_event.
        createQueue(queueName)
            .compose(v -> wsClient.connect(opts))
            .onSuccess(ws -> {
                ws.exceptionHandler(testContext::failNow);
                ws.textMessageHandler(message -> testContext.verify(() -> {
                    JsonObject msg = new JsonObject(message);
                    if ("welcome".equals(msg.getString("type")) && !welcomeSeen.getAndSet(true)) {
                        webClient.post(TEST_PORT, "localhost",
                                "/api/v1/queues/" + testSetupId + "/" + queueName + "/messages")
                            .putHeader("content-type", "application/json").timeout(10000)
                            .sendJsonObject(new JsonObject()
                                .put("payload", new JsonObject().put("test", true))
                                .put("headers", new JsonObject()))
                            .onSuccess(r -> testContext.verify(() -> assertTrue(ok(r.statusCode()),
                                "message publish should succeed, got " + r.statusCode() + ": " + r.bodyAsString())))
                            .onFailure(testContext::failNow);
                    }
                    if ("management_event".equals(msg.getString("type"))) {
                        managementEventReceived.set(true);
                    }
                }));
                vertx.setTimer(5000, id -> testContext.verify(() -> {
                    assertFalse(managementEventReceived.get(),
                            "management_event must NOT be emitted for message-level publish operations");
                    ws.close();
                    testContext.completeNow();
                }));
            })
            .onFailure(testContext::failNow);
    }

    @Test @Order(8) @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testManagementEventBroadcastToAllConnectedClients(Vertx vertx, VertxTestContext testContext) {
        String q = "mgmt_bcast_" + System.currentTimeMillis();
        Checkpoint bothReceived = testContext.checkpoint(2);
        AtomicInteger connected = new AtomicInteger(0);
        AtomicBoolean triggered = new AtomicBoolean(false);

        WebSocketConnectOptions opts = new WebSocketConnectOptions()
                .setHost("localhost").setPort(TEST_PORT).setURI("/ws/monitoring");

        // Attach the handler in EACH connection's own onSuccess so neither welcome frame is missed
        // (attaching after a Future.all barrier can drop the first socket's welcome). Trigger the
        // mutation only once both sockets have welcomed; assert both receive the broadcast.
        io.vertx.core.Handler<WebSocket> attach = ws -> {
            ws.exceptionHandler(testContext::failNow);
            ws.textMessageHandler(message -> testContext.verify(() -> {
                JsonObject msg = new JsonObject(message);
                if ("welcome".equals(msg.getString("type"))
                        && connected.incrementAndGet() == 2 && !triggered.getAndSet(true)) {
                    createQueue(q).onFailure(testContext::failNow);
                }
                if ("management_event".equals(msg.getString("type"))) {
                    assertEquals("queue.creation.completed", msg.getJsonObject("data").getString("eventName"));
                    bothReceived.flag(); // each of the two sockets must receive it
                }
            }));
        };

        wsClient.connect(opts).onSuccess(attach).onFailure(testContext::failNow);
        wsClient.connect(opts).onSuccess(attach).onFailure(testContext::failNow);

        vertx.setTimer(25000, id -> {
            if (!testContext.completed()) {
                testContext.failNow(new AssertionError(
                        "both monitoring clients did not receive the management_event within 25s"));
            }
        });
    }
}
