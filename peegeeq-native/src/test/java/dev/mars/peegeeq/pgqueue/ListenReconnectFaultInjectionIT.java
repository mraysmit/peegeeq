package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.time.Duration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vertx.core.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault-injection IT: forcibly closes the dedicated LISTEN connection and verifies
 * the consumer reconnects (with backoff) and continues to receive messages.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class ListenReconnectFaultInjectionIT {

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE);
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

    private PeeGeeQManager manager;
    private PgNativeQueueFactory factory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    private static final String TOPIC = "reconnect-fault-test";

    @BeforeEach
    void setUp() throws Exception {
        // Configure DB for this test run
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Initialize schema
        initializeSchema();

        // Start manager using a dedicated profile
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("listen-reconnect-test");
        manager = new PeeGeeQManager(cfg, new SimpleMeterRegistry());
        manager.start();

        // Use DatabaseService pattern for factory creation
        DatabaseService databaseService = new PgDatabaseService(manager);
        factory = new PgNativeQueueFactory(databaseService);
        producer = factory.createProducer(TOPIC, String.class);
        consumer = factory.createConsumer(TOPIC, String.class, new ConsumerConfig.Builder()
            .mode(ConsumerMode.HYBRID)
            .pollingInterval(Duration.ofMillis(1000))
            .consumerThreads(1)
            .build());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) consumer.unsubscribe();
        if (factory != null) factory.close();
        if (manager != null) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            manager.closeReactive().onComplete(ar -> closeLatch.countDown());
            closeLatch.await(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void testListenReconnectAfterForcedDisconnect(Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer.subscribe(msg -> {
            testContext.completeNow();
            return Future.succeededFuture();
        });

        // Wait for LISTEN connection to establish
        vertx.setPeriodic(100, waitId -> {
            try {
                if (getSubscriber((PgNativeQueueConsumer<?>) consumer) != null) {
                    vertx.cancelTimer(waitId);

                    // Fault injection: close the dedicated LISTEN PgConnection
                    forceCloseListenConnection(consumer);

                    // Wait for reconnect backoff to re-establish the connection
                    vertx.setPeriodic(100, reconnectId -> {
                        try {
                            if (getSubscriber((PgNativeQueueConsumer<?>) consumer) != null) {
                                vertx.cancelTimer(reconnectId);
                                // Send a message; should be received after reconnect
                                producer.send("after-reconnect");
                            }
                        } catch (Exception e) {
                            testContext.failNow(e);
                        }
                    });
                }
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Consumer should receive message after LISTEN reconnect");
    }

    @Test
    void testUnsubscribeDoesNotReestablishListenConnection(Vertx vertx, VertxTestContext testContext) throws Exception {
        consumer.subscribe(msg -> Future.succeededFuture());

        PgNativeQueueConsumer<?> concrete = (PgNativeQueueConsumer<?>) consumer;

        // Wait for LISTEN connection to establish
        vertx.setPeriodic(100, waitId -> {
            try {
                if (getSubscriber(concrete) != null) {
                    vertx.cancelTimer(waitId);
                    assertNotNull(getSubscriber(concrete), "Subscribed consumer should establish a LISTEN connection");

                    consumer.unsubscribe();

                    // Wait for LISTEN connection to be torn down
                    vertx.setPeriodic(100, checkId -> {
                        try {
                            if (getSubscriber(concrete) == null) {
                                vertx.cancelTimer(checkId);
                                testContext.verify(() -> {
                                    assertNull(getSubscriber(concrete), "Unsubscribed consumer must not establish a new LISTEN connection");
                                    assertEquals(-1L, getListenReconnectTimerId(concrete), "Unsubscribed consumer must not schedule LISTEN reconnect");
                                    assertFalse(isSubscribed(concrete), "Consumer should remain unsubscribed after teardown of LISTEN connection");
                                });
                                testContext.completeNow();
                            }
                        } catch (Exception e) {
                            testContext.failNow(e);
                        }
                    });
                }
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    private static void forceCloseListenConnection(MessageConsumer<String> c) throws Exception {
        PgNativeQueueConsumer<?> concrete = (PgNativeQueueConsumer<?>) c; // same package, safe cast
        concrete.closeSubscriberConnectionForTest();
    }

    private static Object getSubscriber(PgNativeQueueConsumer<?> consumer) throws Exception {
        Field field = PgNativeQueueConsumer.class.getDeclaredField("subscriber");
        field.setAccessible(true);
        return field.get(consumer);
    }

    private static long getListenReconnectTimerId(PgNativeQueueConsumer<?> consumer) throws Exception {
        Field field = PgNativeQueueConsumer.class.getDeclaredField("listenReconnectTimerId");
        field.setAccessible(true);
        return field.getLong(consumer);
    }

    @SuppressWarnings("unchecked")
    private static boolean isSubscribed(PgNativeQueueConsumer<?> consumer) throws Exception {
        Field field = PgNativeQueueConsumer.class.getDeclaredField("subscribed");
        field.setAccessible(true);
        return ((AtomicBoolean) field.get(consumer)).get();
    }

    private void initializeSchema() {
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             java.sql.Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS queue_messages (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    lock_id BIGINT,
                    lock_until TIMESTAMP WITH TIME ZONE,
                    retry_count INT DEFAULT 0,
                    max_retries INT DEFAULT 3,
                    status VARCHAR(50) DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'PROCESSED', 'FAILED', 'DEAD_LETTER')),
                    headers JSONB DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255),
                    priority INT DEFAULT 5 CHECK (priority BETWEEN 1 AND 10)
                )
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS dead_letter_queue (
                    id BIGSERIAL PRIMARY KEY,
                    original_table VARCHAR(50) NOT NULL,
                    original_id BIGINT NOT NULL,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    failure_reason TEXT NOT NULL,
                    retry_count INT NOT NULL,
                    headers JSONB DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255)
                )
                """);

            // Clear existing data
            stmt.execute("TRUNCATE TABLE queue_messages, dead_letter_queue");

        } catch (Exception e) {
            throw new RuntimeException("Schema initialization failed", e);
        }
    }
}



