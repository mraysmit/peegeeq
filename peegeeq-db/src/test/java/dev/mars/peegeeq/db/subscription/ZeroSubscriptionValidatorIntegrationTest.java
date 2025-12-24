package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ZeroSubscriptionValidator.
 *
 * <p>This test validates the zero-subscription protection logic including:
 * <ul>
 *   <li>QUEUE topics always allow writes</li>
 *   <li>PUB_SUB topics with blocking disabled allow writes</li>
 *   <li>PUB_SUB topics with blocking enabled and zero subscriptions block writes</li>
 *   <li>PUB_SUB topics with blocking enabled and active subscriptions allow writes</li>
 *   <li>Unconfigured topics default to QUEUE semantics (allow writes)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-12
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
public class ZeroSubscriptionValidatorIntegrationTest extends BaseIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ZeroSubscriptionValidatorIntegrationTest.class);

    private ZeroSubscriptionValidator validator;
    private TopicConfigService topicConfigService;
    private SubscriptionManager subscriptionManager;
    private PgConnectionManager connectionManager;

    @BeforeEach
    void setUp() throws Exception {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
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

        validator = new ZeroSubscriptionValidator(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        logger.info("ZeroSubscriptionValidator test setup complete");
    }

    /**
     * Test F13: QUEUE topics always allow writes.
     */
    @Test
    void testQueueTopicAlwaysAllowsWrites() throws Exception {
        String topic = "test-queue-allow";

        // Create a QUEUE topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Verify write is allowed
        Boolean allowed = validator.isWriteAllowed(topic)
            .toCompletionStage().toCompletableFuture().get();

        assertTrue(allowed, "QUEUE topics should always allow writes");

        logger.info("✅ QUEUE topic write allowed verified");
    }

    /**
     * Test F14: PUB_SUB topics with blocking disabled allow writes even with zero subscriptions.
     */
    @Test
    void testPubSubTopicWithBlockingDisabledAllowsWrites() throws Exception {
        String topic = "test-pubsub-blocking-disabled";

        // Create a PUB_SUB topic with blocking disabled
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .blockWritesOnZeroSubscriptions(false)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Verify write is allowed (even with zero subscriptions)
        Boolean allowed = validator.isWriteAllowed(topic)
            .toCompletionStage().toCompletableFuture().get();

        assertTrue(allowed, "PUB_SUB topics with blocking disabled should allow writes");

        logger.info("✅ PUB_SUB topic with blocking disabled allows writes verified");
    }

    /**
     * Test F15: PUB_SUB topics with blocking enabled and zero subscriptions block writes.
     *
     * <p><strong>EXPECTED WARNING:</strong> This test intentionally triggers a warning:
     * "Blocking write to topic 'test-pubsub-blocking-enabled' - zero ACTIVE subscriptions
     * and block_writes_on_zero_subscriptions = TRUE"
     * This warning is the expected behavior being tested and is not an error.
     */
    @Test
    void testPubSubTopicWithBlockingEnabledBlocksWrites() throws Exception {
        String topic = "test-pubsub-blocking-enabled";

        // Create a PUB_SUB topic with blocking enabled
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .blockWritesOnZeroSubscriptions(true)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Verify write is blocked (zero subscriptions)
        // EXPECTED WARNING: This will log a warning about blocking the write
        Boolean allowed = validator.isWriteAllowed(topic)
            .toCompletionStage().toCompletableFuture().get();

        assertFalse(allowed, "PUB_SUB topics with blocking enabled and zero subscriptions should block writes");

        logger.info("✅ PUB_SUB topic with blocking enabled blocks writes verified");
    }

    /**
     * Test F16: PUB_SUB topics with blocking enabled and active subscriptions allow writes.
     */
    @Test
    void testPubSubTopicWithBlockingEnabledAndActiveSubscriptionsAllowsWrites() throws Exception {
        String topic = "test-pubsub-blocking-with-subs";

        // Create a PUB_SUB topic with blocking enabled
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .blockWritesOnZeroSubscriptions(true)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Create an active subscription
        subscriptionManager.subscribe(topic, "group-a", SubscriptionOptions.defaults())
            .toCompletionStage().toCompletableFuture().get();

        // Verify write is allowed (has active subscription)
        Boolean allowed = validator.isWriteAllowed(topic)
            .toCompletionStage().toCompletableFuture().get();

        assertTrue(allowed, "PUB_SUB topics with blocking enabled and active subscriptions should allow writes");

        logger.info("✅ PUB_SUB topic with blocking enabled and active subscriptions allows writes verified");
    }

    /**
     * Test F17: Unconfigured topics default to QUEUE semantics and allow writes.
     */
    @Test
    void testUnconfiguredTopicAllowsWrites() throws Exception {
        String topic = "test-unconfigured-topic";

        // Do NOT create topic configuration

        // Verify write is allowed (defaults to QUEUE)
        Boolean allowed = validator.isWriteAllowed(topic)
            .toCompletionStage().toCompletableFuture().get();

        assertTrue(allowed, "Unconfigured topics should default to QUEUE semantics and allow writes");

        logger.info("✅ Unconfigured topic allows writes verified");
    }

    /**
     * Test F18: validateWriteAllowed throws exception when write is blocked.
     *
     * <p><strong>EXPECTED WARNING:</strong> This test intentionally triggers a warning:
     * "Blocking write to topic 'test-validate-blocked' - zero ACTIVE subscriptions
     * and block_writes_on_zero_subscriptions = TRUE"
     * This warning is the expected behavior being tested and is not an error.
     */
    @Test
    void testValidateWriteAllowedThrowsExceptionWhenBlocked() throws Exception {
        String topic = "test-validate-blocked";

        // Create a PUB_SUB topic with blocking enabled
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.PUB_SUB)
            .messageRetentionHours(24)
            .blockWritesOnZeroSubscriptions(true)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Verify validateWriteAllowed throws exception
        // EXPECTED WARNING: This will log a warning about blocking the write
        try {
            validator.validateWriteAllowed(topic)
                .toCompletionStage().toCompletableFuture().get();
            fail("Expected NoActiveSubscriptionsException to be thrown");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof ZeroSubscriptionValidator.NoActiveSubscriptionsException,
                "Expected NoActiveSubscriptionsException");
            assertTrue(e.getCause().getMessage().contains("zero ACTIVE subscriptions"),
                "Exception message should mention zero subscriptions");
        }

        logger.info("✅ validateWriteAllowed throws exception when blocked verified");
    }

    /**
     * Test F19: validateWriteAllowed succeeds when write is allowed.
     */
    @Test
    void testValidateWriteAllowedSucceedsWhenAllowed() throws Exception {
        String topic = "test-validate-allowed";

        // Create a QUEUE topic
        TopicConfig config = TopicConfig.builder()
            .topic(topic)
            .semantics(TopicSemantics.QUEUE)
            .messageRetentionHours(24)
            .build();
        topicConfigService.createTopic(config)
            .toCompletionStage().toCompletableFuture().get();

        // Verify validateWriteAllowed succeeds
        validator.validateWriteAllowed(topic)
            .toCompletionStage().toCompletableFuture().get();

        logger.info("✅ validateWriteAllowed succeeds when allowed verified");
    }
}

