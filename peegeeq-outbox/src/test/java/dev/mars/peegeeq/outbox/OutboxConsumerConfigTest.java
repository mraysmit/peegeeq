package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.ServerSideFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for OutboxConsumerConfig.
 * Tests builder pattern, validation, defaults, and edge cases.
 */
class OutboxConsumerConfigTest {

    @Test
    @DisplayName("Should create config with default values")
    void testDefaultConfig() {
        OutboxConsumerConfig config = OutboxConsumerConfig.defaultConfig();
        
        assertNotNull(config);
        assertEquals(Duration.ofMillis(500), config.getPollingInterval());
        assertEquals(10, config.getBatchSize());
        assertEquals(1, config.getConsumerThreads());
        assertEquals(3, config.getMaxRetries());
        assertNull(config.getServerSideFilter());
        assertFalse(config.hasServerSideFilter());
    }

    @Test
    @DisplayName("Should create config with custom values")
    void testCustomConfig() {
        ServerSideFilter filter = ServerSideFilter.headerEquals("key", "value");
        
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .pollingInterval(Duration.ofSeconds(2))
                .batchSize(50)
                .consumerThreads(5)
                .maxRetries(10)
                .serverSideFilter(filter)
                .build();
        
        assertEquals(Duration.ofSeconds(2), config.getPollingInterval());
        assertEquals(50, config.getBatchSize());
        assertEquals(5, config.getConsumerThreads());
        assertEquals(10, config.getMaxRetries());
        assertEquals(filter, config.getServerSideFilter());
        assertTrue(config.hasServerSideFilter());
    }

    @Test
    @DisplayName("Should reject null polling interval")
    void testNullPollingInterval() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .pollingInterval(null);
        
