package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.createContainer;
import static org.junit.jupiter.api.Assertions.*;

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
            try { manager.close(); } catch (Exception ignore) {}
        }
    }

    @Test
    void connectDedicated_succeeds_withDatabaseService() {
        // Arrange: create adapter using DatabaseService interfaces
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        VertxPoolAdapter adapter = new VertxPoolAdapter(
            databaseService.getVertx(),
            databaseService.getPool(),
            databaseService
        );

        // Act: connect dedicated and run a simple query
        AtomicReference<RowSet<Row>> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        CompletableFuture<Void> done = new CompletableFuture<>();
        adapter.connectDedicated()
            .compose((PgConnection conn) -> conn
                .query("SELECT 1 AS one")
                .execute()
                .onComplete(ar -> conn.close()))
            .onSuccess(rows -> { resultRef.set(rows); done.complete(null); })
            .onFailure(err -> { errorRef.set(err); done.complete(null); });

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(done::isDone);

        // Assert
        assertNull(errorRef.get(), () -> "Unexpected failure: " + errorRef.get());
        assertNotNull(resultRef.get());
        RowSet<Row> rows = resultRef.get();
        assertEquals(1, rows.size());
        Row row = rows.iterator().next();
        assertEquals(1, row.getInteger("one"));
    }

    @Test
    void getPoolOrThrow_returnsDatabaseServicePool() {
        // Arrange: create adapter using DatabaseService interfaces
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        VertxPoolAdapter adapter = new VertxPoolAdapter(
            databaseService.getVertx(),
            databaseService.getPool(),
            databaseService
        );

        // Act
        var pool = adapter.getPoolOrThrow();

        // Assert basic pool access by running a simple query
        assertNotNull(pool);
        AtomicReference<RowSet<Row>> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        pool.query("SELECT 1 AS one").execute()
            .onSuccess(rows -> { resultRef.set(rows); done.complete(null); })
            .onFailure(err -> { errorRef.set(err); done.complete(null); });

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(done::isDone);
        assertNull(errorRef.get());
        assertNotNull(resultRef.get());
        assertEquals(1, resultRef.get().iterator().next().getInteger("one"));
    }

}

