package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import dev.mars.peegeeq.test.categories.TestCategories;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
class VertxPoolAdapterTest {

    private Vertx vertx;
    private PeeGeeQManager peeGeeQManager;
    private VertxPoolAdapter vertxPoolAdapter;

    @BeforeEach
    void setUp() {
        // Set system properties for configuration
        System.setProperty("peegeeq.database.host", "localhost");
        System.setProperty("peegeeq.database.port", "5432");
        System.setProperty("peegeeq.database.name", "test_db");
        System.setProperty("peegeeq.database.username", "test_user");
        System.setProperty("peegeeq.database.password", "test_pass");
        System.setProperty("peegeeq.database.pool.max-size", "10");

        vertx = Vertx.vertx();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        peeGeeQManager = new PeeGeeQManager(config, new SimpleMeterRegistry(), vertx);
        vertxPoolAdapter = new VertxPoolAdapter(vertx, peeGeeQManager);
    }

    @AfterEach
    void tearDown() {
        if (vertxPoolAdapter != null) {
            vertxPoolAdapter.close();
        }
        if (peeGeeQManager != null) {
            peeGeeQManager.close();
        }
        if (vertx != null) {
            vertx.close();
        }
        
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.pool.max-size");
    }

    @Test
    void shouldCreatePoolFromConfiguration() {
        Pool pool = vertxPoolAdapter.getOrCreatePool();
        assertNotNull(pool);
        // We can't easily inspect the pool internals without casting to internal classes, 
        // but we know it didn't throw and returned a pool.
        // Since we used real config, it should be configured.
    }

    @Test
    void shouldReturnCachedPool() {
        Pool pool1 = vertxPoolAdapter.getOrCreatePool();
        Pool pool2 = vertxPoolAdapter.getOrCreatePool();
        assertSame(pool1, pool2);
    }
    
    @Test
    void shouldClosePoolGracefully() {
        vertxPoolAdapter.getOrCreatePool();
        assertNotNull(vertxPoolAdapter.getPool());
        vertxPoolAdapter.close();
        assertNull(vertxPoolAdapter.getPool());
    }
}

