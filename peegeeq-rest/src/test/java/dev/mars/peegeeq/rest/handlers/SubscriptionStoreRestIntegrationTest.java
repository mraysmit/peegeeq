package dev.mars.peegeeq.rest.handlers;

import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.ConsumerGroupStats;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueBrowser;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.setup.DatabaseSetupService;
import dev.mars.peegeeq.rest.PeeGeeQRestServer;
import dev.mars.peegeeq.rest.config.RestServerConfig;
import dev.mars.peegeeq.runtime.PeeGeeQRuntime;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test of the message table that a subscription created through REST is bound to.
 *
 * <p>The start bound of a subscription is a message id. A native queue has its messages in
 * {@code queue_messages} and an outbox queue has them in {@code outbox}. Each REST call that
 * creates a subscription takes the table from the queue factory of the queue. The topics of
 * this test have different next ids in the two tables, so a bound that was resolved against
 * the wrong table is seen.</p>
 *
 * <p>Classification: INTEGRATION TEST
 * <ul>
 *   <li>Real PostgreSQL database via TestContainers</li>
 *   <li>Real Vert.x HTTP server</li>
 *   <li>No mocks. One queue factory is written by hand: it declares no subscription store.</li>
 * </ul>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SubscriptionStoreRestIntegrationTest {

    private static final int TEST_PORT = 18144;
    private static final String SCHEMA = PostgreSQLTestConstants.TEST_SCHEMA;
    private static final String NATIVE_QUEUE = "store_native_queue";
    private static final String OUTBOX_QUEUE = "store_outbox_queue";
    private static final String UNDECLARED_STORE_QUEUE = "store_undeclared_queue";

    /** The two tables that hold messages. */
    private enum MessageTable {
        OUTBOX("outbox"),
        QUEUE_MESSAGES("queue_messages");

        private final String tableName;

        MessageTable(String tableName) {
            this.tableName = tableName;
        }
    }

    /** The next message id of a topic in each message table. */
    private record NextIds(long outbox, long queueMessages) {
    }

    private record StoredSubscription(String topic, Long startFromMessageId, String status) {
    }

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private DatabaseSetupService setupService;
    private String deploymentId;
    private String setupId;
    private WebClient webClient;
    private Pool pool;

    // ── Setup / Teardown ──────────────────────────────────────────────────────

    @BeforeAll
    void setupServer(Vertx vertx, VertxTestContext testContext) {
        setupId = "store-rest-test-" + System.currentTimeMillis();
        String databaseName = "store_rest_db_" + System.currentTimeMillis();
        setupService = PeeGeeQRuntime.createDatabaseSetupService();

        RestServerConfig testConfig = new RestServerConfig(
            TEST_PORT,
            RestServerConfig.MonitoringConfig.defaults(),
            List.of("*")
        );
        PeeGeeQRestServer restServer = new PeeGeeQRestServer(testConfig, setupService);

        vertx.deployVerticle(restServer)
            .compose(id -> {
                deploymentId = id;
                webClient = WebClient.create(vertx);
                return createSetupWithQueues(databaseName);
            })
            .compose(v -> {
                pool = PgBuilder.pool()
                    .with(new PoolOptions().setMaxSize(2))
                    .connectingTo(new PgConnectOptions()
                        .setHost(postgres.getHost())
                        .setPort(postgres.getFirstMappedPort())
                        .setDatabase(databaseName)
                        .setUser(postgres.getUsername())
                        .setPassword(postgres.getPassword()))
                    .using(vertx)
                    .build();
                return insertDivergentMessages();
            })
            .compose(v -> setupService.getSetupResult(setupId))
            .map(setupResult -> setupResult.getQueueFactories()
                .put(UNDECLARED_STORE_QUEUE, new UndeclaredStoreQueueFactory()))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterAll
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        if (webClient != null) {
            webClient.close();
        }
        Future<Void> poolClose = pool != null ? pool.close() : Future.succeededFuture();
        poolClose
            .compose(v -> deploymentId != null ? vertx.undeploy(deploymentId) : Future.<Void>succeededFuture())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    // ── FROM_NOW is resolved against the message table of the queue ───────────

    @Test
    @DisplayName("The management API resolves FROM_NOW of a native queue against queue_messages")
    void managementApiUsesQueueMessagesForANativeQueue(VertxTestContext testContext) {
        String groupName = uniqueGroup("mgmt_native");
        assertCreationStoresBound(testContext, createThroughManagementApi(NATIVE_QUEUE, groupName), 201,
            NATIVE_QUEUE, groupName, MessageTable.QUEUE_MESSAGES);
    }

    @Test
    @DisplayName("The management API resolves FROM_NOW of an outbox queue against outbox")
    void managementApiUsesOutboxForAnOutboxQueue(VertxTestContext testContext) {
        String groupName = uniqueGroup("mgmt_outbox");
        assertCreationStoresBound(testContext, createThroughManagementApi(OUTBOX_QUEUE, groupName), 201,
            OUTBOX_QUEUE, groupName, MessageTable.OUTBOX);
    }

    @Test
    @DisplayName("The subscription API resolves FROM_NOW of a native queue against queue_messages")
    void subscriptionApiUsesQueueMessagesForANativeQueue(VertxTestContext testContext) {
        String groupName = uniqueGroup("sub_native");
        assertCreationStoresBound(testContext, createThroughSubscriptionApi(NATIVE_QUEUE, groupName), 201,
            NATIVE_QUEUE, groupName, MessageTable.QUEUE_MESSAGES);
    }

    @Test
    @DisplayName("The subscription API resolves FROM_NOW of an outbox queue against outbox")
    void subscriptionApiUsesOutboxForAnOutboxQueue(VertxTestContext testContext) {
        String groupName = uniqueGroup("sub_outbox");
        assertCreationStoresBound(testContext, createThroughSubscriptionApi(OUTBOX_QUEUE, groupName), 201,
            OUTBOX_QUEUE, groupName, MessageTable.OUTBOX);
    }

    @Test
    @DisplayName("The consumer group API resolves FROM_NOW of a native queue against queue_messages")
    void consumerGroupApiUsesQueueMessagesForANativeQueue(VertxTestContext testContext) {
        String groupName = uniqueGroup("group_native");
        assertCreationStoresBound(testContext, createThroughConsumerGroupApi(NATIVE_QUEUE, groupName), 201,
            NATIVE_QUEUE, groupName, MessageTable.QUEUE_MESSAGES);
    }

    @Test
    @DisplayName("The consumer group API resolves FROM_NOW of an outbox queue against outbox")
    void consumerGroupApiUsesOutboxForAnOutboxQueue(VertxTestContext testContext) {
        String groupName = uniqueGroup("group_outbox");
        assertCreationStoresBound(testContext, createThroughConsumerGroupApi(OUTBOX_QUEUE, groupName), 201,
            OUTBOX_QUEUE, groupName, MessageTable.OUTBOX);
    }

    @Test
    @DisplayName("The subscription settings API resolves FROM_NOW of a native queue against queue_messages")
    void subscriptionSettingsApiUsesQueueMessagesForANativeQueue(VertxTestContext testContext) {
        String groupName = uniqueGroup("settings_native");
        assertCreationStoresBound(testContext, createThroughSubscriptionSettingsApi(NATIVE_QUEUE, groupName), 200,
            NATIVE_QUEUE, groupName, MessageTable.QUEUE_MESSAGES);
    }

    @Test
    @DisplayName("The subscription settings API resolves FROM_NOW of an outbox queue against outbox")
    void subscriptionSettingsApiUsesOutboxForAnOutboxQueue(VertxTestContext testContext) {
        String groupName = uniqueGroup("settings_outbox");
        assertCreationStoresBound(testContext, createThroughSubscriptionSettingsApi(OUTBOX_QUEUE, groupName), 200,
            OUTBOX_QUEUE, groupName, MessageTable.OUTBOX);
    }

    // ── The subscription and the consumer group have the same topic ───────────

    @Test
    @DisplayName("The consumer group API stores the subscription under the topic of the consumer group")
    void consumerGroupApiUsesTheSameTopicForTheSubscriptionAndTheGroup(VertxTestContext testContext) {
        String groupName = uniqueGroup("topic_identity");
        String prefixedTopic = setupId + "-" + OUTBOX_QUEUE;

        createThroughConsumerGroupApi(OUTBOX_QUEUE, groupName)
            .compose(response -> assertStatus(response, 201))
            .compose(v -> webClient.get(TEST_PORT, "localhost",
                    "/api/v1/queues/" + setupId + "/" + OUTBOX_QUEUE + "/consumer-groups/" + groupName)
                .send())
            .compose(response -> {
                assertEquals(200, response.statusCode(), "Response: " + response.bodyAsString());
                String groupTopic = response.bodyAsJsonObject().getString("topic");
                assertEquals(OUTBOX_QUEUE, groupTopic, "The topic of the consumer group is the queue name");
                return subscriptionsOfGroup(groupName).map(subscriptions -> {
                    assertEquals(1, subscriptions.size(),
                        "The group must have one subscription, but has " + subscriptions);
                    assertEquals(groupTopic, subscriptions.get(0).topic(),
                        "The subscription and the consumer group must have the same topic");
                    assertTrue(subscriptions.stream().noneMatch(s -> prefixedTopic.equals(s.topic())),
                        "No subscription may be stored under the setup id and the queue name");
                    return (Void) null;
                });
            })
            .onComplete(testContext.succeedingThenComplete());
    }

    @Test
    @DisplayName("The subscription settings API updates, reads, and cancels the subscription stored under the queue name")
    void subscriptionSettingsApiActsOnTheSubscriptionUnderTheQueueName(VertxTestContext testContext) {
        String groupName = uniqueGroup("settings_topic");
        String path = "/api/v1/consumer-groups/" + setupId + "/" + OUTBOX_QUEUE + "/" + groupName + "/subscription";

        createThroughConsumerGroupApi(OUTBOX_QUEUE, groupName)
            .compose(response -> assertStatus(response, 201))
            .compose(v -> webClient.post(TEST_PORT, "localhost", path)
                .sendJsonObject(new JsonObject()
                    .put("heartbeatIntervalSeconds", 20)
                    .put("heartbeatTimeoutSeconds", 80)))
            .compose(response -> assertStatus(response, 200))
            .compose(v -> webClient.get(TEST_PORT, "localhost", path).send())
            .compose(response -> {
                assertEquals(200, response.statusCode(), "Response: " + response.bodyAsString());
                JsonObject body = response.bodyAsJsonObject();
                assertEquals("ACTIVE", body.getString("status"),
                    "The read must find the subscription that the consumer group API created. Response: "
                        + response.bodyAsString());
                assertEquals(20, body.getJsonObject("subscriptionOptions").getInteger("heartbeatIntervalSeconds"),
                    "The read must return the updated heartbeat interval. Response: " + response.bodyAsString());
                return webClient.delete(TEST_PORT, "localhost", path).send();
            })
            .compose(response -> assertStatus(response, 204))
            .compose(v -> subscriptionsOfGroup(groupName))
            .onComplete(testContext.succeeding(subscriptions -> testContext.verify(() -> {
                assertEquals(1, subscriptions.size(),
                    "The group must have one subscription, but has " + subscriptions);
                assertEquals(OUTBOX_QUEUE, subscriptions.get(0).topic(),
                    "The subscription must be stored under the queue name");
                assertEquals("CANCELLED", subscriptions.get(0).status(),
                    "The delete must cancel the subscription stored under the queue name");
                testContext.completeNow();
            })));
    }

    // ── A queue implementation without a subscription store ───────────────────

    @Test
    @DisplayName("The management API refuses a queue whose implementation declares no subscription store")
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.rest.handlers.QueueSubscriptionStore",
        message = "No subscription store for queue '" + UNDECLARED_STORE_QUEUE + "'",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    @ExpectedErrorLog(
        logger = "io.vertx.ext.web.handler.LoggerHandler",
        message = "127.0.0.1 - - [",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void managementApiRefusesAQueueWithoutASubscriptionStore(VertxTestContext testContext) {
        String groupName = uniqueGroup("mgmt_undeclared");
        assertRefusedWithoutSubscription(testContext,
            createThroughManagementApi(UNDECLARED_STORE_QUEUE, groupName), groupName);
    }

    @Test
    @DisplayName("The subscription API refuses a queue whose implementation declares no subscription store")
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.rest.handlers.QueueSubscriptionStore",
        message = "No subscription store for queue '" + UNDECLARED_STORE_QUEUE + "'",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    @ExpectedErrorLog(
        logger = "io.vertx.ext.web.handler.LoggerHandler",
        message = "127.0.0.1 - - [",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void subscriptionApiRefusesAQueueWithoutASubscriptionStore(VertxTestContext testContext) {
        String groupName = uniqueGroup("sub_undeclared");
        assertRefusedWithoutSubscription(testContext,
            createThroughSubscriptionApi(UNDECLARED_STORE_QUEUE, groupName), groupName);
    }

    @Test
    @DisplayName("The consumer group API refuses a queue whose implementation declares no subscription store")
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.rest.handlers.QueueSubscriptionStore",
        message = "No subscription store for queue '" + UNDECLARED_STORE_QUEUE + "'",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    @ExpectedErrorLog(
        logger = "io.vertx.ext.web.handler.LoggerHandler",
        message = "127.0.0.1 - - [",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void consumerGroupApiRefusesAQueueWithoutASubscriptionStore(VertxTestContext testContext) {
        String groupName = uniqueGroup("group_undeclared");
        assertRefusedWithoutSubscription(testContext,
            createThroughConsumerGroupApi(UNDECLARED_STORE_QUEUE, groupName), groupName);
    }

    @Test
    @DisplayName("The subscription settings API refuses a queue whose implementation declares no subscription store")
    @ExpectedErrorLog(
        logger = "dev.mars.peegeeq.rest.handlers.QueueSubscriptionStore",
        message = "No subscription store for queue '" + UNDECLARED_STORE_QUEUE + "'",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    @ExpectedErrorLog(
        logger = "io.vertx.ext.web.handler.LoggerHandler",
        message = "127.0.0.1 - - [",
        messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
        throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    void subscriptionSettingsApiRefusesAQueueWithoutASubscriptionStore(VertxTestContext testContext) {
        String groupName = uniqueGroup("settings_undeclared");
        assertRefusedWithoutSubscription(testContext,
            createThroughSubscriptionSettingsApi(UNDECLARED_STORE_QUEUE, groupName), groupName);
    }

    @Test
    @DisplayName("The subscription API refuses a topic that is not a queue of the setup")
    void subscriptionApiRefusesATopicThatIsNotAQueue(VertxTestContext testContext) {
        String groupName = uniqueGroup("sub_no_queue");

        createThroughSubscriptionApi("store_no_such_queue", groupName)
            .compose(response -> assertStatus(response, 404))
            .compose(v -> subscriptionsOfGroup(groupName))
            .onComplete(testContext.succeeding(subscriptions -> testContext.verify(() -> {
                assertTrue(subscriptions.isEmpty(),
                    "A refused creation must store no subscription, but stored " + subscriptions);
                testContext.completeNow();
            })));
    }

    // ── A queue with subscriptions keeps its implementation type ──────────────

    @Test
    @DisplayName("A queue with a subscription refuses a change of its implementation type")
    void implementationChangeIsRefusedWhileTheQueueHasASubscription(VertxTestContext testContext) {
        String queueName = "store_change_live_queue";
        String groupName = uniqueGroup("change_live");

        createQueue(queueName, "native")
            .compose(response -> assertStatus(response, 201))
            .compose(v -> createThroughManagementApi(queueName, groupName))
            .compose(response -> assertStatus(response, 201))
            .compose(v -> createQueue(queueName, "outbox"))
            .compose(response -> assertStatus(response, 409))
            .compose(v -> addQueueToSetup(queueName, "outbox"))
            .compose(response -> assertStatus(response, 409))
            .compose(v -> webClient.get(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName).send())
            .compose(response -> {
                assertEquals(200, response.statusCode(), "Response: " + response.bodyAsString());
                assertEquals("native", response.bodyAsJsonObject().getString("implementationType"),
                    "The queue must keep its implementation type. Response: " + response.bodyAsString());
                return registeredKind(queueName);
            })
            .onComplete(testContext.succeeding(kind -> testContext.verify(() -> {
                assertEquals("native", kind, "The registered implementation type must be unchanged");
                testContext.completeNow();
            })));
    }

    @Test
    @DisplayName("A deleted queue with a subscription cannot be created again with another implementation type")
    void recreationWithAnotherImplementationIsRefusedWhileTheQueueHasASubscription(VertxTestContext testContext) {
        String queueName = "store_change_deleted_queue";
        String groupName = uniqueGroup("change_deleted");

        createQueue(queueName, "native")
            .compose(response -> assertStatus(response, 201))
            .compose(v -> createThroughManagementApi(queueName, groupName))
            .compose(response -> assertStatus(response, 201))
            .compose(v -> deleteQueue(queueName))
            .compose(response -> assertStatus(response, 200))
            .compose(v -> createQueue(queueName, "outbox"))
            .compose(response -> assertStatus(response, 409))
            .compose(v -> registeredKind(queueName))
            .onComplete(testContext.succeeding(kind -> testContext.verify(() -> {
                assertEquals("native", kind, "The registered implementation type must be unchanged");
                testContext.completeNow();
            })));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String uniqueGroup(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    private static Future<Void> assertStatus(HttpResponse<Buffer> response, int expectedStatus) {
        assertEquals(expectedStatus, response.statusCode(), "Response: " + response.bodyAsString());
        return Future.succeededFuture();
    }

    /**
     * Completes the test when the creation answered with the status, the group has one
     * subscription under the queue name, and its start bound is the next id of the queue
     * topic in the message table.
     */
    private void assertCreationStoresBound(VertxTestContext testContext, Future<HttpResponse<Buffer>> creation,
                                           int createdStatus, String queueName, String groupName,
                                           MessageTable messageTable) {
        creation
            .compose(response -> assertStatus(response, createdStatus))
            .compose(v -> nextIds(queueName))
            .compose(nextIds -> subscriptionsOfGroup(groupName).map(subscriptions -> {
                assertNotEquals(nextIds.outbox(), nextIds.queueMessages(),
                    "The test needs different next ids in the two message tables");
                assertEquals(1, subscriptions.size(),
                    "The group must have one subscription, but has " + subscriptions);
                StoredSubscription subscription = subscriptions.get(0);
                assertEquals(queueName, subscription.topic(),
                    "The subscription must be stored under the queue name");
                long expected = messageTable == MessageTable.QUEUE_MESSAGES
                    ? nextIds.queueMessages()
                    : nextIds.outbox();
                assertEquals(expected, subscription.startFromMessageId(),
                    "FROM_NOW must resolve to the next id of the topic in " + messageTable.tableName
                        + ". " + nextIds);
                return (Void) null;
            }))
            .onComplete(testContext.succeedingThenComplete());
    }

    /** Completes the test when the creation answered 501 and the group has no subscription. */
    private void assertRefusedWithoutSubscription(VertxTestContext testContext,
                                                  Future<HttpResponse<Buffer>> creation, String groupName) {
        creation
            .compose(response -> assertStatus(response, 501))
            .compose(v -> subscriptionsOfGroup(groupName))
            .onComplete(testContext.succeeding(subscriptions -> testContext.verify(() -> {
                assertTrue(subscriptions.isEmpty(),
                    "A refused creation must store no subscription, but stored " + subscriptions);
                testContext.completeNow();
            })));
    }

    private Future<HttpResponse<Buffer>> createThroughManagementApi(String queueName, String groupName) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/management/consumer-groups")
            .sendJsonObject(new JsonObject()
                .put("name", groupName)
                .put("setup", setupId)
                .put("queueName", queueName));
    }

    private Future<HttpResponse<Buffer>> createThroughSubscriptionApi(String queueName, String groupName) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/setups/" + setupId + "/subscriptions/" + queueName)
            .sendJsonObject(new JsonObject()
                .put("groupName", groupName)
                .put("startPosition", "FROM_NOW"));
    }

    private Future<HttpResponse<Buffer>> createThroughConsumerGroupApi(String queueName, String groupName) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/consumer-groups")
            .sendJsonObject(new JsonObject()
                .put("groupName", groupName)
                .put("subscriptionOptions", new JsonObject().put("startPosition", "FROM_NOW")));
    }

    /** Creates the consumer group without a subscription, then creates the subscription. */
    private Future<HttpResponse<Buffer>> createThroughSubscriptionSettingsApi(String queueName, String groupName) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/queues/" + setupId + "/" + queueName + "/consumer-groups")
            .sendJsonObject(new JsonObject().put("groupName", groupName))
            .compose(response -> assertStatus(response, 201))
            .compose(v -> webClient.post(TEST_PORT, "localhost",
                    "/api/v1/consumer-groups/" + setupId + "/" + queueName + "/" + groupName + "/subscription")
                .sendJsonObject(new JsonObject().put("startPosition", "FROM_NOW")));
    }

    private Future<HttpResponse<Buffer>> createQueue(String queueName, String implementationType) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/management/queues")
            .timeout(30_000)
            .sendJsonObject(new JsonObject()
                .put("setup", setupId)
                .put("name", queueName)
                .put("type", implementationType));
    }

    private Future<HttpResponse<Buffer>> addQueueToSetup(String queueName, String implementationType) {
        return webClient.post(TEST_PORT, "localhost", "/api/v1/setups/" + setupId + "/queues")
            .timeout(30_000)
            .sendJsonObject(new JsonObject()
                .put("queueName", queueName)
                .put("implementationType", implementationType));
    }

    private Future<HttpResponse<Buffer>> deleteQueue(String queueName) {
        return webClient.delete(TEST_PORT, "localhost", "/api/v1/management/queues/" + setupId + "/" + queueName)
            .send();
    }

    private Future<Void> createSetupWithQueues(String databaseName) {
        JsonObject setupRequest = new JsonObject()
            .put("setupId", setupId)
            .put("databaseConfig", new JsonObject()
                .put("host",         postgres.getHost())
                .put("port",         postgres.getFirstMappedPort())
                .put("databaseName", databaseName)
                .put("username",     postgres.getUsername())
                .put("password",     postgres.getPassword())
                .put("schema",       SCHEMA)
                .put("templateDatabase", "template0")
                .put("encoding",     "UTF8"))
            .put("queues", new JsonArray()
                .add(new JsonObject()
                    .put("queueName",          NATIVE_QUEUE)
                    .put("implementationType", "native"))
                .add(new JsonObject()
                    .put("queueName",          OUTBOX_QUEUE)
                    .put("implementationType", "outbox")));

        return webClient.post(TEST_PORT, "localhost", "/api/v1/database-setup/create")
            .putHeader("content-type", "application/json")
            .timeout(60_000)
            .sendJsonObject(setupRequest)
            .compose(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return Future.succeededFuture();
                }
                return Future.failedFuture("Failed to create setup: "
                    + response.statusCode() + " " + response.bodyAsString());
            });
    }

    /**
     * Gives each queue topic rows in both message tables. The row counts differ, so the next
     * id of a topic in {@code outbox} is not its next id in {@code queue_messages}.
     */
    private Future<Void> insertDivergentMessages() {
        return pool.withTransaction(connection ->
            insertMessages(connection, MessageTable.OUTBOX, NATIVE_QUEUE, 3)
                .compose(v -> insertMessages(connection, MessageTable.OUTBOX, OUTBOX_QUEUE, 2))
                .compose(v -> insertMessages(connection, MessageTable.QUEUE_MESSAGES, NATIVE_QUEUE, 7))
                .compose(v -> insertMessages(connection, MessageTable.QUEUE_MESSAGES, OUTBOX_QUEUE, 4)));
    }

    private Future<Void> insertMessages(SqlConnection connection, MessageTable messageTable, String topic, int count) {
        String sql = "INSERT INTO " + SCHEMA + "." + messageTable.tableName + " (topic, payload) "
            + "SELECT $1::varchar, '{}'::jsonb FROM generate_series(1, $2::int)";
        return connection.preparedQuery(sql)
            .execute(Tuple.of(topic, count))
            .mapEmpty();
    }

    private Future<NextIds> nextIds(String topic) {
        String sql = "SELECT "
            + "(SELECT COALESCE(MAX(id), 0) + 1 FROM " + SCHEMA + ".outbox WHERE topic = $1) AS next_outbox, "
            + "(SELECT COALESCE(MAX(id), 0) + 1 FROM " + SCHEMA + ".queue_messages WHERE topic = $1) AS next_queue_messages";
        return pool.preparedQuery(sql)
            .execute(Tuple.of(topic))
            .map(rows -> {
                Row row = rows.iterator().next();
                return new NextIds(row.getLong("next_outbox"), row.getLong("next_queue_messages"));
            });
    }

    /** Reads every subscription of the group, on any topic. */
    private Future<List<StoredSubscription>> subscriptionsOfGroup(String groupName) {
        String sql = "SELECT topic, start_from_message_id, subscription_status FROM " + SCHEMA
            + ".outbox_topic_subscriptions WHERE group_name = $1";
        return pool.preparedQuery(sql)
            .execute(Tuple.of(groupName))
            .map(rows -> {
                List<StoredSubscription> subscriptions = new ArrayList<>();
                for (Row row : rows) {
                    subscriptions.add(new StoredSubscription(
                        row.getString("topic"),
                        row.getLong("start_from_message_id"),
                        row.getString("subscription_status")));
                }
                return subscriptions;
            });
    }

    /** Reads the implementation type that the object registry records for the queue. */
    private Future<String> registeredKind(String queueName) {
        String sql = "SELECT kind FROM " + SCHEMA + ".peegeeq_object_registry WHERE object_name = $1";
        return pool.preparedQuery(sql)
            .execute(Tuple.of(queueName))
            .map(rows -> rows.iterator().next().getString("kind"));
    }

    /** A queue implementation that declares no subscription store. */
    private static final class UndeclaredStoreQueueFactory implements QueueFactory {

        @Override
        public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
            throw new UnsupportedOperationException("This test factory creates no producer");
        }

        @Override
        public <T> MessageConsumer<T> createConsumer(String topic, Class<T> payloadType) {
            throw new UnsupportedOperationException("This test factory creates no consumer");
        }

        @Override
        public <T> ConsumerGroup<T> createConsumerGroup(String groupName, String topic, Class<T> payloadType) {
            return new InertConsumerGroup<>(groupName, topic);
        }

        @Override
        public <T> Future<QueueBrowser<T>> createBrowser(String topic, Class<T> payloadType) {
            return Future.failedFuture(new UnsupportedOperationException("This test factory creates no browser"));
        }

        @Override
        public String getImplementationType() {
            return "undeclared";
        }

        @Override
        public Future<Boolean> isHealthy() {
            return Future.succeededFuture(true);
        }

        @Override
        public Future<Void> close() {
            return Future.succeededFuture();
        }
    }

    /** A consumer group that has a name and a topic and is never started. */
    private static final class InertConsumerGroup<T> implements ConsumerGroup<T> {

        private final String groupName;
        private final String topic;

        private InertConsumerGroup(String groupName, String topic) {
            this.groupName = groupName;
            this.topic = topic;
        }

        @Override
        public String getGroupName() {
            return groupName;
        }

        @Override
        public String getTopic() {
            return topic;
        }

        @Override
        public ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler) {
            throw new UnsupportedOperationException("This test group has no consumers");
        }

        @Override
        public ConsumerGroupMember<T> addConsumer(String consumerId, MessageHandler<T> handler,
                                                 Predicate<Message<T>> messageFilter) {
            throw new UnsupportedOperationException("This test group has no consumers");
        }

        @Override
        public boolean removeConsumer(String consumerId) {
            return false;
        }

        @Override
        public Set<String> getConsumerIds() {
            return Set.of();
        }

        @Override
        public int getActiveConsumerCount() {
            return 0;
        }

        @Override
        public Future<Void> start() {
            return Future.failedFuture(new UnsupportedOperationException("This test group is never started"));
        }

        @Override
        public Future<Void> start(SubscriptionOptions subscriptionOptions) {
            return Future.failedFuture(new UnsupportedOperationException("This test group is never started"));
        }

        @Override
        public Future<Void> stop() {
            return Future.succeededFuture();
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public ConsumerGroupStats getStats() {
            throw new UnsupportedOperationException("This test group has no statistics");
        }

        @Override
        public ConsumerGroupMember<T> setMessageHandler(MessageHandler<T> handler) {
            throw new UnsupportedOperationException("This test group has no consumers");
        }

        @Override
        public void setGroupFilter(Predicate<Message<T>> groupFilter) {
            throw new UnsupportedOperationException("This test group has no filter");
        }

        @Override
        public Predicate<Message<T>> getGroupFilter() {
            return null;
        }

        @Override
        public Future<Void> close() {
            return Future.succeededFuture();
        }
    }
}
