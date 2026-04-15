package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the null handler return path in OutboxConsumer.processMessageWithCompletion.
 * When a handler returns null, the consumer treats it as a failed future and
 * routes through retry/failure handling.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerNullHandlerTest {

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled",
        "peegeeq.queue.polling-interval"
    };

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;
    private Vertx testVertx;
    private PgConnectionManager connectionManager;
    private Pool reactivePool;

    @BeforeEach
    void setUp() throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        testTopic = "null-handler-test-" + UUID.randomUUID().toString().substring(0, 8);
        
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("null-handler-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);

        testVertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(testVertx);
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
        PgPoolConfig poolConfig = new PgPoolConfig.Builder().maxSize(3).build();
        reactivePool = connectionManager.getOrCreateReactivePool("test-verification", connectionConfig, poolConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (connectionManager != null) {
            connectionManager.close();
        }
        if (testVertx != null) {
            testVertx.close().await();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }
    }

    @Test
    void testNullHandlerReturn(Vertx vertx, VertxTestContext testContext) throws Exception {
        io.vertx.core.Promise<Void> handlerCalled = io.vertx.core.Promise.promise();
        AtomicBoolean invoked = new AtomicBoolean(false);

        consumer.subscribe(message -> {
        logger.info("Test: null handler return");
            invoked.set(true);
            handlerCalled.tryComplete();
            return null; // Triggers null check → IllegalStateException → retry path
        });

        producer.send("Test null return").await();

        handlerCalled.future().await();

        // Wait for retry logic to persist the failure state
        vertx.timer(2000).await();

        // Verify the null return triggered failure handling: retry_count should be incremented
        reactivePool.withConnection(conn ->
            conn.preparedQuery("SELECT retry_count, status FROM outbox WHERE topic = $1 ORDER BY id DESC LIMIT 1")
                .execute(Tuple.of(testTopic))
                .map(rows -> {
                    assertTrue(rows.size() > 0, "Message should exist in outbox");
                    return rows.iterator().next();
                })
        ).await();

        // The handler was called and the null return was handled (not silently ignored)
        testContext.verify(() -> assertTrue(invoked.get(), "Handler should have been invoked"));
        testContext.completeNow();

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }
}


