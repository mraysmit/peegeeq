package dev.mars.peegeeq.bitemporal;

import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive wildcard pattern tests with 100 different patterns.
 * Tests trailing, leading, middle, multiple wildcards, deep nesting, and dot-underscore collision.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
class WildcardPatternComprehensiveTest {
    private static final Logger logger = LoggerFactory.getLogger(WildcardPatternComprehensiveTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_wildcard_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);

    private static PeeGeeQManager peeGeeQManager;
    private static PgBiTemporalEventStore<Map<String, Object>> eventStore;

    @BeforeAll
    static void setUp() throws Exception {
        logger.info("Setting up wildcard pattern comprehensive test...");
        configureSystemPropertiesForContainer(postgres);
        createTestEventsTable();
        
        peeGeeQManager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        peeGeeQManager.start();
        
        @SuppressWarnings("unchecked")
        Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
        eventStore = new PgBiTemporalEventStore<>(peeGeeQManager, mapClass, "wildcard_test_events", new ObjectMapper());
        
        logger.info("Wildcard pattern test setup completed");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (eventStore != null) eventStore.close();
        if (peeGeeQManager != null) peeGeeQManager.stop();
        PgBiTemporalEventStore.clearCachedPools();
    }

    private static void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
    }

    private static void createTestEventsTable() throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS wildcard_test_events (
                    event_id VARCHAR(255) PRIMARY KEY,
                    event_type VARCHAR(255) NOT NULL,
                    aggregate_id VARCHAR(255),
                    correlation_id VARCHAR(255),
                    valid_time TIMESTAMPTZ NOT NULL,
                    transaction_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    payload JSONB NOT NULL,
                    headers JSONB NOT NULL DEFAULT '{}',
                    version BIGINT NOT NULL DEFAULT 1,
                    previous_version_id VARCHAR(255),
                    is_correction BOOLEAN NOT NULL DEFAULT FALSE,
                    correction_reason TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_wildcard_test_events_type ON wildcard_test_events(event_type)");
            stmt.execute("""
                CREATE OR REPLACE FUNCTION notify_wildcard_test_events() RETURNS TRIGGER AS $$
                BEGIN
                    PERFORM pg_notify('bitemporal_events', json_build_object(
                        'event_id', NEW.event_id, 'event_type', NEW.event_type,
                        'aggregate_id', NEW.aggregate_id, 'correlation_id', NEW.correlation_id,
                        'is_correction', NEW.is_correction,
                        'transaction_time', extract(epoch from NEW.transaction_time))::text);
                    PERFORM pg_notify('bitemporal_events_' || replace(NEW.event_type, '.', '_'),
                        json_build_object('event_id', NEW.event_id, 'event_type', NEW.event_type,
                        'aggregate_id', NEW.aggregate_id, 'correlation_id', NEW.correlation_id,
                        'is_correction', NEW.is_correction,
                        'transaction_time', extract(epoch from NEW.transaction_time))::text);
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                """);
            stmt.execute("""
                DROP TRIGGER IF EXISTS trigger_notify_wildcard_test_events ON wildcard_test_events;
                CREATE TRIGGER trigger_notify_wildcard_test_events AFTER INSERT ON wildcard_test_events
                    FOR EACH ROW EXECUTE FUNCTION notify_wildcard_test_events();
                """);
        }
    }

    /**
     * Provides 100 wildcard pattern test cases covering all pattern types.
     */
    static Stream<Arguments> wildcardPatternProvider() {
        return Stream.of(
            // === TRAILING WILDCARDS (12 patterns) ===
            Arguments.of("order.*", "order.created", true, "trailing-01"),
            Arguments.of("order.*", "order.updated", true, "trailing-02"),
            Arguments.of("order.*", "order.deleted", true, "trailing-03"),
            Arguments.of("user.profile.*", "user.profile.updated", true, "trailing-04"),
            Arguments.of("user.profile.*", "user.profile.created", true, "trailing-05"),
            Arguments.of("a.b.c.d.*", "a.b.c.d.e", true, "trailing-06"),
            Arguments.of("payment.*", "payment.processed", true, "trailing-07"),
            Arguments.of("payment.*", "payment.failed", true, "trailing-08"),
            Arguments.of("order.*", "orders.created", false, "trailing-09-nomatch"),
            Arguments.of("order.*", "order", false, "trailing-10-nomatch"),
            Arguments.of("order.*", "order.item.created", false, "trailing-11-nomatch"),
            Arguments.of("user.profile.*", "user.settings.updated", false, "trailing-12-nomatch"),

            // === LEADING WILDCARDS (12 patterns) ===
            Arguments.of("*.created", "order.created", true, "leading-01"),
            Arguments.of("*.created", "user.created", true, "leading-02"),
            Arguments.of("*.created", "payment.created", true, "leading-03"),
            Arguments.of("*.user.deleted", "admin.user.deleted", true, "leading-04"),
            Arguments.of("*.a.b.c", "x.a.b.c", true, "leading-05"),
            Arguments.of("*.updated", "profile.updated", true, "leading-06"),
            Arguments.of("*.created", "created", false, "leading-07-nomatch"),
            Arguments.of("*.created", "order.item.created", false, "leading-08-nomatch"),
            Arguments.of("*.deleted", "order.created", false, "leading-09-nomatch"),
            Arguments.of("*.user.deleted", "user.deleted", false, "leading-10-nomatch"),
            Arguments.of("*.completed", "order.item.completed", false, "leading-11-nomatch"),
            Arguments.of("*.failed", "payment.processing.failed", false, "leading-12-nomatch"),

            // === MIDDLE WILDCARDS (12 patterns) ===
            Arguments.of("order.*.completed", "order.item.completed", true, "middle-01"),
            Arguments.of("order.*.completed", "order.payment.completed", true, "middle-02"),
            Arguments.of("user.*.profile", "user.admin.profile", true, "middle-03"),
            Arguments.of("user.*.profile", "user.guest.profile", true, "middle-04"),
            Arguments.of("a.*.c", "a.b.c", true, "middle-05"),
            Arguments.of("payment.*.status", "payment.card.status", true, "middle-06"),
            Arguments.of("order.*.completed", "order.completed", false, "middle-07-nomatch"),
            Arguments.of("order.*.completed", "order.item.sub.completed", false, "middle-08-nomatch"),
            Arguments.of("user.*.profile", "user.profile", false, "middle-09-nomatch"),
            Arguments.of("a.*.c", "a.c", false, "middle-10-nomatch"),
            Arguments.of("a.*.c", "a.b.d.c", false, "middle-11-nomatch"),
            Arguments.of("order.*.failed", "order.item.completed", false, "middle-12-nomatch"),

            // === MULTIPLE WILDCARDS (15 patterns) ===
            Arguments.of("*.*", "order.created", true, "multi-01"),
            Arguments.of("*.*", "user.updated", true, "multi-02"),
            Arguments.of("*.order.*", "admin.order.created", true, "multi-03"),
            Arguments.of("*.order.*", "user.order.updated", true, "multi-04"),
            Arguments.of("user.*.*.action", "user.admin.profile.action", true, "multi-05"),
            Arguments.of("*.*.*", "a.b.c", true, "multi-06"),
            Arguments.of("*.*.*", "order.item.created", true, "multi-07"),
            Arguments.of("*.*.*.*", "a.b.c.d", true, "multi-08"),
            Arguments.of("a.*.*.d", "a.b.c.d", true, "multi-09"),
            Arguments.of("*.*", "single", false, "multi-10-nomatch"),
            Arguments.of("*.*", "a.b.c", false, "multi-11-nomatch"),
            Arguments.of("*.order.*", "order.created", false, "multi-12-nomatch"),
            Arguments.of("*.*.*", "a.b", false, "multi-13-nomatch"),
            Arguments.of("*.*.*.*", "a.b.c", false, "multi-14-nomatch"),
            Arguments.of("a.*.*.d", "a.b.d", false, "multi-15-nomatch"),

            // === DEEP NESTING 5+ SEGMENTS (10 patterns) ===
            Arguments.of("a.b.c.d.e.*", "a.b.c.d.e.f", true, "deep-01"),
            Arguments.of("*.a.b.c.d.e", "x.a.b.c.d.e", true, "deep-02"),
            Arguments.of("a.*.c.*.e.*.g", "a.b.c.d.e.f.g", true, "deep-03"),
            Arguments.of("x.y.z.*.w", "x.y.z.a.w", true, "deep-04"),
            Arguments.of("one.two.three.four.*", "one.two.three.four.five", true, "deep-05"),
            Arguments.of("a.b.c.d.e.*", "a.b.c.d.e", false, "deep-06-nomatch"),
            Arguments.of("*.a.b.c.d.e", "a.b.c.d.e", false, "deep-07-nomatch"),
            Arguments.of("a.*.c.*.e.*.g", "a.b.c.d.e.g", false, "deep-08-nomatch"),
            Arguments.of("x.y.z.*.w", "x.y.z.w", false, "deep-09-nomatch"),
            Arguments.of("one.two.three.four.*", "one.two.three.five", false, "deep-10-nomatch"),

            // === DOT-UNDERSCORE COLLISION (10 patterns) ===
            Arguments.of("my.channel", "my.channel", true, "collision-01"),
            Arguments.of("my_channel", "my_channel", true, "collision-02"),
            Arguments.of("order.item.created", "order.item.created", true, "collision-03"),
            Arguments.of("order_item.created", "order_item.created", true, "collision-04"),
            Arguments.of("my.*", "my.channel", true, "collision-05"),
            Arguments.of("my.channel", "my_channel", false, "collision-06-nomatch"),
            Arguments.of("my_channel", "my.channel", false, "collision-07-nomatch"),
            Arguments.of("order.item.*", "order_item.created", false, "collision-08-nomatch"),
            Arguments.of("order_item.*", "order.item.created", false, "collision-09-nomatch"),
            Arguments.of("a.b.c", "a_b_c", false, "collision-10-nomatch"),

            // === NUMERIC SEGMENTS (8 patterns) ===
            Arguments.of("v1.*", "v1.created", true, "numeric-01"),
            Arguments.of("v1.*", "v1.updated", true, "numeric-02"),
            Arguments.of("*.v2.created", "api.v2.created", true, "numeric-03"),
            Arguments.of("api.v3.*.response", "api.v3.user.response", true, "numeric-04"),
            Arguments.of("v1.*", "v2.created", false, "numeric-05-nomatch"),
            Arguments.of("*.v2.created", "v2.created", false, "numeric-06-nomatch"),
            Arguments.of("api.v3.*.response", "api.v2.user.response", false, "numeric-07-nomatch"),
            Arguments.of("v1.2.*", "v1.2.patch", true, "numeric-08"),

            // === UNDERSCORE SEGMENTS (8 patterns) - using underscores instead of hyphens ===
            // Note: Hyphens are not allowed in event types per validation rules
            Arguments.of("user_service.*", "user_service.started", true, "underscore-01"),
            Arguments.of("user_service.*", "user_service.stopped", true, "underscore-02"),
            Arguments.of("*.order_created", "shop.order_created", true, "underscore-03"),
            Arguments.of("payment.*.card_declined", "payment.visa.card_declined", true, "underscore-04"),
            Arguments.of("user_service.*", "userservice.started", false, "underscore-05-nomatch"),
            Arguments.of("*.order_created", "order_created", false, "underscore-06-nomatch"),
            Arguments.of("payment.*.card_declined", "payment.card_declined", false, "underscore-07-nomatch"),
            Arguments.of("my_app.*.event", "my_app.module.event", true, "underscore-08"),

            // === NON-MATCHING VERIFICATION (13 patterns) ===
            Arguments.of("order.*", "orders.created", false, "verify-01"),
            Arguments.of("order.*", "order", false, "verify-02"),
            Arguments.of("user.profile", "user.profiles", false, "verify-03"),
            Arguments.of("user.profile", "users.profile", false, "verify-04"),
            Arguments.of("*.created", "order.item.created", false, "verify-05"),
            Arguments.of("a.b.c", "a.b.c.d", false, "verify-06"),
            Arguments.of("a.b.c.d", "a.b.c", false, "verify-07"),
            Arguments.of("exact.match", "exact.matches", false, "verify-08"),
            Arguments.of("exact.match", "exactly.match", false, "verify-09"),
            Arguments.of("foo.bar", "foo.baz", false, "verify-10"),
            Arguments.of("foo.bar", "foobar", false, "verify-11"),
            Arguments.of("a.*.b", "a.x.y.b", false, "verify-12"),
            Arguments.of("*.end", "start.middle.end", false, "verify-13")
        );
    }

    @ParameterizedTest(name = "{3}: pattern={0}, eventType={1}, shouldMatch={2}")
    @MethodSource("wildcardPatternProvider")
    void testWildcardPattern(String pattern, String eventType, boolean shouldMatch, String testId) throws Exception {
        logger.info("Testing [{}]: pattern='{}' with eventType='{}' (shouldMatch={})", testId, pattern, eventType, shouldMatch);

        // Make patterns and event types unique by prefixing with testId
        String prefix = "t" + testId.replace("-", "") + ".";
        String uniquePattern = prefix + pattern;
        String uniqueEventType = prefix + eventType;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BiTemporalEvent<Map<String, Object>>> received = new AtomicReference<>();

        // Subscribe with the pattern
        eventStore.subscribe(uniquePattern, message -> {
            BiTemporalEvent<Map<String, Object>> event = message.getPayload();
            received.set(event);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        }).get(10, TimeUnit.SECONDS);

        Thread.sleep(300); // Allow subscription to stabilize

        // Publish the event
        eventStore.append(uniqueEventType, Map.of("testId", testId), Instant.now()).get(5, TimeUnit.SECONDS);

        // Wait for notification (or timeout)
        boolean receivedNotification = latch.await(3, TimeUnit.SECONDS);

        if (shouldMatch) {
            assertTrue(receivedNotification,
                String.format("Pattern '%s' should match event type '%s' but didn't receive notification",
                    uniquePattern, uniqueEventType));
            assertNotNull(received.get(), "Should have received the event");
            assertEquals(uniqueEventType, received.get().getEventType(), "Event type should match");
        } else {
            assertFalse(receivedNotification,
                String.format("Pattern '%s' should NOT match event type '%s' but received notification",
                    uniquePattern, uniqueEventType));
            assertNull(received.get(), "Should NOT have received the event");
        }

        logger.info("  [{}] PASSED: pattern='{}' {} eventType='{}'",
            testId, uniquePattern, shouldMatch ? "matched" : "correctly rejected", uniqueEventType);
    }
}

