package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
class VertxPoolAdapterFailFastTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            try {
                vertx.close().toCompletionStage().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            } catch (Exception ignore) {}
        }
    }

    @Test
    void getPoolOrThrow_withoutFactoryPool_failsFast() {
        // No pool registered for this clientId
        String clientId = "native-queue";
        PgClientFactory factory = new PgClientFactory(vertx);
        VertxPoolAdapter adapter = new VertxPoolAdapter(vertx, factory, clientId);

        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::getPoolOrThrow);
        assertTrue(ex.getMessage().contains("No pool available for clientId=" + clientId));
    }

    @Test
    void connectDedicated_withoutConfig_failsFast_withClearMessage() {
        // No connection config registered for this clientId
        String clientId = "native-queue";
        PgClientFactory factory = new PgClientFactory(vertx);
        VertxPoolAdapter adapter = new VertxPoolAdapter(vertx, factory, clientId);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<PgConnection> cf = adapter.connectDedicated().toCompletionStage().toCompletableFuture();
        cf.whenComplete((conn, err) -> {
            if (err != null) failure.set(err.getCause() != null ? err.getCause() : err);
        });

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> failure.get() != null);
        assertTrue(failure.get().getMessage().contains("Missing connection config for clientId=" + clientId));
    }
}

