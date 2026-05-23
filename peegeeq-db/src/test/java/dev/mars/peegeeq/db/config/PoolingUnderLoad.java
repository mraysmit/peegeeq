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
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import dev.mars.peegeeq.db.PgTestImageConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;

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
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class PoolingUnderLoad {

    private static final Logger logger = LoggerFactory.getLogger(PoolingUnderLoad.class);

    @Container
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer(PgTestImageConstant.POSTGRES_IMAGE);
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

    private PgClientFactory clientFactory;
    private PgClient pgClient;

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
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientFactory != null) {
            clientFactory.close().onFailure(e -> logger.warn("clientFactory.close() failed in tearDown", e));
        }
    }

    @Test
    void testConnectionPoolingUnderLoad(Vertx vertx, VertxTestContext testContext) {
        int numRequests = 10;
        List<Future<?>> futures = new ArrayList<>();
        
        for (int i = 0; i < numRequests; i++) {
            Future<?> requestFuture = pgClient.withReactiveConnectionResult(connection -> 
                connection.preparedQuery("SELECT 1")
                    .execute()
                    .map(rowSet -> {
                        Row row = rowSet.iterator().next();
                        int result = row.getInteger(0);
                        assertEquals(1, result);
                        return result;
                    })
                    .compose(result -> vertx.timer(100).map(result))
            );
            futures.add(requestFuture);
        }
        
        Future.all(futures)
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }


}
