package dev.mars.peegeeq.api.setup;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.api.database.EventStoreConfig;
import dev.mars.peegeeq.api.database.QueueConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class DatabaseSetupRequestTest {

    @Test
    void testConstructorAndGetters() {
        DatabaseConfig dbConfig = new DatabaseConfig("host", 5432, "db", "user", "pass", "schema", false, null, null, null);
        List<QueueConfig> queues = Collections.emptyList();
        List<EventStoreConfig> eventStores = Collections.emptyList();
        Map<String, Object> props = Collections.singletonMap("key", "value");

        DatabaseSetupRequest request = new DatabaseSetupRequest(
            "setup-1",
            dbConfig,
            queues,
            eventStores,
            props
        );

        assertEquals("setup-1", request.getSetupId());
        assertEquals(dbConfig, request.getDatabaseConfig());
        assertEquals(queues, request.getQueues());
        assertEquals(eventStores, request.getEventStores());
        assertEquals(props, request.getAdditionalProperties());
    }
}
