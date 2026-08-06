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

    /**
     * A service that created its OWN Vert.x must still deliver the result of {@code close()} to a
     * caller whose continuation lives on that same Vert.x.
     *
     * <p>The service is constructed on the JUnit thread — where {@code Vertx.currentContext()} is
     * null — so it owns its Vert.x. The chain then hops onto that owned Vert.x (via the service's
     * own worker executor) before calling {@code close()}, which is how real callers reach it:
     * {@code .eventually(() -> service.close())} from inside a request chain.
     *
     * <p>Before the fix, {@code close()} closed the owned Vert.x as the last step of the returned
     * chain, so the caller's continuation was dispatched to an event loop that no longer existed.
     * It was dropped, {@code completeNow()} never ran, and the only trace was an uncaught
     * {@code RejectedExecutionException: event executor terminated} on a Vert.x thread — the test
     * simply timed out with no cause attached. The two sibling tests above construct the service
     * inside {@code runOnContext}, so they exercise only the external-Vert.x path; nothing covered
     * self-owned teardown, which is why the defect survived.
     */
    @Test
    void closeShouldCompleteForCallerOnItsOwnVertx(VertxTestContext ctx) {
        // Constructed OFF any Vert.x context => the service creates and owns its own Vert.x.
        PeeGeeQDatabaseSetupService service = new PeeGeeQDatabaseSetupService();

        service.setupWorkerExecutor().executeBlocking(() -> "on-owned-vertx", false)
                .compose(ignored -> service.close())
                .onSuccess(v -> ctx.completeNow())
                .onFailure(ctx::failNow);
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
