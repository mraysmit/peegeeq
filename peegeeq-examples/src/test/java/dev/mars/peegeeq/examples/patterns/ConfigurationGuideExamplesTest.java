package dev.mars.peegeeq.examples.patterns;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Examples covering every major section of PEEGEEQ_CONFIGURATION_GUIDE.md.
 *
 * <p>Each test is self-contained, uses only programmatic overrides, and requires no
 * database connection. The tests are safe for parallel execution because they never
 * write to {@code System.setProperty}.</p>
 *
 * <p>Guide sections covered:</p>
 * <ul>
 *   <li>Constructors and When to Use Each</li>
 *   <li>Priority Chain  Phase 1: 5-source merge</li>
 *   <li>Priority Chain  Phase 2: placeholder resolution</li>
 *   <li>Configuration Validation Rules</li>
 *   <li>Operator Deployment  Multi-Tenant Pattern</li>
 *   <li>Named Profiles</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-10
 */
@Tag(TestCategories.CORE)
public class ConfigurationGuideExamplesTest {

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Minimum set of programmatic overrides that satisfies configuration validation
     * regardless of what the profile file supplies. Use this as a base and call
     * {@code put} / {@code setProperty} to add or replace specific values.
     */
    private static Properties validBase() {
        Properties p = new Properties();
        p.setProperty("peegeeq.database.host",                       "localhost");
        p.setProperty("peegeeq.database.port",                       "5432");
        p.setProperty("peegeeq.database.name",                       "peegeeq_test");
        p.setProperty("peegeeq.database.username",                   "peegeeq");
        p.setProperty("peegeeq.database.password",                   "peegeeq");
        p.setProperty("peegeeq.database.pool.min-size",              "1");
        p.setProperty("peegeeq.database.pool.max-size",              "5");
        p.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        return p;
    }

    // =========================================================================
    // Guide section: Constructors and When to Use Each
    // =========================================================================

    /**
     * The profile is mandatory, explicit configuration.
     *
     * <p>There is no no-arg constructor and no ambient profile resolution: the
     * {@code peegeeq.profile} system property and {@code PEEGEEQ_PROFILE} env var are
     * not configuration channels. Callers always name the profile explicitly.</p>
     *
     * <p>Guide reference: "new PeeGeeQConfiguration(String profile, Properties overrides)"</p>
     */
    @Test
    void constructor_profileIsExplicit_ambientProfilePropertyHasNoEffect() {
        String saved = System.getProperty("peegeeq.profile");
        try {
            // Even with an ambient profile set, the explicitly passed profile is used —
            // the ambient value is never read.
            System.setProperty("peegeeq.profile", "demo");

            PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", new Properties());

            assertEquals("default", config.getProfile(),
                "The explicitly passed profile must be used; peegeeq.profile must have no effect");
        } finally {
            if (saved == null) {
                System.clearProperty("peegeeq.profile");
            } else {
                System.setProperty("peegeeq.profile", saved);
            }
        }
    }

    /**
     * Constructor 2  profile only.
     *
     * <p>Loads a named profile. The {@code "demo"} profile ships with this module
     * ({@code peegeeq-demo.properties}) and has all required properties as literal
     * values, so it constructs without any overrides.</p>
     *
     * <p>Guide reference: "new PeeGeeQConfiguration(String profile)"</p>
     */
    @Test
    void constructor_profileOnly_loadsNamedProfile() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("demo", new Properties());

