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

package dev.mars.peegeeq.db.setup;

import dev.mars.peegeeq.api.database.DatabaseConfig;
import dev.mars.peegeeq.db.util.PostgreSqlIdentifierValidator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable store for setup bindings — the persistence behind "remember this setup" and startup
 * auto-reload (Phase R / W-C).
 *
 * <p>Rows live in {@code {schema}.peegeeq_setup_bindings} inside the backend's bootstrap registry
 * database (given to the backend at startup; not any setup's data database). The table holds
 * connection coordinates plus an opaque, nullable {@code credential_ref} — never a password.
 *
 * <p>{@link #ensureSchema()} must be called once (idempotent) before the other operations; the
 * operations themselves never create the table, so an absent table fails loudly instead of being
 * papered over. Each operation uses a short-lived single-connection pool against the registry
 * database, mirroring the temporary-pool idiom of {@link PeeGeeQDatabaseSetupService}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-31
 * @version 1.0
 */
public class SetupBindingRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SetupBindingRegistry.class);

    private final Vertx vertx;
    private final DatabaseConfig registryDatabase;
    private final String schema;
    private final SqlTemplateProcessor templateProcessor = new SqlTemplateProcessor();

    /**
     * @param vertx            the owning Vert.x instance
     * @param registryDatabase coordinates + credentials of the bootstrap registry database; its
     *                         schema names where the bindings table lives and must be a valid
     *                         PostgreSQL identifier
     */
    public SetupBindingRegistry(Vertx vertx, DatabaseConfig registryDatabase) {
        this.vertx = Objects.requireNonNull(vertx, "vertx cannot be null");
        this.registryDatabase = Objects.requireNonNull(registryDatabase, "registryDatabase cannot be null");
        String registrySchema = registryDatabase.getSchema();
        if (registrySchema == null || registrySchema.isBlank()) {
            throw new IllegalArgumentException("Registry database schema is required and cannot be null or blank");
        }
        PostgreSqlIdentifierValidator.validate(registrySchema, "Registry schema");
        this.schema = registrySchema;
    }

    /**
     * Creates the registry schema and bindings table if absent (idempotent). Call once at startup
     * before any other operation.
     */
    public Future<Void> ensureSchema() {
        Pool pool = tempPool();
        return pool.withTransaction(conn ->
                        templateProcessor.applyTemplate(conn, "registry", Map.of("schema", schema)))
                .onSuccess(v -> logger.info("Setup-binding registry schema ensured in '{}.peegeeq_setup_bindings'",
                        schema))
                .onFailure(err -> logger.error("Failed to ensure setup-binding registry schema in '{}': {}",
                        schema, err.getMessage(), err))
                .eventually(pool::close)
                .mapEmpty();
    }

    /**
     * Persists (upserts) a binding. Re-persisting an existing {@code setupId} refreshes its
     * coordinates and {@code updated_at}; {@code created_at} is preserved.
     */
    public Future<Void> saveBinding(SetupBinding binding) {
        Objects.requireNonNull(binding, "binding cannot be null");
        String sql = "INSERT INTO " + schema + ".peegeeq_setup_bindings "
                + "(setup_id, host, port, database_name, schema_name, username, ssl_enabled, credential_ref) "
                + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) "
                + "ON CONFLICT (setup_id) DO UPDATE SET "
                + "host = EXCLUDED.host, port = EXCLUDED.port, database_name = EXCLUDED.database_name, "
                + "schema_name = EXCLUDED.schema_name, username = EXCLUDED.username, "
                + "ssl_enabled = EXCLUDED.ssl_enabled, credential_ref = EXCLUDED.credential_ref, "
                + "updated_at = NOW()";
        Pool pool = tempPool();
        return pool.withTransaction(conn -> conn.preparedQuery(sql).execute(Tuple.of(
                        binding.setupId(), binding.host(), binding.port(), binding.databaseName(),
                        binding.schemaName(), binding.username(), binding.sslEnabled(), binding.credentialRef())))
                .onSuccess(v -> logger.info("Persisted setup binding '{}' ({}:{}/{} schema '{}')",
                        binding.setupId(), binding.host(), binding.port(), binding.databaseName(),
                        binding.schemaName()))
                .onFailure(err -> logger.error("Failed to persist setup binding '{}': {}",
                        binding.setupId(), err.getMessage(), err))
                .eventually(pool::close)
                .mapEmpty();
    }

    /** Reads every persisted binding, ordered by {@code setup_id}. */
    public Future<List<SetupBinding>> listBindings() {
        String sql = "SELECT setup_id, host, port, database_name, schema_name, username, ssl_enabled, "
                + "credential_ref FROM " + schema + ".peegeeq_setup_bindings ORDER BY setup_id";
        Pool pool = tempPool();
        return pool.withConnection(conn -> conn.preparedQuery(sql).execute()
                        .map(rows -> {
                            List<SetupBinding> bindings = new ArrayList<>();
                            for (Row row : rows) {
                                bindings.add(new SetupBinding(
                                        row.getString("setup_id"),
                                        row.getString("host"),
                                        row.getInteger("port"),
                                        row.getString("database_name"),
                                        row.getString("schema_name"),
                                        row.getString("username"),
                                        row.getBoolean("ssl_enabled"),
                                        row.getString("credential_ref")));
                            }
                            return bindings;
                        }))
                .onFailure(err -> logger.error("Failed to list setup bindings from '{}': {}",
                        schema, err.getMessage(), err))
                .eventually(pool::close);
    }

    /** Removes a binding. Idempotent — deleting an absent {@code setupId} succeeds. */
    public Future<Void> deleteBinding(String setupId) {
        Objects.requireNonNull(setupId, "setupId cannot be null");
        String sql = "DELETE FROM " + schema + ".peegeeq_setup_bindings WHERE setup_id = $1";
        Pool pool = tempPool();
        return pool.withTransaction(conn -> conn.preparedQuery(sql).execute(Tuple.of(setupId)))
                .onSuccess(rows -> logger.info("Removed setup binding '{}' ({} row(s))",
                        setupId, rows.rowCount()))
                .onFailure(err -> logger.error("Failed to remove setup binding '{}': {}",
                        setupId, err.getMessage(), err))
                .eventually(pool::close)
                .mapEmpty();
    }

    private Pool tempPool() {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(registryDatabase.getHost())
                .setPort(registryDatabase.getPort())
                .setDatabase(registryDatabase.getDatabaseName())
                .setUser(registryDatabase.getUsername())
                .setPassword(registryDatabase.getPassword());
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(1))
                .connectingTo(connectOptions)
                .using(vertx)
                .build();
    }
}
