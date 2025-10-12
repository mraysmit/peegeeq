package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault-injection IT: forcibly closes the dedicated LISTEN connection and verifies
 * the consumer reconnects (with backoff) and continues to receive messages.
 */
@Testcontainers
public class ListenReconnectFaultInjectionIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

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

        // Use legacy constructor; it now injects Vert.x from PgClientFactory for timers
        factory = new PgNativeQueueFactory(manager.getClientFactory());
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
        if (manager != null) manager.close();
    }

    @Test
    void testListenReconnectAfterForcedDisconnect() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(msg -> {
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Give the consumer a moment to establish LISTEN connection
        Thread.sleep(1000);

        // Fault injection: close the dedicated LISTEN PgConnection via reflection
        // so closeHandler runs and scheduleListenReconnect() is triggered.
        forceCloseListenConnection(consumer);

        // Wait a bit for reconnect backoff (starts at ~1s) to kick in
        Thread.sleep(1500);

        // Send a message; should be received after reconnect
        producer.send("after-reconnect").get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Consumer should receive message after LISTEN reconnect");
    }

    private static void forceCloseListenConnection(MessageConsumer<String> c) throws Exception {
        PgNativeQueueConsumer<?> concrete = (PgNativeQueueConsumer<?>) c; // same package, safe cast
        Field f = PgNativeQueueConsumer.class.getDeclaredField("subscriber");
        f.setAccessible(true);
        Object subscriber = f.get(concrete);
        if (subscriber != null) {
            // Call close() on the PgConnection; closeHandler will schedule reconnect
            subscriber.getClass().getMethod("close").invoke(subscriber);
        }
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

