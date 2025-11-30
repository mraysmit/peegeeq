package dev.mars.peegeeq.api.lifecycle;

import io.vertx.core.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class PeeGeeQCloseHookTest {

    @Test
    void testDefaultCloseAsync() throws ExecutionException, InterruptedException {
        PeeGeeQCloseHook hook = new PeeGeeQCloseHook() {
            @Override
            public String name() {
                return "test-hook";
            }

            @Override
            public Future<Void> closeReactive() {
                return Future.succeededFuture();
            }
        };

        CompletableFuture<Void> future = hook.closeAsync();
        assertNotNull(future);
        future.get(); // Should complete successfully
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
    }
}
