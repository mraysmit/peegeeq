package dev.mars.peegeeq.test.config;

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

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for {@link PeeGeeQTestConfig} schema handling.
 *
 * <p>PeeGeeQ has NO default schema, anywhere in the system. The schema is mandatory,
 * explicit configuration: a builder whose {@code schema(...)} was never called must throw,
 * and ambient channels (JVM system properties, environment variables) must have no effect.
 * The configuration-architecture refactoring
 * (PEEGEEQ_CONFIG_ARCHITECTURE_REPLACE_PROCESS_GLOBALS_WITH_INSTANCE_ISOLATION, Phase 11)
 * removed the System-property sweep deliberately; this contract locks the same rule at the
 * test-support layer so ambient configuration cannot be reintroduced.</p>
 *
 * <p>No database is required: a stub container supplies connection coordinates only.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-12
 */
@Tag(TestCategories.CORE)
class PeeGeeQTestConfigSchemaContractTest {

    private static final Logger logger =
            LoggerFactory.getLogger(PeeGeeQTestConfigSchemaContractTest.class);

    private static final String SCHEMA_PROPERTY = "peegeeq.database.schema";

    private String savedSchemaProperty;

    @BeforeEach
    void saveSchemaProperty() {
        savedSchemaProperty = System.getProperty(SCHEMA_PROPERTY);
    }

    @AfterEach
    void restoreSchemaProperty() {
        if (savedSchemaProperty == null) {
            System.clearProperty(SCHEMA_PROPERTY);
        } else {
            System.setProperty(SCHEMA_PROPERTY, savedSchemaProperty);
        }
    }

    @Test
    void testBuildWithoutSchemaThrows() {
        logger.info("=== TEST METHOD STARTED: testBuildWithoutSchemaThrows ===");

        System.clearProperty(SCHEMA_PROPERTY);

        PeeGeeQTestConfig.Builder builder = PeeGeeQTestConfig.builder()
                .from(new StubContainer());

        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build,
                "build() without an explicit schema(...) call must throw — "
                        + "PeeGeeQ has no default schema");
        assertTrue(ex.getMessage().contains("schema"),
                "The error must name the missing schema, got: " + ex.getMessage());

        logger.info("=== TEST METHOD COMPLETED: testBuildWithoutSchemaThrows ===");
    }

    @Test
    void testExplicitSchemaLandsInProperties() {
        logger.info("=== TEST METHOD STARTED: testExplicitSchemaLandsInProperties ===");

        Properties props = PeeGeeQTestConfig.builder()
                .from(new StubContainer())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        assertEquals(PostgreSQLTestConstants.TEST_SCHEMA, props.getProperty(SCHEMA_PROPERTY),
                "The explicitly set schema must land in the built properties unchanged");

        logger.info("=== TEST METHOD COMPLETED: testExplicitSchemaLandsInProperties ===");
    }

    @Test
    void testSystemPropertyHasNoEffect() {
        logger.info("=== TEST METHOD STARTED: testSystemPropertyHasNoEffect ===");

        System.setProperty(SCHEMA_PROPERTY, "ambient_schema_must_be_ignored");

        PeeGeeQTestConfig.Builder builder = PeeGeeQTestConfig.builder()
                .from(new StubContainer());

        // Phase-11 rule: system properties are not a configuration channel. The builder
        // must still throw — the ambient value must not be picked up as a schema.
        assertThrows(IllegalStateException.class, builder::build,
                "A peegeeq.database.schema system property must have NO effect: "
                        + "build() without an explicit schema(...) call must still throw");

        logger.info("=== TEST METHOD COMPLETED: testSystemPropertyHasNoEffect ===");
    }

    @Test
    void testBlankExplicitSchemaRejected() {
        logger.info("=== TEST METHOD STARTED: testBlankExplicitSchemaRejected ===");

        PeeGeeQTestConfig.Builder builder = PeeGeeQTestConfig.builder()
                .from(new StubContainer())
                .schema("   ");

        assertThrows(IllegalArgumentException.class, builder::build,
                "A blank explicit schema must be rejected, not passed through");

        logger.info("=== TEST METHOD COMPLETED: testBlankExplicitSchemaRejected ===");
    }

    @Test
    void testInvalidExplicitSchemaRejected() {
        logger.info("=== TEST METHOD STARTED: testInvalidExplicitSchemaRejected ===");

        PeeGeeQTestConfig.Builder builder = PeeGeeQTestConfig.builder()
                .from(new StubContainer())
                .schema("bad-schema-name");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build,
                "A schema outside the PostgreSQL identifier whitelist must be rejected");
        assertTrue(ex.getMessage().contains("bad-schema-name"),
                "The rejection message must name the offending schema, got: " + ex.getMessage());

        logger.info("=== TEST METHOD COMPLETED: testInvalidExplicitSchemaRejected ===");
    }

    /**
     * Supplies fixed connection coordinates without starting a container.
     * {@code getFirstMappedPort()} normally requires a started container; the contract
     * under test concerns only schema handling, so no database is needed.
     */
    private static final class StubContainer extends PostgreSQLContainer {

        StubContainer() {
            super(PostgreSQLTestConstants.POSTGRES_IMAGE);
        }

        @Override
        public String getHost() {
            return "stub-host";
        }

        @Override
        public Integer getFirstMappedPort() {
            return 5432;
        }

        @Override
        public String getDatabaseName() {
            return "stub_db";
        }

        @Override
        public String getUsername() {
            return "stub_user";
        }

        @Override
        public String getPassword() {
            return "stub_pass";
        }
    }
}