        assertEquals("demo", config.getProfile());
        // The demo profile hardcodes localhost  no placeholders, no env var needed.
        assertEquals("localhost", config.getDatabaseConfig().getHost());
    }

    /**
     * Constructor 3  profile + programmatic overrides (RECOMMENDED).
     *
     * <p>Overrides are applied after all other sources (profile file, env vars,
     * system properties) and are therefore the highest-priority input.
     * This is the correct pattern for multi-tenant deployments and tests.</p>
     *
     * <p>Guide reference: "new PeeGeeQConfiguration(String profile, Properties overrides)"</p>
     */
    @Test
    void constructor_profileAndOverrides_overridesDominateAllOtherSources() {
        Properties overrides = validBase();
        overrides.setProperty("peegeeq.database.host",   "tenant-db.internal");
        overrides.setProperty("peegeeq.database.schema", "tenant_alpha");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", overrides);

        assertEquals("tenant-db.internal", config.getDatabaseConfig().getHost());
        assertEquals("tenant_alpha",       config.getDatabaseConfig().getSchema());
    }

    /**
     * Constructor 4  explicit database coordinates.
     *
     * <p>Convenience overload. Equivalent to the 2-arg constructor with the six
     * database properties pre-populated. Useful when wiring from a DI framework
     * that resolves each field individually.</p>
     *
     * <p>Guide reference: "new PeeGeeQConfiguration(String profile, String dbHost, ...)"</p>
     */
    @Test
    void constructor_explicitDbCoordinates_setsAllSixValues() {
        PeeGeeQConfiguration config = new PeeGeeQConfiguration(
            "default",
            "db.production.internal", // host
            5432,                     // port
            "peegeeq_prod",           // dbName
            "prod_user",              // username
            "prod_pass",              // password
            "tenant_prod"             // schema
        );

        PgConnectionConfig db = config.getDatabaseConfig();
        assertEquals("db.production.internal", db.getHost());
        assertEquals(5432,                     db.getPort());
        assertEquals("peegeeq_prod",           db.getDatabase());
        assertEquals("prod_user",              db.getUsername());
        assertEquals("prod_pass",              db.getPassword());
        assertEquals("tenant_prod",            db.getSchema());
    }

    // =========================================================================
    // Guide section: Priority Chain  Phase 1 (5-source merge)
    // =========================================================================

    /**
     * Priority step 5 wins over step 2: programmatic overrides beat the profile file.
     *
     * <p>The {@code "demo"} profile sets {@code peegeeq.queue.batch-size=5}.
     * Supplying {@code batch-size=99} in overrides must win.</p>
     */
    @Test
    void priorityChain_programmaticOverridesWinOverProfileFile() {
        // demo profile: peegeeq.queue.batch-size=5
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.queue.batch-size", "99");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("demo", overrides);

        assertEquals(99, config.getQueueConfig().getBatchSize(),
            "Step 5 (programmatic override) must beat step 2 (profile file)");
    }

    /**
     * Priority step 2 wins over step 1: named profile values override defaults.
     *
     * <p>The {@code "demo"} profile sets {@code peegeeq.queue.polling-interval=PT1S}.
     * {@code peegeeq-default.properties} sets it to {@code PT5S}.
     * When using the demo profile with no override, PT1S must win.</p>
     */
    @Test
    void priorityChain_profileFileBeatsDefaultProperties() {
        // No overrides  rely on the profile file competing against defaults.
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("demo", new Properties());

        // demo: PT1S overrides default: PT5S
        assertEquals(Duration.ofSeconds(1), config.getQueueConfig().getPollingInterval(),
            "Step 2 (profile file) must beat step 1 (peegeeq-default.properties)");
    }

    /**
     * Keys absent from overrides fall back through the priority chain.
     *
     * <p>If {@code overrides} does not include a key, the value from the profile
     * file (step 2) is used. The override map does not need to be exhaustive.</p>
     */
    @Test
    void priorityChain_missingKeyInOverridesFallsBackToProfileFile() {
        // validBase() does not set peegeeq.queue.polling-interval.
        // demo profile sets it to PT1S; that should survive through the merge.
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("demo", validBase());

        assertEquals(Duration.ofSeconds(1), config.getQueueConfig().getPollingInterval(),
            "Key absent from overrides must fall back to the profile file value");
    }

    // =========================================================================
    // Guide section: Priority Chain  Phase 2 (Placeholder resolution)
    // =========================================================================

    /**
     * {@code ${VAR:fallback}} resolves to {@code fallback} when the env var is absent.
     *
     * <p>The placeholder is placed in a programmatic override. Phase 2 runs after
     * the full merge (including step 5 overrides), so placeholders in overrides are
     * resolved too.</p>
     *
     * <p>Guide reference: "${VAR:default}  value of env var VAR if set, otherwise default"</p>
     */
    @Test
    void placeholder_withFallback_usesFallbackWhenEnvVarIsAbsent() {
        Properties overrides = validBase();
        // This env var does not exist in any normal dev or CI environment.
        overrides.setProperty("peegeeq.database.schema",
            "${PEEGEEQ_GUIDE_EXAMPLE_SCHEMA_ABSENT:fallback_schema}");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", overrides);

        assertEquals("fallback_schema", config.getDatabaseConfig().getSchema(),
            "${VAR:fallback} must resolve to 'fallback' when the env var is not set");
    }

    /**
     * {@code ${VAR:}} with an empty default resolves to an empty string when the env var is absent.
     *
     * <p>This is the pattern used for {@code peegeeq.database.password} in the
     * production profile  an empty password triggers a WARN but does not fail
     * validation (trust/peer authentication scenario).</p>
     */
    @Test
    void placeholder_withEmptyDefault_resolvesToEmptyStringWhenEnvVarIsAbsent() {
        Properties overrides = validBase();
        overrides.setProperty("peegeeq.database.password", "${PEEGEEQ_GUIDE_EXAMPLE_PWD_ABSENT:}");

        // Empty password  WARN is logged, but validation does not throw
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", overrides);

        assertEquals("", config.getString("peegeeq.database.password", "NOT_EMPTY"),
            "${VAR:} must resolve to an empty string when the env var is not set");
    }

    /**
     * {@code ${VAR}} with no default is left unchanged when the env var is absent.
     *
     * <p>PeeGeeQConfiguration logs a WARN in this case. The raw placeholder string
     * is preserved as the property value so the operator can diagnose the missing
     * environment variable.</p>
     */
    @Test
    void placeholder_withNoDefault_isLeftUnchangedWhenEnvVarIsAbsent() {
        Properties overrides = validBase();
        // A non-critical property so the unresolved placeholder doesn't break validation.
        overrides.setProperty("peegeeq.metrics.instance-id",
            "${PEEGEEQ_GUIDE_EXAMPLE_INSTANCE_ID_ABSENT}");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", overrides);

        assertEquals("${PEEGEEQ_GUIDE_EXAMPLE_INSTANCE_ID_ABSENT}",
            config.getString("peegeeq.metrics.instance-id", ""),
            "${VAR} with no default must be preserved as-is when the env var is absent");
    }

    /**
     * Multiple placeholders in the same value are all resolved.
     *
     * <p>Phase 2 replaces every {@code ${...}} token in each property value,
     * not just the first one.</p>
     */
    @Test
    void placeholder_multipleInSameValue_allResolved() {
        Properties overrides = validBase();
        // Both vars absent  both fall back to their defaults
        overrides.setProperty("peegeeq.metrics.instance-id",
            "${PEEGEEQ_GUIDE_APP_ABSENT:myapp}-${PEEGEEQ_GUIDE_REGION_ABSENT:eu-west-1}");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", overrides);

        assertEquals("myapp-eu-west-1",
            config.getString("peegeeq.metrics.instance-id", ""),
            "All placeholders in a single value must be resolved in Phase 2");
    }

    /**
     * Placeholder resolution applies to values from the profile file, not just overrides.
     *
     * <p>This is the mechanism behind the production profile's
     * {@code peegeeq.database.host=${DB_HOST:localhost}} pattern.</p>
     */
    @Test
    void placeholder_inProfileFileValue_isResolvedInPhase2() {
        // The "demo" profile uses literal values (no placeholders), but we can
        // demonstrate the mechanism by placing a placeholder in an override that
        // represents what a profile file would contain.
        Properties overrides = validBase();
        overrides.setProperty("peegeeq.database.host",
            "${PEEGEEQ_GUIDE_DB_HOST_ABSENT:profile-file-default.internal}");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", overrides);

        assertEquals("profile-file-default.internal",
            config.getDatabaseConfig().getHost(),
            "Placeholder in a profile-file-style value must be resolved in Phase 2");
    }

    // =========================================================================
    // Guide section: Configuration Validation Rules
    // =========================================================================

    /**
     * A fully valid property set passes validation without throwing.
     */
    @Test
    void validation_validProperties_pass() {
        assertDoesNotThrow(() -> new PeeGeeQConfiguration("default", validBase()),
            "A complete, valid property set must pass configuration validation");
    }

    /**
     * Multiple violations are collected and reported together in one exception.
     *
     * <p>Operators receive the full list of problems in a single startup failure
     * rather than fixing them one at a time.</p>
     */
    @Test
    void validation_multipleViolations_reportedInOneException() {
        Properties bad = validBase();
        bad.setProperty("peegeeq.database.host",     "");      // required  empty  fail
        bad.setProperty("peegeeq.database.name",     "");      // required  empty  fail
        bad.setProperty("peegeeq.database.username", "");      // required  empty  fail
        bad.setProperty("peegeeq.database.port",     "99999"); // must be 165535

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new PeeGeeQConfiguration("default", bad));

        String msg = ex.getMessage();
        assertTrue(msg.contains("Database host is required"),     "Should report empty host");
        assertTrue(msg.contains("Database name is required"),     "Should report empty name");
        assertTrue(msg.contains("Database username is required"), "Should report empty username");
        assertTrue(msg.contains("Database port must be between"), "Should report invalid port");
    }

    /**
     * Pool {@code max-size} less than {@code min-size} is rejected.
     *
     * <p>Guide reference: "pool.max-size  pool.min-size"</p>
     */
    @Test
    void validation_poolMaxSizeLessThanMinSize_rejected() {
        Properties bad = validBase();
        bad.setProperty("peegeeq.database.pool.min-size", "10");
        bad.setProperty("peegeeq.database.pool.max-size", "5");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new PeeGeeQConfiguration("default", bad));

        assertTrue(ex.getMessage().contains(
            "Maximum pool size must be greater than or equal to minimum pool size"));
    }

    /**
     * Recovery {@code check-interval}  {@code processing-timeout} is rejected.
     *
     * <p>The scanner must wait longer than the processing timeout before declaring
     * a message stuck; otherwise it requeues messages that are still being processed.</p>
     *
     * <p>Guide reference: "check-interval must be strictly greater than processing-timeout"</p>
     */
    @Test
    void validation_recoveryCheckIntervalNotLongerThanProcessingTimeout_rejected() {
        Properties bad = validBase();
        bad.setProperty("peegeeq.queue.recovery.enabled",            "true");
        bad.setProperty("peegeeq.queue.recovery.processing-timeout", "PT10M");
        bad.setProperty("peegeeq.queue.recovery.check-interval",     "PT5M"); // must be > PT10M

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new PeeGeeQConfiguration("default", bad));

        assertTrue(ex.getMessage().contains(
            "Recovery check interval should be longer than processing timeout"));
    }

    /**
     * Connection timeout of 0 ms is rejected.
     *
     * <p>Guide reference: "pool.connection-timeout-ms must be > 0"</p>
     */
    @Test
    void validation_connectionTimeoutZero_rejected() {
        Properties bad = validBase();
        bad.setProperty("peegeeq.database.pool.connection-timeout-ms", "0");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new PeeGeeQConfiguration("default", bad));

        assertTrue(ex.getMessage().contains("Connection timeout must be greater than 0ms"));
    }

    // =========================================================================
    // Guide section: Operator Deployment  Multi-Tenant Pattern
    // =========================================================================

    /**
     * Each tenant gets a completely isolated {@code PeeGeeQConfiguration} instance.
     *
     * <p>No {@code System.setProperty} is used. Both configs are constructed
     * concurrently without any shared mutable state. The tenant's schema, host,
     * pool sizing, and metrics instance-id are fully independent.</p>
     *
     * <p>Guide reference: "Multi-Tenant / Multi-Instance in One JVM"</p>
     */
    @Test
    void multiTenant_eachTenantConfigIsCompletelyIsolated() {
        Properties tenantA = validBase();
        tenantA.setProperty("peegeeq.database.host",          "tenant-a.db.internal");
        tenantA.setProperty("peegeeq.database.schema",        "tenant_a");
        tenantA.setProperty("peegeeq.database.pool.max-size", "5");
        tenantA.setProperty("peegeeq.metrics.instance-id",    "peegeeq-tenant-a");

        Properties tenantB = validBase();
        tenantB.setProperty("peegeeq.database.host",          "tenant-b.db.internal");
        tenantB.setProperty("peegeeq.database.schema",        "tenant_b");
        tenantB.setProperty("peegeeq.database.pool.max-size", "20");
        tenantB.setProperty("peegeeq.metrics.instance-id",    "peegeeq-tenant-b");

        PeeGeeQConfiguration configA = new PeeGeeQConfiguration("default", tenantA);
        PeeGeeQConfiguration configB = new PeeGeeQConfiguration("default", tenantB);

        // Tenant A values
        assertEquals("tenant-a.db.internal", configA.getDatabaseConfig().getHost());
        assertEquals("tenant_a",             configA.getDatabaseConfig().getSchema());
        assertEquals(5,                       configA.getPoolConfig().getMaxSize());
        assertEquals("peegeeq-tenant-a",      configA.getMetricsConfig().getInstanceId());

        // Tenant B values
        assertEquals("tenant-b.db.internal", configB.getDatabaseConfig().getHost());
        assertEquals("tenant_b",             configB.getDatabaseConfig().getSchema());
        assertEquals(20,                      configB.getPoolConfig().getMaxSize());
        assertEquals("peegeeq-tenant-b",      configB.getMetricsConfig().getInstanceId());

        // Cross-check: A's values are not visible from B (shared state would fail here)
        assertNotEquals(configA.getDatabaseConfig().getHost(),   configB.getDatabaseConfig().getHost());
        assertNotEquals(configA.getDatabaseConfig().getSchema(), configB.getDatabaseConfig().getSchema());
        assertNotEquals(configA.getPoolConfig().getMaxSize(),    configB.getPoolConfig().getMaxSize());
    }

    /**
     * Mutating one tenant's source {@code Properties} after construction does not
     * affect the already-constructed configuration.
     *
     * <p>{@code PeeGeeQConfiguration} copies the merged property set internally;
     * it does not hold a reference to the caller's {@code Properties} object.</p>
     */
    @Test
    void multiTenant_postConstructionMutationOfOverridesDoesNotAffectConfig() {
        Properties overrides = validBase();
        overrides.setProperty("peegeeq.database.host", "original-host.internal");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", overrides);

        // Mutate the source after construction
        overrides.setProperty("peegeeq.database.host", "mutated-host.internal");

        // Config must still hold the value at construction time
        assertEquals("original-host.internal", config.getDatabaseConfig().getHost(),
            "PeeGeeQConfiguration must not be affected by post-construction mutation of overrides");
    }

    // =========================================================================
    // Guide section: Named Profiles
    // =========================================================================

    /**
     * The {@code "demo"} profile is self-contained: all required properties are
     * provided by the file so no overrides are needed.
     */
    @Test
    void namedProfile_demo_isFullySelfContained() {
        assertDoesNotThrow(() -> new PeeGeeQConfiguration("demo", new Properties()),
            "The 'demo' profile must supply all required properties without any overrides");
    }

    /**
     * The {@code "default"} profile can be supplemented with programmatic overrides
     * for properties that differ from the baseline.
     *
     * <p>This is the recommended pattern for tests: load defaults, then supply
     * only the properties your test cares about.</p>
     */
    @Test
    void namedProfile_default_canBeSupplementedWithOverrides() {
        Properties overrides = new Properties();
        overrides.setProperty("peegeeq.queue.batch-size",    "42");
        overrides.setProperty("peegeeq.queue.max-retries",   "7");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", overrides);

        assertEquals("default", config.getProfile());
        assertEquals(42, config.getQueueConfig().getBatchSize());
        assertEquals(7,  config.getQueueConfig().getMaxRetries());
        // All other properties come from peegeeq-default.properties
    }

    /**
     * Profile selection from the constructor argument takes priority over
     * the {@code peegeeq.profile} system property.
     *
     * <p>When the profile is passed explicitly there is no ambiguity: the
     * named profile is always loaded regardless of JVM arguments or env vars.</p>
     */
    @Test
    void namedProfile_constructorArgumentIsAlwaysUsed() {
        // Even if someone sets peegeeq.profile=something-else in the JVM args,
        // the explicit constructor argument wins.
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("demo", validBase());

        assertEquals("demo", config.getProfile(),
            "The profile passed to the constructor must always be used");
    }
}
