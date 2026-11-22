/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.peegeeq.examples.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxConsumerConfig;
import dev.mars.peegeeq.outbox.OutboxFactory;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.vertx.TestCleanup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import java.util.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for server-side filtering in the Outbox module.
 * Tests that messages are filtered at the database level using JSONB header queries.
 *
 * <p>Each test sends its messages one after another, with every non-matching message
 * before the last matching one. The outbox claim takes PENDING rows in insertion order,
 * so a filter that let a non-matching message through would claim it before the last
 * matching message is delivered. Once the expected messages have arrived, the test
 * therefore asserts that the non-matching rows are still PENDING.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxServerSideFilteringTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxServerSideFilteringTest.class);

    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private Pool pool;

    /** One message to send: its payload and headers. */
    private record Outgoing(String payload, Map<String, String> headers) {}

    @BeforeEach
    void setUp(VertxTestContext ctx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("=== Setting up OutboxServerSideFilteringTest ===");
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                var databaseService = new PgDatabaseService(manager);
                pool = databaseService.getPool();
                QueueFactoryProvider provider = new PgQueueFactoryProvider();
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                outboxFactory = (OutboxFactory) provider.createFactory("outbox", databaseService);
                logger.info("Outbox server-side filtering test setup completed");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        logger.info("=== Tearing down OutboxServerSideFilteringTest ===");
        // The factory closes the consumers and producers it created. The manager closes after the
        // factory close has settled, and the first failure fails the teardown.
        TestCleanup.run(
                () -> outboxFactory != null ? outboxFactory.close() : Future.<Void>succeededFuture(),
                () -> manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
            .onSuccess(v -> {
                logger.info("Outbox server-side filtering test teardown completed");
                ctx.completeNow();
            })
            .onFailure(err -> {
                logger.error("Error during teardown", err);
                ctx.failNow(err);
            });
    }

    @Test
    @DisplayName("Outbox server-side filter: headerEquals delivers only matching messages")
    @io.vertx.junit5.Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testOutboxServerSideFilterEquals(VertxTestContext testContext) {
        logger.info("=== Testing Outbox Server-Side Filter EQUALS ===");
        final int expected = 2;
        String topic = "outbox-filter-equals-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        ServerSideFilter filter = ServerSideFilter.headerEquals("type", "ORDER");
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Promise<Void> allExpectedReceived = Promise.promise();

        consumer.subscribe(recordingHandler(testContext, expected, receivedMessages, allExpectedReceived))
            .compose(ready -> sendInOrder(producer, List.of(
                new Outgoing("Order 1", Map.of("type", "ORDER")),
                new Outgoing("Payment 1", Map.of("type", "PAYMENT")),
                new Outgoing("Payment 2", Map.of("type", "PAYMENT")),
                new Outgoing("Order 2", Map.of("type", "ORDER")))))
            .compose(sent -> allExpectedReceived.future())
            .compose(received -> countPendingRows(topic))
            .onSuccess(pending -> testContext.verify(() -> {
                Assertions.assertEquals(2L, pending, "The 2 PAYMENT rows should remain unclaimed");
                Assertions.assertEquals(expected, receivedMessages.size());
                Assertions.assertTrue(receivedMessages.stream().allMatch(m -> m.startsWith("Order")),
                    "All received messages should be ORDER type");
                consumer.close();
                producer.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Outbox server-side filter: headerIn delivers only set-matching messages")
    @io.vertx.junit5.Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testOutboxServerSideFilterIn(VertxTestContext testContext) {
        logger.info("=== Testing Outbox Server-Side Filter IN ===");
        final int expected = 3;
        String topic = "outbox-filter-in-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        ServerSideFilter filter = ServerSideFilter.headerIn("type", Set.of("ORDER", "REFUND"));
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Promise<Void> allExpectedReceived = Promise.promise();

        consumer.subscribe(recordingHandler(testContext, expected, receivedMessages, allExpectedReceived))
            .compose(ready -> sendInOrder(producer, List.of(
                new Outgoing("Order 1", Map.of("type", "ORDER")),
                new Outgoing("Payment 1", Map.of("type", "PAYMENT")),
                new Outgoing("Refund 1", Map.of("type", "REFUND")),
                new Outgoing("Order 2", Map.of("type", "ORDER")))))
            .compose(sent -> allExpectedReceived.future())
            .compose(received -> countPendingRows(topic))
            .onSuccess(pending -> testContext.verify(() -> {
                Assertions.assertEquals(1L, pending, "The PAYMENT row should remain unclaimed");
                Assertions.assertEquals(expected, receivedMessages.size());
                Assertions.assertFalse(receivedMessages.contains("Payment 1"), "PAYMENT should not be delivered");
                consumer.close();
                producer.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Outbox server-side filter: AND composition delivers only conjunction-matching messages")
    @io.vertx.junit5.Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testOutboxServerSideFilterAnd(VertxTestContext testContext) {
        logger.info("=== Testing Outbox Server-Side Filter AND ===");
        final int expected = 1;
        String topic = "outbox-filter-and-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        ServerSideFilter filter = ServerSideFilter.and(
            ServerSideFilter.headerEquals("type", "ORDER"),
            ServerSideFilter.headerEquals("priority", "HIGH")
        );
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Promise<Void> allExpectedReceived = Promise.promise();

        consumer.subscribe(recordingHandler(testContext, expected, receivedMessages, allExpectedReceived))
            .compose(ready -> sendInOrder(producer, List.of(
                new Outgoing("Order Low", Map.of("type", "ORDER", "priority", "LOW")),
                new Outgoing("Payment High", Map.of("type", "PAYMENT", "priority", "HIGH")),
                new Outgoing("Order High", Map.of("type", "ORDER", "priority", "HIGH")))))
            .compose(sent -> allExpectedReceived.future())
            .compose(received -> countPendingRows(topic))
            .onSuccess(pending -> testContext.verify(() -> {
                Assertions.assertEquals(2L, pending, "The 2 rows that fail the AND should remain unclaimed");
                Assertions.assertEquals(List.of("Order High"), receivedMessages);
                consumer.close();
                producer.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Outbox server-side filter: NULL-safe  messages missing the filter header are excluded")
    @io.vertx.junit5.Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
    void testOutboxServerSideFilterMissingHeader(VertxTestContext testContext) {
        logger.info("=== Testing Outbox Server-Side Filter with Missing Headers ===");
        final int expected = 1;
        String topic = "outbox-filter-missing-" + System.currentTimeMillis();

        MessageProducer<String> producer = outboxFactory.createProducer(topic, String.class);

        ServerSideFilter filter = ServerSideFilter.headerEquals("type", "ORDER");
        OutboxConsumerConfig config = OutboxConsumerConfig.builder()
            .serverSideFilter(filter)
            .build();

        MessageConsumer<String> consumer = outboxFactory.createConsumer(topic, String.class, config);

        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        Promise<Void> allExpectedReceived = Promise.promise();

        consumer.subscribe(recordingHandler(testContext, expected, receivedMessages, allExpectedReceived))
            .compose(ready -> sendInOrder(producer, List.of(
                new Outgoing("No Type Header 1", Map.of("other", "value")),
                new Outgoing("No Type Header 2", Map.of()),
                new Outgoing("Order With Type", Map.of("type", "ORDER")))))
            .compose(sent -> allExpectedReceived.future())
            .compose(received -> countPendingRows(topic))
            .onSuccess(pending -> testContext.verify(() -> {
                Assertions.assertEquals(2L, pending, "The 2 rows without a type header should remain unclaimed");
                Assertions.assertEquals(List.of("Order With Type"), receivedMessages);
                consumer.close();
                producer.close();
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    /**
     * Records each delivered payload. Completes {@code allExpectedReceived} at the
     * {@code expected}-th delivery and fails the test on any delivery after it.
     */
    private static MessageHandler<String> recordingHandler(VertxTestContext testContext, int expected,
            List<String> receivedMessages, Promise<Void> allExpectedReceived) {
        AtomicInteger seen = new AtomicInteger();
        return message -> {
            logger.info("Received filtered message: {} with headers: {}", message.getPayload(), message.getHeaders());
            receivedMessages.add(message.getPayload());
            int n = seen.incrementAndGet();
            if (n > expected) {
                testContext.failNow(new AssertionError("Unexpected extra message after filter: " + message.getPayload()));
            } else if (n == expected) {
                allExpectedReceived.tryComplete();
            }
            return Future.succeededFuture();
        };
    }

    /** Sends the messages one after another, so their outbox rows are created in list order. */
    private static Future<Void> sendInOrder(MessageProducer<String> producer, List<Outgoing> messages) {
        Future<Void> chain = Future.succeededFuture();
        for (Outgoing message : messages) {
            chain = chain.compose(v -> producer.send(message.payload(), message.headers()));
        }
        return chain;
    }

    /** Counts the outbox rows of {@code topic} that no consumer has claimed. */
    private Future<Long> countPendingRows(String topic) {
        return pool.preparedQuery("SELECT COUNT(*) FROM " + PostgreSQLTestConstants.TEST_SCHEMA
                + ".outbox WHERE topic = $1 AND status = 'PENDING'")
            .execute(Tuple.of(topic))
            .map(rows -> rows.iterator().next().getLong(0));
    }
}
