package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the null handler return path in OutboxConsumer.processMessageWithCompletion.
 * When a handler returns null, the consumer treats it as a failed future and
 * routes through retry/failure handling.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerNullHandlerTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerNullHandlerTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        testTopic = "null-handler-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
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
    void tearDown(VertxTestContext tearDownContext) throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (manager != null) {
            (outboxFactory != null ? outboxFactory.close() : Future.succeededFuture())
                    .eventually(() -> manager.closeReactive())
                    .onSuccess(v -> tearDownContext.completeNow())
                    .onFailure(tearDownContext::failNow);
            assertTrue(tearDownContext.awaitCompletion(10, TimeUnit.SECONDS));
        } else {
            tearDownContext.completeNow();
        }
    }

    @Test
    void testNullHandlerReturn(VertxTestContext testContext) throws Exception {
        // Verify the null-return error path by confirming the message is retried:
        // null return  IllegalStateException  incrementRetryAndReset  retry_count incremented,
        // status reset to PENDING  message re-delivered on next poll.
        // Two handler invocations prove the retry cycle ran end-to-end.
        AtomicInteger invocationCount = new AtomicInteger(0);
        Checkpoint retried = testContext.checkpoint();

        consumer.subscribe(message -> {
            logger.error("===== INTENTIONAL ERROR TEST ===== The next WARN log ('Message handler returned null Future') is EXPECTED");
            if (invocationCount.incrementAndGet() == 2) {
                retried.flag();
            }
            return null; // Triggers null check  IllegalStateException  retry path
        }).compose(v -> producer.send("Test null return"))
          .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
                "Handler should be invoked twice: once initially, once after null-return retry cycle");
    }
}


