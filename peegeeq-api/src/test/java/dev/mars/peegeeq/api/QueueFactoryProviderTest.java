package dev.mars.peegeeq.api;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class QueueFactoryProviderTest {

    @Test
    void testDefaultMethods() {
        QueueFactoryProvider provider = new QueueFactoryProvider() {
            @Override
            public QueueFactory createFactory(String implementationType, DatabaseService databaseService, Map<String, Object> configuration) {
                return null; // Mock return
            }

            @Override
            public QueueFactory createFactory(String implementationType, DatabaseService databaseService) {
                return null;
            }

            @Override
            public Set<String> getSupportedTypes() {
                return null;
            }

            @Override
            public boolean isTypeSupported(String implementationType) {
                return false;
            }

            @Override
            public String getDefaultType() {
                return null;
            }

            @Override
            public Optional<String> getBestAvailableType() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> getConfigurationSchema(String implementationType) {
                return null;
            }
        };

        // Test createNamedFactory(String, String, DatabaseService, Map)
        // This default implementation delegates to createFactory(String, DatabaseService, Map)
        // Since our anonymous class returns null for createFactory, we expect null here too, 
        // but the execution path is what we are testing.
        QueueFactory factory1 = provider.createNamedFactory("type", "configName", null, new HashMap<>());
        assertNull(factory1);

        // Test createNamedFactory(String, String, DatabaseService)
        // This default implementation delegates to createNamedFactory(..., new HashMap<>())
        QueueFactory factory2 = provider.createNamedFactory("type", "configName", null);
        assertNull(factory2);
    }
}
