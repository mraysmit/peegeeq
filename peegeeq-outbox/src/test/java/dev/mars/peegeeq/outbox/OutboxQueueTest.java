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
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

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
    void tearDown() throws Exception {
        // Simplified cleanup - don't fail tests due to resource cleanup issues
        try {
            if (queue != null) {
                queue.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.warn("Queue cleanup failed, continuing", e);
        }
    }

    @Test
    void testSendMessage(VertxTestContext testContext) throws Exception {
        JsonObject message = new JsonObject().put("test", "value");
        Checkpoint sent = testContext.checkpoint();

        queue.send(message)
            .onSuccess(v -> sent.flag())
            .onFailure(throwable -> testContext.failNow("Failed to send message: " + throwable.getMessage()));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Failed to send message");
    }

    @Test
    void testAcknowledgeMessage(VertxTestContext testContext) throws Exception {
        Checkpoint acknowledged = testContext.checkpoint();

        queue.acknowledge("test-message-id")
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    acknowledged.flag();
                } else {
                    testContext.failNow("Failed to acknowledge message: " + ar.cause().getMessage());
                }
            });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Failed to acknowledge message");
    }

    @Test
    void testCreateMessage(VertxTestContext testContext) throws Exception {
        JsonObject payload = new JsonObject().put("test", "value");
        Checkpoint created = testContext.checkpoint();

        vertx.runOnContext(v -> {
            try {
                var message = queue.createMessage(payload);

                assertNotNull(message);
                assertNotNull(message.getId());
                assertEquals(payload, message.getPayload());
                assertNotNull(message.getCreatedAt());
                assertNotNull(message.getHeaders());

                created.flag();
            } catch (Exception e) {
                testContext.failNow("Failed to create message: " + e.getMessage());
            }
        });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Failed to create message");
    }
}
