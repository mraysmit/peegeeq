package dev.mars.peegeeq.api.lifecycle;

import io.vertx.core.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class PeeGeeQCloseHookTest {

    @Test
    void testCloseReactive() {
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

        Future<Void> future = hook.closeReactive();
        assertNotNull(future);
        assertTrue(future.succeeded());
        assertTrue(future.isComplete());
        assertFalse(future.failed());
    }
}
