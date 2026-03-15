package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgConnection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class VertxPoolAdapterFailFastTest {

    @Test
    void getPoolOrThrow_withNullPool_failsFast(Vertx vertx) {
        // Create adapter with null pool
        VertxPoolAdapter adapter = new VertxPoolAdapter(vertx, null, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, adapter::getPoolOrThrow);
        assertTrue(ex.getMessage().contains("Pool is not available"));
    }

    @Test
    void connectDedicated_withoutConnectOptionsProvider_failsFast_withClearMessage(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Create adapter with null ConnectOptionsProvider
        VertxPoolAdapter adapter = new VertxPoolAdapter(vertx, null, null);

        adapter.connectDedicated()
            .onSuccess(conn -> testContext.failNow(new AssertionError("Should have failed")))
            .onFailure(err -> {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                testContext.verify(() ->
                    assertTrue(cause.getMessage().contains("No ConnectOptionsProvider available")));
                testContext.completeNow();
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }
}

