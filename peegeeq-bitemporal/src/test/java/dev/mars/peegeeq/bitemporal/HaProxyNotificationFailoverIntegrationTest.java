package dev.mars.peegeeq.bitemporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class HaProxyNotificationFailoverIntegrationTest {

    private static final Logger logger =
        LoggerFactory.getLogger(HaProxyNotificationFailoverIntegrationTest.class);

    private static final int HAPROXY_PORT = 5400;
    private static final String DATABASE = "peegeeq_notification_failover";
    private static final String USERNAME = "peegeeq_test";
    private static final String PASSWORD = "peegeeq_test";
    private static final String SCHEMA = "public";
    private static final String TABLE = "bitemporal_event_log";
    private static final String EVENT_ID = "proxy-failover-event";
    private static final String EVENT_TYPE = "proxy.failover";
    private static final int MAX_ATTEMPTS = 80;
    private static final long RETRY_DELAY_MS = 500;

    private static Network network;

    @SuppressWarnings("resource")
    private static PostgreSQLContainer primary;

    @SuppressWarnings("resource")
    private static PostgreSQLContainer secondary;

    @SuppressWarnings("resource")
    private static GenericContainer<?> haproxy;

    private PeeGeeQManager manager;
    private ReactiveNotificationHandler<String> notificationHandler;

    @BeforeAll
    static void startInfrastructure() {
        network = Network.newNetwork();

        primary = postgres("pg_primary");
        secondary = postgres("pg_secondary");
        primary.start();
        secondary.start();

        haproxy = new GenericContainer<>("haproxy:2.8-alpine")
            .withNetwork(network)
            .withClasspathResourceMapping(
                "haproxy-notification-failover.cfg",
                "/usr/local/etc/haproxy/haproxy.cfg",
                BindMode.READ_ONLY)
            .withExposedPorts(HAPROXY_PORT)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)));
        haproxy.start();
    }

    @SuppressWarnings("resource")
    private static PostgreSQLContainer postgres(String networkAlias) {
        return new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withNetwork(network)
            .withNetworkAliases(networkAlias)
            .withDatabaseName(DATABASE)
            .withUsername(USERNAME)
            .withPassword(PASSWORD)
            .withInitScript("haproxy-notification-check-init.sql");
    }

    @AfterAll
    static void stopInfrastructure() {
        if (haproxy != null && haproxy.isRunning()) {
            haproxy.stop();
        }
        if (secondary != null && secondary.isRunning()) {
            secondary.stop();
        }
        if (primary != null && primary.isRunning()) {
            primary.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    @AfterEach
    void closeResources(VertxTestContext testContext) {
        Future<Void> handlerClose = notificationHandler == null
            ? Future.succeededFuture()
            : notificationHandler.stop();

        handlerClose.transform(handlerResult -> {
            Future<Void> managerClose = manager == null
                ? Future.succeededFuture()
                : manager.closeReactive();
            return managerClose.transform(managerResult -> combineCleanup(handlerResult, managerResult));
        })
        .onSuccess(ignored -> testContext.completeNow())
        .onFailure(testContext::failNow);
    }

    private static Future<Void> combineCleanup(
            AsyncResult<Void> handlerResult, AsyncResult<Void> managerResult) {
        Throwable failure = handlerResult.cause();
        if (managerResult.failed()) {
            if (failure == null) {
                failure = managerResult.cause();
            } else if (failure != managerResult.cause()) {
                failure.addSuppressed(managerResult.cause());
            }
        }
        return failure == null ? Future.succeededFuture() : Future.failedFuture(failure);
    }

    @Test
    @Timeout(value = 90, timeUnit = TimeUnit.SECONDS)
    @DisplayName("Configured proxy carries pooled queries and LISTEN notifications across failover")
    void configuredProxyCarriesPoolAndNotificationsAcrossFailover(
            Vertx vertx, VertxTestContext testContext) {
        Properties overrides = configurationOverrides();
        PeeGeeQConfiguration configuration = new PeeGeeQConfiguration("default", overrides);

        testContext.verify(() -> {
            assertEquals(haproxy.getHost(), configuration.getDatabaseConfig().getHost());
            assertEquals(haproxy.getMappedPort(HAPROXY_PORT),
                configuration.getDatabaseConfig().getPort());
        });

        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry(), vertx);
        PgConnectOptions listenOptions = manager.getDatabaseService().getConnectOptions();
        testContext.verify(() -> {
            assertEquals(haproxy.getHost(), listenOptions.getHost());
            assertEquals(haproxy.getMappedPort(HAPROXY_PORT), listenOptions.getPort());
        });

        Promise<Void> notificationReceived = Promise.promise();
        BiTemporalEvent<String> expectedEvent = new TestBiTemporalEvent();
        notificationHandler = new ReactiveNotificationHandler<>(
            vertx,
            listenOptions,
            new ObjectMapper(),
            String.class,
            eventId -> Future.succeededFuture(expectedEvent),
            SCHEMA,
            TABLE);

        Pool configuredPool = manager.getPool();
        notificationHandler.start()
            .compose(ignored -> notificationHandler.subscribe(EVENT_TYPE, null, message -> {
                if (EVENT_ID.equals(message.getPayload().getEventId())) {
                    notificationReceived.tryComplete();
                }
                return Future.succeededFuture();
            }))
            .compose(ignored -> configuredPool.query("SELECT 1 AS health").execute())
            .compose(rows -> {
                testContext.verify(() ->
                    assertEquals(1, rows.iterator().next().getInteger("health")));
                return stopPrimary(vertx);
            })
            .compose(ignored -> publishUntilReceived(
                vertx, notificationReceived, MAX_ATTEMPTS, null))
            .compose(ignored -> queryUntilHealthy(vertx, configuredPool, MAX_ATTEMPTS, null))
            .onSuccess(value -> testContext.verify(() -> {
                assertTrue(notificationReceived.future().isComplete(),
                    "LISTEN connection should receive a notification from the secondary");
                assertEquals(1, value,
                    "Configured pool should reconnect through HAProxy to the secondary");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private static Properties configurationOverrides() {
        Properties properties = new Properties();
        properties.setProperty("peegeeq.database.host", primary.getHost());
        properties.setProperty("peegeeq.database.port", String.valueOf(primary.getFirstMappedPort()));
        properties.setProperty("peegeeq.database.proxy.host", haproxy.getHost());
        properties.setProperty("peegeeq.database.proxy.port",
            String.valueOf(haproxy.getMappedPort(HAPROXY_PORT)));
        properties.setProperty("peegeeq.database.name", DATABASE);
        properties.setProperty("peegeeq.database.username", USERNAME);
        properties.setProperty("peegeeq.database.password", PASSWORD);
        properties.setProperty("peegeeq.database.schema", SCHEMA);
        properties.setProperty("peegeeq.database.pool.shared", "false");
        properties.setProperty("peegeeq.database.pool.connection-timeout-ms", "3000");
        properties.setProperty("peegeeq.metrics.enabled", "false");
        properties.setProperty("peegeeq.health.enabled", "false");
        return properties;
    }

    private static Future<Void> stopPrimary(Vertx vertx) {
        return vertx.<Void>executeBlocking(() -> {
            primary.stop();
            return null;
        });
    }

    private static Future<Void> publishUntilReceived(
            Vertx vertx,
            Promise<Void> notificationReceived,
            int attemptsRemaining,
            Throwable lastFailure) {
        if (notificationReceived.future().isComplete()) {
            return Future.succeededFuture();
        }
        if (attemptsRemaining == 0) {
            return Future.failedFuture(new AssertionError(
                "LISTEN connection did not receive a notification after failover", lastFailure));
        }

        return publishNotification(vertx).transform(publishResult -> {
            Throwable nextFailure = lastFailure;
            if (publishResult.failed()) {
                nextFailure = publishResult.cause();
                logger.warn("Secondary NOTIFY attempt failed: {}", nextFailure.getMessage());
            }
            if (notificationReceived.future().isComplete()) {
                return Future.succeededFuture();
            }
            Throwable failureForNextAttempt = nextFailure;
            return vertx.timer(RETRY_DELAY_MS)
                .compose(ignored -> publishUntilReceived(
                    vertx, notificationReceived, attemptsRemaining - 1, failureForNextAttempt));
        });
    }

    private static Future<Void> publishNotification(Vertx vertx) {
        PgConnectOptions secondaryOptions = new PgConnectOptions()
            .setHost(secondary.getHost())
            .setPort(secondary.getFirstMappedPort())
            .setDatabase(DATABASE)
            .setUser(USERNAME)
            .setPassword(PASSWORD);

        String channel = ReactiveNotificationHandler.createSafeChannelName(
            SCHEMA + "_bitemporal_events_", TABLE, EVENT_TYPE.replace('.', '_'));
        String payload = new JsonObject()
            .put("event_id", EVENT_ID)
            .put("event_type", EVENT_TYPE)
            .put("aggregate_id", "proxy-failover-aggregate")
            .encode();

        return PgConnection.connect(vertx, secondaryOptions)
            .compose(connection -> connection
                .preparedQuery("SELECT pg_notify($1, $2)")
                .execute(Tuple.of(channel, payload))
                .eventually(connection::close))
            .mapEmpty();
    }

    private static Future<Integer> queryUntilHealthy(
            Vertx vertx, Pool pool, int attemptsRemaining, Throwable lastFailure) {
        if (attemptsRemaining == 0) {
            return Future.failedFuture(new AssertionError(
                "Configured pool did not reconnect through HAProxy", lastFailure));
        }

        return pool.query("SELECT 1 AS health").execute().transform(queryResult -> {
            if (queryResult.succeeded()) {
                return Future.succeededFuture(
                    queryResult.result().iterator().next().getInteger("health"));
            }
            logger.warn("HAProxy pool query attempt failed: {}", queryResult.cause().getMessage());
            return vertx.timer(RETRY_DELAY_MS)
                .compose(ignored -> queryUntilHealthy(
                    vertx, pool, attemptsRemaining - 1, queryResult.cause()));
        });
    }

    private static final class TestBiTemporalEvent implements BiTemporalEvent<String> {
        @Override public String getEventId() { return EVENT_ID; }
        @Override public String getEventType() { return EVENT_TYPE; }
        @Override public String getPayload() { return "proxy-failover-payload"; }
        @Override public Instant getValidTime() { return Instant.EPOCH; }
        @Override public Instant getTransactionTime() { return Instant.EPOCH; }
        @Override public long getVersion() { return 1; }
        @Override public String getPreviousVersionId() { return null; }
        @Override public Map<String, String> getHeaders() { return Map.of(); }
        @Override public String getCorrelationId() { return null; }
        @Override public String getCausationId() { return null; }
        @Override public String getAggregateId() { return "proxy-failover-aggregate"; }
        @Override public boolean isCorrection() { return false; }
        @Override public String getCorrectionReason() { return null; }
    }
}
