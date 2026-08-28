package dev.mars.peegeeq.db.infrastructure;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final String PROFILE_PREFIX = "peegeeq-";
    private static final String PROPERTIES_SUFFIX = ".properties";
    private static final String UNSUPPORTED_MIN_SIZE_PROPERTY = "peegeeq.database.pool.min-size";

    private static final List<BundledProfileTimeout> BUNDLED_PROFILE_TIMEOUTS = List.of(
        new BundledProfileTimeout("bitemporal-optimized", 30_000L, 600_000L),
        new BundledProfileTimeout("default", 30_000L, 600_000L),
        new BundledProfileTimeout("development", 30_000L, 600_000L),
        new BundledProfileTimeout("extreme-performance", 30_000L, 600_000L),
        new BundledProfileTimeout("high-performance", 10_000L, 1_800_000L),
        new BundledProfileTimeout("high-throughput", 30_000L, 300_000L),
        new BundledProfileTimeout("low-latency", 5_000L, 60_000L),
        new BundledProfileTimeout("parallel-test", 20_000L, 600_000L),
        new BundledProfileTimeout("production", 10_000L, 300_000L),
        new BundledProfileTimeout("reliable", 60_000L, 600_000L),
        new BundledProfileTimeout("vertx5-optimized", 30_000L, 600_000L)
    );

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
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration(profile, validDatabaseOverrides());

        assertEquals(Duration.ofMillis(expectedConnectionTimeoutMs),cfg.getPoolConfig().getConnectionTimeout());
        assertEquals(Duration.ofMillis(expectedIdleTimeoutMs), cfg.getPoolConfig().getIdleTimeout());
        assertEquals(Long.toString(expectedConnectionTimeoutMs), cfg.getString("peegeeq.database.pool.connection-timeout-ms"));
        assertEquals(Long.toString(expectedIdleTimeoutMs), cfg.getString("peegeeq.database.pool.idle-timeout-ms"));
        assertFalse(cfg.getProperties().containsKey("peegeeq.database.pool.connection-timeout"));
        assertFalse(cfg.getProperties().containsKey("peegeeq.database.pool.idle-timeout"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("bundledProfiles")
    void bundledProfilesDoNotAdvertiseUnsupportedMinimumSize(String profile) {
        PeeGeeQConfiguration cfg = new PeeGeeQConfiguration(profile, validDatabaseOverrides());

        assertFalse(cfg.getProperties().containsKey(UNSUPPORTED_MIN_SIZE_PROPERTY),
                () -> profile + " must not advertise unsupported property " + UNSUPPORTED_MIN_SIZE_PROPERTY);
    }

    @Test
    void everyBundledProfileHasTimeoutExpectations() throws Exception {
        var defaultProfile = PgPoolConfigPropertyBindingTest.class.getClassLoader()
                .getResource(PROFILE_PREFIX + "default" + PROPERTIES_SUFFIX);
        assertNotNull(defaultProfile, "The bundled default profile must be available on the test classpath");

        Set<String> bundledProfiles;
        Path profileDirectory = Path.of(defaultProfile.toURI()).getParent();
        try (Stream<Path> resources = Files.list(profileDirectory)) {
            bundledProfiles = resources
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(PROFILE_PREFIX) && name.endsWith(PROPERTIES_SUFFIX))
                    .map(name -> name.substring(PROFILE_PREFIX.length(), name.length() - PROPERTIES_SUFFIX.length()))
                    .collect(Collectors.toUnmodifiableSet());
        }

        Set<String> profilesWithExpectations = BUNDLED_PROFILE_TIMEOUTS.stream()
                .map(BundledProfileTimeout::profile)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(bundledProfiles, profilesWithExpectations,
                "Every bundled PeeGeeQ profile must have exact timeout expectations");
    }

    private static Properties validDatabaseOverrides() {
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.database.host", "localhost");
        overrides.setProperty("peegeeq.database.port", "5432");
        overrides.setProperty("peegeeq.database.name", "peegeeq");
        overrides.setProperty("peegeeq.database.username", "peegeeq");
        overrides.setProperty("peegeeq.database.password", "peegeeq");
        overrides.setProperty("peegeeq.database.schema", "public");
        return overrides;
    }

    static Stream<Arguments> bundledProfileTimeouts() {
        return BUNDLED_PROFILE_TIMEOUTS.stream()
                .map(timeout -> arguments(timeout.profile(), timeout.connectionTimeoutMs(), timeout.idleTimeoutMs()));
    }

    static Stream<String> bundledProfiles() {
        return BUNDLED_PROFILE_TIMEOUTS.stream().map(BundledProfileTimeout::profile);
    }

    private record BundledProfileTimeout(String profile, long connectionTimeoutMs, long idleTimeoutMs) {
    }
}
