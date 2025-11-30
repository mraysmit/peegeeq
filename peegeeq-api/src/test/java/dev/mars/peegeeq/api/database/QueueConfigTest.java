package dev.mars.peegeeq.api.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;

@Tag("core")
class QueueConfigTest {

    @Test
    void testConstructorAndGetters() {
        Duration visibilityTimeout = Duration.ofMinutes(10);
        Duration pollingInterval = Duration.ofSeconds(5);
        
        QueueConfig config = new QueueConfig(
            "test-queue",
            visibilityTimeout,
            5,
            true,
            20,
            pollingInterval,
            true,
            "dlq-queue"
        );

        assertEquals("test-queue", config.getQueueName());
        assertEquals(visibilityTimeout, config.getVisibilityTimeout());
        assertEquals(5, config.getMaxRetries());
        assertTrue(config.isDeadLetterEnabled());
        assertEquals(20, config.getBatchSize());
        assertEquals(pollingInterval, config.getPollingInterval());
        assertTrue(config.isFifoEnabled());
        assertEquals("dlq-queue", config.getDeadLetterQueueName());
    }

    @Test
    void testDefaults() {
        QueueConfig config = new QueueConfig(
            "test-queue",
            null, // visibilityTimeout
            3,
            false,
            0, // batchSize
            null, // pollingInterval
            false,
            null
        );

        assertEquals(Duration.ofMinutes(5), config.getVisibilityTimeout());
        assertEquals(10, config.getBatchSize());
        assertEquals(Duration.ofSeconds(1), config.getPollingInterval());
    }
    
    @Test
    void testBuilder() {
        QueueConfig config = new QueueConfig.Builder()
            .queueName("builder-queue")
            .visibilityTimeoutSeconds(60)
            .maxRetries(5)
            .deadLetterEnabled(true)
            .batchSize(20)
            .pollingInterval(Duration.ofSeconds(2))
            .fifoEnabled(true)
            .deadLetterQueueName("builder-dlq")
            .build();

        assertEquals("builder-queue", config.getQueueName());
        assertEquals(Duration.ofSeconds(60), config.getVisibilityTimeout());
        assertEquals(5, config.getMaxRetries());
        assertTrue(config.isDeadLetterEnabled());
        assertEquals(20, config.getBatchSize());
        assertEquals(Duration.ofSeconds(2), config.getPollingInterval());
        assertTrue(config.isFifoEnabled());
        assertEquals("builder-dlq", config.getDeadLetterQueueName());
    }

    @Test
    void testBuilderWithDuration() {
        Duration timeout = Duration.ofMinutes(3);
        QueueConfig config = new QueueConfig.Builder()
            .queueName("duration-queue")
            .visibilityTimeout(timeout)
            .build();
            
        assertEquals(timeout, config.getVisibilityTimeout());
    }
}
