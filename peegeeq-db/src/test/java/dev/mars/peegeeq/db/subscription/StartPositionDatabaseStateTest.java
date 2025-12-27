package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.StartPosition;
import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.api.subscription.SubscriptionInfo;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Critical test suite validating database state for StartPosition storage.
 * 
 * <p><b>PURPOSE:</b> Ensure StartPosition values are correctly persisted to database
 * and can be accurately retrieved. This is production-critical because incorrect
 * storage/retrieval can cause consumers to miss messages or start from wrong position.
 * 
 * <p><b>STRATEGY:</b> "Thorough scientific test to validate behaviour 100%"
 * <ul>
 *   <li>Store subscription with specific StartPosition</li>
 *   <li>Query database DIRECTLY via SQL to verify stored values</li>
 *   <li>Retrieve via SubscriptionManager API</li>
 *   <li>Compare all three states (input → database → output)</li>
 * </ul>
 * 
 * <p><b>CRITICAL INVARIANTS:</b>
 * <ul>
 *   <li>FROM_BEGINNING MUST store start_from_message_id = 1</li>
 *   <li>FROM_NOW MUST store start_from_message_id = maxId + 1</li>
 *   <li>FROM_MESSAGE_ID MUST store exact provided message ID</li>
 *   <li>FROM_TIMESTAMP MUST store exact provided timestamp</li>
 *   <li>Retrieval MUST correctly convert database values back to StartPosition</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-23
 * @version 1.0
 */
