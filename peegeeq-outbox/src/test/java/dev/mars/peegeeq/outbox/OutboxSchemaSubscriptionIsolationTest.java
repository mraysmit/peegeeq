package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
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
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-S14 coverage for schema-isolated subscription creation.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox subscription schema isolation - TC-S14")
class OutboxSchemaSubscriptionIsolationTest {

    private static final String TENANT_A = "tc_s14_tenant_a";
    private static final String TENANT_B = "tc_s14_tenant_b";
    private static final String CONTROL_SCHEMA = "public";

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory factory;
    private ConsumerGroup<String> consumerGroup;

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> closeFactory = factory != null ? factory.close() : Future.succeededFuture();
        closeFactory
                .transform(factoryResult -> {
                    Future<Void> closeManager = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return closeManager.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("TC-S14: start with options reads and writes only the configured schema")
    void startWithOptionsUsesConfiguredSchemaOnly(VertxTestContext testContext) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic = "tc-s14-topic-" + suffix;
        String groupName = "tc-s14-group-" + suffix;

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, TENANT_A, SchemaComponent.QUEUE_ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, TENANT_B, SchemaComponent.QUEUE_ALL);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, CONTROL_SCHEMA, SchemaComponent.QUEUE_ALL);

        Properties properties = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(TENANT_A)
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration configuration = new PeeGeeQConfiguration("default", properties);
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    factory = new OutboxFactory(new PgDatabaseService(manager), configuration);
                    return insertPendingMessage(manager.getPool(), TENANT_A, topic, "tenant-a-message");
                })
                .compose(v -> insertPendingMessage(manager.getPool(), TENANT_B, topic, "tenant-b-message-1"))
                .compose(v -> insertPendingMessage(manager.getPool(), TENANT_B, topic, "tenant-b-message-2"))
                .compose(v -> insertPendingMessage(manager.getPool(), TENANT_B, topic, "tenant-b-message-3"))
                .compose(v -> {
                    consumerGroup = factory.createConsumerGroup(groupName, topic, String.class);
                    consumerGroup.addConsumer("tc-s14-member", message -> Future.succeededFuture());
                    return consumerGroup.start(SubscriptionOptions.defaults());
                })
                .compose(v -> findSubscription(manager.getPool(), TENANT_A, topic, groupName))
                .compose(subscription -> {
                    testContext.verify(() -> {
                        assertEquals(1, subscription.count());
                        assertEquals(2L, subscription.startFromMessageId(),
                                "FROM_NOW must derive its offset from tenant A's one seeded outbox row");
                    });
                    return countSubscriptions(manager.getPool(), TENANT_B, topic, groupName);
                })
                .compose(tenantBCount -> {
                    testContext.verify(() -> assertEquals(0, tenantBCount,
                            "Starting tenant A must not create a tenant B subscription"));
                    return countSubscriptions(manager.getPool(), CONTROL_SCHEMA, topic, groupName);
                })
                .onSuccess(controlCount -> testContext.verify(() -> {
                    assertEquals(0, controlCount,
                            "Starting tenant A must not create a control-schema subscription");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S14 should complete within 30 seconds");
    }

    private Future<Void> insertPendingMessage(Pool pool, String schema, String topic, String payload) {
        String table = OutboxFactory.quoteIdentifier(schema) + ".outbox";
        return pool.preparedQuery("INSERT INTO " + table
                        + " (topic, payload, status, created_at) VALUES ($1, $2, 'PENDING', NOW())")
                .execute(Tuple.of(topic, new JsonObject().put("data", payload)))
                .mapEmpty();
    }

    private Future<SubscriptionRecord> findSubscription(
            Pool pool, String schema, String topic, String groupName) {
        String table = OutboxFactory.quoteIdentifier(schema) + ".outbox_topic_subscriptions";
        return pool.preparedQuery("SELECT COUNT(*) OVER () AS cnt, start_from_message_id FROM " + table
                        + " WHERE topic = $1 AND group_name = $2")
                .execute(Tuple.of(topic, groupName))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return new SubscriptionRecord(row.getInteger("cnt"), row.getLong("start_from_message_id"));
                });
    }

    private Future<Integer> countSubscriptions(Pool pool, String schema, String topic, String groupName) {
        String table = OutboxFactory.quoteIdentifier(schema) + ".outbox_topic_subscriptions";
        return pool.preparedQuery("SELECT COUNT(*) AS cnt FROM " + table
                        + " WHERE topic = $1 AND group_name = $2")
                .execute(Tuple.of(topic, groupName))
                .map(rows -> rows.iterator().next().getInteger("cnt"));
    }

    private record SubscriptionRecord(int count, long startFromMessageId) {
    }
}
