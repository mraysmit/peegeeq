package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.mars.peegeeq.test.vertx.TestCleanup;

/**
 * Integration test demonstrating distributed tracing with W3C Trace Context.
 * <p>
 * This test shows how trace context (traceId, spanId, correlationId) is automatically
 * propagated from producer to consumer through message headers and MDC.
 * <p>
 * <b>Documentation:</b>
 * <ul>
 *   <li><a href="../../../../../../../docs/DISTRIBUTED_TRACING_GUIDE.md">Distributed Tracing Guide</a> - Complete guide with examples and best practices</li>
 *   <li><a href="../../../../../../../docs/UNDERSTANDING_BLANK_TRACE_IDS.md">Understanding Blank Trace IDs</a> - Why some logs have blank trace IDs</li>
 *   <li><a href="../../../../../../../docs/MDC_DISTRIBUTED_TRACING.md">MDC Distributed Tracing</a> - Technical API reference</li>
 * </ul>
 * <p>
 * <b>What This Test Demonstrates:</b>
 * <ul>
 *   <li>Generating W3C Trace Context IDs (traceId, spanId)</li>
 *   <li>Sending messages with trace headers (traceparent, correlationId)</li>
 *   <li>Automatic MDC population in consumer</li>
 *   <li>Trace context propagation verification</li>
 *   <li>Logs showing populated trace IDs during message processing</li>
 * </ul>
 * <p>
 * <b>Expected Behavior:</b>
 * <ul>
 *   <li>Initialization logs have blank trace IDs (normal and expected)</li>
 *   <li>Consumer logs during message processing have populated trace IDs</li>
 *   <li>All logs within message handler automatically include trace context</li>
 * </ul>
 */
@Testcontainers
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
class DistributedTracingTest {
    private static final Logger logger = LoggerFactory.getLogger(DistributedTracingTest.class);


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
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        testTopic = "tracing-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> testContext.verify(() -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);

                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumer = outboxFactory.createConsumer(testTopic, String.class);
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        Future<Void> factoryClose = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        factoryClose
                .transform(TestCleanup.cleanup(() -> manager != null
                        ? manager.closeReactive()
                        : Future.succeededFuture()))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testDistributedTracingWithW3CTraceContext(VertxTestContext testContext) {
        logger.info("Test: distributed tracing with w3 c trace context");
        // Generate W3C Trace Context IDs
        String traceId = generateTraceId();
        String spanId = generateSpanId();
        String correlationId = "order-" + UUID.randomUUID();

        // Create W3C traceparent header
        String traceparent = String.format("00-%s-%s-01", traceId, spanId);

        // Prepare message headers with trace context
        Map<String, String> headers = Map.of(
            "traceparent", traceparent,
            "correlationId", correlationId,
            "source", "distributed-tracing-test"
        );

        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = testContext.checkpoint();

        // The trace context is in the MDC while the handler runs.
        consumer.subscribe(message -> {
                    testContext.verify(() -> {
                        assertEquals(traceId, MDC.get("traceId"), "Trace ID should be propagated to consumer");
                        assertEquals(spanId, MDC.get("spanId"), "Span ID should be propagated to consumer");
                        assertEquals(correlationId, MDC.get("correlationId"), "Correlation ID should be propagated to consumer");
                    });
                    messageReceived.flag();
                    return Future.succeededFuture();
                })
                .compose(v -> producer.send("test-payload-with-tracing", headers, correlationId).mapEmpty())
                .onFailure(testContext::failNow);
    }

    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    void testAutomaticTraceGenerationForMissingHeaders(VertxTestContext testContext) {
        logger.info("Test: automatic trace generation for missing headers");
        // A strict checkpoint: a second delivery fails the test.
        Checkpoint messageReceived = testContext.checkpoint();

        consumer.subscribe(message -> {
                    String traceId = MDC.get("traceId");
                    testContext.verify(() -> {
                        assertNotNull(traceId, "Trace ID should be automatically generated");
                        assertFalse(traceId.isEmpty(), "Trace ID should not be empty");
                    });
                    messageReceived.flag();
                    return Future.succeededFuture();
                })
                .compose(v -> producer.send("payload-with-no-headers").mapEmpty())
                .onFailure(testContext::failNow);
    }

    /**
     * Generate a W3C Trace Context trace-id (32 hex characters).
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Generate a W3C Trace Context span-id (16 hex characters).
     */
    private String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}


