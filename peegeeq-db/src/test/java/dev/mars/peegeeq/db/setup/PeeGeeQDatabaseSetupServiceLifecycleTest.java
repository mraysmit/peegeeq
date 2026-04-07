package dev.mars.peegeeq.db.setup;

import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import org.junit.jupiter.api.Test;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PeeGeeQDatabaseSetupServiceLifecycleTest {

    @Test
    void closeShouldCloseSetupWorkerExecutor() throws Exception {
        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
        WorkerExecutor worker = service.setupWorkerExecutor();

        // Sanity check: worker should be usable before close.
        worker.executeBlocking(() -> "ok", false)
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        service.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
            worker.executeBlocking(() -> "after-close", false)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .join());

        Throwable effective = (thrown instanceof CompletionException && thrown.getCause() != null)
            ? thrown.getCause()
            : thrown;
        assertTrue(effective instanceof RejectedExecutionException,
            "Expected rejected execution once setup worker executor is closed");
    }

    @Test
    void closeShouldNotCloseExternalVertx() throws Exception {
        Vertx externalVertx = Vertx.vertx();
        try {
            PeeGeeQDatabaseSetupService service = createServiceInsideVertxContext(externalVertx);

            service.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            CompletableFuture<Void> stillUsable = new CompletableFuture<>();
            externalVertx.runOnContext(v -> stillUsable.complete(null));
            stillUsable.get(5, TimeUnit.SECONDS);
        } finally {
            externalVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private static PeeGeeQDatabaseSetupService createServiceInsideVertxContext(Vertx vertx) throws Exception {
        CompletableFuture<PeeGeeQDatabaseSetupService> future = new CompletableFuture<>();
        vertx.runOnContext(v -> future.complete(new PeeGeeQDatabaseSetupService()));
        return future.get(5, TimeUnit.SECONDS);
    }
}
