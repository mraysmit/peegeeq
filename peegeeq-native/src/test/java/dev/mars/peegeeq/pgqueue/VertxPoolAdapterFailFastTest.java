package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.database.ConnectOptionsProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
class VertxPoolAdapterFailFastTest {

    @Test
    void constructor_withNullPool_failsFast(Vertx vertx) {
        // Real fail-fast: a null dependency is rejected at the boundary, not swallowed
        // and surfaced later as a confusing downstream error.
        NullPointerException ex = assertThrows(NullPointerException.class,
            () -> new VertxPoolAdapter(vertx, null, null));
        assertTrue(ex.getMessage().contains("pool"),
            "The error must name the missing dependency, got: " + ex.getMessage());
    }

    @Test
    void constructor_withNullConnectOptionsProvider_failsFast(Vertx vertx) {
        // A pool but no ConnectOptionsProvider is rejected at construction — there is no
        // legitimate production path that builds the adapter without one.
        @SuppressWarnings("resource")
        io.vertx.sqlclient.Pool pool = io.vertx.pgclient.PgBuilder.pool().using(vertx).build();
        try {
            NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new VertxPoolAdapter(vertx, pool, null));
            assertTrue(ex.getMessage().contains("connectOptionsProvider"),
                "The error must name the missing dependency, got: " + ex.getMessage());
        } finally {
            pool.close();
        }
    }

    @Test
    void constructor_withNullVertx_failsFast() {
        @SuppressWarnings("resource")
        Vertx vertx = Vertx.vertx();
        try {
            io.vertx.sqlclient.Pool pool = io.vertx.pgclient.PgBuilder.pool().using(vertx).build();
            NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new VertxPoolAdapter(null, pool, (ConnectOptionsProvider) null));
            assertTrue(ex.getMessage().contains("vertx"),
                "The error must name the missing dependency, got: " + ex.getMessage());
            pool.close();
        } finally {
            vertx.close();
        }
    }
}

