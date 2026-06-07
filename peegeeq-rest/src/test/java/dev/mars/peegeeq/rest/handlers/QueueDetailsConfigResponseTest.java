package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.setup.DatabaseSetupResult;
import dev.mars.peegeeq.api.setup.DatabaseSetupStatus;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.rest.support.ControllableSetupService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@code GET /api/v1/queues/:setupId/:queueName} verifying that
 * the {@code config} sub-object is present in the response and contains the
 * correct values from the stored {@link QueueConfig}.
 *
 * <p>Uses ControllableSetupService + a minimal QueueFactory stub — no database.
 *
 * <p>Two scenarios:
 * <ul>
 *   <li>Config stored → response reflects exact config values</li>
 *   <li>Config absent (null) → response uses hard-coded defaults</li>
 * </ul>
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueDetailsConfigResponseTest {

    private static final int PORT = 18141;
    private static final String SETUP_ID  = "cfg-test-setup";
    private static final String QUEUE_NAME = "cfg-test-queue";

    private WebClient webClient;

    // ── Minimal QueueFactory stub ─────────────────────────────────────────────

    /** Returns "native" type, always healthy, 1 message, rate 0.0, processing 0 ms. */
    private static QueueFactory minimalFactory() {
        return new QueueFactory() {
            @Override public String getImplementationType() { return "native"; }
            @Override public Future<Boolean> isHealthy()   { return Future.succeededFuture(true); }
            @Override public Future<Long> countMessages(String topic) { return Future.succeededFuture(1L); }
            @Override public <T> MessageProducer<T> createProducer(String topic, Class<T> type) { return null; }
            @Override public <T> MessageConsumer<T> createConsumer(String topic, Class<T> type) { return null; }
            @Override public <T> ConsumerGroup<T> createConsumerGroup(String g, String topic, Class<T> type) { return null; }
            @Override public <T> Future<QueueBrowser<T>> createBrowser(String topic, Class<T> type) { return Future.failedFuture("not used"); }
            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };
    }

    /** Build a DatabaseSetupResult that holds the given QueueFactory and optionally a QueueConfig. */
    private static DatabaseSetupResult resultWith(QueueFactory factory, QueueConfig config) {
        DatabaseSetupResult r = new DatabaseSetupResult(
                SETUP_ID,
                Map.of(QUEUE_NAME, factory),
                Map.of(),
                DatabaseSetupStatus.ACTIVE);
        if (config != null) {
            r.putQueueConfig(QUEUE_NAME, config);
        }
        return r;
    }

    private Future<JsonObject> getDetails(WebClient client, int port) {
        return client.get(port, "localhost", "/api/v1/queues/" + SETUP_ID + "/" + QUEUE_NAME)
                .timeout(10_000)
                .send()
                .compose(response -> {
                    if (response.statusCode() == 200) {
                        return Future.succeededFuture(response.bodyAsJsonObject());
                    }
                    return Future.failedFuture("Unexpected status " + response.statusCode()
                            + ": " + response.bodyAsString());
                });
    }

    // =========================================================================
    // C1 — config present: response reflects stored QueueConfig values
    //
    // Setup: DatabaseSetupResult has a QueueConfig with non-default values.
    // Assert: response.config contains those exact values.
    // RED means: handler does not call getQueueConfig() or builds the config
    //            object with wrong values.
    // =========================================================================

    @Test
    void getQueueDetails_withStoredConfig_respondsWithConfigValues(
            Vertx vertx, VertxTestContext ctx) {

        QueueConfig cfg = new QueueConfig.Builder()
                .queueName(QUEUE_NAME)
                .implementationType("native")
                .maxRetries(7)
                .visibilityTimeoutSeconds(90)
                .batchSize(25)
                .pollingInterval(Duration.ofSeconds(4))
                .fifoEnabled(true)
                .deadLetterEnabled(true)
                .deadLetterQueueName("my-dlq")
                .build();

        DatabaseSetupResult setupResult = resultWith(minimalFactory(), cfg);

        ControllableSetupService svc = ControllableSetupService.defaults()
                .withGetSetupResult(id -> Future.succeededFuture(setupResult));

        RestServerConfig restCfg = new RestServerConfig(PORT, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(restCfg, svc))
                .compose(deployId -> {
                    webClient = WebClient.create(vertx);
                    return getDetails(webClient, PORT)
                            .eventually(() -> vertx.undeploy(deployId));
                })
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    JsonObject config = body.getJsonObject("config");
                    assertNotNull(config, "Response must contain a 'config' sub-object");

                    assertEquals(7,        config.getInteger("maxRetries"),              "maxRetries mismatch");
                    assertEquals(90,       config.getInteger("visibilityTimeoutSeconds"), "visibilityTimeoutSeconds mismatch");
                    assertEquals(25,       config.getInteger("batchSize"),               "batchSize mismatch");
                    assertEquals(4,        config.getInteger("pollingIntervalSeconds"),  "pollingIntervalSeconds mismatch");
                    assertTrue(config.getBoolean("fifoEnabled"),                          "fifoEnabled should be true");
                    assertTrue(config.getBoolean("deadLetterEnabled"),                    "deadLetterEnabled should be true");
                    assertEquals("my-dlq", config.getString("deadLetterQueueName"),      "deadLetterQueueName mismatch");

                    ctx.completeNow();
                })));
    }

    // =========================================================================
    // C2 — config absent: response falls back to hard-coded defaults
    //
    // Setup: DatabaseSetupResult has no QueueConfig for the queue (getQueueConfig → null).
    // Assert: response.config contains the documented default values.
    // RED means: handler throws a NullPointerException or omits the config object.
    // =========================================================================

    @Test
    void getQueueDetails_withNoStoredConfig_respondsWithDefaults(
            Vertx vertx, VertxTestContext ctx) {

        DatabaseSetupResult setupResult = resultWith(minimalFactory(), null /* no config */);

        ControllableSetupService svc = ControllableSetupService.defaults()
                .withGetSetupResult(id -> Future.succeededFuture(setupResult));

        int auxPort = PORT + 1;
        RestServerConfig restCfg = new RestServerConfig(auxPort, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(restCfg, svc))
                .compose(deployId -> {
                    WebClient auxClient = WebClient.create(vertx);
                    return getDetails(auxClient, auxPort)
                            .eventually(() -> vertx.undeploy(deployId));
                })
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    JsonObject config = body.getJsonObject("config");
                    assertNotNull(config, "Response must contain a 'config' sub-object even when no config is stored");

                    // Hard-coded defaults from ManagementApiHandler.getQueueDetails
                    assertEquals(3,     config.getInteger("maxRetries"),              "default maxRetries should be 3");
                    assertEquals(300,   config.getInteger("visibilityTimeoutSeconds"), "default visibilityTimeoutSeconds should be 300");
                    assertEquals(10,    config.getInteger("batchSize"),               "default batchSize should be 10");
                    assertEquals(5,     config.getInteger("pollingIntervalSeconds"),  "default pollingIntervalSeconds should be 5");
                    assertFalse(config.getBoolean("fifoEnabled"),                      "default fifoEnabled should be false");
                    assertTrue(config.getBoolean("deadLetterEnabled"),                 "default deadLetterEnabled should be true");
                    assertNull(config.getString("deadLetterQueueName"),                "default deadLetterQueueName should be null");

                    ctx.completeNow();
                })));
    }

    // =========================================================================
    // C3 — config present: implementationType reflected in response top-level field
    //
    // Assert: the top-level "implementationType" field matches the factory's type.
    // RED means: handler skips the implementationType call or uses the wrong field.
    // =========================================================================

    @Test
    void getQueueDetails_implementationTypeFromFactory(
            Vertx vertx, VertxTestContext ctx) {

        QueueFactory outboxFactory = new QueueFactory() {
            @Override public String getImplementationType() { return "outbox"; }
            @Override public Future<Boolean> isHealthy()   { return Future.succeededFuture(true); }
            @Override public Future<Long> countMessages(String topic) { return Future.succeededFuture(0L); }
            @Override public <T> MessageProducer<T> createProducer(String topic, Class<T> t) { return null; }
            @Override public <T> MessageConsumer<T> createConsumer(String topic, Class<T> t) { return null; }
            @Override public <T> ConsumerGroup<T> createConsumerGroup(String g, String topic, Class<T> t) { return null; }
            @Override public <T> Future<QueueBrowser<T>> createBrowser(String topic, Class<T> t) { return Future.failedFuture("not used"); }
            @Override public Future<Void> close() { return Future.succeededFuture(); }
        };

        DatabaseSetupResult setupResult = resultWith(outboxFactory, null);

        ControllableSetupService svc = ControllableSetupService.defaults()
                .withGetSetupResult(id -> Future.succeededFuture(setupResult));

        int auxPort = PORT + 2;
        RestServerConfig restCfg = new RestServerConfig(auxPort, RestServerConfig.MonitoringConfig.defaults(), List.of("*"));

        vertx.deployVerticle(new PeeGeeQRestServer(restCfg, svc))
                .compose(deployId -> {
                    WebClient auxClient = WebClient.create(vertx);
                    return getDetails(auxClient, auxPort)
                            .eventually(() -> vertx.undeploy(deployId));
                })
                .onComplete(ctx.succeeding(body -> ctx.verify(() -> {
                    assertEquals("outbox", body.getString("implementationType"),
                            "implementationType must come from the QueueFactory");
                    ctx.completeNow();
                })));
    }
}
