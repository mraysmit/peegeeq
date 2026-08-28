package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.PeeGeeQDefaults;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.consumer.WatermarkCalculator;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-S15 coverage for the complete OFFSET_WATERMARK path in a non-default schema.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@DisplayName("Outbox OFFSET_WATERMARK schema isolation - TC-S15")
class OutboxOffsetWatermarkSchemaIsolationTest {

    private static final String TENANT_SCHEMA = "tc_s15_tenant_wm";
    private static final String CONTROL_SCHEMA = "public";
    private static final String PARTITION_KEY = "tc-s15-partition";

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private PgDatabaseService databaseService;
    private QueueFactory factory;
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
    @DisplayName("TC-S15: offset commit, watermark, and completion stay in the configured schema")
    void offsetWatermarkPathUsesConfiguredSchemaOnly(Vertx vertx, VertxTestContext testContext)
            throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String topic = "tc-s15-topic-" + suffix;
        String groupName = "tc-s15-group-" + suffix;
        AtomicLong lastTenantMessageId = new AtomicLong();
        Set<String> deliveredMessageIds = ConcurrentHashMap.newKeySet();
        Promise<Void> allDelivered = Promise.promise();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, TENANT_SCHEMA,
                SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, CONTROL_SCHEMA,
                SchemaComponent.QUEUE_ALL, SchemaComponent.CONSUMER_GROUP_FANOUT);

        Properties properties = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(TENANT_SCHEMA)
                .build();
        PeeGeeQConfiguration configuration = new PeeGeeQConfiguration("default", properties);
        manager = new PeeGeeQManager(configuration, new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    databaseService = new PgDatabaseService(manager);
                    QueueFactoryProvider provider = new PgQueueFactoryProvider();
                    OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                    factory = provider.createFactory("outbox", (DatabaseService) databaseService);
                    return configureOffsetWatermarkTopic(databaseService.getPool(), topic);
                })
                .compose(v -> insertControlMessage(databaseService.getPool(), topic))
                .compose(v -> {
                    MessageProducer<String> producer = factory.createProducer(topic, String.class);
                    return producer.send("tenant-message-1", null, null, PARTITION_KEY)
                            .compose(ignored -> producer.send("tenant-message-2", null, null, PARTITION_KEY));
                })
                .compose(v -> findMaxMessageId(databaseService.getPool(), TENANT_SCHEMA, topic))
                .compose(maxMessageId -> {
                    lastTenantMessageId.set(maxMessageId);
                    consumerGroup = factory.createConsumerGroup(groupName, topic, String.class);
                    consumerGroup.setMessageHandler(message -> {
                        if (deliveredMessageIds.add(message.getId()) && deliveredMessageIds.size() == 2) {
                            allDelivered.tryComplete();
                        }
                        return Future.succeededFuture();
                    });
                    return consumerGroup.start(SubscriptionOptions.fromBeginning());
                })
                .compose(v -> allDelivered.future())
                .compose(v -> awaitDatabaseCondition(vertx,
                        () -> findCommittedOffset(databaseService.getPool(), TENANT_SCHEMA, topic, groupName)
                                .map(offset -> offset >= lastTenantMessageId.get()),
                        15_000,
                        "Tenant partition offset should commit through the final message"))
                .compose(v -> {
                    WatermarkCalculator calculator = new WatermarkCalculator(
                            databaseService.getClientFactory().getConnectionManager(),
                            PeeGeeQDefaults.DEFAULT_POOL_ID);
                    return calculator.calculateAndSweep(topic);
                })
                .compose(v -> findTenantState(databaseService.getPool(), topic, groupName))
                .compose(tenantState -> {
                    testContext.verify(() -> {
                        assertEquals(2, tenantState.completedCount());
                        assertEquals(0, tenantState.pendingCount());
                        assertEquals(lastTenantMessageId.get(), tenantState.committedOffset());
                        assertEquals(lastTenantMessageId.get(), tenantState.watermarkId());
                    });
                    return findControlState(databaseService.getPool(), topic, groupName);
                })
                .compose(controlState -> {
                    testContext.verify(() -> {
                        assertEquals(1, controlState.pendingCount(),
                                "The control-schema message must remain pending");
                        assertEquals(0, controlState.completedCount());
                        assertEquals(0, controlState.subscriptionCount());
                        assertEquals(0, controlState.offsetCount());
                        assertEquals(0, controlState.watermarkCount());
                    });
                    return consumerGroup.stopGracefully();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "TC-S15 should complete within 30 seconds");
    }

    private Future<Void> configureOffsetWatermarkTopic(Pool pool, String topic) {
        return pool.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) "
                                + "VALUES ($1, 'PUB_SUB', 'OFFSET_WATERMARK')")
                .execute(Tuple.of(topic))
                .mapEmpty();
    }

    private Future<Void> insertControlMessage(Pool pool, String topic) {
        String table = qualifiedTable(CONTROL_SCHEMA, "outbox");
        return pool.preparedQuery("INSERT INTO " + table
                        + " (topic, payload, status, message_group, created_at) "
                        + "VALUES ($1, $2, 'PENDING', $3, NOW())")
                .execute(Tuple.of(topic, new JsonObject().put("value", "control-message"), PARTITION_KEY))
                .mapEmpty();
    }

    private Future<Long> findMaxMessageId(Pool pool, String schema, String topic) {
        String table = qualifiedTable(schema, "outbox");
        return pool.preparedQuery("SELECT MAX(id) AS max_id FROM " + table + " WHERE topic = $1")
                .execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getLong("max_id"));
    }

    private Future<Long> findCommittedOffset(Pool pool, String schema, String topic, String groupName) {
        String table = qualifiedTable(schema, "outbox_partition_offsets");
        return pool.preparedQuery("SELECT COALESCE(MAX(committed_offset), 0) AS committed_offset FROM "
                        + table + " WHERE topic = $1 AND group_name = $2")
                .execute(Tuple.of(topic, groupName))
                .map(rows -> rows.iterator().next().getLong("committed_offset"));
    }

    private Future<TenantState> findTenantState(Pool pool, String topic, String groupName) {
        String outbox = qualifiedTable(TENANT_SCHEMA, "outbox");
        String offsets = qualifiedTable(TENANT_SCHEMA, "outbox_partition_offsets");
        String watermarks = qualifiedTable(TENANT_SCHEMA, "outbox_topic_watermarks");
        String sql = "SELECT "
                + "(SELECT COUNT(*)::int FROM " + outbox
                + " WHERE topic = $1 AND status = 'COMPLETED') AS completed_count, "
                + "(SELECT COUNT(*)::int FROM " + outbox
                + " WHERE topic = $1 AND status = 'PENDING') AS pending_count, "
                + "COALESCE((SELECT MAX(committed_offset) FROM " + offsets
                + " WHERE topic = $1 AND group_name = $2), 0) AS committed_offset, "
                + "COALESCE((SELECT watermark_id FROM " + watermarks
                + " WHERE topic = $1), 0) AS watermark_id";
        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return new TenantState(
                            row.getInteger("completed_count"),
                            row.getInteger("pending_count"),
                            row.getLong("committed_offset"),
                            row.getLong("watermark_id"));
                });
    }

    private Future<ControlState> findControlState(Pool pool, String topic, String groupName) {
        String outbox = qualifiedTable(CONTROL_SCHEMA, "outbox");
        String subscriptions = qualifiedTable(CONTROL_SCHEMA, "outbox_topic_subscriptions");
        String offsets = qualifiedTable(CONTROL_SCHEMA, "outbox_partition_offsets");
        String watermarks = qualifiedTable(CONTROL_SCHEMA, "outbox_topic_watermarks");
        String sql = "SELECT "
                + "(SELECT COUNT(*)::int FROM " + outbox
                + " WHERE topic = $1 AND status = 'PENDING') AS pending_count, "
                + "(SELECT COUNT(*)::int FROM " + outbox
                + " WHERE topic = $1 AND status = 'COMPLETED') AS completed_count, "
                + "(SELECT COUNT(*)::int FROM " + subscriptions
                + " WHERE topic = $1 AND group_name = $2) AS subscription_count, "
                + "(SELECT COUNT(*)::int FROM " + offsets
                + " WHERE topic = $1 AND group_name = $2) AS offset_count, "
                + "(SELECT COUNT(*)::int FROM " + watermarks
                + " WHERE topic = $1) AS watermark_count";
        return pool.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    return new ControlState(
                            row.getInteger("pending_count"),
                            row.getInteger("completed_count"),
                            row.getInteger("subscription_count"),
                            row.getInteger("offset_count"),
                            row.getInteger("watermark_count"));
                });
    }

    private Future<Void> awaitDatabaseCondition(
            Vertx vertx, AsyncCondition condition, long timeoutMillis, String failureMessage) {
        return pollCondition(vertx, condition, System.currentTimeMillis() + timeoutMillis, failureMessage);
    }

    private Future<Void> pollCondition(
            Vertx vertx, AsyncCondition condition, long deadline, String failureMessage) {
        return condition.evaluate()
                .transform(result -> {
                    if (result.succeeded() && Boolean.TRUE.equals(result.result())) {
                        return Future.succeededFuture();
                    }
                    if (System.currentTimeMillis() > deadline) {
                        String reason = result.failed()
                                ? ": " + result.cause().getMessage()
                                : " (timed out)";
                        return Future.failedFuture(new AssertionError(failureMessage + reason));
                    }
                    return vertx.timer(100)
                            .compose(ignored -> pollCondition(vertx, condition, deadline, failureMessage));
                });
    }

    private String qualifiedTable(String schema, String table) {
        return OutboxFactory.quoteIdentifier(schema) + "." + table;
    }

    @FunctionalInterface
    private interface AsyncCondition {
        Future<Boolean> evaluate();
    }

    private record TenantState(
            int completedCount, int pendingCount, long committedOffset, long watermarkId) {
    }

    private record ControlState(
            int pendingCount,
            int completedCount,
            int subscriptionCount,
            int offsetCount,
            int watermarkCount) {
    }
}