        assertThrows(NullPointerException.class, builder::build);
    }

    @Test
    @DisplayName("Should reject zero polling interval")
    void testZeroPollingInterval() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .pollingInterval(Duration.ZERO);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Polling interval must be positive"));
    }

    @Test
    @DisplayName("Should reject negative polling interval")
    void testNegativePollingInterval() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .pollingInterval(Duration.ofMillis(-100));
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Polling interval must be positive"));
    }

    @Test
    @DisplayName("Should reject polling interval over 24 hours")
    void testPollingIntervalTooLarge() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .pollingInterval(Duration.ofHours(25));
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Polling interval too large"));
        assertTrue(ex.getMessage().contains("maximum 24 hours"));
    }

    @Test
    @DisplayName("Should accept 24 hour polling interval")
    void testPollingIntervalMaximum() {
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .pollingInterval(Duration.ofHours(24))
                .build();
        
        assertEquals(Duration.ofHours(24), config.getPollingInterval());
    }

    @Test
    @DisplayName("Should reject zero batch size")
    void testZeroBatchSize() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .batchSize(0);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Batch size must be positive"));
    }

    @Test
    @DisplayName("Should reject negative batch size")
    void testNegativeBatchSize() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .batchSize(-10);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Batch size must be positive"));
    }

    @Test
    @DisplayName("Should reject batch size over 10000")
    void testBatchSizeTooLarge() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .batchSize(10001);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Batch size too large"));
        assertTrue(ex.getMessage().contains("maximum 10000"));
    }

    @Test
    @DisplayName("Should accept maximum batch size")
    void testBatchSizeMaximum() {
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .batchSize(10000)
                .build();
        
        assertEquals(10000, config.getBatchSize());
    }

    @Test
    @DisplayName("Should reject zero consumer threads")
    void testZeroConsumerThreads() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .consumerThreads(0);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Consumer threads must be positive"));
    }

    @Test
    @DisplayName("Should reject negative consumer threads")
    void testNegativeConsumerThreads() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .consumerThreads(-5);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Consumer threads must be positive"));
    }

    @Test
    @DisplayName("Should reject consumer threads over 1000")
    void testConsumerThreadsTooLarge() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .consumerThreads(1001);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Consumer threads too large"));
        assertTrue(ex.getMessage().contains("maximum 1000"));
    }

    @Test
    @DisplayName("Should accept maximum consumer threads")
    void testConsumerThreadsMaximum() {
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .consumerThreads(1000)
                .build();
        
        assertEquals(1000, config.getConsumerThreads());
    }

    @Test
    @DisplayName("Should reject negative max retries")
    void testNegativeMaxRetries() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .maxRetries(-1);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Max retries cannot be negative"));
    }

    @Test
    @DisplayName("Should accept zero max retries")
    void testZeroMaxRetries() {
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .maxRetries(0)
                .build();
        
        assertEquals(0, config.getMaxRetries());
    }

    @Test
    @DisplayName("Should reject max retries over 100")
    void testMaxRetriesTooLarge() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder()
                .maxRetries(101);
        
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(ex.getMessage().contains("Max retries too large"));
        assertTrue(ex.getMessage().contains("maximum 100"));
    }

    @Test
    @DisplayName("Should accept maximum max retries")
    void testMaxRetriesMaximum() {
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .maxRetries(100)
                .build();
        
        assertEquals(100, config.getMaxRetries());
    }

    @Test
    @DisplayName("Should allow null server side filter")
    void testNullServerSideFilter() {
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .serverSideFilter(null)
                .build();
        
        assertNull(config.getServerSideFilter());
        assertFalse(config.hasServerSideFilter());
    }

    @Test
    @DisplayName("Should support method chaining")
    void testMethodChaining() {
        OutboxConsumerConfig.Builder builder = OutboxConsumerConfig.builder();
        
        OutboxConsumerConfig.Builder result = builder
                .pollingInterval(Duration.ofSeconds(1))
                .batchSize(20)
                .consumerThreads(2)
                .maxRetries(5)
                .serverSideFilter(ServerSideFilter.headerEquals("test", "value"));
        
        assertSame(builder, result, "Builder methods should return same instance for chaining");
        
        OutboxConsumerConfig config = result.build();
        assertEquals(Duration.ofSeconds(1), config.getPollingInterval());
        assertEquals(20, config.getBatchSize());
        assertEquals(2, config.getConsumerThreads());
        assertEquals(5, config.getMaxRetries());
    }

    @Test
    @DisplayName("Should produce meaningful toString")
    void testToString() {
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .pollingInterval(Duration.ofSeconds(1))
                .batchSize(25)
                .consumerThreads(3)
                .maxRetries(7)
                .build();
        
        String str = config.toString();
        assertNotNull(str);
        assertTrue(str.contains("OutboxConsumerConfig"));
        assertTrue(str.contains("pollingInterval="));
        assertTrue(str.contains("batchSize="));
        assertTrue(str.contains("consumerThreads="));
        assertTrue(str.contains("maxRetries="));
    }

    @Test
    @DisplayName("Should include server side filter in toString when present")
    void testToStringWithFilter() {
        ServerSideFilter filter = ServerSideFilter.headerEquals("key", "value");
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .serverSideFilter(filter)
                .build();
        
        String str = config.toString();
        assertTrue(str.contains("serverSideFilter="));
    }

    @Test
    @DisplayName("Should show null filter in toString when absent")
    void testToStringWithoutFilter() {
        OutboxConsumerConfig config = OutboxConsumerConfig.builder().build();
        
        String str = config.toString();
        assertTrue(str.contains("serverSideFilter=null"));
    }

    @Test
    @DisplayName("Should allow building multiple configs from same builder class")
    void testMultipleBuilds() {
        OutboxConsumerConfig config1 = OutboxConsumerConfig.builder()
                .batchSize(10)
                .build();
        
        OutboxConsumerConfig config2 = OutboxConsumerConfig.builder()
                .batchSize(20)
                .build();
        
        assertEquals(10, config1.getBatchSize());
        assertEquals(20, config2.getBatchSize());
        assertNotSame(config1, config2);
    }

    @Test
    @DisplayName("Should handle edge case minimum values")
    void testEdgeCaseMinimumValues() {
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
                .pollingInterval(Duration.ofMillis(1))
                .batchSize(1)
                .consumerThreads(1)
                .maxRetries(0)
                .build();
        
        assertEquals(Duration.ofMillis(1), config.getPollingInterval());
        assertEquals(1, config.getBatchSize());
        assertEquals(1, config.getConsumerThreads());
        assertEquals(0, config.getMaxRetries());
    }
}
