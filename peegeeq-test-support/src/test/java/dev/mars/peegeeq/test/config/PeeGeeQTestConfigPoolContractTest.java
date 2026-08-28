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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Contract tests for the deterministic pool defaults supplied by
 * {@link PeeGeeQTestConfig}.
 */
@Tag(TestCategories.CORE)
class PeeGeeQTestConfigPoolContractTest {

    @Test
    void testPoolIsNonSharedForDeterministicClose() {
        assertEquals("false", buildProperties().getProperty("peegeeq.database.pool.shared"));
    }

    @Test
    void testPoolMaxSizeLeavesParallelExecutionHeadroom() {
        assertEquals("3", buildProperties().getProperty("peegeeq.database.pool.max-size"));
    }

    @Test
    void testUnsupportedPoolMinSizeIsNotAdvertised() {
        assertFalse(buildProperties().containsKey("peegeeq.database.pool.min-size"));
    }

    @Test
    void testPoolConnectionTimeoutFailsFast() {
        assertEquals("5000",
                buildProperties().getProperty("peegeeq.database.pool.connection-timeout-ms"));
    }

    @Test
    void testPoolIdleTimeoutSupportsFastTeardown() {
        assertEquals("2000",
                buildProperties().getProperty("peegeeq.database.pool.idle-timeout-ms"));
    }

    private Properties buildProperties() {
        return PeeGeeQTestConfig.builder()
                .from(new StubContainer())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();
    }

    /** Supplies fixed connection coordinates without starting PostgreSQL. */
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
