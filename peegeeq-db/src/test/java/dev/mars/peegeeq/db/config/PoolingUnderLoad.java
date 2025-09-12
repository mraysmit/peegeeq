package dev.mars.peegeeq.db.config;

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


import dev.mars.peegeeq.db.client.PgClient;
import dev.mars.peegeeq.db.client.PgClientFactory;
import dev.mars.peegeeq.db.transaction.PgTransactionManager;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Implementation of PoolingUnderLoad functionality.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Testcontainers
public class PoolingUnderLoad {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PgClientFactory clientFactory;
    private PgClient pgClient;
    private PgTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        clientFactory = new PgClientFactory(Vertx.vertx());

        // Create connection config from TestContainer
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        // Create client
        pgClient = clientFactory.createClient("test-client", connectionConfig);

        // Create transaction manager
        transactionManager = new PgTransactionManager(pgClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientFactory != null) {
            clientFactory.close();
        }
    }

    @Test
    void testTransactionErrorHandling() {
        // Test that SQL errors are properly propagated
        assertThrows(SQLException.class, () -> {
            transactionManager.executeInTransaction(transaction -> {
                try (Statement stmt = transaction.getConnection().createStatement()) {
                    // Execute invalid SQL
                    stmt.execute("SELECT * FROM non_existent_table");
                }
            });
        });
    }


    @Test
    void testConnectionPoolingUnderLoad() throws Exception {
        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    // Use reactive patterns instead of deprecated JDBC methods
                    pgClient.withReactiveConnectionResult(connection -> {
                        // Simulate some work with reactive delay
                        return connection.preparedQuery("SELECT 1")
                            .execute()
                            .map(rowSet -> {
                                Row row = rowSet.iterator().next();
                                int result = row.getInteger(0);
                                assertEquals(1, result);
                                return result;
                            });
                    }).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

                    // Add a small delay to simulate work
                    Thread.sleep(100);
                } catch (Exception e) {
                    fail("Exception in thread: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all threads completed in time");
        executor.shutdown();
    }


}
