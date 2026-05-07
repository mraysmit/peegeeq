package dev.mars.peegeeq.db.infrastructure;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * T1 Locks the contract between documented pool property keys and
 * {@link PeeGeeQConfiguration#getPoolConfig()}.
 *
 * <p>Every property key declared in {@code peegeeq-default.properties} must
 * round-trip through the configuration loader. A sentinel value distinct from
 * the default is set via {@code System.setProperty}, and the corresponding
 * getter on {@link PgPoolConfig} must reflect it.</p>
 *
 * <p>This is a CORE test no database, no TestContainers.</p>
 *
 * <p>Runs sequentially because each parameterized test mutates global
 * {@code System} properties, which are not thread-safe.</p>
 */
@Tag(TestCategories.CORE)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("system-properties")
class PgPoolConfigPropertyBindingTest {

    @ParameterizedTest(name = "[{index}] {0} = {1}")
    @MethodSource("poolPropertyKeys")
    void getPoolConfig_appliesEveryDocumentedSystemProperty(String key, String sentinel,
                                                            Function<PgPoolConfig, Object> getter,
                                                            Object expected) {
        System.setProperty(key, sentinel);
        try {
            PeeGeeQConfiguration cfg = new PeeGeeQConfiguration("test-binding-" + UUID.randomUUID());
            assertEquals(expected, getter.apply(cfg.getPoolConfig()),
                "Property " + key + " = " + sentinel + " must be reflected in PgPoolConfig");
        } finally {
            System.clearProperty(key);
        }
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
}
