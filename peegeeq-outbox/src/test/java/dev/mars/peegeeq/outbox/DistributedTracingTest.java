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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    void setUp() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "tracing-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);

        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
    }

    @Test
    void testDistributedTracingWithW3CTraceContext(VertxTestContext testContext) throws Exception {
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

        // Capture trace context from consumer
        AtomicReference<String> consumerTraceId = new AtomicReference<>();
        AtomicReference<String> consumerSpanId = new AtomicReference<>();
        AtomicReference<String> consumerCorrelationId = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        // Subscribe consumer
        consumer.subscribe(message -> {
            consumerTraceId.set(MDC.get("traceId"));
            consumerSpanId.set(MDC.get("spanId"));
            consumerCorrelationId.set(MDC.get("correlationId"));

            messageReceived.flag();
            return Future.succeededFuture();
        });

        // Send message with trace context
        producer.send("test-payload-with-tracing", headers, correlationId).await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Consumer should receive message");

        // Verify trace context was propagated
        assertEquals(traceId, consumerTraceId.get(), "Trace ID should be propagated to consumer");
        assertEquals(spanId, consumerSpanId.get(), "Span ID should be propagated to consumer");
        assertEquals(correlationId, consumerCorrelationId.get(), "Correlation ID should be propagated to consumer");
    }

    @Test
    void testAutomaticTraceGenerationForMissingHeaders(VertxTestContext testContext) throws Exception {
        logger.info("Test: automatic trace generation for missing headers");
        AtomicReference<String> consumerTraceId = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        // Subscribe consumer
        consumer.subscribe(message -> {
            consumerTraceId.set(MDC.get("traceId"));

            messageReceived.flag();
            return Future.succeededFuture();
        });

        // Send message without any trace headers
        producer.send("payload-with-no-headers").await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Consumer should receive message");

        assertNotNull(consumerTraceId.get(), "Trace ID should be automatically generated");
        assertFalse(consumerTraceId.get().isEmpty(), "Trace ID should not be empty");
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



