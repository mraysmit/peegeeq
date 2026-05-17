package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
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
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.MDC;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for L9: Fan-Out Trace Propagation.
 *
 * <p>Verifies that when a consumer group processes a message, the trace context
 * creates a child span (same traceId, new spanId with parent linkage) rather than
 * reusing the original publish span. Also verifies consumerGroup appears in MDC.</p>
 */
@Testcontainers
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Execution(ExecutionMode.SAME_THREAD)
class FanOutTracePropagationTest {
    private static final Logger logger = LoggerFactory.getLogger(FanOutTracePropagationTest.class);


    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "fanout-trace-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                DatabaseService databaseService = new PgDatabaseService(manager);
                outboxFactory = new OutboxFactory(databaseService, config);
                producer = outboxFactory.createProducer(testTopic, String.class);
                consumer = outboxFactory.createConsumer(testTopic, String.class);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
        if (outboxFactory != null) outboxFactory.close();
        MDC.clear();
        if (manager != null) {
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void consumerGroupProcessing_createsChildSpan_sameTraceIdDifferentSpanId(
            VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer group processing creates child span same trace id different span id");
        // Arrange: known publish trace context
        String publishTraceId = "abcdef0123456789abcdef0123456789";
        String publishSpanId = "1234567890abcdef";
        String traceparent = String.format("00-%s-%s-01", publishTraceId, publishSpanId);

        Map<String, String> headers = Map.of("traceparent", traceparent);

        // Set consumer group name
        ((OutboxConsumer<String>) consumer).setConsumerGroupName("payments-processor");

        AtomicReference<String> consumerTraceId = new AtomicReference<>();
        AtomicReference<String> consumerSpanId = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        // Act: subscribe and send
        consumer.subscribe(message -> {
            consumerTraceId.set(MDC.get("traceId"));
            consumerSpanId.set(MDC.get("spanId"));
            messageReceived.flag();
            return Future.succeededFuture();
        });

        producer.send("child-span-test", headers, null).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Consumer should receive message");

        // Assert: same traceId (correlation preserved), different spanId (child span)
        assertEquals(publishTraceId, consumerTraceId.get(),
                "Consumer should see same traceId as publisher (trace correlation)");
        assertNotNull(consumerSpanId.get(), "Consumer spanId should not be null");
        assertNotEquals(publishSpanId, consumerSpanId.get(),
                "Consumer spanId should be a NEW child span, not the original publish spanId");
    }

    @Test
    void consumerGroupProcessing_setsConsumerGroupInMDC(
            VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer group processing sets consumer group in m d c");
        String publishTraceId = "fedcba9876543210fedcba9876543210";
        String publishSpanId = "abcdef1234567890";
        String traceparent = String.format("00-%s-%s-01", publishTraceId, publishSpanId);

        Map<String, String> headers = Map.of("traceparent", traceparent);

        ((OutboxConsumer<String>) consumer).setConsumerGroupName("order-fulfillment");

        AtomicReference<String> consumerGroupMdc = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            consumerGroupMdc.set(MDC.get("consumerGroup"));
            messageReceived.flag();
            return Future.succeededFuture();
        });

        producer.send("group-mdc-test", headers, null).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

        assertEquals("order-fulfillment", consumerGroupMdc.get(),
                "consumerGroup should appear in MDC during message processing");
    }

    @Test
    void consumerWithoutGroupName_usesParseOrCreate_noChildSpan(
            VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer without group name uses parse or create no child span");
        // Consumer WITHOUT a group name no child span should be created
        // (backward compat: same behaviour as before)
        String publishTraceId = "11111111111111111111111111111111";
        String publishSpanId = "2222222222222222";
        String traceparent = String.format("00-%s-%s-01", publishTraceId, publishSpanId);

        Map<String, String> headers = Map.of("traceparent", traceparent);

        // Don't set consumer group name leave it null

        AtomicReference<String> consumerTraceId = new AtomicReference<>();
        AtomicReference<String> consumerSpanId = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            consumerTraceId.set(MDC.get("traceId"));
            consumerSpanId.set(MDC.get("spanId"));
            messageReceived.flag();
            return Future.succeededFuture();
        });

        producer.send("no-group-test", headers, null).onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

        // Without a group name, should still parse the traceparent normally
        assertEquals(publishTraceId, consumerTraceId.get(),
                "traceId should match publisher even without consumer group");
        // spanId behaviour: still the original publish spanId (no child span)
        assertEquals(publishSpanId, consumerSpanId.get(),
                "Without consumer group, spanId should be the original publish spanId (no child span)");
    }

    @Test
    void consumerGroupProcessing_withoutTraceparent_generatesNewTrace(
            VertxTestContext testContext) throws Exception {
        logger.info("Test: consumer group processing without traceparent generates new trace");
        // Message with no traceparent header should generate new trace AND child span
        ((OutboxConsumer<String>) consumer).setConsumerGroupName("analytics-group");

        AtomicReference<String> consumerTraceId = new AtomicReference<>();
        AtomicReference<String> consumerSpanId = new AtomicReference<>();
        AtomicReference<String> consumerGroupMdc = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
            consumerTraceId.set(MDC.get("traceId"));
            consumerSpanId.set(MDC.get("spanId"));
            consumerGroupMdc.set(MDC.get("consumerGroup"));
            messageReceived.flag();
            return Future.succeededFuture();
        });

        producer.send("no-traceparent-test").onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));

        // Should have generated a trace context
        assertNotNull(consumerTraceId.get(), "traceId should be auto-generated");
        assertNotNull(consumerSpanId.get(), "spanId should be present");
        assertEquals("analytics-group", consumerGroupMdc.get(),
                "consumerGroup should appear in MDC even for auto-generated traces");
    }
}
