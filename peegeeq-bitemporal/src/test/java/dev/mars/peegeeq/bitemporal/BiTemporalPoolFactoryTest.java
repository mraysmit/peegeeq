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
import org.junit.jupiter.api.extension.ExtendWith;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class BiTemporalPoolFactoryTest {

    private Vertx vertx;
    private PeeGeeQManager peeGeeQManager;
    private BiTemporalPoolFactory poolFactory;

    @BeforeEach
    void setUp(Vertx vertx) {
        // Set system properties for configuration
        System.setProperty("peegeeq.database.host", "localhost");
        System.setProperty("peegeeq.database.port", "5432");
        System.setProperty("peegeeq.database.name", "test_db");
        System.setProperty("peegeeq.database.username", "test_user");
        System.setProperty("peegeeq.database.password", "test_pass");
        System.setProperty("peegeeq.database.pool.max-size", "10");

        this.vertx = vertx;
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        peeGeeQManager = new PeeGeeQManager(config, new SimpleMeterRegistry(), vertx);
        poolFactory = new BiTemporalPoolFactory(vertx, peeGeeQManager);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (poolFactory != null) {
            poolFactory.close();
        }

        Future<Void> closeFuture = peeGeeQManager != null
                ? peeGeeQManager.closeReactive().recover(error -> Future.<Void>succeededFuture())
                : Future.succeededFuture();
        
        closeFuture
                .onSuccess(v -> {
                    System.clearProperty("peegeeq.database.host");
                    System.clearProperty("peegeeq.database.port");
                    System.clearProperty("peegeeq.database.name");
                    System.clearProperty("peegeeq.database.username");
                    System.clearProperty("peegeeq.database.password");
                    System.clearProperty("peegeeq.database.pool.max-size");
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @Test
    void shouldCreatePoolFromConfiguration() {
        Pool pool = poolFactory.getOrCreatePool();
        assertNotNull(pool);
        // We can't easily inspect the pool internals without casting to internal classes, 
        // but we know it didn't throw and returned a pool.
        // Since we used real config, it should be configured.
    }

    @Test
    void shouldReturnCachedPool() {
        Pool pool1 = poolFactory.getOrCreatePool();
        Pool pool2 = poolFactory.getOrCreatePool();
        assertSame(pool1, pool2);
    }
    
    @Test
    void shouldClosePoolGracefully() {
        poolFactory.getOrCreatePool();
        assertNotNull(poolFactory.getPool());
        poolFactory.close();
        assertNull(poolFactory.getPool());
    }
}



