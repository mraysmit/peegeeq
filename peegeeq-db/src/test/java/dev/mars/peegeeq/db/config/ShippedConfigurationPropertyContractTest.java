package dev.mars.peegeeq.db.config;

import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Locks bundled PeeGeeQ profiles to the public, runtime-owned property surface.
 * A key may be shipped only when the core loader or an explicitly documented
 * production owner consumes it. Historical aliases remain readable for
 * compatibility but must never be advertised by a bundled profile.
 */
@Tag(TestCategories.CORE)
class ShippedConfigurationPropertyContractTest {

    private static final String PROFILE_PREFIX = "peegeeq-";
    private static final String PROPERTIES_SUFFIX = ".properties";

    private static final Set<String> SHIPPABLE_PROPERTIES = Set.of(
        "peegeeq.database.host",
        "peegeeq.database.port",
        "peegeeq.database.name",
        "peegeeq.database.username",
        "peegeeq.database.password",
        "peegeeq.database.schema",
        "peegeeq.database.ssl.enabled",
        "peegeeq.database.pool.max-size",
        "peegeeq.database.pool.max-wait-queue-size",
        "peegeeq.database.pool.connection-timeout-ms",
        "peegeeq.database.pool.idle-timeout-ms",
        "peegeeq.database.pool.shared",
        "peegeeq.database.pool.wait-queue-multiplier",
        "peegeeq.database.pipelining.enabled",
        "peegeeq.database.pipelining.limit",
        "peegeeq.database.event.loop.size",
        "peegeeq.database.worker.pool.size",
        "peegeeq.database.use.event.bus.distribution",
        "peegeeq.verticle.instances",
        "peegeeq.queue.max-retries",
        "peegeeq.queue.visibility-timeout",
        "peegeeq.queue.batch-size",
        "peegeeq.queue.polling-interval",
        "peegeeq.consumer.threads",
        "peegeeq.queue.recovery.enabled",
        "peegeeq.queue.recovery.processing-timeout",
        "peegeeq.queue.recovery.check-interval",
        "peegeeq.queue.dead-consumer-detection.enabled",
        "peegeeq.queue.dead-consumer-detection.interval",
        "peegeeq.queue.consumer-group-retry.enabled",
        "peegeeq.queue.consumer-group-retry.interval",
        "peegeeq.metrics.enabled",
        "peegeeq.metrics.depth-cache-interval",
        "peegeeq.metrics.instance-id",
        "peegeeq.circuit-breaker.enabled",
        "peegeeq.circuit-breaker.minimum-number-of-calls",
        "peegeeq.circuit-breaker.wait-duration-in-open-state",
        "peegeeq.circuit-breaker.sliding-window-size",
        "peegeeq.circuit-breaker.failure-rate-threshold",
        "peegeeq.circuit-breaker.slow-call-rate-threshold",
        "peegeeq.circuit-breaker.slow-call-duration-threshold",
        "peegeeq.circuit-breaker.permitted-calls-in-half-open-state",
        "peegeeq.health.enabled",
        "peegeeq.health.queue-checks-enabled",
        "peegeeq.health.check-interval",
        "peegeeq.health.timeout",
        "peegeeq.notices.info.enabled",
        "peegeeq.notices.info.level",
        "peegeeq.notices.other.enabled",
        "peegeeq.notices.other.level",
        "peegeeq.notices.metrics.enabled"
    );

    @Test
    void everyShippedPropertyHasAnAuthoritativeRuntimeOwner() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Path profile : bundledProfiles()) {
            Properties properties = load(profile);
            properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("peegeeq."))
                .filter(key -> !SHIPPABLE_PROPERTIES.contains(key))
                .sorted()
                .forEach(key -> violations.add(profile.getFileName() + ": " + key));
        }

        assertEquals(List.of(), violations,
            "Bundled profiles may contain only canonical properties with a production owner");
    }

    @Test
    void executableInventoryMatchesEveryShippedPeeGeeQKey() throws Exception {
        Set<String> shippedKeys = new TreeSet<>();
        for (Path profile : bundledProfiles()) {
            load(profile).stringPropertyNames().stream()
                .filter(key -> key.startsWith("peegeeq."))
                .forEach(shippedKeys::add);
        }

        assertEquals(SHIPPABLE_PROPERTIES, shippedKeys,
            "The executable property inventory and bundled profiles must evolve together");
    }

    private static List<Path> bundledProfiles() throws Exception {
        var defaultProfile = ShippedConfigurationPropertyContractTest.class.getClassLoader()
            .getResource(PROFILE_PREFIX + "default" + PROPERTIES_SUFFIX);
        assertNotNull(defaultProfile, "The bundled default profile must be available");

        try (Stream<Path> resources = Files.list(Path.of(defaultProfile.toURI()).getParent())) {
            return resources
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(PROFILE_PREFIX) && name.endsWith(PROPERTIES_SUFFIX);
                })
                .sorted()
                .toList();
        }
    }

    private static Properties load(Path profile) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(profile)) {
            properties.load(input);
        }
        return properties;
    }
}
