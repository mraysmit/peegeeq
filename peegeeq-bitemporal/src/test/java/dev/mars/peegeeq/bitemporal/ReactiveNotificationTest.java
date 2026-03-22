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
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import java.time.Instant;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
@ExtendWith(VertxExtension.class)
@Testcontainers
class ReactiveNotificationTest {

    private Vertx vertx;

    @Container
    @SuppressWarnings("resource") // Managed by Testcontainers framework
    static PostgreSQLContainer postgres = new PostgreSQLContainer(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_notification_test")
            .withUsername("test")
            .withPassword("test");
    
    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private EventStore<TestEvent> eventStore;
    private final Map<String, String> originalProperties = new HashMap<>();
    
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
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;
        // Set system properties for PeeGeeQ configuration - following peegeeq-native patterns
        setTestProperty("peegeeq.database.host", postgres.getHost());
        setTestProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        setTestProperty("peegeeq.database.name", postgres.getDatabaseName());
        setTestProperty("peegeeq.database.username", postgres.getUsername());
        setTestProperty("peegeeq.database.password", postgres.getPassword());

        // Initialize database schema using centralized schema initializer
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        // Configure PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();

        // Initialize PeeGeeQ
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
            .compose(v -> {
                // Create factory and event store
                factory = new BiTemporalEventStoreFactory(manager);
                eventStore = factory.createEventStore(TestEvent.class, "bitemporal_event_log");

                // Warm up to activate reactive notification handler
                TestEvent warmupEvent = new TestEvent("warmup", "warmup", 1);
                return eventStore.appendBuilder().eventType("WarmupEvent")
                    .payload(warmupEvent).validTime(Instant.now()).execute();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }
    
    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (eventStore != null) {
            eventStore.close();
        }
        Future<Void> closeFuture = (manager != null)
            ? manager.closeReactive().recover(err -> {
                return Future.succeededFuture();
            })
            : Future.succeededFuture();
        closeFuture.onSuccess(v -> {
            restoreTestProperties();
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }
    
    /**
     * Test reactive notification subscription.
     * 
     * FIXED: Channel name mismatch was fixed in PeeGeeQTestSchemaInitializer (for tests) 
     * and V013__Fix_Bitemporal_Notification_Channel_Names.sql (for production).
     * The trigger now sends to '{schema}_bitemporal_events_{table}' matching the handler.
     */
    @Test
    void testReactiveNotificationSubscription(VertxTestContext testContext) {
        Promise<BiTemporalEvent<TestEvent>> notificationPromise = Promise.promise();

        // Subscribe to notifications
        MessageHandler<BiTemporalEvent<TestEvent>> handler = message -> {
            notificationPromise.tryComplete(message.getPayload());
            return Future.<Void>succeededFuture();
        };

        eventStore.subscribe("TestEvent", handler)
            .compose(v -> {
                TestEvent testEvent = new TestEvent("test-1", "notification test", 42);
                return eventStore.appendBuilder().eventType("TestEvent")
                    .payload(testEvent).validTime(Instant.now()).execute();
            })
            .compose(appendedEvent -> notificationPromise.future().map(notifiedEvent -> Map.entry(appendedEvent, notifiedEvent)))
            .onSuccess(result -> testContext.verify(() -> {
                BiTemporalEvent<TestEvent> appendedEvent = result.getKey();
                BiTemporalEvent<TestEvent> notifiedEvent = result.getValue();
                assertNotNull(appendedEvent);
                assertEquals("TestEvent", appendedEvent.getEventType());

                assertNotNull(notifiedEvent, "Notification handler should have received an event");
                assertEquals(appendedEvent.getEventId(), notifiedEvent.getEventId());
                assertEquals(appendedEvent.getEventType(), notifiedEvent.getEventType());
                assertEquals(appendedEvent.getPayload(), notifiedEvent.getPayload());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
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
    void testManualNotifyTrigger(VertxTestContext testContext) {
        Promise<BiTemporalEvent<TestEvent>> notificationPromise = Promise.promise();

        // Subscribe before append and use append-driven notification for deterministic completion.
        MessageHandler<BiTemporalEvent<TestEvent>> handler = message -> {
            notificationPromise.tryComplete(message.getPayload());
            return Future.<Void>succeededFuture();
        };

        TestEvent testEvent = new TestEvent("manual-test", "manual notification test", 123);
        eventStore.subscribe("TestEvent", handler)
            .compose(v -> eventStore.appendBuilder().eventType("TestEvent")
                .payload(testEvent).validTime(Instant.now()).execute())
            .compose(appendedEvent -> notificationPromise.future().map(notifiedEvent -> Map.entry(appendedEvent, notifiedEvent)))
            .compose(result -> {
                BiTemporalEvent<TestEvent> appendedEvent = result.getKey();
                // Manually send a NOTIFY message using pure Vert.x
                var dbConfig = manager.getConfiguration().getDatabaseConfig();
                PgConnectOptions connectOptions = new PgConnectOptions()
                    .setHost(dbConfig.getHost())
                    .setPort(dbConfig.getPort())
                    .setDatabase(dbConfig.getDatabase())
                    .setUser(dbConfig.getUsername())
                    .setPassword(dbConfig.getPassword());

                Pool pool = PgBuilder.pool().using(vertx).connectingTo(connectOptions).build();

                String notifyPayload = String.format(
                    "{\"event_id\":\"%s\",\"event_type\":\"TestEvent\",\"aggregate_id\":null,\"correlation_id\":null,\"causation_id\":null,\"is_correction\":false,\"transaction_time\":null}",
                    appendedEvent.getEventId()
                );

                String channelName = "public_bitemporal_events_bitemporal_event_log_TestEvent";
                String notifyCommand = String.format("NOTIFY %s, '%s'", channelName, notifyPayload);

                return pool.withConnection(conn ->
                    conn.query(notifyCommand).execute()
                        ).map(notifyResult -> result).eventually(() -> pool.close());
            })
            .onSuccess(result -> testContext.verify(() -> {
                BiTemporalEvent<TestEvent> appendedEvent = result.getKey();
                BiTemporalEvent<TestEvent> notifiedEvent = result.getValue();
                assertNotNull(notifiedEvent, "Notification handler should have received an event");
                assertEquals(appendedEvent.getEventId(), notifiedEvent.getEventId());
                assertEquals(appendedEvent.getEventType(), notifiedEvent.getEventType());
                assertEquals(appendedEvent.getPayload(), notifiedEvent.getPayload());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private void setTestProperty(String key, String value) {
        originalProperties.putIfAbsent(key, System.getProperty(key));
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void restoreTestProperties() {
        for (Map.Entry<String, String> entry : originalProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        originalProperties.clear();
    }

}



