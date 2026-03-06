package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillProgress;
import dev.mars.peegeeq.db.subscription.BackfillService.BackfillResult;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BackfillService} covering validation paths and pure-logic
 * methods that do not require a database connection.
 */
@Tag(TestCategories.CORE)
class BackfillServiceUnitTest {

    private Vertx vertx;
    private PgConnectionManager connectionManager;
    private BackfillService backfillService;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);
        backfillService = new BackfillService(connectionManager, "test-service");
    }

    @AfterEach
    void tearDown() {
        if (connectionManager != null) connectionManager.close();
        if (vertx != null) vertx.close();
    }

    // =========================================================================
    // Constructor validation
    // =========================================================================

    @Test
    void constructor_nullConnectionManager_throws() {
        assertThrows(NullPointerException.class,
                () -> new BackfillService(null, "svc"));
    }

    @Test
    void constructor_nullServiceId_throws() {
        assertThrows(NullPointerException.class,
                () -> new BackfillService(connectionManager, null));
    }

    // =========================================================================
    // startBackfill argument validation
    // =========================================================================

    @Test
    void startBackfill_nullTopic_throws() {
        assertThrows(NullPointerException.class,
                () -> backfillService.startBackfill(null, "group", 100, 0));
    }

    @Test
    void startBackfill_nullGroupName_throws() {
        assertThrows(NullPointerException.class,
                () -> backfillService.startBackfill("topic", null, 100, 0));
    }

    @Test
    void startBackfill_zeroBatchSize_returnsFailed() {
        Future<BackfillResult> future = backfillService.startBackfill("topic", "group", 0, 0);
        assertFailedWith(future, IllegalArgumentException.class, "batchSize must be positive");
    }

    @Test
    void startBackfill_negativeBatchSize_returnsFailed() {
        Future<BackfillResult> future = backfillService.startBackfill("topic", "group", -1, 0);
        assertFailedWith(future, IllegalArgumentException.class, "batchSize must be positive");
    }

    @Test
    void startBackfill_negativeMaxMessages_returnsFailed() {
        Future<BackfillResult> future = backfillService.startBackfill("topic", "group", 100, -1);
        assertFailedWith(future, IllegalArgumentException.class, "maxMessages cannot be negative");
    }

    // =========================================================================
    // cancelBackfill argument validation
    // =========================================================================

    @Test
    void cancelBackfill_nullTopic_throws() {
        assertThrows(NullPointerException.class,
                () -> backfillService.cancelBackfill(null, "group"));
    }

    @Test
    void cancelBackfill_nullGroupName_throws() {
        assertThrows(NullPointerException.class,
                () -> backfillService.cancelBackfill("topic", null));
    }

    // =========================================================================
    // getBackfillProgress argument validation
    // =========================================================================

    @Test
    void getBackfillProgress_nullTopic_throws() {
        assertThrows(NullPointerException.class,
                () -> backfillService.getBackfillProgress(null, "group"));
    }

    @Test
    void getBackfillProgress_nullGroupName_throws() {
        assertThrows(NullPointerException.class,
                () -> backfillService.getBackfillProgress("topic", null));
    }

    // =========================================================================
    // BackfillProgress.percentComplete() logic
    // =========================================================================

    @Test
    void percentComplete_nullTotal_returnsMinusOne() {
        BackfillProgress progress = new BackfillProgress("IN_PROGRESS", null, 500L, null, null, null);
        assertEquals(-1.0, progress.percentComplete());
    }

    @Test
    void percentComplete_zeroTotal_returnsMinusOne() {
        BackfillProgress progress = new BackfillProgress("IN_PROGRESS", null, 500L, 0L, null, null);
        assertEquals(-1.0, progress.percentComplete());
    }

    @Test
    void percentComplete_nullProcessed_treatsAsZero() {
        BackfillProgress progress = new BackfillProgress("IN_PROGRESS", null, null, 1000L, null, null);
        assertEquals(0.0, progress.percentComplete(), 0.001);
    }

    @Test
    void percentComplete_halfDone_returnsFifty() {
        BackfillProgress progress = new BackfillProgress("IN_PROGRESS", null, 500L, 1000L, null, null);
        assertEquals(50.0, progress.percentComplete(), 0.001);
    }

    @Test
    void percentComplete_fullyDone_returnsOneHundred() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BackfillProgress progress = new BackfillProgress("COMPLETED", null, 1000L, 1000L, now, now);
        assertEquals(100.0, progress.percentComplete(), 0.001);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void assertFailedWith(Future<?> future, Class<? extends Throwable> expectedType) {
        assertFailedWith(future, expectedType, null);
    }

    private void assertFailedWith(Future<?> future, Class<? extends Throwable> expectedType, String messageFragment) {
        assertTrue(future.failed(), "Expected a failed future");
        Throwable cause = future.cause();
        assertInstanceOf(expectedType, cause);
        if (messageFragment != null) {
            assertTrue(cause.getMessage().contains(messageFragment),
                    "Expected message to contain '" + messageFragment + "' but was: " + cause.getMessage());
        }
    }
}
