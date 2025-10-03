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


import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.SharedPostgresExtension;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
@ExtendWith(SharedPostgresExtension.class)
public class PgClientTest {

    private PgClientFactory clientFactory;
    private PgClient pgClient;

    @BeforeEach
    void setUp() {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
        clientFactory = new PgClientFactory(Vertx.vertx());

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
                .maximumPoolSize(5)
                .build();

        // Create client and ensure DataSource is set up for JDBC operations
        pgClient = clientFactory.createClient("test-client", connectionConfig, poolConfig);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientFactory != null) {
            clientFactory.close();
        }
    }

    @Test
    void testGetReactiveConnection() throws Exception {
        // Test reactive connection - following established Vert.x 5.x patterns
        Future<Integer> result = pgClient.getReactiveConnection()
            .compose(connection -> {
                // Execute a simple query using reactive patterns
                Future<Integer> queryResult = connection.preparedQuery("SELECT 1")
                    .execute()
                    .map(rowSet -> {
                        Row row = rowSet.iterator().next();
                        return row.getInteger(0);
                    });

                // Close connection and return result
                return queryResult.onComplete(ar -> connection.close());
            });

        // Convert to CompletableFuture and wait for result - following established patterns
        CompletableFuture<Integer> completableFuture = result.toCompletionStage().toCompletableFuture();
        Integer value = completableFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(value);
        assertEquals(1, value);
    }

    @Test
    void testWithReactiveConnectionResultInteger() throws Exception {
        // Test withReactiveConnectionResult with integer - following established patterns
        Future<Integer> result = pgClient.withReactiveConnectionResult(connection -> {
            return connection.preparedQuery("SELECT 1")
                .execute()
                .map(rowSet -> {
                    Row row = rowSet.iterator().next();
                    return row.getInteger(0);
                });
        });

        // Convert to CompletableFuture and wait for result - following established patterns
        CompletableFuture<Integer> completableFuture = result.toCompletionStage().toCompletableFuture();
        Integer value = completableFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(value);
        assertEquals(1, value);
    }

    @Test
    void testWithReactiveConnectionResultString() throws Exception {
        // Test withReactiveConnectionResult with string - following established patterns
        Future<String> result = pgClient.withReactiveConnectionResult(connection -> {
            return connection.preparedQuery("SELECT 'test' as message")
                .execute()
                .map(rowSet -> {
                    Row row = rowSet.iterator().next();
                    return row.getString("message");
                });
        });

        // Convert to CompletableFuture and wait for result - following established patterns
        CompletableFuture<String> completableFuture = result.toCompletionStage().toCompletableFuture();
        String value = completableFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(value);
        assertEquals("test", value);
    }

    @Test
    void testDeprecatedMethodsThrowExceptions() {
        // Test that deprecated JDBC methods throw appropriate exceptions
        assertThrows(UnsupportedOperationException.class, () -> {
            pgClient.getConnection();
        });

        assertThrows(UnsupportedOperationException.class, () -> {
            pgClient.createListenerConnection();
        });

        assertThrows(UnsupportedOperationException.class, () -> {
            pgClient.withConnection(conn -> {});
        });

        assertThrows(UnsupportedOperationException.class, () -> {
            pgClient.withConnectionResult(conn -> null);
        });
    }
}