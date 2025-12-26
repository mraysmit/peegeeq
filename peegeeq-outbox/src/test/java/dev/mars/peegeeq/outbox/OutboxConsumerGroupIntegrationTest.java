package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.ConsumerGroupMember;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxConsumerGroupIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private ConsumerGroup<String> consumerGroup;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        System.err.println("=== OutboxConsumerGroupIntegrationTest SETUP STARTED ===");
        
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "group-test-" + UUID.randomUUID().toString().substring(0, 8);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("group-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumerGroup = outboxFactory.createConsumerGroup("test-group", testTopic, String.class);
        
        System.err.println("=== OutboxConsumerGroupIntegrationTest SETUP COMPLETED ===");
    }

    @AfterEach
    void tearDown() throws Exception {
        System.err.println("=== OutboxConsumerGroupIntegrationTest TEARDOWN STARTED ===");
        if (consumerGroup != null) {
            consumerGroup.stop();
            consumerGroup.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.stop();
            manager.close();
        }
        System.err.println("=== OutboxConsumerGroupIntegrationTest TEARDOWN COMPLETED ===");
    }

    @Test
    void testGroupDistribution() throws Exception {
        int messageCount = 20;
        CountDownLatch latch = new CountDownLatch(messageCount);
        List<String> member1Messages = Collections.synchronizedList(new ArrayList<>());
        List<String> member2Messages = Collections.synchronizedList(new ArrayList<>());

        ConsumerGroupMember<String> member1 = consumerGroup.addConsumer("member-1", message -> {
            member1Messages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        ConsumerGroupMember<String> member2 = consumerGroup.addConsumer("member-2", message -> {
            member2Messages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        consumerGroup.start();

        for (int i = 0; i < messageCount; i++) {
            producer.send("Message-" + i);
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Did not receive all messages");

        System.out.println("Member 1 received: " + member1Messages.size());
        System.out.println("Member 2 received: " + member2Messages.size());

        assertFalse(member1Messages.isEmpty(), "Member 1 should have received messages");
        assertFalse(member2Messages.isEmpty(), "Member 2 should have received messages");
        assertEquals(messageCount, member1Messages.size() + member2Messages.size(), "Total messages should match");
    }

    @Test
    void testGroupFiltering() throws Exception {
        int messageCount = 10;
        CountDownLatch latch = new CountDownLatch(messageCount / 2); // Expect half
        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

        consumerGroup.setGroupFilter(msg -> msg.getPayload().startsWith("Keep"));

        consumerGroup.addConsumer("member-1", message -> {
            receivedMessages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        consumerGroup.start();

        for (int i = 0; i < messageCount; i++) {
            if (i % 2 == 0) {
                producer.send("Keep-" + i);
            } else {
                producer.send("Drop-" + i);
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Did not receive expected messages");
        
        // Wait a bit more to ensure no dropped messages are received
        Thread.sleep(1000);

        assertEquals(messageCount / 2, receivedMessages.size());
        assertTrue(receivedMessages.stream().allMatch(s -> s.startsWith("Keep")));
        
        assertEquals(messageCount / 2, consumerGroup.getStats().getTotalMessagesFiltered());
    }

    @Test
    void testMemberFiltering() throws Exception {
        int messageCount = 10;
        CountDownLatch latch = new CountDownLatch(messageCount);
        List<String> member1Messages = Collections.synchronizedList(new ArrayList<>());
        List<String> member2Messages = Collections.synchronizedList(new ArrayList<>());

        consumerGroup.addConsumer("member-A", message -> {
            member1Messages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        }, msg -> msg.getPayload().contains("-A-"));

        consumerGroup.addConsumer("member-B", message -> {
            member2Messages.add(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        }, msg -> msg.getPayload().contains("-B-"));

        consumerGroup.start();

        for (int i = 0; i < messageCount / 2; i++) {
            producer.send("Message-A-" + i);
            producer.send("Message-B-" + i);
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Did not receive all messages");

        assertEquals(messageCount / 2, member1Messages.size());
        assertEquals(messageCount / 2, member2Messages.size());
        assertTrue(member1Messages.stream().allMatch(s -> s.contains("-A-")));
        assertTrue(member2Messages.stream().allMatch(s -> s.contains("-B-")));
    }
    
    @Test
    void testNoEligibleConsumer() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        
        // Member only accepts "A"
        consumerGroup.addConsumer("member-A", message -> {
            latch.countDown(); // Should not happen
            return CompletableFuture.completedFuture(null);
        }, msg -> msg.getPayload().equals("A"));
        
        consumerGroup.start();
        
        producer.send("B");
        
        // Wait to ensure it's processed (and filtered)
        Thread.sleep(2000);
        
        assertEquals(1, latch.getCount(), "Message should not have been processed");
        assertEquals(1, consumerGroup.getStats().getTotalMessagesFiltered());
    }
    
    @Test
    void testDynamicMemberManagement() throws Exception {
        CountDownLatch latch1 = new CountDownLatch(1);
        consumerGroup.addConsumer("member-1", message -> {
            latch1.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        consumerGroup.start();
        
        producer.send("Msg1");
        assertTrue(latch1.await(5, TimeUnit.SECONDS));
        
        // Remove member
        consumerGroup.removeConsumer("member-1");
        assertEquals(0, consumerGroup.getActiveConsumerCount());
        
        // Add new member
        CountDownLatch latch2 = new CountDownLatch(1);
        consumerGroup.addConsumer("member-2", message -> {
            latch2.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        assertEquals(1, consumerGroup.getActiveConsumerCount());
        
        producer.send("Msg2");
        assertTrue(latch2.await(5, TimeUnit.SECONDS));
    }
}
