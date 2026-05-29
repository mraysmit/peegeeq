package dev.mars.peegeeq.examples.nativequeue;

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REGRESSION GUARD: System properties MUST NOT leak into {@link PeeGeeQConfiguration}.
 *
 * <p>Historically {@code PeeGeeQConfiguration.loadProperties()} swept process-wide JVM
 * System properties on every construction. That created a multi-tenant contamination
 * defect: System properties are a single global namespace shared by ALL threads and ALL
 * PeeGeeQ instances in the same JVM, so two tenants could not hold different values for
 * the same {@code peegeeq.*} key simultaneously. The sweep has been removed.
 *
 * <p>The tests in this class are <b>regression guards</b> that fail if the sweep is
 * reintroduced. They write {@code peegeeq.*} values into the JVM-global System property
 * table and assert that fresh {@code PeeGeeQConfiguration} instances do <i>not</i>
 * observe those values  the default supplied to {@code getInt} must win.
 *
 * <p>The correct isolation pattern is shown in {@link #builderPatternProvidesCompleteIsolation()}:
 * build a {@code Properties} object via {@code PeeGeeQTestConfig.builder()} and pass it
 * to the 2-arg constructor {@code new PeeGeeQConfiguration(profile, props)}.
 *
 * @see PeeGeeQTestConfig for the correct isolation pattern
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ResourceLock("system-properties")
class SystemPropertiesConfigurationDemoTest {

    private static final Logger logger = LoggerFactory.getLogger(SystemPropertiesConfigurationDemoTest.class);
    private static final String BATCH_SIZE_KEY = "peegeeq.queue.batch-size";

    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * REGRESSION GUARD: A System property written anywhere in the JVM must NOT bleed into
     * a {@code PeeGeeQConfiguration} that did not request it via the 2-arg constructor's
     * overrides argument.
     *
     * <p>Scenario: some other component (another tenant, a Spring bean, a tuning tool)
     * sets {@code peegeeq.queue.batch-size} in System. Our instance never asked for that
     * value, so {@code getInt(key, 10)} must return the supplied default (10)  not the
     * System property value. If this test fails with {@code expected: <10> but was: <999>}
     * the System property sweep has been reintroduced.
     */
    @Test
    @DisplayName("REGRESSION GUARD: System property set by unrelated code does NOT contaminate other instances")
    void systemPropertyDoesNotContaminateUnrelatedInstances() {
        // DB params for our instance  correct builder pattern, no batch-size set
        Properties baseProps = PeeGeeQTestConfig.builder().from(postgres).build();

        // Simulate: unrelated code elsewhere in the JVM sets batch-size globally
        System.setProperty(BATCH_SIZE_KEY, "999");
        try {
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", baseProps);
            int batchSize = config.getInt(BATCH_SIZE_KEY, 10);

            logger.info("ISOLATION CONFIRMED: System.setProperty(\"{}\", \"999\") did not leak; got batch-size={}",
                BATCH_SIZE_KEY, batchSize);

            assertEquals(10, batchSize,
                "REGRESSION: System.setProperty by unrelated code leaked into a config that did not " +
                "request it. PeeGeeQConfiguration.loadProperties() must not sweep peegeeq.* System properties.");
        } finally {
            System.clearProperty(BATCH_SIZE_KEY);
        }
    }

    /**
     * REGRESSION GUARD: Sequential {@code System.setProperty} writes by different tenants
     * must NOT influence any {@code PeeGeeQConfiguration} that did not declare those values
     * in its explicit overrides.
     *
     * <p>Scenario: Tenant A and Tenant B initialise sequentially in the same JVM. Each
     * writes its desired {@code peegeeq.queue.batch-size} to System properties (a thing
     * neither tenant should do, but we simulate it here to prove the sweep is gone). Both
     * tenants construct configs without supplying batch-size in overrides. Every
     * {@code getInt(key, 10)} call must return the supplied default (10), regardless of
     * which value was most recently written to System.
     */
    @Test
    @DisplayName("REGRESSION GUARD: Sequential System.setProperty writes do NOT contaminate independent tenants")
    void sequentialSystemPropertyWritesDoNotContaminateTenants() {
        Properties propsA = PeeGeeQTestConfig.builder().from(postgres).build();
        Properties propsB = PeeGeeQTestConfig.builder().from(postgres).build();

        try {
            // Tenant A writes its desired batch size to System and constructs.
            System.setProperty(BATCH_SIZE_KEY, "7");
            PeeGeeQConfiguration tenantAFirst = new PeeGeeQConfiguration("default", propsA);
            assertEquals(10, tenantAFirst.getInt(BATCH_SIZE_KEY, 10),
                "REGRESSION: Tenant A's config picked up its own System.setProperty(\"7\"). " +
                "The sweep must remain removed; only explicit overrides should win.");

            // Tenant B writes a different value to System and constructs.
            System.setProperty(BATCH_SIZE_KEY, "13");
            PeeGeeQConfiguration tenantBConfig = new PeeGeeQConfiguration("default", propsB);
            assertEquals(10, tenantBConfig.getInt(BATCH_SIZE_KEY, 10),
                "REGRESSION: Tenant B's config picked up its own System.setProperty(\"13\"). " +
                "The sweep must remain removed; only explicit overrides should win.");

            // Tenant A reconnects or its config is reconstructed.
            PeeGeeQConfiguration tenantAReconstructed = new PeeGeeQConfiguration("default", propsA);
            int tenantABatchSize = tenantAReconstructed.getInt(BATCH_SIZE_KEY, 10);

            logger.info("ISOLATION CONFIRMED: tenantA reconstructed batch-size={}  unaffected by " +
                "either System.setProperty write (7 or 13).", tenantABatchSize);

            assertEquals(10, tenantABatchSize,
                "REGRESSION: Tenant A's reconstructed config was contaminated by a System property. " +
                "Two tenants must be able to coexist without leaking through JVM-global state.");
        } finally {
            System.clearProperty(BATCH_SIZE_KEY);
        }
    }

    /**
     * SOLUTION: Use {@code PeeGeeQTestConfig.builder()} and pass an explicit
     * {@code Properties} object to the 2-arg constructor. Each instance holds its own
     * isolated property set. No writes to System properties occur. No tenant can
     * contaminate another, even when a System property for the same key is present.
     *
     * <p>This is the only correct pattern for multi-tenant or concurrent use.
     */
    @Test
    @DisplayName("SOLUTION: Builder pattern gives each instance complete, isolated configuration")
    void builderPatternProvidesCompleteIsolation() {
        // Each tenant builds its own Properties explicitly  no shared global state
        Properties propsA = PeeGeeQTestConfig.builder()
            .from(postgres)
            .property(BATCH_SIZE_KEY, "7")
            .build();
        Properties propsB = PeeGeeQTestConfig.builder()
            .from(postgres)
            .property(BATCH_SIZE_KEY, "13")
            .build();

        PeeGeeQConfiguration tenantA = new PeeGeeQConfiguration("default", propsA);
        PeeGeeQConfiguration tenantB = new PeeGeeQConfiguration("default", propsB);

        assertEquals(7,  tenantA.getInt(BATCH_SIZE_KEY, -1), "Tenant A reads its own value");
        assertEquals(13, tenantB.getInt(BATCH_SIZE_KEY, -1), "Tenant B reads its own value");

        // Even with a conflicting System property, both instances are immune 
        // the override key is already in their respective Properties, so it wins over System.
        System.setProperty(BATCH_SIZE_KEY, "999");
        try {
            PeeGeeQConfiguration tenantAReconstructed = new PeeGeeQConfiguration("default", propsA);
            PeeGeeQConfiguration tenantBReconstructed = new PeeGeeQConfiguration("default", propsB);

            assertEquals(7,  tenantAReconstructed.getInt(BATCH_SIZE_KEY, -1),
                "Tenant A is immune to System property pollution when key is in explicit overrides");
            assertEquals(13, tenantBReconstructed.getInt(BATCH_SIZE_KEY, -1),
                "Tenant B is immune to System property pollution when key is in explicit overrides");

            logger.info("ISOLATION CONFIRMED: tenantA={}, tenantB={}  unaffected by System.setProperty(999)",
                tenantAReconstructed.getInt(BATCH_SIZE_KEY, -1),
                tenantBReconstructed.getInt(BATCH_SIZE_KEY, -1));
        } finally {
            System.clearProperty(BATCH_SIZE_KEY);
        }
    }
}

