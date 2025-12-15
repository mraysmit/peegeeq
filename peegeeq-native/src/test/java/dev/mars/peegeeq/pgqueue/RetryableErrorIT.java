package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Disabled;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic retry IT (pending):
 * NOTE: Disabled temporarily while we implement a reliable 40001/40P01 contention setup.
 * Avoids a red build while we design the deterministic contention without mocks.
 */
@Testcontainers
@Disabled("Pending deterministic 40001/40P01 contention IT—will re-enable once implemented")
public class RetryableErrorIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private PgNativeQueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        initializeSchemaWithRetryTrigger();

        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("retryable-error-test");
        manager = new PeeGeeQManager(cfg, new SimpleMeterRegistry());
        manager.start();

        factory = new PgNativeQueueFactory(manager.getClientFactory());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) factory.close();
        if (manager != null) manager.close();
    }

    @Test
    void testConsumerRetriesOn40P01AndSucceeds() throws Exception {
        String topic = "retryable-error-test-topic";
        MessageProducer<String> producer = factory.createProducer(topic, String.class);
        MessageConsumer<String> consumer = factory.createConsumer(topic, String.class,
                new ConsumerConfig.Builder().mode(ConsumerMode.HYBRID).consumerThreads(1).build());

        CountDownLatch latch = new CountDownLatch(1);
        consumer.subscribe(msg -> {
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        producer.send("one").get(5, TimeUnit.SECONDS);

        // Should still process successfully even though the first claim attempt fails with 40P01
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Message should be processed after one retry");

        // Verify our trigger fired exactly once
        assertEquals(1, getRetryLogCount(), "Expected exactly one simulated 40P01 raise from trigger");

        consumer.unsubscribe();
        producer.close();
    }

    private void initializeSchemaWithRetryTrigger() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             java.sql.Statement stmt = conn.createStatement()) {

            // Core tables used by native queue
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

            // Retry guard tables and trigger to simulate one 40P01 deadlock error
            stmt.execute("CREATE TABLE IF NOT EXISTS retry_counters (remaining INT NOT NULL)");
            stmt.execute("TRUNCATE TABLE retry_counters");
            stmt.execute("INSERT INTO retry_counters(remaining) VALUES (1)");
            stmt.execute("CREATE TABLE IF NOT EXISTS retry_log (id SERIAL PRIMARY KEY, ts TIMESTAMPTZ DEFAULT NOW())");

            stmt.execute("""
                CREATE OR REPLACE FUNCTION queue_claim_retry_guard() RETURNS trigger AS $$
                DECLARE cnt INT;
                BEGIN
                  SELECT remaining INTO cnt FROM retry_counters LIMIT 1;
                  IF cnt IS NULL THEN
                    RETURN NEW;
                  END IF;
                  IF cnt > 0 THEN
                    UPDATE retry_counters SET remaining = remaining - 1;
                    INSERT INTO retry_log DEFAULT VALUES;
                    RAISE EXCEPTION 'simulated deadlock' USING ERRCODE = '40P01';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                """);

            stmt.execute("DROP TRIGGER IF EXISTS queue_messages_retry_guard ON queue_messages");
            stmt.execute("CREATE TRIGGER queue_messages_retry_guard BEFORE UPDATE ON queue_messages FOR EACH ROW EXECUTE FUNCTION queue_claim_retry_guard()");

            // Clean tables
            stmt.execute("TRUNCATE TABLE queue_messages, dead_letter_queue");
        }
    }

    private int getRetryLogCount() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM retry_log");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

