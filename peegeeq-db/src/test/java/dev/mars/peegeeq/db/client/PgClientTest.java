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
import org.testcontainers.postgresql.PostgreSQLContainer;


import java.util.concurrent.TimeUnit;

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
@ExtendWith(SharedPostgresTestExtension.class)
public class PgClientTest {

    private PgClientFactory clientFactory;
    private PgClient pgClient;
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();
        vertx = Vertx.vertx();
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
                .maxSize(5)
                .build();

        // Create client and ensure DataSource is set up for JDBC operations
        pgClient = clientFactory.createClient("test-client", connectionConfig, poolConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientFactory != null) {
            clientFactory.close();
        }
        if (vertx != null) {
            vertx.close();
        }
    }

    @Test
    void testGetReactiveConnection() throws Exception {
        VertxTestContext testContext = new VertxTestContext();

        pgClient.getReactiveConnection()
            .compose(connection -> {
                return connection.preparedQuery("SELECT 1")
                    .execute()
                    .map(rowSet -> {
                        Row row = rowSet.iterator().next();
                        return row.getInteger(0);
                    })
                    .onComplete(ar -> connection.close());
            })
            .onSuccess(value -> testContext.verify(() -> {
                assertNotNull(value);
                assertEquals(1, value);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            Throwable cause = testContext.causeOfFailure();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    @Test
    void testWithReactiveConnectionResultInteger() throws Exception {
        VertxTestContext testContext = new VertxTestContext();

        pgClient.withReactiveConnectionResult(connection -> {
            return connection.preparedQuery("SELECT 1")
                .execute()
                .map(rowSet -> {
                    Row row = rowSet.iterator().next();
                    return row.getInteger(0);
                });
        })
        .onSuccess(value -> testContext.verify(() -> {
            assertNotNull(value);
            assertEquals(1, value);
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            Throwable cause = testContext.causeOfFailure();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    @Test
    void testWithReactiveConnectionResultString() throws Exception {
        VertxTestContext testContext = new VertxTestContext();

        pgClient.withReactiveConnectionResult(connection -> {
            return connection.preparedQuery("SELECT 'test' as message")
                .execute()
                .map(rowSet -> {
                    Row row = rowSet.iterator().next();
                    return row.getString("message");
                });
        })
        .onSuccess(value -> testContext.verify(() -> {
            assertNotNull(value);
            assertEquals("test", value);
            testContext.completeNow();
        }))
        .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            Throwable cause = testContext.causeOfFailure();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    @Test
    void testReactiveMethodsWork() throws Exception {
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
    void testGetReactivePool() throws Exception {
        Pool pool = pgClient.getReactivePool();
        assertNotNull(pool, "Pool should not be null");

        VertxTestContext testContext = new VertxTestContext();

        pool.getConnection()
            .compose(connection -> {
                return connection.preparedQuery("SELECT 42 as answer")
                    .execute()
                    .map(rowSet -> {
                        Row row = rowSet.iterator().next();
                        return row.getInteger("answer");
                    })
                    .onComplete(ar -> connection.close());
            })
            .onSuccess(answer -> testContext.verify(() -> {
                assertEquals(42, answer, "Pool should be functional");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
        if (testContext.failed()) {
            Throwable cause = testContext.causeOfFailure();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }
}