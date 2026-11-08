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
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ten concurrent requests through one {@link PgClient} each borrow a pooled connection, run a
 * query and hold the connection for 100 ms; all of them complete.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class PoolingUnderLoadIntegrationTest {

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PgClientFactory clientFactory;
    private PgClient pgClient;

    @BeforeEach
    void setUp(Vertx vertx) {
        // The factory uses the Vert.x of VertxExtension, which owns and closes it.
        clientFactory = new PgClientFactory(vertx);

        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        pgClient = clientFactory.createClient("test-client", connectionConfig);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (clientFactory == null) {
            testContext.completeNow();
            return;
        }
        clientFactory.close().onComplete(testContext.succeedingThenComplete());
    }

    @Test
    void testConnectionPoolingUnderLoad(Vertx vertx, VertxTestContext testContext) {
        int numRequests = 10;
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < numRequests; i++) {
            futures.add(pgClient.withReactiveConnectionResult(connection ->
                    connection.preparedQuery("SELECT 1")
                            .execute()
                            .map(rowSet -> rowSet.iterator().next().getInteger(0))
                            .compose(result -> vertx.timer(100).map(result))));
        }

        Future.all(futures)
                .onComplete(testContext.succeeding(all -> testContext.verify(() -> {
                    for (int i = 0; i < numRequests; i++) {
                        assertEquals(1, all.<Integer>resultAt(i), "request " + i);
                    }
                    testContext.completeNow();
                })));
    }
}
