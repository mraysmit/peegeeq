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
import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;


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
    
    public static void main(String[] args) throws Exception {
        logger.info("Starting CloudEvents integration example");

        PeeGeeQManager manager = new PeeGeeQManager(
            new PeeGeeQConfiguration("development", new java.util.Properties()),
            new SimpleMeterRegistry());

        AtomicReference<MessageProducer<CloudEvent>> producerRef = new AtomicReference<>();
        AtomicReference<MessageConsumer<CloudEvent>> consumerRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        manager.start()
            .compose(v -> {
                logger.info("PeeGeeQ Manager started");

                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                OutboxFactoryRegistrar.registerWith(provider);
                QueueFactory factory = provider.createFactory("outbox", databaseService);

                MessageProducer<CloudEvent> producer = factory.createProducer("cloudevents-demo", CloudEvent.class);
                MessageConsumer<CloudEvent> consumer = factory.createConsumer("cloudevents-demo", CloudEvent.class);
                producerRef.set(producer);
                consumerRef.set(consumer);

                try {
                    demonstrateObjectMapperIntegration(manager.getObjectMapper());
                } catch (Exception e) {
                    return Future.<Void>failedFuture(e);
                }
                return demonstrateCloudEventsMessaging(producer, consumer);
            })
            .onSuccess(v -> logger.info("CloudEvents example completed successfully"))
            .onFailure(err -> logger.error("Error in CloudEvents example", err))
            .eventually(() -> {
                // Cleanup runs in both success and failure paths.
                try {
                    MessageConsumer<CloudEvent> consumer = consumerRef.get();
                    if (consumer != null) consumer.close();
                    MessageProducer<CloudEvent> producer = producerRef.get();
                    if (producer != null) producer.close();
                    manager.close();
                } catch (Exception e) {
                    logger.error("Error during cleanup", e);
                } finally {
                    done.countDown();
                }
                return Future.succeededFuture();
            });

        done.await();
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
    private static Future<Void> demonstrateCloudEventsMessaging(
            MessageProducer<CloudEvent> producer,
            MessageConsumer<CloudEvent> consumer) {

        logger.info("=== CloudEvents Messaging ===");

        // Set up consumer to process CloudEvents
        Promise<CloudEvent> receivedEventPromise = Promise.promise();

        consumer.subscribe(message -> {
            try {
                CloudEvent event = message.getPayload();
                logger.info("Received CloudEvent: id={}, type={}, source={}",
                    event.getId(), event.getType(), event.getSource());

                // Log extensions
                String correlationId = (String) event.getExtension("correlationid");
                String priority = (String) event.getExtension("priority");
                logger.info("Event metadata - correlationId: {}, priority: {}", correlationId, priority);

                receivedEventPromise.complete(event);
                return Future.succeededFuture();
            } catch (Exception e) {
                logger.error("Error processing CloudEvent", e);
                receivedEventPromise.fail(e);
                return Future.failedFuture(e);
            }
        }).onFailure(err -> logger.error("Consumer subscription failed", err));

        // Build and send the CloudEvent, then await receipt via the promise (composed, never blocked).
        CloudEvent eventToSend;
        try {
            eventToSend = CloudEventBuilder.v1()
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
        } catch (Exception e) {
            return Future.failedFuture(e);
        }

        logger.info("Sending CloudEvent: id={}, type={}", eventToSend.getId(), eventToSend.getType());

        return producer.send(eventToSend, Map.of("priority", "HIGH"))
            .onSuccess(v -> logger.info("CloudEvent sent successfully"))
            .compose(v -> receivedEventPromise.future())
            .map(receivedEvent -> {
                logger.info("CloudEvent processing completed: id={}", receivedEvent.getId());
                return null;
            });
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
