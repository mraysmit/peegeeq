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

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiring verification for {@code PartitionedConsumerEngine} inside the outbox subsystem.
 *
 * <p>Mirrors {@code peegeeq-native}'s
 * {@code PartitionedNativeConsumerIntegrationTest#testStart_offsetWatermarkTopic_autoJoins}:
 * starting a consumer group on an OFFSET_WATERMARK topic must cause the engine to join
 * the partitioned group, which is visible as one or more rows in
 * {@code outbox_partition_assignments}. If the engine is not wired in, the row count
 * stays at zero that is exactly what this test catches.</p>
 *
 * <p>Vert.x 5 reactive only no {@code CompletableFuture}, no blocking
 * {@code .get()}/{@code .join()}, no {@code Thread.sleep}.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox OFFSET_WATERMARK engine wiring verification")
class OutboxOffsetWatermarkWiringTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxOffsetWatermarkWiringTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private QueueFactory factory;

    @BeforeEach
    void setUp() {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres,
                SchemaComponent.QUEUE_ALL,
                SchemaComponent.CONSUMER_GROUP_FANOUT);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        factory = provider.createFactory("outbox", (DatabaseService) databaseService);
    }

    @AfterEach
    void tearDown(Vertx vertx) {
        if (factory != null) {
            try {
                factory.close();
            } catch (Exception e) {
                logger.warn("Error closing factory: {}", e.getMessage());
            }
        }
        if (manager != null) {
            try {
                manager.closeReactive().await();
            } catch (Exception e) {
                logger.warn("Error closing manager: {}", e.getMessage());
            }
        }
        Promise<Void> delay = Promise.promise();
        vertx.setTimer(500, id -> delay.complete());
        delay.future().await();
    }

    /**
     * Starting a consumer group on an OFFSET_WATERMARK topic must cause the engine
     * to join the partitioned group, leaving rows in {@code outbox_partition_assignments}.
     */
    @Test
    @DisplayName("ConsumerGroup.start on OFFSET_WATERMARK topic creates partition assignments")
    void start_offsetWatermarkTopic_createsPartitionAssignments(Vertx vertx,
                                                                VertxTestContext testContext) throws Exception {
        String topic = "owm-wiring-" + UUID.randomUUID().toString().substring(0, 8);
        String groupName = "owm-wiring-group";

        // Configure OFFSET_WATERMARK topic + subscription, seed three partitions.
        configureOffsetWatermarkTopic(topic, groupName)
                .compose(v -> insertOutboxMessage(topic, "part-A", "payload-1"))
                .compose(v -> insertOutboxMessage(topic, "part-B", "payload-2"))
                .compose(v -> insertOutboxMessage(topic, "part-C", "payload-3"))
                .compose(v -> {
                    ConsumerGroup<String> group = factory.createConsumerGroup(groupName, topic, String.class);
                    group.setMessageHandler(msg -> Future.succeededFuture());

                    return group.start(SubscriptionOptions.fromBeginning())
                            .compose(started -> {
                                // Allow async join to propagate.
                                Promise<Void> delay = Promise.promise();
                                vertx.setTimer(2000, id -> delay.complete());
                                return delay.future();
                            })
                            .compose(delayed -> countPartitionAssignments(topic, groupName))
                            .map(count -> {
                                logger.info("partition assignments after start: {}", count);
                                assertTrue(count >= 1,
                                        "OutboxConsumerGroup must wire PartitionedConsumerEngine on " +
                                        "OFFSET_WATERMARK topics expected >= 1 row in " +
                                        "outbox_partition_assignments, got " + count);
                                return (Void) null;
                            })
                            .eventually(() -> group.stopGracefully());
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Test timed out");
    }

    private Future<Void> configureOffsetWatermarkTopic(String topic, String groupName) {
        return databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) " +
                        "VALUES ($1, 'PUB_SUB', 'OFFSET_WATERMARK') " +
                        "ON CONFLICT (topic) DO UPDATE SET completion_tracking_mode = 'OFFSET_WATERMARK'")
                        .execute(Tuple.of(topic))
                        .compose(r -> conn.preparedQuery(
                                "INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status) " +
                                "VALUES ($1, $2, 'ACTIVE') " +
                                "ON CONFLICT (topic, group_name) DO NOTHING")
                                .execute(Tuple.of(topic, groupName)))
                        .mapEmpty());
    }

    private Future<Void> insertOutboxMessage(String topic, String messageGroup, String payload) {
        io.vertx.core.json.JsonObject payloadJson = new io.vertx.core.json.JsonObject().put("data", payload);
        return databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "INSERT INTO outbox (topic, payload, status, message_group, created_at) " +
                        "VALUES ($1, $2, 'PENDING', $3, NOW())")
                        .execute(Tuple.of(topic, payloadJson, messageGroup))
                        .mapEmpty());
    }

    private Future<Integer> countPartitionAssignments(String topic, String groupName) {
        return databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "SELECT COUNT(*) AS cnt FROM outbox_partition_assignments " +
                        "WHERE topic = $1 AND group_name = $2")
                        .execute(Tuple.of(topic, groupName))
                        .map(rows -> rows.iterator().next().getInteger("cnt")));
    }
}
