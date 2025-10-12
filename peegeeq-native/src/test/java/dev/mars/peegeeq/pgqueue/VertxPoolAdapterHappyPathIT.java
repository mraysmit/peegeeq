package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import io.vertx.core.Vertx;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.PerformanceProfile.BASIC;
import static dev.mars.peegeeq.test.containers.PeeGeeQTestContainerFactory.createContainer;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class VertxPoolAdapterHappyPathIT {

    @Container
    static final PostgreSQLContainer<?> postgres = createContainer(BASIC);

    private Vertx vertx;
    private PgClientFactory factory;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        factory = new PgClientFactory(vertx);
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            try { factory.closeAsync().toCompletionStage().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join(); } catch (Exception ignore) {}
        }
        if (vertx != null) {
            try { vertx.close().toCompletionStage().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join(); } catch (Exception ignore) {}
        }
    }

    @Test
    void connectDedicated_succeeds_afterFactoryClientCreated() {
        // Arrange: register configs and create client/pool under clientId "native-queue"
        PgConnectionConfig connCfg = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .sslEnabled(false)
            .build();

        PgPoolConfig poolCfg = new PgPoolConfig.Builder()
            .maxSize(4)
            .maxWaitQueueSize(16)
            .shared(true)
            .build();

        factory.createClient("native-queue", connCfg, poolCfg);
        VertxPoolAdapter adapter = new VertxPoolAdapter(vertx, factory, "native-queue");

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
    void getPoolOrThrow_returnsFactoryManagedPool() {
        // Arrange
        PgConnectionConfig connCfg = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .sslEnabled(false)
            .build();

        PgPoolConfig poolCfg = new PgPoolConfig.Builder()
            .maxSize(3)
            .maxWaitQueueSize(8)
            .shared(true)
            .build();

        factory.createClient("native-queue", connCfg, poolCfg);
        VertxPoolAdapter adapter = new VertxPoolAdapter(vertx, factory, "native-queue");

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

