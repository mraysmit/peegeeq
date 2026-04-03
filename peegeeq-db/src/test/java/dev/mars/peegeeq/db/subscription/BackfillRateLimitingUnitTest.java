package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BackfillService} adaptive rate-limiting feature.
 *
 * <p>Validates batch delay parameter validation, constructor parameter
 * validation with the Vertx-aware constructor, and configuration defaults.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-04-04
 */
@Tag(TestCategories.CORE)
class BackfillRateLimitingUnitTest {

    private Vertx vertx;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);
    }

    @AfterEach
    void tearDown() {
        if (connectionManager != null) connectionManager.closeAsync();
        if (vertx != null) vertx.close();
    }

    // =========================================================================
    // Constructor validation: Vertx-aware constructor
    // =========================================================================

    @Test
    void constructor_withVertx_succeeds() {
        BackfillService service = new BackfillService(connectionManager, "svc", vertx);
        assertNotNull(service);
    }

    @Test
    void constructor_withVertx_nullVertx_throws() {
        assertThrows(NullPointerException.class,
                () -> new BackfillService(connectionManager, "svc", null));
    }

    @Test
    void constructor_withVertx_nullConnectionManager_throws() {
        assertThrows(NullPointerException.class,
                () -> new BackfillService(null, "svc", vertx));
    }

    @Test
    void constructor_withVertx_nullServiceId_throws() {
        assertThrows(NullPointerException.class,
                () -> new BackfillService(connectionManager, null, vertx));
    }

    // =========================================================================
    // Legacy constructor still works (no Vertx, no delay)
    // =========================================================================

    @Test
    void legacyConstructor_stillWorks() {
        BackfillService service = new BackfillService(connectionManager, "svc");
        assertNotNull(service);
    }

    // =========================================================================
    // startBackfill overload with batchDelayMs validation
    // =========================================================================

    @Test
    void startBackfill_negativeBatchDelay_returnsFailed() {
        BackfillService service = new BackfillService(connectionManager, "svc", vertx);
        var future = service.startBackfill("topic", "group", 100, 0, -1L);
        assertTrue(future.failed(), "Expected a failed future for negative batch delay");
        assertInstanceOf(IllegalArgumentException.class, future.cause());
        assertTrue(future.cause().getMessage().contains("batchDelayMs"));
    }

    @Test
    void startBackfill_zeroBatchDelay_accepted() {
        // Zero delay should be accepted (no throttling) — will fail at DB level
        // but should NOT fail at parameter validation
        BackfillService service = new BackfillService(connectionManager, "svc", vertx);
        var future = service.startBackfill("topic", "group", 100, 0, 0L);
        // Will fail because no actual pool exists, but should not be an IllegalArgumentException
        assertTrue(future.failed());
        assertFalse(future.cause() instanceof IllegalArgumentException,
                "Zero delay should pass validation, failure should be from DB access");
    }

    @Test
    void startBackfill_positiveBatchDelay_accepted() {
        BackfillService service = new BackfillService(connectionManager, "svc", vertx);
        var future = service.startBackfill("topic", "group", 100, 0, 50L);
        // Will fail because no actual pool exists, but should not be an IllegalArgumentException
        assertTrue(future.failed());
        assertFalse(future.cause() instanceof IllegalArgumentException,
                "Positive delay should pass validation, failure should be from DB access");
    }

    @Test
    void startBackfill_delayWithoutVertx_returnsFailed() {
        // Legacy constructor (no Vertx) should reject non-zero delay
        BackfillService service = new BackfillService(connectionManager, "svc");
        var future = service.startBackfill("topic", "group", 100, 0, 50L);
        assertTrue(future.failed(), "Expected failure when using delay without Vertx");
        assertInstanceOf(IllegalStateException.class, future.cause());
        assertTrue(future.cause().getMessage().contains("Vertx"),
                "Error should mention Vertx requirement");
    }

    @Test
    void startBackfill_zeroDelayWithoutVertx_accepted() {
        // Zero delay should work even without Vertx (no timer needed)
        BackfillService service = new BackfillService(connectionManager, "svc");
        var future = service.startBackfill("topic", "group", 100, 0, 0L);
        // Validation must NOT reject with our rate-limiting validation messages.
        // The future may fail downstream (no DB pool), which is fine for this test.
        if (future.failed()) {
            String msg = future.cause().getMessage();
            assertFalse(msg != null && msg.contains("batchDelayMs"),
                    "Should not be rejected by batchDelayMs validation");
            assertFalse(msg != null && msg.contains("Vertx instance is required for inter-batch delay"),
                    "Should not be rejected for missing Vertx when delay is zero");
        }
    }

    @Test
    void startBackfill_nullTopic_withDelay_throws() {
        BackfillService service = new BackfillService(connectionManager, "svc", vertx);
        assertThrows(NullPointerException.class,
                () -> service.startBackfill(null, "group", 100, 0, 50L));
    }

    @Test
    void startBackfill_nullGroupName_withDelay_throws() {
        BackfillService service = new BackfillService(connectionManager, "svc", vertx);
        assertThrows(NullPointerException.class,
                () -> service.startBackfill("topic", null, 100, 0, 50L));
    }

    @Test
    void startBackfill_zeroBatchSize_withDelay_returnsFailed() {
        BackfillService service = new BackfillService(connectionManager, "svc", vertx);
        var future = service.startBackfill("topic", "group", 0, 0, 50L);
        assertTrue(future.failed());
        assertInstanceOf(IllegalArgumentException.class, future.cause());
        assertTrue(future.cause().getMessage().contains("batchSize"));
    }
}