// FLAKY: Subscription persistence issues with outbox_topic_subscriptions table - needs investigation
@Tag(TestCategories.FLAKY)
public class StartPositionDatabaseStateTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(StartPositionDatabaseStateTest.class);
    
    private SubscriptionManager subscriptionManager;
    private TopicConfigService topicConfigService;
    private PgConnectionManager connectionManager;
    
    @BeforeEach
    void setUp() throws Exception {
        connectionManager = new PgConnectionManager(manager.getVertx(), null);
        
        PostgreSQLContainer<?> postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();
        
        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(10)
            .build();
        
        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);
        
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        
        logger.info("StartPositionDatabaseStateTest setup complete");
    }
    
    @Test
    void testFromBeginningStoresMessageIdOne() throws Exception {
        logger.info("=== Testing FROM_BEGINNING stores start_from_message_id = 1 ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-from-beginning-" + uniqueId;
        String groupName = "group-beginning-" + uniqueId;
        
        // Create topic
        createTopic(topic);
        
        // Subscribe with FROM_BEGINNING
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .build();
        
        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        logger.info("✓ Subscription created with FROM_BEGINNING");
        
        // Query database directly to verify stored value
        DatabaseState dbState = queryDatabaseState(topic, groupName);
        
        assertNotNull(dbState, "Subscription should exist in database");
        assertEquals(1L, dbState.startFromMessageId, 
            "FROM_BEGINNING MUST store start_from_message_id = 1");
        assertNull(dbState.startFromTimestamp, 
            "start_from_timestamp should be NULL for FROM_BEGINNING");
        
        logger.info("✓ Database verification: start_from_message_id = {}", dbState.startFromMessageId);
        
        // Retrieve via API
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        assertNotNull(subscription, "Subscription should be retrievable via API");

        // Convert to SubscriptionOptions to verify StartPosition
        SubscriptionOptions retrievedOptions = subscriptionToOptions(subscription);
        
        assertEquals(StartPosition.FROM_BEGINNING, retrievedOptions.getStartPosition(),
            "Retrieved StartPosition MUST be FROM_BEGINNING");
        
        logger.info("✅ Round-trip verification PASSED: FROM_BEGINNING → DB(1) → FROM_BEGINNING");
    }
    
    @Test
    void testFromNowStoresMaxIdPlusOne() throws Exception {
        logger.info("=== Testing FROM_NOW stores start_from_message_id = maxId + 1 ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-from-now-" + uniqueId;
        String groupName = "group-now-" + uniqueId;
        
        // Create topic and insert some messages to establish max ID
        createTopic(topic);
        insertTestMessage(topic, "message-1");
        insertTestMessage(topic, "message-2");
        insertTestMessage(topic, "message-3");
        
        Long maxId = getMaxMessageId(topic);
        logger.info("✓ Max message ID in topic: {}", maxId);
        
        // Subscribe with FROM_NOW
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_NOW)
            .build();
        
        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        logger.info("✓ Subscription created with FROM_NOW");
        
        // Query database directly
        DatabaseState dbState = queryDatabaseState(topic, groupName);
        
        assertNotNull(dbState);
        assertEquals(maxId + 1, dbState.startFromMessageId,
            String.format("FROM_NOW MUST store start_from_message_id = maxId + 1 (expected %d)", maxId + 1));
        assertNull(dbState.startFromTimestamp);
        
        logger.info("✓ Database verification: start_from_message_id = {} (maxId+1)", dbState.startFromMessageId);
        
        // Retrieve via API
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionOptions retrievedOptions = subscriptionToOptions(subscription);

        // FROM_NOW retrieval is complex: if start_from_message_id > 1, it becomes FROM_MESSAGE_ID
        // This is actually correct behavior for retrieval
        assertTrue(
            retrievedOptions.getStartPosition() == StartPosition.FROM_NOW ||
            retrievedOptions.getStartPosition() == StartPosition.FROM_MESSAGE_ID,
            "Retrieved position should be FROM_NOW or FROM_MESSAGE_ID (both valid)"
        );

        if (retrievedOptions.getStartPosition() == StartPosition.FROM_MESSAGE_ID) {
            assertEquals(maxId + 1, retrievedOptions.getStartFromMessageId(),
                "Message ID should match maxId + 1");
        }

        logger.info("✅ Round-trip verification PASSED: FROM_NOW → DB({}) → {}",
            maxId + 1, retrievedOptions.getStartPosition());
    }
    
    @Test
    void testFromMessageIdStoresExactValue() throws Exception {
        logger.info("=== Testing FROM_MESSAGE_ID stores exact message ID ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-from-message-id-" + uniqueId;
        String groupName = "group-message-id-" + uniqueId;
        
        createTopic(topic);
        
        Long explicitMessageId = 42L;
        
        // Subscribe with FROM_MESSAGE_ID
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(explicitMessageId)
            .build();
        
        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        logger.info("✓ Subscription created with FROM_MESSAGE_ID = {}", explicitMessageId);
        
        // Query database directly
        DatabaseState dbState = queryDatabaseState(topic, groupName);
        
        assertNotNull(dbState);
        assertEquals(explicitMessageId, dbState.startFromMessageId,
            "FROM_MESSAGE_ID MUST store exact provided message ID");
        assertNull(dbState.startFromTimestamp);
        
        logger.info("✓ Database verification: start_from_message_id = {}", dbState.startFromMessageId);
        
        // Retrieve via API
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionOptions retrievedOptions = subscriptionToOptions(subscription);

        assertEquals(StartPosition.FROM_MESSAGE_ID, retrievedOptions.getStartPosition());
        assertEquals(explicitMessageId, retrievedOptions.getStartFromMessageId(),
            "Retrieved message ID MUST match stored value");

        logger.info("✅ Round-trip verification PASSED: FROM_MESSAGE_ID({}) → DB({}) → FROM_MESSAGE_ID({})",
            explicitMessageId, dbState.startFromMessageId, retrievedOptions.getStartFromMessageId());
    }
    
    @Test
    void testFromTimestampStoresExactValue() throws Exception {
        logger.info("=== Testing FROM_TIMESTAMP stores exact timestamp ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-from-timestamp-" + uniqueId;
        String groupName = "group-timestamp-" + uniqueId;
        
        createTopic(topic);
        
        Instant explicitTimestamp = Instant.now().minus(1, ChronoUnit.HOURS);
        
        // Subscribe with FROM_TIMESTAMP
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_TIMESTAMP)
            .startFromTimestamp(explicitTimestamp)
            .build();
        
        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        logger.info("✓ Subscription created with FROM_TIMESTAMP = {}", explicitTimestamp);
        
        // Query database directly
        DatabaseState dbState = queryDatabaseState(topic, groupName);
        
        assertNotNull(dbState);
        assertNull(dbState.startFromMessageId,
            "start_from_message_id should be NULL for FROM_TIMESTAMP");
        assertNotNull(dbState.startFromTimestamp);
        
        // Timestamps may lose precision in database, so compare within tolerance
        long diffMillis = Math.abs(explicitTimestamp.toEpochMilli() - 
                                   dbState.startFromTimestamp.toEpochMilli());
        assertTrue(diffMillis < 1000, 
            String.format("Timestamp difference should be < 1 second (was %d ms)", diffMillis));
        
        logger.info("✓ Database verification: start_from_timestamp = {} (diff: {} ms)", 
            dbState.startFromTimestamp, diffMillis);
        
        // Retrieve via API
        SubscriptionInfo subscription = subscriptionManager.getSubscription(topic, groupName)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        SubscriptionOptions retrievedOptions = subscriptionToOptions(subscription);

        assertEquals(StartPosition.FROM_TIMESTAMP, retrievedOptions.getStartPosition());
        assertNotNull(retrievedOptions.getStartFromTimestamp());

        long retrievedDiffMillis = Math.abs(explicitTimestamp.toEpochMilli() -
                                            retrievedOptions.getStartFromTimestamp().toEpochMilli());
        assertTrue(retrievedDiffMillis < 1000,
            "Retrieved timestamp should match within 1 second");

        logger.info("✅ Round-trip verification PASSED: FROM_TIMESTAMP({}) → DB({}) → FROM_TIMESTAMP({})",
            explicitTimestamp, dbState.startFromTimestamp, retrievedOptions.getStartFromTimestamp());
    }
    
    @Test
    void testEdgeCaseMessageIdZero() throws Exception {
        logger.info("=== Testing FROM_MESSAGE_ID with ID = 0 (edge case) ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-message-id-zero-" + uniqueId;
        String groupName = "group-zero-" + uniqueId;
        
        createTopic(topic);
        
        // Subscribe with message ID = 0
        SubscriptionOptions options = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_MESSAGE_ID)
            .startFromMessageId(0L)
            .build();
        
        subscriptionManager.subscribe(topic, groupName, options)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
        
        DatabaseState dbState = queryDatabaseState(topic, groupName);
        
        assertNotNull(dbState);
        assertEquals(0L, dbState.startFromMessageId,
            "Should accept and store message ID = 0");
        
        logger.info("✅ Edge case PASSED: message ID = 0 handled correctly");
    }
    
    @Test
    void testUpdateSubscriptionChangesStartPosition() throws Exception {
        logger.info("=== Testing update subscription changes start position in database ===");

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String topic = "test-update-position-" + uniqueId;
        String groupName = "group-update-" + uniqueId;

        createTopic(topic);

        // Insert some messages first so FROM_NOW will give a different value than FROM_BEGINNING
        // FROM_BEGINNING always stores 1, FROM_NOW stores max_id + 1
        insertTestMessage(topic, "{\"test\": \"message1\"}");
        insertTestMessage(topic, "{\"test\": \"message2\"}");
        insertTestMessage(topic, "{\"test\": \"message3\"}");

        Long maxId = getMaxMessageId(topic);
        logger.info("✓ Inserted 3 messages, max_id = {}", maxId);

        // Initial: FROM_NOW (should store max_id + 1)
        SubscriptionOptions initialOptions = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_NOW)
            .build();

        subscriptionManager.subscribe(topic, groupName, initialOptions)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        DatabaseState initialState = queryDatabaseState(topic, groupName);
        logger.info("✓ Initial state (FROM_NOW): start_from_message_id = {}", initialState.startFromMessageId);

        // Verify FROM_NOW stored max_id + 1
        assertEquals(maxId + 1, initialState.startFromMessageId,
            "FROM_NOW should store max_id + 1");

        // Update: FROM_BEGINNING (should store 1)
        SubscriptionOptions updatedOptions = SubscriptionOptions.builder()
            .startPosition(StartPosition.FROM_BEGINNING)
            .build();

        subscriptionManager.subscribe(topic, groupName, updatedOptions)
            .toCompletionStage()
            .toCompletableFuture()
            .get();

        DatabaseState updatedState = queryDatabaseState(topic, groupName);

        assertEquals(1L, updatedState.startFromMessageId,
            "After update to FROM_BEGINNING, database should store start_from_message_id = 1");
        assertNotEquals(initialState.startFromMessageId, updatedState.startFromMessageId,
            "Start position should have changed in database");

        logger.info("✅ Update PASSED: {} → 1 (FROM_NOW → FROM_BEGINNING)",
            initialState.startFromMessageId);
    }
    
    // ==================== Helper Methods ====================
    
    private void createTopic(String topic) throws Exception {
        TopicConfig topicConfig = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .build();
        
        topicConfigService.createTopic(topicConfig)
            .toCompletionStage()
            .toCompletableFuture()
            .get();
    }
    
    private void insertTestMessage(String topic, String messageBody) throws Exception {
        // Wrap the message body in a JSON object to ensure valid JSONB
        String jsonPayload = String.format("{\"message\": \"%s\"}", messageBody);

        String insertSql = """
            INSERT INTO outbox (topic, payload, status, created_at)
            VALUES ($1, $2::jsonb, 'PENDING', NOW())
            """;

        connectionManager.withConnection("peegeeq-main", connection ->
            connection.preparedQuery(insertSql)
                .execute(Tuple.of(topic, jsonPayload))
                .mapEmpty()
        ).toCompletionStage().toCompletableFuture().get();
    }
    
    private Long getMaxMessageId(String topic) throws Exception {
        String maxIdSql = "SELECT COALESCE(MAX(id), 0) AS max_id FROM outbox WHERE topic = $1";
        
        RowSet<Row> rows = connectionManager.withConnection("peegeeq-main", connection ->
            connection.preparedQuery(maxIdSql)
                .execute(Tuple.of(topic))
        ).toCompletionStage().toCompletableFuture().get();
        
        return rows.iterator().next().getLong("max_id");
    }
    
    /**
     * Query database directly to verify stored subscription values.
     * This bypasses SubscriptionManager API to get raw database state.
     */
    private DatabaseState queryDatabaseState(String topic, String groupName) throws Exception {
        String sql = """
            SELECT 
                start_from_message_id,
                start_from_timestamp,
                heartbeat_interval_seconds,
                heartbeat_timeout_seconds
            FROM outbox_topic_subscriptions
            WHERE topic = $1 AND group_name = $2
            """;
        
        RowSet<Row> rows = connectionManager.withConnection("peegeeq-main", connection ->
            connection.preparedQuery(sql)
                .execute(Tuple.of(topic, groupName))
        ).toCompletionStage().toCompletableFuture().get();
        
        if (rows.size() == 0) {
            return null;
        }
        
        Row row = rows.iterator().next();
        return new DatabaseState(
            row.getLong("start_from_message_id"),
            row.getLocalDateTime("start_from_timestamp") != null ? 
                row.getLocalDateTime("start_from_timestamp").toInstant(java.time.ZoneOffset.UTC) : null,
            row.getInteger("heartbeat_interval_seconds"),
            row.getInteger("heartbeat_timeout_seconds")
        );
    }
    
    /**
     * Convert SubscriptionInfo to SubscriptionOptions (same logic as ConsumerGroupHandler).
     */
    private SubscriptionOptions subscriptionToOptions(SubscriptionInfo subscription) {
        SubscriptionOptions.Builder builder = SubscriptionOptions.builder();

        // Determine StartPosition
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
    
    /**
     * Raw database state (what's actually stored in PostgreSQL).
     */
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
