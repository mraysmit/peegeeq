package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class DatabaseSetupResultTest {

    @Test
    void testConstructorAndGetters() {
        Map<String, QueueFactory> queueFactories = Collections.emptyMap();
        Map<String, EventStore<?>> eventStores = Collections.emptyMap();
        DatabaseSetupStatus status = DatabaseSetupStatus.ACTIVE;

        DatabaseSetupResult result = new DatabaseSetupResult(
            "setup-1",
            queueFactories,
            eventStores,
            status
        );

        assertEquals("setup-1", result.getSetupId());
        assertEquals(queueFactories, result.getQueueFactories());
        assertEquals(eventStores, result.getEventStores());
        assertEquals(status, result.getStatus());
        assertNull(result.getConnectionUrl());
        assertTrue(result.getCreatedAt() > 0);
    }
}
