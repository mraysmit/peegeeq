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

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
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

import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent.QUEUE_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxDuplicateMetricsIntegrationTest {

    @Container
    private static final PostgreSQLContainer postgres =
            PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory factory;
    private MessageProducer<String> producer;
    private SimpleMeterRegistry meterRegistry;
    private String topic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(
                postgres,
                PostgreSQLTestConstants.TEST_SCHEMA,
                QUEUE_ALL);
        topic = "outbox-duplicate-metrics-" + UUID.randomUUID().toString().substring(0, 8);

        Properties properties = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.pool.max-size", "1")
                .property("peegeeq.queue.dead-consumer-detection.enabled", "false")
                .property("peegeeq.queue.consumer-group-retry.enabled", "false")
                .build();
        PeeGeeQConfiguration configuration =
                new PeeGeeQConfiguration("outbox-duplicate-metrics-test", properties);
        meterRegistry = new SimpleMeterRegistry();
        manager = new PeeGeeQManager(configuration, meterRegistry);

        manager.start()
                .onSuccess(ignored -> testContext.verify(() -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), configuration);
                    producer = factory.createProducer(topic, String.class);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> factoryClose = factory != null
                ? factory.close()
                : Future.succeededFuture();
        factoryClose
                .transform(factoryResult -> {
                    Future<Void> managerClose = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return managerClose.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(ignored -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void duplicateIdempotencyKeyIncrementsDuplicateMetricWithoutIncrementingSentMetric(
            VertxTestContext testContext) {
        Map<String, String> headers = Map.of(
                "idempotencyKey", "duplicate-metric-" + UUID.randomUUID());

        producer.send("first-payload", headers)
                .compose(ignored -> producer.send("duplicate-payload", headers))
                .onSuccess(ignored -> testContext.verify(() -> {
                    assertEquals(1.0, meterRegistry.get("peegeeq.messages.sent.by.topic")
                            .tag("topic", topic)
                            .counter()
                            .count(),
                            "Only the inserted message is sent");
                    assertEquals(1.0, meterRegistry.get("peegeeq.messages.duplicates")
                            .counter()
                            .count(),
                            "The rejected duplicate is observable globally");
                    assertEquals(1.0, meterRegistry.get("peegeeq.messages.duplicates.by.topic")
                            .tag("topic", topic)
                            .counter()
                            .count(),
                            "The rejected duplicate is attributable to its topic");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
}
