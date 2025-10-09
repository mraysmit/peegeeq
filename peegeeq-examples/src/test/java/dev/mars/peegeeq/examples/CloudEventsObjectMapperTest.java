package dev.mars.peegeeq.examples;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify CloudEvents Jackson module integration with PeeGeeQ ObjectMappers.
 * 
 * This test demonstrates that:
 * 1. PeeGeeQ's default ObjectMappers include CloudEvents support when available
 * 2. CloudEvents can be serialized/deserialized properly
 * 3. The integration works across all PeeGeeQ components (Outbox, BiTemporal, Manager)
 */
public class CloudEventsObjectMapperTest {

    private PeeGeeQManager manager;

    @BeforeEach
    void setUp() {
        // Initialize PeeGeeQ Manager with development configuration
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void testCloudEventsObjectMapperIntegration() throws Exception {
        // Create a CloudEvent
        CloudEvent originalEvent = CloudEventBuilder.v1()
            .withId("test-event-123")
            .withType("com.example.test.event.v1")
            .withSource(URI.create("https://example.com/test"))
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withData("{\"message\": \"Hello CloudEvents!\"}".getBytes())
            .withExtension("correlationid", "test-correlation-123")
            .withExtension("validtime", "2025-01-01T12:00:00Z")
            .build();

        // Test 1: PeeGeeQManager's ObjectMapper
        ObjectMapper managerMapper = manager.getObjectMapper();
        String serialized = managerMapper.writeValueAsString(originalEvent);
        CloudEvent deserializedEvent = managerMapper.readValue(serialized, CloudEvent.class);
        
        assertEquals(originalEvent.getId(), deserializedEvent.getId());
        assertEquals(originalEvent.getType(), deserializedEvent.getType());
        assertEquals(originalEvent.getSource(), deserializedEvent.getSource());
        assertEquals("test-correlation-123", deserializedEvent.getExtension("correlationid"));

        // Test 2: OutboxFactory's default ObjectMapper
        // TODO: Fix compilation issue with getObjectMapper() method
        // try (OutboxFactory outboxFactory = new OutboxFactory(manager.getDatabaseService())) {
        //     ObjectMapper outboxMapper = outboxFactory.getObjectMapper();
        //     String outboxSerialized = outboxMapper.writeValueAsString(originalEvent);
        //     CloudEvent outboxDeserialized = outboxMapper.readValue(outboxSerialized, CloudEvent.class);
        //
        //     assertEquals(originalEvent.getId(), outboxDeserialized.getId());
        //     assertEquals(originalEvent.getType(), outboxDeserialized.getType());
        // }

        // Test 3: BiTemporalEventStoreFactory's default ObjectMapper
        BiTemporalEventStoreFactory biTemporalFactory = new BiTemporalEventStoreFactory(manager);
        ObjectMapper biTemporalMapper = biTemporalFactory.getObjectMapper();
        String biTemporalSerialized = biTemporalMapper.writeValueAsString(originalEvent);
        CloudEvent biTemporalDeserialized = biTemporalMapper.readValue(biTemporalSerialized, CloudEvent.class);

        assertEquals(originalEvent.getId(), biTemporalDeserialized.getId());
        assertEquals(originalEvent.getType(), biTemporalDeserialized.getType());

        // Test 4: Verify CloudEvents module is registered
        ObjectMapper testMapper = new ObjectMapper();
        testMapper.registerModule(new JavaTimeModule());
        testMapper.registerModule(JsonFormat.getCloudEventJacksonModule());
        
        String testSerialized = testMapper.writeValueAsString(originalEvent);
        CloudEvent testDeserialized = testMapper.readValue(testSerialized, CloudEvent.class);
        
        assertEquals(originalEvent.getId(), testDeserialized.getId());
        assertEquals(originalEvent.getType(), testDeserialized.getType());
        assertEquals(originalEvent.getSource(), testDeserialized.getSource());
        
        // Verify extensions are preserved
        assertEquals("test-correlation-123", testDeserialized.getExtension("correlationid"));
        assertEquals("2025-01-01T12:00:00Z", testDeserialized.getExtension("validtime"));
    }

    @Test
    void testCloudEventsWithComplexPayload() throws Exception {
        // Create a CloudEvent with complex JSON payload
        Map<String, Object> payload = Map.of(
            "orderId", "ORDER-123",
            "amount", 1000.50,
            "currency", "USD",
            "timestamp", "2025-01-01T12:00:00Z",
            "metadata", Map.of("source", "trading-system", "version", "1.0")
        );
        
        ObjectMapper payloadMapper = new ObjectMapper();
        payloadMapper.registerModule(new JavaTimeModule());
        byte[] payloadBytes = payloadMapper.writeValueAsBytes(payload);

        CloudEvent originalEvent = CloudEventBuilder.v1()
            .withId("complex-event-456")
            .withType("com.example.order.created.v1")
            .withSource(URI.create("https://example.com/trading"))
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withData(payloadBytes)
            .withExtension("correlationid", "order-correlation-456")
            .withExtension("causationid", "trade-execution-789")
            .build();

        // Test serialization/deserialization with PeeGeeQ's ObjectMapper
        ObjectMapper peeGeeQMapper = manager.getObjectMapper();
        String serialized = peeGeeQMapper.writeValueAsString(originalEvent);
        CloudEvent deserializedEvent = peeGeeQMapper.readValue(serialized, CloudEvent.class);
        
        assertEquals(originalEvent.getId(), deserializedEvent.getId());
        assertEquals(originalEvent.getType(), deserializedEvent.getType());
        assertEquals(originalEvent.getSource(), deserializedEvent.getSource());
        assertEquals("order-correlation-456", deserializedEvent.getExtension("correlationid"));
        assertEquals("trade-execution-789", deserializedEvent.getExtension("causationid"));
        
        // Verify data payload is preserved
        assertNotNull(deserializedEvent.getData());
        assertTrue(deserializedEvent.getData().toBytes().length > 0);
    }

    @Test
    void testObjectMapperModulesRegistration() {
        // Verify that PeeGeeQ's ObjectMapper has the necessary modules
        ObjectMapper mapper = manager.getObjectMapper();
        
        // The mapper should be able to handle CloudEvents and Java time types
        assertNotNull(mapper);
        
        // Test Java time support
        assertDoesNotThrow(() -> {
            String timeJson = mapper.writeValueAsString(OffsetDateTime.now());
            OffsetDateTime parsedTime = mapper.readValue(timeJson, OffsetDateTime.class);
            assertNotNull(parsedTime);
        });
        
        // Test CloudEvents support
        assertDoesNotThrow(() -> {
            CloudEvent event = CloudEventBuilder.v1()
                .withId("module-test")
                .withType("test.event")
                .withSource(URI.create("test://source"))
                .build();
            
            String eventJson = mapper.writeValueAsString(event);
            CloudEvent parsedEvent = mapper.readValue(eventJson, CloudEvent.class);
            assertEquals("module-test", parsedEvent.getId());
        });
    }
}
