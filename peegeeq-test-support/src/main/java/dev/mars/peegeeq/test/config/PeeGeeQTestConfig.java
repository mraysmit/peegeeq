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

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;

/**
 * Fluent builder that produces a {@link Properties} object containing PeeGeeQ database
 * configuration derived from a running {@link PostgreSQLContainer}, without writing
 * anything to {@code System.getProperties()}.
 *
 * <p>Intended for tests that run in parallel within the same JVM fork. Calling
 * {@code System.setProperty(...)} for PeeGeeQ keys is a known source of flaky test
 * failures because the properties are global mutable state. This builder passes the
 * extracted values directly to the
 * {@code PeeGeeQConfiguration(String profile, Properties overrides)} constructor
 * instead.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Properties props = PeeGeeQTestConfig.builder()
 *     .from(postgres)
 *     .schema(PostgreSQLTestConstants.TEST_SCHEMA)
 *     .property("peegeeq.health.check-interval", "PT5S")
 *     .build();
 *
 * PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", props);
 * PeeGeeQManager manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
 * }</pre>
 *
 * <p>The schema is REQUIRED: PeeGeeQ has no default schema anywhere in the system, and
 * ambient channels (system properties, environment variables) are not configuration.
 * {@link Builder#build()} throws if {@link Builder#schema(String)} was never called.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-06
 */
public final class PeeGeeQTestConfig {

    private PeeGeeQTestConfig() {}

    /** Returns a new {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @deprecated FOR REMOVAL — reads a JVM system property, which is not a PeeGeeQ
     * configuration channel (the Phase 11 config-architecture refactoring removed ambient
     * configuration deliberately; this method reintroduced it in error). Do not add new
     * callers. Existing call sites are being re-pointed to explicit schemas
     * ({@code PostgreSQLTestConstants.TEST_SCHEMA} or per-test literals); this method is
     * deleted once they are gone.
     */
    @Deprecated(forRemoval = true)
    public static String resolveSchema() {
        String configured = System.getProperty("peegeeq.database.schema", "public").trim();
        if (configured.isEmpty()) {
            return "public";
        }
        if (!configured.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException(
                "Invalid peegeeq.database.schema system property: " + configured);
        }
        return configured;
    }

    public static final class Builder {

        private String host;
        private int port;
        private String database;
        private String username;
        private String password;
        private String schema;
        private final Properties extras = new Properties();

        private Builder() {}

        /**
         * Extracts connection coordinates from a running {@link PostgreSQLContainer}.
         * Must be called before {@link #build()}.
         */
        public Builder from(PostgreSQLContainer container) {
            this.host     = container.getHost();
            this.port     = container.getFirstMappedPort();
            this.database = container.getDatabaseName();
            this.username = container.getUsername();
            this.password = container.getPassword();
            return this;
        }

        /**
         * Sets the schema name. REQUIRED: PeeGeeQ has no default schema anywhere in the
         * system, so {@link #build()} throws if this was never called. The value must be
         * a valid unquoted PostgreSQL identifier (letters, digits, underscores; no
         * hyphens). Shared-container suites pass
         * {@code PostgreSQLTestConstants.TEST_SCHEMA}; schema-isolation tests pass their
         * own explicit literals.
         */
        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        /**
         * Sets an arbitrary additional PeeGeeQ property (e.g.
         * {@code "peegeeq.database.ssl.enabled"}, {@code "peegeeq.database.pool.max-size"}).
         * These are applied last and override any value derived from the container.
         */
        public Builder property(String key, String value) {
            extras.setProperty(key, value);
            return this;
        }

        /**
         * Builds a {@link Properties} map containing all configured PeeGeeQ database settings.
         * The returned object is safe to pass to
         * {@code new PeeGeeQConfiguration(profile, overrides)}  it never touches
         * {@code System.getProperties()}.
         *
         * @throws IllegalStateException if {@link #from(PostgreSQLContainer)} was not called
         */
        public Properties build() {
            if (host == null) {
                throw new IllegalStateException(
                    "PeeGeeQTestConfig.Builder: call from(container) before build()");
            }
            if (schema == null) {
                throw new IllegalStateException(
                    "PeeGeeQTestConfig.Builder: schema must be set explicitly via schema(...) — "
                    + "PeeGeeQ has no default schema");
            }
            String validatedSchema = schema.trim();
            if (validatedSchema.isEmpty()) {
                throw new IllegalArgumentException(
                    "PeeGeeQTestConfig.Builder: schema cannot be blank — "
                    + "PeeGeeQ has no default schema");
            }
            if (!validatedSchema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                throw new IllegalArgumentException(
                    "PeeGeeQTestConfig.Builder: invalid schema name: " + schema);
            }

            Properties props = new Properties();
            props.setProperty("peegeeq.database.host",     host);
            props.setProperty("peegeeq.database.port",     String.valueOf(port));
            props.setProperty("peegeeq.database.name",     database);
            props.setProperty("peegeeq.database.username", username);
            props.setProperty("peegeeq.database.password", password);
            props.setProperty("peegeeq.database.schema",   validatedSchema);
            props.setProperty("peegeeq.database.ssl.enabled", "false");

            // Safe test defaults: disable background jobs that poll shared database state.
            // Tests specifically testing these jobs (e.g. ConsumerGroupRetryJobLifecycleTest)
            // use Properties directly and are unaffected by this default.
            props.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
            props.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");

            // Apply caller-supplied extras last so they can override any of the above
            extras.forEach((k, v) -> props.setProperty(k.toString(), v.toString()));

            return props;
        }
    }
}
