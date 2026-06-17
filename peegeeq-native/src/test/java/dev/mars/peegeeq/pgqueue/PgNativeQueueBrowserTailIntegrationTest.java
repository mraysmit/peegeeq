package dev.mars.peegeeq.pgqueue;

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

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link dev.mars.peegeeq.api.messaging.QueueBrowser#tail} on the native
 * queue — the NON-DESTRUCTIVE live observe capability (Phase 12, §7.12).
 *
 * <p>The core guarantee: observing new messages via {@code tail} must never consume them — a
 * message pushed to the tail must still be browsable afterwards, exactly like a {@code SELECT}
 * that does not delete its rows.
 *
 * <p>Written test-first: it fails against the current code (the {@code QueueBrowser.tail}
 * default returns {@code UnsupportedOperationException}) and passes once
 * {@code PgNativeQueueBrowser} implements {@code tail} via LISTEN/NOTIFY → browse → push.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-06-17
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PgNativeQueueBrowserTailIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueBrowserTailIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private PgNativeQueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA,
                SchemaComponent.NATIVE_QUEUE, SchemaComponent.OUTBOX, SchemaComponent.DEAD_LETTER_QUEUE);

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("native-tail-test", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    databaseService = new PgDatabaseService(manager);
                    factory = new PgNativeQueueFactory(databaseService, config);
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
                .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(err -> {
                    logger.warn("Error during teardown: {}", err.getMessage());
                    testContext.completeNow();
                });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void tail_observesNewMessage_andLeavesItBrowsable(VertxTestContext ctx) {
        String topic = "tail-nondestructive-" + System.currentTimeMillis();
        Promise<Message<String>> observed = Promise.promise();

        factory.<String>createBrowser(topic, String.class)
                .compose(browser ->
                        // Establish the non-destructive live tail.
                        browser.tail(message -> {
                                    observed.tryComplete(message);
                                    return Future.succeededFuture();
                                })
                                // Once subscribed, publish a message — the tail must observe it.
                                .compose(v -> {
                                    MessageProducer<String> producer = factory.createProducer(topic, String.class);
                                    return producer.send("hello-tail");
                                })
                                .compose(v -> observed.future())
                                .compose(msg -> {
                                    ctx.verify(() -> assertEquals("hello-tail", msg.getPayload(),
                                            "tail must observe the published message"));
                                    // NON-DESTRUCTIVE proof: the observed message must still be in the queue.
                                    return browser.browse(50, 0);
                                })
                                .eventually(() -> {
                                    try { browser.close(); } catch (Exception ignored) { }
                                    return Future.succeededFuture();
                                }))
                .onSuccess(messages -> ctx.verify(() -> {
                    assertTrue(messages.stream().anyMatch(m -> "hello-tail".equals(m.getPayload())),
                            "observing via tail must NOT consume — the message must remain browsable");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
    }
}
