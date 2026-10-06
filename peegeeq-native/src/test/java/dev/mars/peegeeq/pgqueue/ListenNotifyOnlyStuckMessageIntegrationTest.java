package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Integration tests for messages that become deliverable without a NOTIFY while a
 * {@code LISTEN_NOTIFY_ONLY} consumer is connected.
 *
 * A listen-only consumer drains the queue when its LISTEN connection is established and when a
 * NOTIFY arrives. Two state changes make a message deliverable with no NOTIFY: a delayed
 * message reaching its {@code visible_at}, and a lock held by another consumer expiring. Each
 * test puts one message in that state and requires the connected consumer to receive it.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class ListenNotifyOnlyStuckMessageIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ListenNotifyOnlyStuckMessageIntegrationTest.class);

    private static final String SCHEMA = PostgreSQLTestConstants.TEST_SCHEMA;
    private static final String ADMIN_POOL = "listen-only-stuck-admin";
    private static final long POLL_INTERVAL_MS = 200;

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PgConnectionManager adminManager;
    private PeeGeeQManager manager;
    private QueueFactory factory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(SCHEMA)
                .property("peegeeq.queue.visibility-timeout", "PT30S")
                .build();
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SCHEMA,
                SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

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

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
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
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception e) {
                logger.warn("Error closing consumer", e);
            }
        }
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

    private MessageConsumer<String> listenOnlyConsumer(String topic) {
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();
        return factory.createConsumer(topic, String.class, config);
    }

    /** Describes the topic's rows on the database clock, for the failure message. */
    private Future<String> describeStoredMessages(String topic) {
        return adminManager.withConnection(ADMIN_POOL, conn ->
                conn.preparedQuery("SELECT id, status, (visible_at <= now()) AS visible, "
                                + "(lock_until IS NOT NULL AND lock_until < now()) AS lock_expired "
                                + "FROM " + SCHEMA + ".queue_messages WHERE topic = $1 ORDER BY id")
                        .execute(Tuple.of(topic))
                        .map(rows -> {
                            StringBuilder description = new StringBuilder();
                            for (Row row : rows) {
                                description.append("[id=").append(row.getLong("id"))
                                        .append(" status=").append(row.getString("status"))
                                        .append(" visible=").append(row.getBoolean("visible"))
                                        .append(" lockExpired=").append(row.getBoolean("lock_expired"))
                                        .append("]");
                            }
                            return description.isEmpty() ? "no rows" : description.toString();
                        }));
    }

    /** Bounded poll: succeeds when the handler has run, fails on the deadline with the stored state. */
    private Future<Void> awaitDelivery(Vertx vertx, AtomicBoolean delivered, long deadlineMillis,
                                       String topic, String expectation) {
        if (delivered.get()) {
            return Future.succeededFuture();
        }
        if (System.currentTimeMillis() >= deadlineMillis) {
            return describeStoredMessages(topic).compose(stored -> Future.failedFuture(
                    new AssertionError(expectation + " Stored state at the deadline: " + stored)));
        }
        return vertx.timer(POLL_INTERVAL_MS)
                .compose(tick -> awaitDelivery(vertx, delivered, deadlineMillis, topic, expectation));
    }

    @Test
    void connectedListenOnlyConsumerReceivesDelayedMessageWhenItBecomesVisible(Vertx vertx,
                                                                              VertxTestContext testContext) {
        String topic = "listen-only-delayed-topic";
        AtomicBoolean delivered = new AtomicBoolean(false);
        producer = factory.createProducer(topic, String.class);
        consumer = listenOnlyConsumer(topic);

        consumer.subscribe(message -> {
                    delivered.set(true);
                    return Future.succeededFuture();
                })
                // The NOTIFY for this message fires at commit, two seconds before visible_at.
                .compose(v -> producer.send("delayed payload", Map.of("delaySeconds", "2"), null, null))
                .compose(v -> awaitDelivery(vertx, delivered, System.currentTimeMillis() + 15_000, topic,
                        "A connected LISTEN_NOTIFY_ONLY consumer must receive a message delayed by 2 s "
                                + "within 15 s of the send."))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void connectedListenOnlyConsumerReceivesMessageWhoseLockExpired(Vertx vertx, VertxTestContext testContext) {
        String topic = "listen-only-expired-lock-topic";
        AtomicBoolean delivered = new AtomicBoolean(false);
        producer = factory.createProducer(topic, String.class);
        consumer = listenOnlyConsumer(topic);

        producer.send("payload locked by a consumer that stopped")
                // Stand in for a consumer that claimed the message and stopped: the row is LOCKED
                // and its lock has already expired.
                .compose(v -> adminManager.withTransaction(ADMIN_POOL, conn ->
                        conn.preparedQuery("UPDATE " + SCHEMA + ".queue_messages "
                                        + "SET status = 'LOCKED', lock_until = now() - interval '1 second' "
                                        + "WHERE topic = $1")
                                .execute(Tuple.of(topic))))
                .compose(updated -> consumer.subscribe(message -> {
                    delivered.set(true);
                    return Future.succeededFuture();
                }))
                .compose(v -> awaitDelivery(vertx, delivered, System.currentTimeMillis() + 20_000, topic,
                        "A connected LISTEN_NOTIFY_ONLY consumer must receive a message whose lock "
                                + "expired, within 20 s of subscribing."))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }
}
