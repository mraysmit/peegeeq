package dev.mars.peegeeq.db.subscription;

import dev.mars.peegeeq.api.messaging.SubscriptionOptions;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

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
    void setUp() {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();

        connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        validator = new ZeroSubscriptionValidator(connectionManager, "peegeeq-main");
        topicConfigService = new TopicConfigService(connectionManager, "peegeeq-main");
        subscriptionManager = new SubscriptionManager(connectionManager, "peegeeq-main");

        logger.info("ZeroSubscriptionValidator test setup complete");
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

    /**
     * Test F13: QUEUE topics always allow writes.
     */
    @Test
    void testQueueTopicAlwaysAllowsWrites(VertxTestContext testContext) {
        String topic = "test-queue-allow";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .messageRetentionHours(24)
                .build())
            .compose(v -> validator.isWriteAllowed(topic))
            .onComplete(testContext.succeeding(allowed -> testContext.verify(() -> {
                assertTrue(allowed, "QUEUE topics should always allow writes");
                logger.info("QUEUE topic write allowed verified");
                testContext.completeNow();
            })));
    }

    /**
     * Test F14: PUB_SUB topics with blocking disabled allow writes even with zero subscriptions.
     */
    @Test
    void testPubSubTopicWithBlockingDisabledAllowsWrites(VertxTestContext testContext) {
        String topic = "test-pubsub-blocking-disabled";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .blockWritesOnZeroSubscriptions(false)
                .build())
            .compose(v -> validator.isWriteAllowed(topic))
            .onComplete(testContext.succeeding(allowed -> testContext.verify(() -> {
                assertTrue(allowed, "PUB_SUB topics with blocking disabled should allow writes");
                logger.info("PUB_SUB topic with blocking disabled allows writes verified");
                testContext.completeNow();
            })));
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
    void testPubSubTopicWithBlockingEnabledBlocksWrites(VertxTestContext testContext) {
        String topic = "test-pubsub-blocking-enabled";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .blockWritesOnZeroSubscriptions(true)
                .build())
            .compose(v -> validator.isWriteAllowed(topic))
            .onComplete(testContext.succeeding(allowed -> testContext.verify(() -> {
                assertFalse(allowed, "PUB_SUB topics with blocking enabled and zero subscriptions should block writes");
                logger.info("PUB_SUB topic with blocking enabled blocks writes verified");
                testContext.completeNow();
            })));
    }

    /**
     * Test F16: PUB_SUB topics with blocking enabled and active subscriptions allow writes.
     */
    @Test
    void testPubSubTopicWithBlockingEnabledAndActiveSubscriptionsAllowsWrites(VertxTestContext testContext) {
        String topic = "test-pubsub-blocking-with-subs";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .blockWritesOnZeroSubscriptions(true)
                .build())
            .compose(v -> subscriptionManager.subscribe(topic, "group-a", SubscriptionOptions.defaults()))
            .compose(v -> validator.isWriteAllowed(topic))
            .onComplete(testContext.succeeding(allowed -> testContext.verify(() -> {
                assertTrue(allowed, "PUB_SUB topics with blocking enabled and active subscriptions should allow writes");
                logger.info("PUB_SUB topic with blocking enabled and active subscriptions allows writes verified");
                testContext.completeNow();
            })));
    }

    /**
     * Test F17: Unconfigured topics default to QUEUE semantics and allow writes.
     */
    @Test
    void testUnconfiguredTopicAllowsWrites(VertxTestContext testContext) {
        String topic = "test-unconfigured-topic";

        validator.isWriteAllowed(topic)
            .onComplete(testContext.succeeding(allowed -> testContext.verify(() -> {
                assertTrue(allowed, "Unconfigured topics should default to QUEUE semantics and allow writes");
                logger.info("Unconfigured topic allows writes verified");
                testContext.completeNow();
            })));
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
    void testValidateWriteAllowedThrowsExceptionWhenBlocked(VertxTestContext testContext) {
        logger.warn("===== INTENTIONAL WARN TEST ===== The next WARN log ('Blocking write to topic - zero ACTIVE subscriptions') is EXPECTED this test verifies writes are blocked when block_writes_on_zero_subscriptions = TRUE");
        String topic = "test-validate-blocked";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.PUB_SUB)
                .messageRetentionHours(24)
                .blockWritesOnZeroSubscriptions(true)
                .build())
            .compose(v -> validator.validateWriteAllowed(topic))
            .transform(ar -> {
                if (ar.succeeded()) {
                    return Future.failedFuture(new AssertionError("Expected NoActiveSubscriptionsException to be thrown"));
                }
                try {
                    assertTrue(ar.cause() instanceof ZeroSubscriptionValidator.NoActiveSubscriptionsException,
                        "Expected NoActiveSubscriptionsException");
                    assertTrue(ar.cause().getMessage().contains("zero ACTIVE subscriptions"),
                        "Exception message should mention zero subscriptions");
                    logger.info("validateWriteAllowed throws exception when blocked verified");
                } catch (Throwable t) {
                    return Future.failedFuture(t);
                }
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    /**
     * Test F19: validateWriteAllowed succeeds when write is allowed.
     */
    @Test
    void testValidateWriteAllowedSucceedsWhenAllowed(VertxTestContext testContext) {
        String topic = "test-validate-allowed";

        topicConfigService.createTopic(TopicConfig.builder()
                .topic(topic)
                .semantics(TopicSemantics.QUEUE)
                .messageRetentionHours(24)
                .build())
            .compose(v -> validator.validateWriteAllowed(topic))
            .onSuccess(v -> {
                logger.info("validateWriteAllowed succeeds when allowed verified");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }
}

