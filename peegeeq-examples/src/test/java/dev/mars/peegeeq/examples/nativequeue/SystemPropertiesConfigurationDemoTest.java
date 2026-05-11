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
 * PROBLEM DEMONSTRATION: Why System properties MUST NOT be used to configure PeeGeeQ instances.
 *
 * <p>This test class demonstrates the multi-tenant contamination defect caused by
 * {@code PeeGeeQConfiguration.loadProperties()} sweeping process-wide JVM System properties
 * on every construction. System properties are a single global namespace shared by ALL
 * threads and ALL PeeGeeQ instances in the same JVM. Two tenants cannot hold different
 * values for the same {@code peegeeq.*} key simultaneously.
 *
 * <p><b>These tests prove the defect, not a solution. Do not copy these System.setProperty
 * patterns.</b> The correct isolation pattern is demonstrated in the final test: build a
 * {@code Properties} object via {@code PeeGeeQTestConfig.builder()} and pass it to the
 * 2-arg constructor {@code new PeeGeeQConfiguration(profile, props)}.
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
     * PROBLEM: A System property written anywhere in the JVM bleeds into every
     * {@code PeeGeeQConfiguration} constructed afterwards that does not explicitly
     * override that key via the 2-arg constructor.
     *
     * <p>Scenario: some other component (another tenant, a Spring bean, a tuning tool)
     * sets {@code peegeeq.queue.batch-size} in System. Our instance never asked for
     * that value, but {@code loadProperties()} sweeps it in silently on construction.
     */
    @Test
    @DisplayName("PROBLEM: System property set by any code contaminates all subsequent instances")
    void systemPropertyContaminatesUnrelatedInstances() {
        // DB params for our instance — correct builder pattern, no batch-size set
        Properties baseProps = PeeGeeQTestConfig.builder().from(postgres).build();

        // Simulate: unrelated code elsewhere in the JVM sets batch-size globally
        System.setProperty(BATCH_SIZE_KEY, "999");
        try {
            PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", baseProps);
            int batchSize = config.getInt(BATCH_SIZE_KEY, 10);

            logger.warn("CONTAMINATION CONFIRMED: expected default=10, actual batch-size={}", batchSize);
            logger.warn("Value 999 was written by unrelated code — our instance never requested it.");

            assertEquals(999, batchSize,
                "DEFECT: System.setProperty by unrelated code silently overrides our instance's " +
                "configuration. loadProperties() sweeps ALL peegeeq.* System properties on every construction.");
        } finally {
            System.clearProperty(BATCH_SIZE_KEY);
        }
    }

    /**
     * PROBLEM: The last {@code System.setProperty} call wins for ALL instances
     * constructed afterwards — regardless of which tenant intended the write.
     *
     * <p>Scenario: Tenant A and Tenant B initialise sequentially in the same JVM,
     * each setting their desired {@code peegeeq.queue.batch-size}. Tenant A constructs
     * its config, then Tenant B sets its (different) value. Any subsequent reconstruction
     * of Tenant A's config — on reconnect, reload, or failover — silently inherits
     * Tenant B's value. The two values cannot coexist in System properties simultaneously.
     */
    @Test
    @DisplayName("PROBLEM: Last writer wins — two tenants cannot hold different values for the same key")
    void lastWriterWins_multiTenantContamination() {
        Properties propsA = PeeGeeQTestConfig.builder().from(postgres).build();
        Properties propsB = PeeGeeQTestConfig.builder().from(postgres).build();

        try {
            // Tenant A sets its desired batch size and constructs
            System.setProperty(BATCH_SIZE_KEY, "7");
            PeeGeeQConfiguration tenantAFirst = new PeeGeeQConfiguration("default", propsA);
            assertEquals(7, tenantAFirst.getInt(BATCH_SIZE_KEY, -1),
                "Tenant A initially reads its own value — looks correct so far");

            // Tenant B sets its desired batch size and constructs — overwrites A's global value
            System.setProperty(BATCH_SIZE_KEY, "13");
            PeeGeeQConfiguration tenantBConfig = new PeeGeeQConfiguration("default", propsB);
            assertEquals(13, tenantBConfig.getInt(BATCH_SIZE_KEY, -1),
                "Tenant B reads its own value — still looks correct");

            // Tenant A reconnects or its config is reconstructed (connection pool reset, etc.)
            PeeGeeQConfiguration tenantAReconstructed = new PeeGeeQConfiguration("default", propsA);
            int tenantABatchSize = tenantAReconstructed.getInt(BATCH_SIZE_KEY, -1);

            logger.warn("CONTAMINATION CONFIRMED: Tenant A's reconstructed config has batch-size={}, " +
                "but Tenant A asked for 7. Tenant B's System.setProperty(\"...\", \"13\") won.", tenantABatchSize);

            assertEquals(13, tenantABatchSize,
                "DEFECT: Tenant A's config is contaminated by Tenant B's System.setProperty. " +
                "Two tenants cannot hold different values for the same System property key simultaneously.");
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
        // Each tenant builds its own Properties explicitly — no shared global state
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

        // Even with a conflicting System property, both instances are immune —
        // the override key is already in their respective Properties, so it wins over System.
        System.setProperty(BATCH_SIZE_KEY, "999");
        try {
            PeeGeeQConfiguration tenantAReconstructed = new PeeGeeQConfiguration("default", propsA);
            PeeGeeQConfiguration tenantBReconstructed = new PeeGeeQConfiguration("default", propsB);

            assertEquals(7,  tenantAReconstructed.getInt(BATCH_SIZE_KEY, -1),
                "Tenant A is immune to System property pollution when key is in explicit overrides");
            assertEquals(13, tenantBReconstructed.getInt(BATCH_SIZE_KEY, -1),
                "Tenant B is immune to System property pollution when key is in explicit overrides");

            logger.info("ISOLATION CONFIRMED: tenantA={}, tenantB={} — unaffected by System.setProperty(999)",
                tenantAReconstructed.getInt(BATCH_SIZE_KEY, -1),
                tenantBReconstructed.getInt(BATCH_SIZE_KEY, -1));
        } finally {
            System.clearProperty(BATCH_SIZE_KEY);
        }
    }
}

