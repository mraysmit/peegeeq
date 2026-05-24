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
    void setUp() throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "group-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumerGroup = outboxFactory.createConsumerGroup("test-group", testTopic, String.class);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        if (consumerGroup != null) {
            consumerGroup.stop()
                .compose(v -> consumerGroup.close())
                .onFailure(e -> logger.warn("consumerGroup stop/close failed in tearDown", e));
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
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

        consumerGroup.start();

        // Give group workers a brief moment to fully subscribe before publishing.
        vertx.timer(300).await();

        for (int i = 0; i < messageCount; i++) {
            producer.send("Message-" + i).onFailure(testContext::failNow);
        }

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
        Checkpoint latch = testContext.checkpoint(messageCount / 2); // Expect half
        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

        consumerGroup.setGroupFilter(msg -> msg.getPayload().startsWith("Keep"));

        consumerGroup.addConsumer("member-1", message -> {
        logger.info("Test: group filtering");
            receivedMessages.add(message.getPayload());
            latch.flag();
            return Future.succeededFuture();
        });

        consumerGroup.start();

        for (int i = 0; i < messageCount; i++) {
            if (i % 2 == 0) {
                producer.send("Keep-" + i);
            } else {
                producer.send("Drop-" + i);
            }
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Did not receive expected messages");
        
        // Wait a bit more to ensure no dropped messages are received
        vertx.timer(1000).await();

        assertEquals(messageCount / 2, receivedMessages.size());
        assertTrue(receivedMessages.stream().allMatch(s -> s.startsWith("Keep")));
        
        // With the current retry/DLQ handling for rejected messages, each filtered message
        // is re-polled on retries so totalMessagesFiltered may exceed messageCount/2.
        assertTrue(consumerGroup.getStats().getTotalMessagesFiltered() >= messageCount / 2,
            "At least " + (messageCount / 2) + " messages should have been filtered, got: " +
            consumerGroup.getStats().getTotalMessagesFiltered());
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

        consumerGroup.start();

        for (int i = 0; i < messageCount / 2; i++) {
            producer.send("Message-A-" + i);
            producer.send("Message-B-" + i);
        }

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
        
        consumerGroup.start();
        
        producer.send("B");
        
        // Wait to ensure it's processed (and filtered)
        vertx.timer(2000).await();
        
        assertEquals(0, processedCount.get(), "Message should not have been processed");
        // With no-eligible-consumer handling (MessageFilteredException → reset to PENDING),
        // the message may be polled and filtered multiple times before the test checks.
        assertTrue(consumerGroup.getStats().getTotalMessagesFiltered() >= 1,
            "At least 1 message should have been filtered, got: " +
            consumerGroup.getStats().getTotalMessagesFiltered());
        testContext.completeNow();
    }
    
    @Test
    void testDynamicMemberManagement(io.vertx.core.Vertx vertx, VertxTestContext testContext) throws Exception {
        io.vertx.core.Promise<Void> signal1 = io.vertx.core.Promise.promise();
        consumerGroup.addConsumer("member-1", message -> {
        logger.info("Test: dynamic member management");
            signal1.tryComplete();
            return Future.succeededFuture();
        });
        
        consumerGroup.start();
        
        producer.send("Msg1").onFailure(testContext::failNow);
        signal1.future().await();
        
        // Remove member
        consumerGroup.removeConsumer("member-1");
        assertEquals(0, consumerGroup.getActiveConsumerCount());
        
        // Add new member
        io.vertx.core.Promise<Void> signal2 = io.vertx.core.Promise.promise();
        consumerGroup.addConsumer("member-2", message -> {
            signal2.tryComplete();
            return Future.succeededFuture();
        });
        
        assertEquals(1, consumerGroup.getActiveConsumerCount());
        
        producer.send("Msg2").onFailure(testContext::failNow);
        signal2.future().await();
        testContext.completeNow();
    }
}


