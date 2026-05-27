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

import dev.mars.peegeeq.api.messaging.QueueBrowser;
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
 * Integration tests for {@link PgNativeQueueFactory#createBrowser(String, Class)}.
 *
 * Verifies the reactive Future-returning signature of createBrowser, including
 * success path, validation failures, post-close behavior, and a browse roundtrip
 * through the native queue_messages table.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-05-16
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class PgNativeQueueFactoryIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PgNativeQueueFactoryIntegrationTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private PgNativeQueueFactory factory;

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.NATIVE_QUEUE,
                SchemaComponent.OUTBOX,
                SchemaComponent.DEAD_LETTER_QUEUE);

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .build();

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("native-factory-test", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    databaseService = new PgDatabaseService(manager);
                    factory = new PgNativeQueueFactory(databaseService);
                    ctx.completeNow();
                })
                .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        logger.info("Tearing down: closing resources and manager");
        (factory != null ? factory.close() : Future.<Void>succeededFuture())
            .compose(v -> manager != null ? manager.closeReactive() : Future.succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(err -> {
                logger.warn("Error during teardown: {}", err.getMessage());
                testContext.completeNow();
            });
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // ============================================================
    // createBrowser Tests
    // ============================================================

    @Test
    void testCreateBrowser_Success(VertxTestContext testContext) {
        factory.<String>createBrowser("native-browse-topic", String.class)
                .onSuccess(browser -> testContext.verify(() -> {
                    assertNotNull(browser);
                    assertInstanceOf(PgNativeQueueBrowser.class, browser);
                    try { browser.close(); } catch (Exception ignored) { }
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testCreateBrowser_InvalidTopic_FailsFuture(VertxTestContext testContext) {
        // Native factory uses TopicNameValidator which rejects null/blank topics
        factory.<String>createBrowser("", String.class)
                .onSuccess(b -> testContext.failNow("Expected failure but got browser: " + b))
                .onFailure(err -> testContext.verify(() -> {
                    assertNotNull(err);
                    testContext.completeNow();
                }));
    }

    @Test
    void testCreateBrowser_AfterClose_FailsFuture(VertxTestContext testContext) {
        factory.close()
            .compose(v -> factory.<String>createBrowser("native-topic", String.class))
                .onSuccess(b -> testContext.failNow("Expected failure but got browser: " + b))
                .onFailure(err -> testContext.verify(() -> {
                    assertInstanceOf(IllegalStateException.class, err);
                    testContext.completeNow();
                }));
    }

    @Test
    void testCreateBrowser_BrowseReturnsList(VertxTestContext testContext) {
        factory.<String>createBrowser("native-empty-topic", String.class)
                .compose(browser -> browser.browse(10, 0)
                        .eventually(() -> {
                            try { browser.close(); } catch (Exception ignored) { }
                            return Future.succeededFuture();
                        }))
                .onSuccess(messages -> testContext.verify(() -> {
                    assertNotNull(messages);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testCreateBrowser_MultipleBrowsers(VertxTestContext testContext) {
        Future.all(
                factory.<String>createBrowser("native-topic-a", String.class),
                factory.<String>createBrowser("native-topic-b", String.class)
        ).onSuccess(cf -> testContext.verify(() -> {
            QueueBrowser<?> b1 = cf.resultAt(0);
            QueueBrowser<?> b2 = cf.resultAt(1);
            assertNotNull(b1);
            assertNotNull(b2);
            assertNotSame(b1, b2);
            try { b1.close(); } catch (Exception ignored) { }
            try { b2.close(); } catch (Exception ignored) { }
            testContext.completeNow();
        })).onFailure(testContext::failNow);
    }
}
