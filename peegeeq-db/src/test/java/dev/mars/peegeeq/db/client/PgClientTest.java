package dev.mars.peegeeq.db.client;

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


import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.time.Duration;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Implementation of PgClientTest functionality.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
public class PgClientTest {

    private static final Logger logger = LoggerFactory.getLogger(PgClientTest.class);

    private PgClientFactory clientFactory;
    private PgClient pgClient;

    @BeforeEach
    void setUp(Vertx vertx) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();
        clientFactory = new PgClientFactory(vertx);

        // Create connection config from TestContainer
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        // Create pool config
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(3)
                .shared(false)
                .idleTimeout(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofSeconds(5))
                .build();

        // Create client and ensure DataSource is set up for JDBC operations
        pgClient = clientFactory.createClient("test-client", connectionConfig, poolConfig);
    }

    @AfterEach
    void tearDown() {
        if (clientFactory != null) {
            clientFactory.close().onFailure(e -> logger.warn("clientFactory.close() failed in tearDown", e));
        }
    }

    @Test
    void testGetReactiveConnection(VertxTestContext testContext) {
        pgClient.getReactiveConnection()
            .compose(connection -> {
                return connection.preparedQuery("SELECT 1")
                    .execute()
                    .map(rowSet -> {
                        Row row = rowSet.iterator().next();
                        return row.getInteger(0);
                    })
                    .eventually(() -> connection.close());
            })
            .onComplete(testContext.succeeding(value -> testContext.verify(() -> {
                assertNotNull(value);
                assertEquals(1, value);
                testContext.completeNow();
            })));
    }

    @Test
    void testWithReactiveConnectionResultInteger(VertxTestContext testContext) {
        pgClient.withReactiveConnectionResult(connection -> {
            return connection.preparedQuery("SELECT 1")
                .execute()
                .map(rowSet -> {
                    Row row = rowSet.iterator().next();
                    return row.getInteger(0);
                });
        })
        .onComplete(testContext.succeeding(value -> testContext.verify(() -> {
            assertNotNull(value);
            assertEquals(1, value);
            testContext.completeNow();
        })));
    }

    @Test
    void testWithReactiveConnectionResultString(VertxTestContext testContext) {
        pgClient.withReactiveConnectionResult(connection -> {
            return connection.preparedQuery("SELECT 'test' as message")
                .execute()
                .map(rowSet -> {
                    Row row = rowSet.iterator().next();
                    return row.getString("message");
                });
        })
        .onComplete(testContext.succeeding(value -> testContext.verify(() -> {
            assertNotNull(value);
            assertEquals("test", value);
            testContext.completeNow();
        })));
    }

    @Test
    void testReactiveMethodsWork() {
        // Test reactive connection methods work
        assertDoesNotThrow(() -> {
            Future<SqlConnection> connectionFuture = pgClient.getReactiveConnection();
            assertNotNull(connectionFuture);
        });

        // Test reactive withConnection works
        assertDoesNotThrow(() -> {
            Future<Void> result = pgClient.withReactiveConnection(conn -> Future.succeededFuture());
            assertNotNull(result);
        });

        // Test reactive withConnectionResult works
        assertDoesNotThrow(() -> {
            Future<String> result = pgClient.withReactiveConnectionResult(conn -> Future.succeededFuture("test"));
            assertNotNull(result);
        });
    }

    @Test
    void testGetReactivePool(VertxTestContext testContext) {
        Pool pool = pgClient.getReactivePool();
        assertNotNull(pool, "Pool should not be null");

        pool.getConnection()
            .compose(connection -> {
                return connection.preparedQuery("SELECT 42 as answer")
                    .execute()
                    .map(rowSet -> {
                        Row row = rowSet.iterator().next();
                        return row.getInteger("answer");
                    })
                    .eventually(() -> connection.close());
            })
            .onComplete(testContext.succeeding(answer -> testContext.verify(() -> {
                assertEquals(42, answer, "Pool should be functional");
                testContext.completeNow();
            })));
    }
}