package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerGroupIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerGroupIntegrationTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private ConsumerGroup<String> consumerGroup;
    private String testTopic;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        testTopic = "group-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumerGroup = outboxFactory.createConsumerGroup("test-group", testTopic, String.class);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        logger.info("Tearing down: closing factory resources and manager");
        Future<Void> closeFactory = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        closeFactory
                .transform(factoryResult -> {
                    Future<Void> closeManager = manager != null
                            ? manager.closeReactive()
                            : Future.succeededFuture();
                    return closeManager.transform(managerResult -> {
                        if (factoryResult.failed()) {
                            return Future.failedFuture(factoryResult.cause());
                        }
                        if (managerResult.failed()) {
                            return Future.failedFuture(managerResult.cause());
                        }
                        return Future.succeededFuture();
                    });
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    void testGroupDistribution(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 20;
        Checkpoint latch = testContext.checkpoint(messageCount);
        List<String> member1Messages = Collections.synchronizedList(new ArrayList<>());
        List<String> member2Messages = Collections.synchronizedList(new ArrayList<>());

        ConsumerGroupMember<String> member1 = consumerGroup.addConsumer("member-1", message -> {
        logger.info("Test: group distribution");
            member1Messages.add(message.getPayload());
            latch.flag();
            return Future.succeededFuture();
        });

        ConsumerGroupMember<String> member2 = consumerGroup.addConsumer("member-2", message -> {
            member2Messages.add(message.getPayload());
            latch.flag();
            return Future.succeededFuture();
        });

        consumerGroup.start()
                .compose(v -> {
                    List<Future<Void>> sends = new ArrayList<>(messageCount);
                    for (int i = 0; i < messageCount; i++) {
                        sends.add(producer.send("Message-" + i));
                    }
                    return Future.all(sends).mapEmpty();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(20, TimeUnit.SECONDS), "Did not receive all messages");

        logger.info("Member 1 received: {}", member1Messages.size());
        logger.info("Member 2 received: {}", member2Messages.size());

        assertFalse(member1Messages.isEmpty(), "Member 1 should have received messages");
        assertFalse(member2Messages.isEmpty(), "Member 2 should have received messages");
        assertEquals(messageCount, member1Messages.size() + member2Messages.size(), "Total messages should match");
    }

    @Test
    void testGroupFiltering(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 10;
        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

        consumerGroup.setGroupFilter(msg -> msg.getPayload().startsWith("Keep"));

        consumerGroup.addConsumer("member-1", message -> {
            logger.info("Test: group filtering");
            receivedMessages.add(message.getPayload());
            return Future.succeededFuture();
        });

        consumerGroup.start()
                .compose(v -> {
                    List<Future<Void>> sends = new ArrayList<>(messageCount);
                    for (int i = 0; i < messageCount; i++) {
                        sends.add(producer.send(i % 2 == 0 ? "Keep-" + i : "Drop-" + i));
                    }
                    return Future.all(sends).mapEmpty();
                })
                .compose(v -> awaitCondition(vertx,
                        () -> receivedMessages.size() == messageCount / 2
                                && consumerGroup.getStats().getTotalMessagesFiltered() >= messageCount / 2,
                        System.currentTimeMillis() + 30_000,
                        "Expected accepted and filtered messages were not both observed"))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(messageCount / 2, receivedMessages.size());
                    assertTrue(receivedMessages.stream().allMatch(s -> s.startsWith("Keep")));
                    assertTrue(consumerGroup.getStats().getTotalMessagesFiltered() >= messageCount / 2,
                        "At least " + (messageCount / 2) + " messages should have been filtered, got: " +
                        consumerGroup.getStats().getTotalMessagesFiltered());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }

    @Test
    void testMemberFiltering(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 10;
        Checkpoint latch = testContext.checkpoint(messageCount);
        List<String> member1Messages = Collections.synchronizedList(new ArrayList<>());
        List<String> member2Messages = Collections.synchronizedList(new ArrayList<>());

        consumerGroup.addConsumer("member-A", message -> {
        logger.info("Test: member filtering");
            member1Messages.add(message.getPayload());
            latch.flag();
            return Future.succeededFuture();
        }, msg -> msg.getPayload().contains("-A-"));

        consumerGroup.addConsumer("member-B", message -> {
            member2Messages.add(message.getPayload());
            latch.flag();
            return Future.succeededFuture();
        }, msg -> msg.getPayload().contains("-B-"));

        consumerGroup.start()
                .compose(v -> {
                    List<Future<Void>> sends = new ArrayList<>(messageCount);
                    for (int i = 0; i < messageCount / 2; i++) {
                        sends.add(producer.send("Message-A-" + i));
                        sends.add(producer.send("Message-B-" + i));
                    }
                    return Future.all(sends).mapEmpty();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Did not receive all messages");

        assertEquals(messageCount / 2, member1Messages.size());
        assertEquals(messageCount / 2, member2Messages.size());
        assertTrue(member1Messages.stream().allMatch(s -> s.contains("-A-")));
        assertTrue(member2Messages.stream().allMatch(s -> s.contains("-B-")));
    }
    
    @Test
    void testNoEligibleConsumer(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger processedCount = new AtomicInteger(0);
        
        // Member only accepts "A"
        consumerGroup.addConsumer("member-A", message -> {
        logger.info("Test: no eligible consumer");
            processedCount.incrementAndGet(); // Should not happen
            return Future.succeededFuture();
        }, msg -> msg.getPayload().equals("A"));
        
        consumerGroup.start()
                .compose(v -> producer.send("B"))
                .compose(v -> awaitCondition(vertx,
                        () -> consumerGroup.getStats().getTotalMessagesFiltered() >= 1,
                        System.currentTimeMillis() + 10_000,
                        "No-eligible-consumer filter was not observed"))
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(0, processedCount.get(), "Message should not have been processed");
                    assertTrue(consumerGroup.getStats().getTotalMessagesFiltered() >= 1,
                        "At least 1 message should have been filtered, got: " +
                        consumerGroup.getStats().getTotalMessagesFiltered());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);
    }
    
    @Test
    void testDynamicMemberManagement(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        io.vertx.core.Promise<Void> signal1 = io.vertx.core.Promise.promise();
        consumerGroup.addConsumer("member-1", message -> {
        logger.info("Test: dynamic member management");
            signal1.tryComplete();
            return Future.succeededFuture();
        });
        
        io.vertx.core.Promise<Void> signal2 = io.vertx.core.Promise.promise();
        consumerGroup.start()
                .compose(v -> producer.send("Msg1"))
                .compose(v -> signal1.future())
                .compose(v -> {
                    assertTrue(consumerGroup.removeConsumer("member-1"));
                    assertEquals(0, consumerGroup.getActiveConsumerCount());

                    consumerGroup.addConsumer("member-2", message -> {
                        signal2.tryComplete();
                        return Future.succeededFuture();
                    });
                    assertEquals(1, consumerGroup.getActiveConsumerCount());
                    return producer.send("Msg2");
                })
                .compose(v -> signal2.future())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    private Future<Void> awaitCondition(io.vertx.core.Vertx vertx, Supplier<Boolean> condition,
                                        long deadline, String failureMessage) {
        final boolean ready;
        try {
            ready = condition.get();
        } catch (Throwable error) {
            return Future.failedFuture(error);
        }
        if (ready) {
            return Future.succeededFuture();
        }
        if (System.currentTimeMillis() >= deadline) {
            return Future.failedFuture(new AssertionError(failureMessage));
        }
        return vertx.timer(50)
                .compose(v -> awaitCondition(vertx, condition, deadline, failureMessage));
    }
}

