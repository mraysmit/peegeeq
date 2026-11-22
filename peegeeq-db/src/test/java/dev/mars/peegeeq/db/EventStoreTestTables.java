package dev.mars.peegeeq.db;

/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
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

import dev.mars.peegeeq.db.setup.SqlTemplateProcessor;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

/**
 * Creates named bi-temporal event-store tables for tests from the production templates.
 *
 * <p>Production creates an event store by applying {@code base/06-event-store-template.sql}
 * (the {@code event_store_template} table) and the {@code eventstore} template directory
 * (the table, its indexes, the notify function and the trigger) through
 * {@link SqlTemplateProcessor}. Tests that need an event-store table under another name use
 * this class instead of hand-written DDL, so the table has exactly the production shape and
 * notifies on the channels the store listens to.</p>
 */
public final class EventStoreTestTables {

    private EventStoreTestTables() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates {@code schema.tableName} from the production templates unless it already exists.
     * The templates run in one transaction through a short-lived pool, which is closed whatever
     * the outcome; a failed template or a failed close fails the returned Future.
     *
     * <p>The trigger template is not idempotent, so an existing table is left as it is.</p>
     */
    public static Future<Void> createIfMissing(Vertx vertx, PostgreSQLContainer postgres,
                                               String schema, String tableName) {
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool pool = PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(1).setShared(false))
            .connectingTo(connectOptions)
            .using(vertx)
            .build();

        SqlTemplateProcessor templates = new SqlTemplateProcessor();
        Map<String, String> params = Map.of(
            "schema", schema,
            "tableName", tableName,
            "notificationPrefix", tableName + "_");

        return pool.withTransaction(connection -> connection
                .preparedQuery("SELECT to_regclass($1) IS NOT NULL AS present")
                .execute(Tuple.of(schema + ".\"" + tableName + "\""))
                .compose(rows -> rows.iterator().next().getBoolean("present")
                    ? Future.<Void>succeededFuture()
                    : templates.applyTemplate(connection, "base/06-event-store-template.sql", params)
                        .compose(v -> templates.applyTemplate(connection, "eventstore", params))))
            .transform(TestCleanup.cleanup(() -> pool.close()));
    }
}
