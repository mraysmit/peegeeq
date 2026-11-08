package dev.mars.peegeeq.outbox;

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


import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import dev.mars.peegeeq.test.categories.TestCategories;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;



import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Implementation of OutboxQueueTest functionality.
 * 
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxQueueTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxQueueTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private Vertx vertx;
    private OutboxQueue<JsonObject> queue;

    @BeforeEach
    void setUp(Vertx vertx) {
        this.vertx = vertx;

        // Create connection options from TestContainer
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

        // Create pool options
        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(5);

        // Create queue
        queue = new OutboxQueue<>(vertx, connectOptions, poolOptions, 
                new ObjectMapper(), "outbox_messages", JsonObject.class);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        if (queue != null) {
            queue.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(err -> {
                    logger.error("Queue cleanup failed", err);
                    testContext.failNow(err);
                });
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testSendMessage(VertxTestContext testContext) {
        JsonObject message = new JsonObject().put("test", "value");
        Checkpoint sent = testContext.checkpoint();

        queue.send(message)
            .onSuccess(v -> sent.flag())
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testAcknowledgeMessage(VertxTestContext testContext) {
        Checkpoint acknowledged = testContext.checkpoint();

        queue.acknowledge("test-message-id")
            .onSuccess(v -> acknowledged.flag())
            .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testCreateMessage(VertxTestContext testContext) {
        JsonObject payload = new JsonObject().put("test", "value");
        Checkpoint created = testContext.checkpoint();

        // verify() routes a failed assertion to the test context; an AssertionError thrown on the
        // event loop would otherwise reach the Vert.x exception handler and the test would time out.
        vertx.runOnContext(v -> testContext.verify(() -> {
            var message = queue.createMessage(payload);

            assertNotNull(message);
            assertNotNull(message.getId());
            assertEquals(payload, message.getPayload());
            assertNotNull(message.getCreatedAt());
            assertNotNull(message.getHeaders());

            created.flag();
        }));
    }
}
