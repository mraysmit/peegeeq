package dev.mars.peegeeq.db.infrastructure;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * T1 Locks the contract between documented pool property keys and
 * {@link PeeGeeQConfiguration#getPoolConfig()}.
 *
 * <p>Every property key declared in {@code peegeeq-default.properties} must
 * round-trip through the configuration loader when supplied via the 2-arg
 * constructor overrides. A sentinel value distinct from the default is set in
 * an explicit {@link java.util.Properties} object, and the corresponding getter
 * on {@link PgPoolConfig} must reflect it.</p>
 *
 * <p>System properties are no longer swept by {@code loadProperties()} (Phase 11
 * removal). The 2-arg constructor is the correct override path for all callers.</p>
 *
 * <p>This is a CORE test  no database, no TestContainers.</p>
 */
@Tag(TestCategories.CORE)
class PgPoolConfigPropertyBindingTest {

    @ParameterizedTest(name = "[{index}] {0} = {1}")
    @MethodSource("poolPropertyKeys")
    void getPoolConfig_appliesEveryDocumentedSystemProperty(String key, String sentinel,
                                                            Function<PgPoolConfig, Object> getter,
                                                            Object expected) {
        java.util.Properties overrides = new java.util.Properties();
        overrides.setProperty(key, sentinel);
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-binding-" + UUID.randomUUID(), overrides);
        assertEquals(expected, getter.apply(cfg.getPoolConfig()),
            "Property " + key + " = " + sentinel + " must be reflected in PgPoolConfig");
    }

    static Stream<Arguments> poolPropertyKeys() {
        return Stream.of(
            arguments("peegeeq.database.pool.max-size",              "17",    (Function<PgPoolConfig, Object>) PgPoolConfig::getMaxSize,                  17),
            arguments("peegeeq.database.pool.connection-timeout-ms", "1234",  (Function<PgPoolConfig, Object>) c -> c.getConnectionTimeout().toMillis(),   1234L),
            arguments("peegeeq.database.pool.idle-timeout-ms",       "5678",  (Function<PgPoolConfig, Object>) c -> c.getIdleTimeout().toMillis(),         5678L),
            arguments("peegeeq.database.pool.shared",                "false", (Function<PgPoolConfig, Object>) PgPoolConfig::isShared,                     false),
            arguments("peegeeq.database.pool.max-wait-queue-size",   "9",     (Function<PgPoolConfig, Object>) PgPoolConfig::getMaxWaitQueueSize,          9)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: connection={1}ms, idle={2}ms")
    @MethodSource("bundledProfileTimeouts")
    void bundledProfilesBindCanonicalTimeoutProperties(String profile,
                                                        long expectedConnectionTimeoutMs,
                                                        long expectedIdleTimeoutMs) {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration(profile, new Properties());

        assertEquals(Duration.ofMillis(expectedConnectionTimeoutMs),
                cfg.getPoolConfig().getConnectionTimeout());
        assertEquals(Duration.ofMillis(expectedIdleTimeoutMs),
                cfg.getPoolConfig().getIdleTimeout());
        assertEquals(Long.toString(expectedConnectionTimeoutMs),
                cfg.getString("peegeeq.database.pool.connection-timeout-ms"));
        assertEquals(Long.toString(expectedIdleTimeoutMs),
                cfg.getString("peegeeq.database.pool.idle-timeout-ms"));
        assertFalse(cfg.getProperties().containsKey("peegeeq.database.pool.connection-timeout"));
        assertFalse(cfg.getProperties().containsKey("peegeeq.database.pool.idle-timeout"));
    }

    static Stream<Arguments> bundledProfileTimeouts() {
        return Stream.of(
            arguments("bitemporal-optimized", 30_000L, 600_000L),
            arguments("extreme-performance", 30_000L, 600_000L),
            arguments("high-performance", 10_000L, 1_800_000L),
            arguments("high-throughput", 30_000L, 300_000L),
            arguments("low-latency", 5_000L, 60_000L),
            arguments("parallel-test", 20_000L, 600_000L),
            arguments("reliable", 60_000L, 600_000L),
            arguments("vertx5-optimized", 30_000L, 600_000L)
        );
    }
}
