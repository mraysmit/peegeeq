package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.database.QueueConfig;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueueConfig storage on DatabaseSetupResult.
 *
 * Verifies that putQueueConfig / getQueueConfig / getQueueConfigs behave
 * correctly for the normal path, multiple entries, and missing keys.
 */
@Tag("core")
class DatabaseSetupResultQueueConfigTest {

    private DatabaseSetupResult emptyResult() {
        return new DatabaseSetupResult(
                "test-setup",
                Collections.emptyMap(),
                Collections.emptyMap(),
                DatabaseSetupStatus.ACTIVE);
    }

    private QueueConfig nativeConfig(String name) {
        return new QueueConfig.Builder()
                .queueName(name)
                .implementationType("native")
                .maxRetries(5)
                .visibilityTimeoutSeconds(120)
                .batchSize(20)
                .pollingInterval(Duration.ofSeconds(3))
                .fifoEnabled(true)
                .deadLetterEnabled(true)
                .deadLetterQueueName(name + "_dlq")
                .build();
    }

    // ── putQueueConfig / getQueueConfig ───────────────────────────────────────

    @Test
    void putAndGet_returnsSameConfig() {
        DatabaseSetupResult result = emptyResult();
        QueueConfig cfg = nativeConfig("orders");

        result.putQueueConfig("orders", cfg);

        QueueConfig fetched = result.getQueueConfig("orders");
        assertNotNull(fetched);
        assertSame(cfg, fetched);
    }

    @Test
    void getQueueConfig_unknownName_returnsNull() {
        DatabaseSetupResult result = emptyResult();

        assertNull(result.getQueueConfig("does-not-exist"));
    }

    @Test
    void putQueueConfig_multipleQueues_eachReturnedCorrectly() {
        DatabaseSetupResult result = emptyResult();
        QueueConfig cfgA = nativeConfig("queue-a");
        QueueConfig cfgB = nativeConfig("queue-b");

        result.putQueueConfig("queue-a", cfgA);
        result.putQueueConfig("queue-b", cfgB);

        assertSame(cfgA, result.getQueueConfig("queue-a"));
        assertSame(cfgB, result.getQueueConfig("queue-b"));
        assertEquals(2, result.getQueueConfigs().size());
    }

    @Test
    void putQueueConfig_overwrite_returnsLatestValue() {
        DatabaseSetupResult result = emptyResult();
        QueueConfig first  = nativeConfig("q");
        QueueConfig second = nativeConfig("q");

        result.putQueueConfig("q", first);
        result.putQueueConfig("q", second);

        assertSame(second, result.getQueueConfig("q"));
        assertEquals(1, result.getQueueConfigs().size());
    }

    // ── getQueueConfigs ───────────────────────────────────────────────────────

    @Test
    void getQueueConfigs_initiallyEmpty() {
        DatabaseSetupResult result = emptyResult();

        assertTrue(result.getQueueConfigs().isEmpty());
    }

    @Test
    void getQueueConfigs_reflectsAllPuts() {
        DatabaseSetupResult result = emptyResult();
        result.putQueueConfig("alpha", nativeConfig("alpha"));
        result.putQueueConfig("beta",  nativeConfig("beta"));
        result.putQueueConfig("gamma", nativeConfig("gamma"));

        Map<String, QueueConfig> all = result.getQueueConfigs();
        assertEquals(3, all.size());
        assertTrue(all.containsKey("alpha"));
        assertTrue(all.containsKey("beta"));
        assertTrue(all.containsKey("gamma"));
    }

    // ── Config field values ───────────────────────────────────────────────────

    @Test
    void putQueueConfig_preservesAllFields() {
        DatabaseSetupResult result = emptyResult();
        QueueConfig cfg = new QueueConfig.Builder()
                .queueName("payments")
                .implementationType("outbox")
                .maxRetries(7)
                .visibilityTimeoutSeconds(60)
                .batchSize(50)
                .pollingInterval(Duration.ofSeconds(2))
                .fifoEnabled(false)
                .deadLetterEnabled(false)
                .deadLetterQueueName(null)
                .build();

        result.putQueueConfig("payments", cfg);
        QueueConfig fetched = result.getQueueConfig("payments");

        assertNotNull(fetched);
        assertEquals("outbox",    fetched.getImplementationType());
        assertEquals(7,            fetched.getMaxRetries());
        assertEquals(60,           fetched.getVisibilityTimeout().getSeconds());
        assertEquals(50,           fetched.getBatchSize());
        assertEquals(2,            fetched.getPollingInterval().getSeconds());
        assertFalse(fetched.isFifoEnabled());
        assertFalse(fetched.isDeadLetterEnabled());
        assertNull(fetched.getDeadLetterQueueName());
    }

    // ── Independence from queue factories ────────────────────────────────────

    @Test
    void queueConfigs_independentOfQueueFactories() {
        // Factories map is empty; config can still be stored and retrieved
        DatabaseSetupResult result = emptyResult();
        assertTrue(result.getQueueFactories().isEmpty());

        result.putQueueConfig("standalone", nativeConfig("standalone"));
        assertNotNull(result.getQueueConfig("standalone"));
        assertTrue(result.getQueueFactories().isEmpty(),
                "putQueueConfig must not affect the queueFactories map");
    }
}
