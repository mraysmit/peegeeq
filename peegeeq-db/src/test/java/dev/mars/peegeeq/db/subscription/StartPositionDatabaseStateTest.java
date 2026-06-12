package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.INTEGRATION)
public class StartPositionDatabaseStateTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(StartPositionDatabaseStateTest.class);
    
    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;
    
    @BeforeEach
    void setUp() {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);
        
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();
        
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();
        
        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);
        
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        
        logger.info("StartPositionDatabaseStateTest setup complete");
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (connectionManager != null) {
            connectionManager.close()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }
    
    @Test
    void testFromBeginningStoresMessageIdOne(VertxTestContext testContext) {
        logger.info("=== Testing FROM_BEGINNING stores start_from_message_id = 1 ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-from-beginning-" + uniqueId;
        String groupName = "group-beginning-" + uniqueId;
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> queryDatabaseState(topic, groupName))
            .compose(dbState -> {
                assertNotNull(dbState, "Subscription should exist in database");
                assertEquals(1L, dbState.startFromMessageId,
                    "FROM_BEGINNING MUST store start_from_message_id = 1");
                assertNull(dbState.startFromTimestamp,
                    "start_from_timestamp should be NULL for FROM_BEGINNING");
                return subscriptionManager.getSubscription(topic, groupName);
            })
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                assertNotNull(subscription, "Subscription should be retrievable via API");
                SubscriptionOptions retrievedOptions = subscriptionToOptions(subscription);
                assertEquals(StartPosition.FROM_BEGINNING, retrievedOptions.getStartPosition(),
                    "Retrieved StartPosition MUST be FROM_BEGINNING");
                logger.info("Round-trip verification PASSED: FROM_BEGINNING");
                testContext.completeNow();
            })));
    }

    @Test
    void testFromNowStoresMaxIdPlusOne(VertxTestContext testContext) {
        logger.info("=== Testing FROM_NOW stores start_from_message_id = maxId + 1 ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-from-now-" + uniqueId;
        String groupName = "group-now-" + uniqueId;

        AtomicReference<Long> maxIdRef = new AtomicReference<>();
        createTopic(topic)
            .compose(v -> insertTestMessage(topic, "message-1"))
            .compose(v -> insertTestMessage(topic, "message-2"))
            .compose(v -> insertTestMessage(topic, "message-3"))
            .compose(v -> getMaxMessageId(topic))
            .compose(maxId -> {
                maxIdRef.set(maxId);
                return subscriptionManager.subscribe(topic, groupName,
                    SubscriptionOptions.builder().startPosition(StartPosition.FROM_NOW).build());
            })
            .compose(v -> queryDatabaseState(topic, groupName))
            .compose(dbState -> {
                assertNotNull(dbState);
                assertEquals(maxIdRef.get() + 1, dbState.startFromMessageId,
                    String.format("FROM_NOW MUST store start_from_message_id = maxId + 1 (expected %d)",
                        maxIdRef.get() + 1));
                assertNull(dbState.startFromTimestamp);
                return subscriptionManager.getSubscription(topic, groupName);
            })
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                SubscriptionOptions retrievedOptions = subscriptionToOptions(subscription);
                assertTrue(
                    retrievedOptions.getStartPosition() == StartPosition.FROM_NOW ||
                    retrievedOptions.getStartPosition() == StartPosition.FROM_MESSAGE_ID,
                    "Retrieved position should be FROM_NOW or FROM_MESSAGE_ID"
                );
                if (retrievedOptions.getStartPosition() == StartPosition.FROM_MESSAGE_ID) {
                    assertEquals(maxIdRef.get() + 1, retrievedOptions.getStartFromMessageId(),
                        "Message ID should match maxId + 1");
                }
                logger.info("Round-trip verification PASSED: FROM_NOW");
                testContext.completeNow();
            })));
    }

    @Test
    void testFromMessageIdStoresExactValue(VertxTestContext testContext) {
        logger.info("=== Testing FROM_MESSAGE_ID stores exact message ID ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-from-message-id-" + uniqueId;
        String groupName = "group-message-id-" + uniqueId;
        Long explicitMessageId = 42L;
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(explicitMessageId)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> queryDatabaseState(topic, groupName))
            .compose(dbState -> {
                assertNotNull(dbState);
                assertEquals(explicitMessageId, dbState.startFromMessageId,
                    "FROM_MESSAGE_ID MUST store exact provided message ID");
                assertNull(dbState.startFromTimestamp);
                return subscriptionManager.getSubscription(topic, groupName);
            })
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                SubscriptionOptions retrievedOptions = subscriptionToOptions(subscription);
                assertEquals(StartPosition.FROM_MESSAGE_ID, retrievedOptions.getStartPosition());
                assertEquals(explicitMessageId, retrievedOptions.getStartFromMessageId(),
                    "Retrieved message ID MUST match stored value");
                logger.info("Round-trip verification PASSED: FROM_MESSAGE_ID");
                testContext.completeNow();
            })));
    }
    
    @Test
    void testFromTimestampStoresExactValue(VertxTestContext testContext) {
        logger.info("=== Testing FROM_TIMESTAMP stores exact timestamp ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-from-timestamp-" + uniqueId;
        String groupName = "group-timestamp-" + uniqueId;
        Instant explicitTimestamp = Instant.now().minus(1, ChronoUnit.HOURS);
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_TIMESTAMP)
            .startFromTimestamp(explicitTimestamp)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> queryDatabaseState(topic, groupName))
            .compose(dbState -> {
                assertNotNull(dbState);
                assertNull(dbState.startFromMessageId,
                    "start_from_message_id should be NULL for FROM_TIMESTAMP");
                assertNotNull(dbState.startFromTimestamp);
                long diffMillis = Math.abs(explicitTimestamp.toEpochMilli() -
                    dbState.startFromTimestamp.toEpochMilli());
                assertTrue(diffMillis < 1000,
                    String.format("Timestamp difference should be < 1 second (was %d ms)", diffMillis));
                return subscriptionManager.getSubscription(topic, groupName);
            })
            .onComplete(testContext.succeeding(subscription -> testContext.verify(() -> {
                SubscriptionOptions retrievedOptions = subscriptionToOptions(subscription);
                assertEquals(StartPosition.FROM_TIMESTAMP, retrievedOptions.getStartPosition());
                assertNotNull(retrievedOptions.getStartFromTimestamp());
                long retrievedDiffMillis = Math.abs(explicitTimestamp.toEpochMilli() -
                    retrievedOptions.getStartFromTimestamp().toEpochMilli());
                assertTrue(retrievedDiffMillis < 1000,
                    "Retrieved timestamp should match within 1 second");
                logger.info("Round-trip verification PASSED: FROM_TIMESTAMP");
                testContext.completeNow();
            })));
    }
    
    @Test
    void testEdgeCaseMessageIdZero(VertxTestContext testContext) {
        logger.info("=== Testing FROM_MESSAGE_ID with ID = 0 (edge case) ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-message-id-zero-" + uniqueId;
        String groupName = "group-zero-" + uniqueId;
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(0L)
            .build();

        createTopic(topic)
            .compose(v -> subscriptionManager.subscribe(topic, groupName, options))
            .compose(v -> queryDatabaseState(topic, groupName))
            .onComplete(testContext.succeeding(dbState -> testContext.verify(() -> {
                assertNotNull(dbState);
                assertEquals(0L, dbState.startFromMessageId,
                    "Should accept and store message ID = 0");
                logger.info("Edge case PASSED: message ID = 0 handled correctly");
                testContext.completeNow();
            })));
    }
    
    @Test
    void testUpdateSubscriptionChangesStartPosition(VertxTestContext testContext) {
        logger.info("=== Testing update subscription changes start position in database ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-update-position-" + uniqueId;
        String groupName = "group-update-" + uniqueId;

        AtomicReference<Long> maxIdRef = new AtomicReference<>();
        AtomicReference<Long> initialStartIdRef = new AtomicReference<>();
        createTopic(topic)
            .compose(v -> insertTestMessage(topic, "{\"test\": \"message1\"}"))
            .compose(v -> insertTestMessage(topic, "{\"test\": \"message2\"}"))
            .compose(v -> insertTestMessage(topic, "{\"test\": \"message3\"}"))
            .compose(v -> getMaxMessageId(topic))
            .compose(maxId -> {
                maxIdRef.set(maxId);
                return subscriptionManager.subscribe(topic, groupName,
                    SubscriptionOptions.builder().startPosition(StartPosition.FROM_NOW).build());
            })
            .compose(v -> queryDatabaseState(topic, groupName))
            .compose(initialState -> {
                assertEquals(maxIdRef.get() + 1, initialState.startFromMessageId,
                    "FROM_NOW should store max_id + 1");
                initialStartIdRef.set(initialState.startFromMessageId);
                return subscriptionManager.subscribe(topic, groupName,
                    SubscriptionOptions.builder().startPosition(StartPosition.FROM_BEGINNING).build());
            })
            .compose(v -> queryDatabaseState(topic, groupName))
            .onComplete(testContext.succeeding(updatedState -> testContext.verify(() -> {
                assertEquals(1L, updatedState.startFromMessageId,
                    "After update to FROM_BEGINNING, database should store start_from_message_id = 1");
                assertNotEquals(initialStartIdRef.get(), updatedState.startFromMessageId,
                    "Start position should have changed in database");
                logger.info("Update PASSED: {} -> 1 (FROM_NOW -> FROM_BEGINNING)",
                    initialStartIdRef.get());
                testContext.completeNow();
            })));
    }
    
    // ==================== Helper Methods ====================
    
    private Future<Void> createTopic(String topic) {
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        return topicConfigService.createTopic(topicConfig);
    }
    
    private Future<Void> insertTestMessage(String topic, String messageBody) {
        String jsonPayload = String.format("{\"message\": \"%s\"}", messageBody);
        String insertSql = """
            INSERT INTO outbox (topic, payload, status, created_at)
            VALUES ($1, $2::jsonb, 'PENDING', NOW())
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
            connection.preparedQuery(insertSql)
                .execute(Tuple.of(topic, jsonPayload))
                .mapEmpty()
        );
    }
    
    private Future<Long> getMaxMessageId(String topic) {
        String maxIdSql = "SELECT COALESCE(MAX(id), 0) AS max_id FROM outbox WHERE topic = $1";
        return connectionManager.withConnection("peegeeq-main", connection ->
            connection.preparedQuery(maxIdSql)
                .execute(Tuple.of(topic))
                .map(rows -> rows.iterator().next().getLong("max_id"))
        );
    }
    
    private Future<DatabaseState> queryDatabaseState(String topic, String groupName) {
        String sql = """
            SELECT 
                start_from_message_id,
                start_from_timestamp,
                heartbeat_interval_seconds,
                heartbeat_timeout_seconds
            FROM outbox_topic_subscriptions
            WHERE topic = $1 AND group_name = $2
            """;
        return connectionManager.withConnection("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
                .map(rows -> {
                    if (rows.size() == 0) return null;
                    Row row = rows.iterator().next();
                    return new DatabaseState(
                        row.getLong("start_from_message_id"),
                        row.getLocalDateTime("start_from_timestamp") != null ?
                            row.getLocalDateTime("start_from_timestamp").toInstant(java.time.ZoneOffset.UTC) : null,
                        row.getInteger("heartbeat_interval_seconds"),
                        row.getInteger("heartbeat_timeout_seconds")
                    );
                })
        );
    }
    
    private SubscriptionOptions subscriptionToOptions(SubscriptionInfo subscription) {
        SubscriptionOptions.Builder builder = SubscriptionOptions.builder();

        if (subscription.startFromMessageId() != null) {
            if (subscription.startFromMessageId() == 1L) {
                builder.startPosition(StartPosition.FROM_BEGINNING);
            } else {
                builder.startPosition(StartPosition.FROM_MESSAGE_ID);
                builder.startFromMessageId(subscription.startFromMessageId());
            }
        } else if (subscription.startFromTimestamp() != null) {
            builder.startPosition(StartPosition.FROM_TIMESTAMP);
            builder.startFromTimestamp(subscription.startFromTimestamp());
        } else {
            builder.startPosition(StartPosition.FROM_NOW);
        }

        builder.heartbeatIntervalSeconds(subscription.heartbeatIntervalSeconds());
        builder.heartbeatTimeoutSeconds(subscription.heartbeatTimeoutSeconds());

        return builder.build();
    }
    
    private static class DatabaseState {
        final Long startFromMessageId;
        final Instant startFromTimestamp;
        final Integer heartbeatIntervalSeconds;
        final Integer heartbeatTimeoutSeconds;
        
        DatabaseState(Long startFromMessageId, Instant startFromTimestamp, 
                     Integer heartbeatIntervalSeconds, Integer heartbeatTimeoutSeconds) {
            this.startFromMessageId = startFromMessageId;
            this.startFromTimestamp = startFromTimestamp;
            this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
            this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        }
    }
}