package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the native producer when PostgreSQL rejects {@code pg_notify}.
 *
 * The producer inserts the message and calls {@code pg_notify} in one transaction. This test
 * makes the notify call fail for real: the producer connects as a role without EXECUTE on
 * {@code pg_notify}. The container belongs to this class alone, so the revoke affects no other
 * test. Because the insert and the notify share one transaction, the rejected notify must fail
 * the send and leave nothing in {@code queue_messages}.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class NativeProducerNotifyFailureIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(NativeProducerNotifyFailureIntegrationTest.class);

    private static final String SCHEMA = PostgreSQLTestConstants.TEST_SCHEMA;
    private static final String RESTRICTED_ROLE = "notify_denied_producer";
    private static final String RESTRICTED_PASSWORD = "notify_denied_producer_pw";
    private static final String ADMIN_POOL = "notify-failure-admin";
    private static final String PLAIN_TOPIC = "notify-rejected-topic";
    private static final String GROUPED_TOPIC = "notify-rejected-grouped-topic";
    /** PostgreSQL SQLSTATE for insufficient_privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PgConnectionManager adminManager;
    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA,
                SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        // The producer's manager connects as a role that may use the schema but may not call pg_notify.
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(SCHEMA)
                .property("peegeeq.database.username", RESTRICTED_ROLE)
                .property("peegeeq.database.password", RESTRICTED_PASSWORD)
                .build();

        adminManager = new PgConnectionManager(vertx, null);
        PgConnectionConfig adminConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(SCHEMA)
                .build();
        adminManager.getOrCreateReactivePool(ADMIN_POOL, adminConfig,
                new PgPoolConfig.Builder().maxSize(1).shared(false).build());

        adminManager.withTransaction(ADMIN_POOL, conn ->
                        // The container is shared by the tests in this class, so the role may already exist.
                        conn.query("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
                                        + RESTRICTED_ROLE + "') THEN CREATE ROLE " + RESTRICTED_ROLE
                                        + " LOGIN PASSWORD '" + RESTRICTED_PASSWORD + "'; END IF; END $$")
                                .execute()
                                .compose(r -> conn.query("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + RESTRICTED_ROLE)
                                        .execute())
                                .compose(r -> conn.query("GRANT ALL ON ALL TABLES IN SCHEMA " + SCHEMA
                                        + " TO " + RESTRICTED_ROLE).execute())
                                .compose(r -> conn.query("GRANT ALL ON ALL SEQUENCES IN SCHEMA " + SCHEMA
                                        + " TO " + RESTRICTED_ROLE).execute())
                                .compose(r -> conn.query(
                                        "REVOKE EXECUTE ON FUNCTION pg_catalog.pg_notify(text, text) FROM PUBLIC")
                                        .execute()))
                .compose(r -> {
                    manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps),
                            new SimpleMeterRegistry());
                    return manager.start();
                })
                .onSuccess(v -> {
                    PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                    PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                    factory = provider.createFactory("native", new PgDatabaseService(manager));
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                logger.warn("Error closing producer", e);
            }
        }
        Future.<Void>succeededFuture()
                .compose(v -> factory != null ? factory.close() : Future.succeededFuture())
                .compose(v -> adminManager != null ? adminManager.close() : Future.succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> {
                    manager = null;
                    testContext.completeNow();
                })
                .onFailure(err -> {
                    logger.error("Error during reactive teardown", err);
                    manager = null;
                    testContext.failNow(err);
                });
    }

    private Future<Long> storedMessageCount(String topic) {
        return adminManager.withConnection(ADMIN_POOL, conn ->
                conn.preparedQuery("SELECT COUNT(*) AS stored FROM " + SCHEMA + ".queue_messages WHERE topic = $1")
                        .execute(Tuple.of(topic))
                        .map(rows -> rows.iterator().next().getLong("stored")));
    }

    private static PgException pgExceptionIn(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof PgException pgException) {
                return pgException;
            }
        }
        return null;
    }

    private Future<Void> assertRejectedAndNothingStored(String topic, AsyncResult<Void> sendResult) {
        assertTrue(sendResult.failed(), "send must fail when PostgreSQL rejects pg_notify");
        PgException rejection = pgExceptionIn(sendResult.cause());
        assertNotNull(rejection, "failure must carry the PostgreSQL error, got: " + sendResult.cause());
        assertEquals(INSUFFICIENT_PRIVILEGE, rejection.getSqlState(),
                "failure must be the pg_notify permission error, got: " + rejection.getMessage());
        return storedMessageCount(topic).compose(stored -> {
            assertEquals(0L, stored, "a failed send must store no message; the insert and the notify "
                    + "share one transaction");
            return Future.succeededFuture();
        });
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeQueueProducer",
            message = "Failed to send message to topic " + PLAIN_TOPIC + ": ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void sendFailsAndStoresNothingWhenNotifyIsRejected(VertxTestContext testContext) {
        producer = factory.createProducer(PLAIN_TOPIC, String.class);

        producer.send("payload that must not be lost silently")
                .transform(sendResult -> assertRejectedAndNothingStored(PLAIN_TOPIC, sendResult))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.pgqueue.PgNativeQueueProducer",
            message = "Failed to send message to topic " + GROUPED_TOPIC + ": ",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void groupedSendFailsAndStoresNothingWhenNotifyIsRejected(VertxTestContext testContext) {
        producer = factory.createProducer(GROUPED_TOPIC, String.class);

        producer.send("grouped payload that must not be lost silently", Map.of(), null, "group-a")
                .transform(sendResult -> assertRejectedAndNothingStored(GROUPED_TOPIC, sendResult))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
}
