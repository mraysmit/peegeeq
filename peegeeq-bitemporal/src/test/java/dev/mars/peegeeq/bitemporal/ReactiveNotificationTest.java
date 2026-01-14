package dev.mars.peegeeq.bitemporal;

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;

/**
 * Tests for reactive LISTEN/NOTIFY functionality in PgBiTemporalEventStore.
 * Following peegeeq-native patterns for testing reactive notifications.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-09-07
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class ReactiveNotificationTest {
    
    @Container
    @SuppressWarnings("resource") // Managed by Testcontainers framework
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_notification_test")
            .withUsername("test")
            .withPassword("test");
    
    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private EventStore<TestEvent> eventStore;
    
    /**
     * Test event class.
     */
    public static class TestEvent {
        private final String id;
        private final String data;
        private final int value;
        
        @JsonCreator
        public TestEvent(@JsonProperty("id") String id,
                        @JsonProperty("data") String data,
                        @JsonProperty("value") int value) {
            this.id = id;
            this.data = data;
            this.value = value;
        }
        
        public String getId() { return id; }
        public String getData() { return data; }
        public int getValue() { return value; }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestEvent testEvent = (TestEvent) o;
            return value == testEvent.value &&
                    Objects.equals(id, testEvent.id) &&
                    Objects.equals(data, testEvent.data);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(id, data, value);
        }
        
        @Override
        public String toString() {
            return "TestEvent{" +
                    "id='" + id + '\'' +
                    ", data='" + data + '\'' +
                    ", value=" + value +
                    '}';
        }
    }
    
    @BeforeEach
    void setUp() throws Exception {
        // Set system properties for PeeGeeQ configuration - following peegeeq-native patterns
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Initialize database schema using centralized schema initializer
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        // Configure PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();

        // Initialize PeeGeeQ
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and event store
        factory = new BiTemporalEventStoreFactory(manager);
        eventStore = factory.createEventStore(TestEvent.class);

        // Ensure reactive notification handler is active by triggering pool creation
        // This follows the pattern from working integration tests
        TestEvent warmupEvent = new TestEvent("warmup", "warmup", 1);
        eventStore.append("WarmupEvent", warmupEvent, Instant.now()).get(5, TimeUnit.SECONDS);

        // Give the reactive notification handler time to become active
        Thread.sleep(1000);
    }
    
    @AfterEach
    void tearDown() {
        if (eventStore != null) {
            eventStore.close();
        }
        // Factory doesn't need explicit closing
        if (manager != null) {
            manager.stop();
        }
    }
    
    /**
     * Test reactive notification subscription.
     * 
     * FIXED: Channel name mismatch was fixed in PeeGeeQTestSchemaInitializer (for tests) 
     * and V013__Fix_Bitemporal_Notification_Channel_Names.sql (for production).
     * The trigger now sends to '{schema}_bitemporal_events_{table}' matching the handler.
     */
    @Test
    void testReactiveNotificationSubscription() throws Exception {
        // Test that reactive notification subscription works
        // Following peegeeq-native patterns for testing LISTEN/NOTIFY
        
        CountDownLatch notificationLatch = new CountDownLatch(1);
        AtomicReference<BiTemporalEvent<TestEvent>> receivedEvent = new AtomicReference<>();
        
        // Subscribe to notifications
        MessageHandler<BiTemporalEvent<TestEvent>> handler = message -> {
            receivedEvent.set(message.getPayload());
            notificationLatch.countDown();
            return CompletableFuture.completedFuture(null);
        };
        
        // Subscribe to notifications
        eventStore.subscribe("TestEvent", handler).get(5, TimeUnit.SECONDS);

        // Give subscription time to establish
        Thread.sleep(2000);
        
        // Append an event (this should trigger a notification)
        TestEvent testEvent = new TestEvent("test-1", "notification test", 42);
        BiTemporalEvent<TestEvent> appendedEvent = eventStore.append("TestEvent", testEvent, Instant.now())
            .get(5, TimeUnit.SECONDS);
        
        assertNotNull(appendedEvent);
        assertEquals("TestEvent", appendedEvent.getEventType());
        assertEquals(testEvent, appendedEvent.getPayload());
        
        // Wait for notification (this tests the reactive LISTEN/NOTIFY functionality)
        boolean notificationReceived = notificationLatch.await(10, TimeUnit.SECONDS);
        assertTrue(notificationReceived, "Notification should be received within timeout");

        // Verify the notification content
        BiTemporalEvent<TestEvent> notifiedEvent = receivedEvent.get();
        assertNotNull(notifiedEvent, "Notification handler should have received an event");
        assertEquals(appendedEvent.getEventId(), notifiedEvent.getEventId());
        assertEquals(appendedEvent.getEventType(), notifiedEvent.getEventType());
        assertEquals(appendedEvent.getPayload(), notifiedEvent.getPayload());
    }
    
    /**
     * Test manual NOTIFY trigger with correct channel name.
     * 
     * This test manually sends a NOTIFY to the correct schema-qualified channel name
     * to verify the handler receives notifications independently of the database trigger.
     * 
     * Channel format: {schema}_bitemporal_events_{table}
     * For public schema with bitemporal_event_log table: public_bitemporal_events_bitemporal_event_log
     */
    @Test
    void testManualNotifyTrigger() throws Exception {
        // Test that we can manually trigger a NOTIFY and the handler receives it
        // This tests the reactive notification infrastructure directly
        
        CountDownLatch notificationLatch = new CountDownLatch(1);
        AtomicReference<BiTemporalEvent<TestEvent>> receivedEvent = new AtomicReference<>();
        
        // Subscribe to notifications
        MessageHandler<BiTemporalEvent<TestEvent>> handler = message -> {
            receivedEvent.set(message.getPayload());
            notificationLatch.countDown();
            return CompletableFuture.completedFuture(null);
        };
        
        eventStore.subscribe("TestEvent", handler).get(5, TimeUnit.SECONDS);
        
        // Give subscription time to establish
        Thread.sleep(1000);
        
        // First, append an event to have something in the database
        TestEvent testEvent = new TestEvent("manual-test", "manual notification test", 123);
        BiTemporalEvent<TestEvent> appendedEvent = eventStore.append("TestEvent", testEvent, Instant.now())
            .get(5, TimeUnit.SECONDS);
        
        // Manually send a NOTIFY message using pure Vert.x (simulating what the database trigger would do)
        var dbConfig = manager.getConfiguration().getDatabaseConfig();
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(dbConfig.getHost())
            .setPort(dbConfig.getPort())
            .setDatabase(dbConfig.getDatabase())
            .setUser(dbConfig.getUsername())
            .setPassword(dbConfig.getPassword());

        Pool pool = PgBuilder.pool().connectingTo(connectOptions).build();

        String notifyPayload = String.format(
            "{\"event_id\":\"%s\",\"event_type\":\"TestEvent\",\"aggregate_id\":null,\"correlation_id\":null,\"causation_id\":null,\"is_correction\":false,\"transaction_time\":null}",
            appendedEvent.getEventId()
        );

        // FIXED: Use correct channel name format: {schema}_bitemporal_events_{table}
        // For public schema with bitemporal_event_log table
        String channelName = "public_bitemporal_events_bitemporal_event_log";
        String notifyCommand = String.format("NOTIFY %s, '%s'", channelName, notifyPayload);

        pool.withConnection(conn ->
            conn.query(notifyCommand).execute()
        ).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        pool.close();
        
        // Wait for notification
        boolean notificationReceived = notificationLatch.await(10, TimeUnit.SECONDS);
        assertTrue(notificationReceived, "Notification should be received within timeout");
        
        // Verify the notification content
        BiTemporalEvent<TestEvent> notifiedEvent = receivedEvent.get();
        assertNotNull(notifiedEvent, "Notification handler should have received an event");
        assertEquals(appendedEvent.getEventId(), notifiedEvent.getEventId());
        assertEquals(appendedEvent.getEventType(), notifiedEvent.getEventType());
        assertEquals(appendedEvent.getPayload(), notifiedEvent.getPayload());
    }
}
