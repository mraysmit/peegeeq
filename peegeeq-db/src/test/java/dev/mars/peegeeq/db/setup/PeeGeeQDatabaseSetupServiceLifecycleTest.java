package dev.mars.peegeeq.db.setup;

import java.util.concurrent.CompletionException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
@Tag(TestCategories.CORE)
class PeeGeeQDatabaseSetupServiceLifecycleTest {

    @Test
    void closeShouldCloseSetupWorkerExecutor(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(ignored -> {
            PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();
            WorkerExecutor worker = service.setupWorkerExecutor();

            worker.executeBlocking(() -> "ok", false)
                    .compose(ok -> service.close())
                    .onSuccess(v -> {
                        try {
                            worker.executeBlocking(() -> "after-close", false)
                                    .onSuccess(result -> {
                                        ctx.failNow(new AssertionError("Expected worker to be rejected after close"));
                                    })
                                    .onFailure(err -> {
                                        Throwable effective = err;
                                        if (err instanceof CompletionException && err.getCause() != null) {
                                            effective = err.getCause();
                                        }
                                        if (effective instanceof RejectedExecutionException) {
                                            ctx.completeNow();
                                        } else {
                                            ctx.failNow(new AssertionError(
                                                    "Expected RejectedExecutionException but got: " + effective.getClass().getName(),
                                                    effective));
                                        }
                                    });
                        } catch (RejectedExecutionException e) {
                            // Caught inline
                            ctx.completeNow();
                        } catch (IllegalStateException e) {
                            if (e.getMessage() != null && e.getMessage().contains("close")) {
                                ctx.completeNow();
                            } else {
                                ctx.failNow(e);
                            }
                        } catch (Exception e) {
                            ctx.failNow(e);
                        }
                    })
                    .onFailure(ctx::failNow);
        });
    }

    @Test
    void closeShouldNotCloseExternalVertx(Vertx vertx, VertxTestContext ctx) {
        vertx.runOnContext(v -> {
            PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();

            service.close()
                    .compose(closed -> {
                        Promise<Void> stillUsable = Promise.promise();
                        vertx.runOnContext(c -> stillUsable.complete(null));
                        return stillUsable.future();
                    })
                    .onSuccess(v2 -> ctx.completeNow())
                    .onFailure(ctx::failNow);
        });
    }
}
