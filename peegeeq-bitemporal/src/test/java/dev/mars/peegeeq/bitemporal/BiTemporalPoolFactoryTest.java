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

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class BiTemporalPoolFactoryTest {

    private Vertx vertx;
    private PeeGeeQManager peeGeeQManager;
    private BiTemporalPoolFactory poolFactory;

    @BeforeEach
    void setUp(Vertx vertx) {
        // Set configuration properties for pool factory
        Properties testProps = new Properties();
        testProps.setProperty("peegeeq.database.host", "localhost");
        testProps.setProperty("peegeeq.database.port", "5432");
        testProps.setProperty("peegeeq.database.name", "test_db");
        testProps.setProperty("peegeeq.database.username", "test_user");
        testProps.setProperty("peegeeq.database.password", "test_pass");
        testProps.setProperty("peegeeq.database.pool.max-size", "10");

        this.vertx = vertx;
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        peeGeeQManager = new PeeGeeQManager(config, new SimpleMeterRegistry(), vertx);
        poolFactory = new BiTemporalPoolFactory(vertx, peeGeeQManager);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (poolFactory != null) {
            poolFactory.close();
        }

        Future<Void> closeFuture = peeGeeQManager != null
                ? peeGeeQManager.closeReactive().transform(ar -> Future.<Void>succeededFuture())
                : Future.succeededFuture();
        
        closeFuture
                .onSuccess(v -> {
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



