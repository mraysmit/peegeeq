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
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Example demonstrating CloudEvents integration with PeeGeeQ.
 * 
 * This example shows how to:
 * 1. Create CloudEvents with rich metadata
 * 2. Send CloudEvents through PeeGeeQ outbox pattern
 * 3. Consume CloudEvents with automatic deserialization
 * 4. Use CloudEvents extensions for correlation and causation tracking
 */
public class CloudEventsExample {
    
    private static final Logger logger = LoggerFactory.getLogger(CloudEventsExample.class);
    
    public static void main(String[] args) {
        logger.info("Starting CloudEvents integration example");
        
        PeeGeeQManager manager = null;
        MessageProducer<CloudEvent> producer = null;
        MessageConsumer<CloudEvent> consumer = null;
        
        try {
            // Initialize PeeGeeQ Manager
            manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
            manager.start();
            logger.info("PeeGeeQ Manager started");
            
            // Create outbox factory with CloudEvents support
            PgDatabaseService databaseService = new PgDatabaseService(manager);
            PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
            OutboxFactoryRegistrar.registerWith(provider);
            
            QueueFactory factory = provider.createFactory("outbox", databaseService);
            
            // Create producer and consumer for CloudEvents
            producer = factory.createProducer("cloudevents-demo", CloudEvent.class);
            consumer = factory.createConsumer("cloudevents-demo", CloudEvent.class);
            
            // Demonstrate CloudEvents ObjectMapper integration
            demonstrateObjectMapperIntegration(manager.getObjectMapper());
            
            // Demonstrate CloudEvents messaging
            demonstrateCloudEventsMessaging(producer, consumer);
            
            logger.info("CloudEvents example completed successfully");
            
        } catch (Exception e) {
            logger.error("Error in CloudEvents example", e);
        } finally {
            // Cleanup resources
            try {
                if (consumer != null) consumer.close();
                if (producer != null) producer.close();
                if (manager != null) manager.close();
            } catch (Exception e) {
                logger.error("Error during cleanup", e);
            }
        }
    }
    
    /**
     * Demonstrates CloudEvents serialization/deserialization with PeeGeeQ's ObjectMapper.
     */
    private static void demonstrateObjectMapperIntegration(ObjectMapper objectMapper) throws Exception {
        logger.info("=== CloudEvents ObjectMapper Integration ===");
        
        // Create a CloudEvent with rich metadata
        CloudEvent originalEvent = CloudEventBuilder.v1()
            .withId("demo-event-" + UUID.randomUUID())
            .withType("com.example.order.created.v1")
            .withSource(URI.create("https://example.com/orders"))
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withData(createOrderPayload())
            .withExtension("correlationid", "workflow-" + UUID.randomUUID())
            .withExtension("causationid", "user-action-123")
            .withExtension("validtime", OffsetDateTime.now().toString())
            .build();
        
        logger.info("Created CloudEvent: id={}, type={}, source={}", 
            originalEvent.getId(), originalEvent.getType(), originalEvent.getSource());
        
        // Serialize CloudEvent using PeeGeeQ's ObjectMapper
        String serialized = objectMapper.writeValueAsString(originalEvent);
        logger.info("Serialized CloudEvent: {} characters", serialized.length());
        
        // Deserialize CloudEvent
        CloudEvent deserializedEvent = objectMapper.readValue(serialized, CloudEvent.class);
        logger.info("Deserialized CloudEvent: id={}, type={}, source={}", 
            deserializedEvent.getId(), deserializedEvent.getType(), deserializedEvent.getSource());
        
        // Verify extensions are preserved
        String correlationId = (String) deserializedEvent.getExtension("correlationid");
        String causationId = (String) deserializedEvent.getExtension("causationid");
        String validTime = (String) deserializedEvent.getExtension("validtime");
        
        logger.info("Extensions preserved - correlationId: {}, causationId: {}, validTime: {}", 
            correlationId, causationId, validTime);
    }
    
    /**
     * Demonstrates CloudEvents messaging through PeeGeeQ outbox pattern.
     */
    private static void demonstrateCloudEventsMessaging(
            MessageProducer<CloudEvent> producer, 
            MessageConsumer<CloudEvent> consumer) throws Exception {
        
        logger.info("=== CloudEvents Messaging ===");
        
        // Set up consumer to process CloudEvents
        CompletableFuture<CloudEvent> receivedEventFuture = new CompletableFuture<>();
        
        consumer.subscribe(message -> {
            try {
                CloudEvent event = message.getPayload();
                logger.info("Received CloudEvent: id={}, type={}, source={}", 
                    event.getId(), event.getType(), event.getSource());
                
                // Log extensions
                String correlationId = (String) event.getExtension("correlationid");
                String priority = (String) event.getExtension("priority");
                logger.info("Event metadata - correlationId: {}, priority: {}", correlationId, priority);
                
                receivedEventFuture.complete(event);
                return CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                logger.error("Error processing CloudEvent", e);
                receivedEventFuture.completeExceptionally(e);
                return CompletableFuture.failedFuture(e);
            }
        });
        
        // Create and send a CloudEvent
        CloudEvent eventToSend = CloudEventBuilder.v1()
            .withId("messaging-demo-" + UUID.randomUUID())
            .withType("com.example.trade.executed.v1")
            .withSource(URI.create("https://example.com/trading"))
            .withTime(OffsetDateTime.now())
            .withDataContentType("application/json")
            .withData(createTradePayload())
            .withExtension("correlationid", "trade-workflow-" + UUID.randomUUID())
            .withExtension("priority", "HIGH")
            .withExtension("validtime", OffsetDateTime.now().toString())
            .build();
        
        logger.info("Sending CloudEvent: id={}, type={}", eventToSend.getId(), eventToSend.getType());
        
        // Send the CloudEvent through outbox
        producer.send(eventToSend, Map.of("priority", "HIGH")).get();
        logger.info("CloudEvent sent successfully");
        
        // Wait for the event to be received and processed
        CloudEvent receivedEvent = receivedEventFuture.get();
        logger.info("CloudEvent processing completed: id={}", receivedEvent.getId());
    }
    
    /**
     * Creates sample order payload data.
     */
    private static byte[] createOrderPayload() throws Exception {
        Map<String, Object> orderData = Map.of(
            "orderId", "ORDER-" + UUID.randomUUID(),
            "customerId", "CUST-12345",
            "amount", 1500.75,
            "currency", "USD",
            "items", Map.of(
                "productId", "PROD-789",
                "quantity", 2,
                "unitPrice", 750.375
            )
        );
        
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsBytes(orderData);
    }
    
    /**
     * Creates sample trade payload data.
     */
    private static byte[] createTradePayload() throws Exception {
        Map<String, Object> tradeData = Map.of(
            "tradeId", "TRADE-" + UUID.randomUUID(),
            "symbol", "AAPL",
            "side", "BUY",
            "quantity", 100,
            "price", 150.25,
            "executionTime", OffsetDateTime.now().toString(),
            "venue", "NASDAQ"
        );
        
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsBytes(tradeData);
    }
}
