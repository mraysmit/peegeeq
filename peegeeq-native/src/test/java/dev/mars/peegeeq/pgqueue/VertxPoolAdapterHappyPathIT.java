package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.createContainer;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@Testcontainers
class VertxPoolAdapterHappyPathIT {

    @Container
    static final PostgreSQLContainer<?> postgres = createContainer(BASIC);

    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() {
        // Configure system properties for TestContainers
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Initialize PeeGeeQ Manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            try { manager.closeReactive().toCompletionStage().toCompletableFuture().join(); } catch (Exception ignore) {}
        }
    }

    @Test
    void connectDedicated_succeeds_withDatabaseService(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Arrange: create adapter using DatabaseService interfaces
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        VertxPoolAdapter adapter = new VertxPoolAdapter(
            databaseService.getVertx(),
            databaseService.getPool(),
            databaseService
        );

        // Act: connect dedicated and run a simple query
        adapter.connectDedicated()
            .compose((PgConnection conn) -> conn
                .query("SELECT 1 AS one")
                .execute()
                .onComplete(ar -> conn.close()))
            .onSuccess(rows -> {
                testContext.verify(() -> {
                    assertNotNull(rows);
                    assertEquals(1, rows.size());
                    Row row = rows.iterator().next();
                    assertEquals(1, row.getInteger("one"));
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void getPoolOrThrow_returnsDatabaseServicePool(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Arrange: create adapter using DatabaseService interfaces
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        VertxPoolAdapter adapter = new VertxPoolAdapter(
            databaseService.getVertx(),
            databaseService.getPool(),
            databaseService
        );

        // Act
        var pool = adapter.getPoolOrThrow();
        assertNotNull(pool);

        // Assert basic pool access by running a simple query
        pool.query("SELECT 1 AS one").execute()
            .onSuccess(rows -> {
                testContext.verify(() -> {
                    assertNotNull(rows);
                    assertEquals(1, rows.iterator().next().getInteger("one"));
                });
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

}



