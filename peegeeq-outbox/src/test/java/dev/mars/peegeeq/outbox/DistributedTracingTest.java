package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

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
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15-alpine");
        container.withDatabaseName("testdb");
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "tracing-test-" + UUID.randomUUID().toString().substring(0, 8);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("tracing-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);

        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);

        logger.info("=== Test setup complete for topic: {} ===", testTopic);
    }

    @AfterEach
    void tearDown() throws Exception {
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
            CountDownLatch closeLatch = new CountDownLatch(1);
            manager.closeReactive().onComplete(ar -> closeLatch.countDown());
            closeLatch.await(10, TimeUnit.SECONDS);
        }
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
    }

    @Test
    void testDistributedTracingWithW3CTraceContext(Vertx vertx, VertxTestContext testContext) throws Exception {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== Testing Distributed Tracing with W3C Trace Context ===");
        System.out.println("=".repeat(80));

        // Generate W3C Trace Context IDs
        String traceId = generateTraceId();
        String spanId = generateSpanId();
        String correlationId = "order-" + UUID.randomUUID();

        System.out.println("\n📋 Generated trace context:");
        System.out.println("  🔍 traceId:       " + traceId);
        System.out.println("  🔍 spanId:        " + spanId);
        System.out.println("  🔍 correlationId: " + correlationId);

        // Create W3C traceparent header
        String traceparent = String.format("00-%s-%s-01", traceId, spanId);
        System.out.println("  🔍 traceparent:   " + traceparent);

        // Prepare message headers with trace context
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", traceparent);
        headers.put("correlationId", correlationId);
        headers.put("source", "distributed-tracing-test");

        // Capture trace context from consumer
        AtomicReference<String> consumerTraceId = new AtomicReference<>();
        AtomicReference<String> consumerSpanId = new AtomicReference<>();
        AtomicReference<String> consumerCorrelationId = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        // Subscribe consumer
        consumer.subscribe(message -> {
            // Capture MDC values set by the consumer
            consumerTraceId.set(MDC.get("traceId"));
            consumerSpanId.set(MDC.get("spanId"));
            consumerCorrelationId.set(MDC.get("correlationId"));

            System.out.println("\n📨 Consumer received message with trace context:");
            System.out.println("  traceId from MDC:       " + consumerTraceId.get());
            System.out.println("  spanId from MDC:        " + consumerSpanId.get());
            System.out.println("  correlationId from MDC: " + consumerCorrelationId.get());

            // Log with the logger to show MDC in action
            logger.info("Processing message - this log should show trace IDs!");
            logger.debug("Message payload: {}", message.getPayload());
            logger.info("Simulating business logic processing...");
            logger.info("Message processing complete!");

            messageReceived.flag();
            return Future.succeededFuture();
        });

        // Send message with trace context
        // Use the 3-parameter send method to explicitly set correlation ID
        System.out.println("\n📤 Sending message with trace context...");
        CountDownLatch traceSendLatch = new CountDownLatch(1);
        producer.send("test-payload-with-tracing", headers, correlationId).onComplete(ar -> traceSendLatch.countDown());
        assertTrue(traceSendLatch.await(5, TimeUnit.SECONDS), "Send should complete");
        System.out.println("Message sent successfully");

        // Wait for consumer to process
        System.out.println("\n⏳ Waiting for consumer to process message...");
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Consumer should receive message");

        // Give a moment for all logs to flush
        CountDownLatch flushLatch = new CountDownLatch(1);
        vertx.timer(500).onComplete(ar -> flushLatch.countDown());
        assertTrue(flushLatch.await(5, TimeUnit.SECONDS), "Flush timer should complete");

        // Verify trace context was propagated
        System.out.println("\n🔍 Verifying trace context propagation:");
        assertEquals(traceId, consumerTraceId.get(), "Trace ID should be propagated to consumer");
        System.out.println("  Trace ID matches: " + traceId);

        assertEquals(spanId, consumerSpanId.get(), "Span ID should be propagated to consumer");
        System.out.println("  Span ID matches: " + spanId);

        assertEquals(correlationId, consumerCorrelationId.get(), "Correlation ID should be propagated to consumer");
        System.out.println("  Correlation ID matches: " + correlationId);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("DISTRIBUTED TRACING TEST PASSED!");
        System.out.println("   Trace context successfully propagated from producer to consumer");
        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    void testAutomaticTraceGenerationForMissingHeaders(Vertx vertx, VertxTestContext testContext) throws Exception {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("=== Testing Automatic Trace Generation for Missing Headers ===");
        System.out.println("=".repeat(80));

        // Prepare context capture
        AtomicReference<String> consumerTraceId = new AtomicReference<>();
        AtomicReference<String> consumerSpanId = new AtomicReference<>();
        Checkpoint messageReceived = testContext.checkpoint();

        // Subscribe consumer
        consumer.subscribe(message -> {
            // Capture MDC values - THESE SHOULD BE AUTOMATICALLY GENERATED NOW
            consumerTraceId.set(MDC.get("traceId"));
            consumerSpanId.set(MDC.get("spanId"));

            System.out.println("\n📨 Consumer received message (originally without headers):");
            System.out.println("  traceId from MDC: " + consumerTraceId.get());
            System.out.println("  spanId from MDC:  " + consumerSpanId.get());

            // Log with logger
            logger.info("Processing message with auto-generated trace context");

            messageReceived.flag();
            return Future.succeededFuture();
        });

        // Send message WITHOUT ANY TRACE HEADERS
        System.out.println("\n📤 Sending message with NO trace context...");
        CountDownLatch noTraceSendLatch = new CountDownLatch(1);
        producer.send("payload-with-no-headers").onComplete(ar -> noTraceSendLatch.countDown());
        assertTrue(noTraceSendLatch.await(5, TimeUnit.SECONDS), "Send should complete");

        // Wait for consumer
        System.out.println("\n⏳ Waiting for consumer...");
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Consumer should receive message");

        // Verify
        System.out.println("\n🔍 Verifying automatic trace generation:");
        assertNotNull(consumerTraceId.get(), "Trace ID should be automatically generated");
        assertFalse(consumerTraceId.get().isEmpty(), "Trace ID should not be empty");
        
        System.out.println("  Auto-generated Trace ID found: " + consumerTraceId.get());
        System.out.println("  Test confirmed: detailed logs are now ensured even for untraced messages.");

        System.out.println("\n" + "=".repeat(80));
        System.out.println("AUTOMATIC TRACE GENERATION TEST PASSED!");
        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * Generate a W3C Trace Context trace-id (32 hex characters).
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "") + 
               UUID.randomUUID().toString().replace("-", "").substring(0, 32 - 32);
    }

    /**
     * Generate a W3C Trace Context span-id (16 hex characters).
     */
    private String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}



