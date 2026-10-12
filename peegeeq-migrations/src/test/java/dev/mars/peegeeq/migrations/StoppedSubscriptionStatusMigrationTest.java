package dev.mars.peegeeq.migrations;

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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the step from migration V020 to V021 on a schema that already holds subscriptions.
 *
 * <p>V021 replaces the status check constraint of {@code outbox_topic_subscriptions} so that
 * it accepts STOPPED. Each test migrates its own schema to V020, writes rows, migrates to
 * V021, and checks one effect: existing rows are kept, STOPPED becomes a valid status, and
 * an unknown status stays invalid.</p>
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Tag(TestCategories.INTEGRATION)
class StoppedSubscriptionStatusMigrationTest {

    private static final String STATUS_CONSTRAINT = "outbox_topic_subscriptions_subscription_status_check";
    private static final String CHECK_VIOLATION = "23514";
    private static final String TOPIC = "migration-v021-topic";

    @Container
    private static final PostgreSQLContainer postgres =
        PostgreSQLTestConstants.createStandardContainer();

    private Vertx vertx;
    private Pool pool;

    @BeforeEach
    void setUp(Vertx vertx) {
        this.vertx = vertx;
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool = PgBuilder.pool()
            .with(new PoolOptions().setMaxSize(2).setShared(false))
            .connectingTo(connectOptions)
            .using(vertx)
            .build();
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        pool.close()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void existingRowsSurviveTheMigration(VertxTestContext testContext) {
        String schema = "migration_v021_rows";

        migrate(schema, "020")
            .compose(v -> insertSubscription(schema, "group-active", "ACTIVE"))
            .compose(v -> insertSubscription(schema, "group-paused", "PAUSED"))
            .compose(v -> insertSubscription(schema, "group-cancelled", "CANCELLED"))
            .compose(v -> insertSubscription(schema, "group-dead", "DEAD"))
            .compose(v -> migrate(schema, "021"))
            .compose(v -> readStatuses(schema))
            .onSuccess(statuses -> testContext.verify(() -> {
                assertEquals(
                    Map.of(
                        "group-active", "ACTIVE",
                        "group-cancelled", "CANCELLED",
                        "group-dead", "DEAD",
                        "group-paused", "PAUSED"),
                    statuses,
                    "V021 must keep every existing subscription row and its status");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void stoppedIsRejectedBeforeTheMigrationAndAcceptedAfterIt(VertxTestContext testContext) {
        String schema = "migration_v021_stopped";

        migrate(schema, "020")
            .compose(v -> insertSubscription(schema, "group-active", "ACTIVE"))
            .compose(v -> expectStatusRejected(schema, "group-stopped", "STOPPED"))
            .compose(v -> migrate(schema, "021"))
            .compose(v -> insertSubscription(schema, "group-stopped", "STOPPED"))
            .compose(v -> updateStatus(schema, "group-active", "STOPPED"))
            .compose(v -> readStatuses(schema))
            .onSuccess(statuses -> testContext.verify(() -> {
                assertEquals(
                    Map.of(
                        "group-active", "STOPPED",
                        "group-stopped", "STOPPED"),
                    statuses,
                    "After V021 a row can be inserted with STOPPED and an existing row can be set to STOPPED");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void unknownStatusIsRejectedAfterTheMigration(VertxTestContext testContext) {
        String schema = "migration_v021_unknown";

        migrate(schema, "020")
            .compose(v -> insertSubscription(schema, "group-active", "ACTIVE"))
            .compose(v -> migrate(schema, "021"))
            .compose(v -> expectStatusRejected(schema, "group-unknown", "UNKNOWN"))
            .compose(v -> readStatuses(schema))
            .onSuccess(statuses -> testContext.verify(() -> {
                assertEquals(
                    Map.of("group-active", "ACTIVE"),
                    statuses,
                    "A rejected insert must leave no row");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Runs the Flyway migrations of this module on one schema, up to and including the
     * target version. Flyway blocks, so the call runs on a worker thread.
     */
    private Future<Void> migrate(String schema, String targetVersion) {
        return vertx.executeBlocking(() -> {
            Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("filesystem:src/main/resources/db/migration")
                .target(targetVersion)
                .load()
                .migrate();
            return null;
        }, false).mapEmpty();
    }

    private Future<Void> insertSubscription(String schema, String groupName, String status) {
        String sql = "INSERT INTO " + schema + ".outbox_topic_subscriptions "
            + "(topic, group_name, subscription_status) VALUES ($1, $2, $3)";
        return pool.withTransaction(connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(TOPIC, groupName, status))
                .mapEmpty());
    }

    private Future<Void> updateStatus(String schema, String groupName, String status) {
        String sql = "UPDATE " + schema + ".outbox_topic_subscriptions "
            + "SET subscription_status = $3 WHERE topic = $1 AND group_name = $2";
        return pool.withTransaction(connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(TOPIC, groupName, status))
                .mapEmpty());
    }

    /**
     * Succeeds only when the insert fails with a check violation on the status constraint.
     */
    private Future<Void> expectStatusRejected(String schema, String groupName, String status) {
        return insertSubscription(schema, groupName, status)
            .transform(result -> {
                if (result.succeeded()) {
                    return Future.failedFuture(new AssertionError(
                        "Status '" + status + "' must be rejected by " + STATUS_CONSTRAINT));
                }
                if (result.cause() instanceof PgException pgException
                        && CHECK_VIOLATION.equals(pgException.getSqlState())
                        && STATUS_CONSTRAINT.equals(pgException.getConstraint())) {
                    return Future.<Void>succeededFuture();
                }
                return Future.failedFuture(new AssertionError(
                    "Status '" + status + "' must fail with a check violation on "
                        + STATUS_CONSTRAINT + " but failed with: " + result.cause(), result.cause()));
            });
    }

    private Future<Map<String, String>> readStatuses(String schema) {
        String sql = "SELECT group_name, subscription_status FROM " + schema
            + ".outbox_topic_subscriptions WHERE topic = $1";
        return pool.preparedQuery(sql)
            .execute(Tuple.of(TOPIC))
            .map(rows -> {
                Map<String, String> statuses = new TreeMap<>();
                for (Row row : rows) {
                    statuses.put(row.getString("group_name"), row.getString("subscription_status"));
                }
                return statuses;
            });
    }
}
