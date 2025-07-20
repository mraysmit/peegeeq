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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Comprehensive example demonstrating the bi-temporal event store capabilities.
 * 
 * This example shows:
 * - Append-only event storage with bi-temporal dimensions
 * - Event corrections and versioning
 * - Historical queries and point-in-time views
 * - Real-time event subscriptions
 * - Type-safe event handling
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
public class BiTemporalEventStoreExample {
    
    private static final Logger logger = LoggerFactory.getLogger(BiTemporalEventStoreExample.class);
    
    /**
     * Example event payload representing an basic order.
     */
    public static class OrderEvent {
        private final String orderId;
        private final String customerId;
        private final BigDecimal amount;
        private final String status;
        
        @JsonCreator
        public OrderEvent(@JsonProperty("orderId") String orderId,
                         @JsonProperty("customerId") String customerId,
                         @JsonProperty("amount") BigDecimal amount,
                         @JsonProperty("status") String status) {
            this.orderId = orderId;
            this.customerId = customerId;
            this.amount = amount;
            this.status = status;
        }
        
        public String getOrderId() { return orderId; }
        public String getCustomerId() { return customerId; }
        public BigDecimal getAmount() { return amount; }
        public String getStatus() { return status; }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OrderEvent that = (OrderEvent) o;
            return Objects.equals(orderId, that.orderId) &&
                   Objects.equals(customerId, that.customerId) &&
                   Objects.equals(amount, that.amount) &&
                   Objects.equals(status, that.status);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(orderId, customerId, amount, status);
        }
        
        @Override
        public String toString() {
            return "OrderEvent{" +
                    "orderId='" + orderId + '\'' +
                    ", customerId='" + customerId + '\'' +
                    ", amount=" + amount +
                    ", status='" + status + '\'' +
                    '}';
        }
    }
    
    public static void main(String[] args) {
        logger.info("================= Starting Bi-Temporal Event Store Example =============================");
        
        // Start PostgreSQL container
        try (@SuppressWarnings("resource") PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine3.20")
                .withDatabaseName("peegeeq_test")
                .withUsername("test")
                .withPassword("test")) {
            
            postgres.start();
            logger.info("PostgreSQL container started on port: {}", postgres.getFirstMappedPort());
            
            // Set system properties for PeeGeeQ configuration
            System.setProperty("peegeeq.database.host", postgres.getHost());
            System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
            System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
            System.setProperty("peegeeq.database.username", postgres.getUsername());
            System.setProperty("peegeeq.database.password", postgres.getPassword());

            // Configure PeeGeeQ
            PeeGeeQConfiguration config = new PeeGeeQConfiguration();
            
            // Initialize PeeGeeQ
            try (PeeGeeQManager manager = new PeeGeeQManager(config)) {
                manager.start();
                logger.info("PeeGeeQ Manager started successfully");
                
                // Create bi-temporal event store factory
                BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
                
                // Create event store for OrderEvent
                try (EventStore<OrderEvent> eventStore = factory.createEventStore(OrderEvent.class)) {
                    
                    // Run the example
                    runBiTemporalExample(eventStore).join();
                    
                } catch (Exception e) {
                    logger.error("Error in bi-temporal example: {}", e.getMessage(), e);
                }
                
            } catch (Exception e) {
                logger.error("Error with PeeGeeQ Manager: {}", e.getMessage(), e);
            }
            
        } catch (Exception e) {
            logger.error("Error with PostgreSQL container: {}", e.getMessage(), e);
        }
        
        logger.info("Bi-Temporal Event Store Example completed");
    }
    
    private static CompletableFuture<Void> runBiTemporalExample(EventStore<OrderEvent> eventStore) {
        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("=== Bi-Temporal Event Store Example ===");
                
                // 1. Append initial events
                logger.info("\n1. Appending initial events...");
                
                Instant baseTime = Instant.now().minus(1, ChronoUnit.HOURS);
                
                OrderEvent order1 = new OrderEvent("ORDER-001", "CUST-123", new BigDecimal("99.99"), "CREATED");
                OrderEvent order2 = new OrderEvent("ORDER-002", "CUST-456", new BigDecimal("149.99"), "CREATED");
                
                BiTemporalEvent<OrderEvent> event1 = eventStore.append(
                    "OrderCreated", order1, baseTime, Map.of("source", "web", "region", "US"), "corr-001", "ORDER-001"
                ).join();
                
                BiTemporalEvent<OrderEvent> event2 = eventStore.append(
                    "OrderCreated", order2, baseTime.plus(10, ChronoUnit.MINUTES), Map.of("source", "mobile", "region", "EU"), "corr-002", "ORDER-002"
                ).join();
                
                logger.info("Created event 1: {}", event1.getEventId());
                logger.info("Created event 2: {}", event2.getEventId());
                
                // 2. Query all events
                logger.info("\n2. Querying all events...");
                
                List<BiTemporalEvent<OrderEvent>> allEvents = eventStore.query(EventQuery.all()).join();
                logger.info("Found {} events:", allEvents.size());
                for (BiTemporalEvent<OrderEvent> event : allEvents) {
                    logger.info("  Event: {} - {} - {}", event.getEventId(), event.getEventType(), event.getPayload());
                }
                
                // 3. Query by event type
                logger.info("\n3. Querying by event type...");
                
                List<BiTemporalEvent<OrderEvent>> orderEvents = eventStore.query(
                    EventQuery.forEventType("OrderCreated")
                ).join();

                logger.info("Found {} OrderCreated events", orderEvents.size());
                
                // 4. Query by aggregate
                logger.info("\n4. Querying by aggregate...");
                
                List<BiTemporalEvent<OrderEvent>> order1Events = eventStore.query(EventQuery.forAggregate("ORDER-001")
                ).join();
                logger.info("Found {} events for ORDER-001", order1Events.size());
                
                // 5. Add a correction event
                logger.info("\n5. Adding correction event...");
                
                OrderEvent correctedOrder = new OrderEvent("ORDER-001", "CUST-123", new BigDecimal("89.99"), "CREATED");
                
                BiTemporalEvent<OrderEvent> correctionEvent = eventStore.appendCorrection(
                    event1.getEventId(), "OrderCreated", correctedOrder, baseTime,
                        Map.of("source", "web", "region", "US", "corrected", "true"), "corr-001", "ORDER-001", "Price correction due to incorrect otiginal entry"
                ).join();
                
                logger.info("Created correction event: {}", correctionEvent.getEventId());
                
                // 6. Get all versions of an event
                logger.info("\n6. Getting all versions of ORDER-001 event...");
                
                List<BiTemporalEvent<OrderEvent>> versions = eventStore.getAllVersions(event1.getEventId()).join();
                logger.info("Found {} versions:", versions.size());
                for (BiTemporalEvent<OrderEvent> version : versions) {
                    logger.info("  Version {}: {} - Correction: {}", 
                              version.getVersion(), version.getPayload().getAmount(), 
                              version.isCorrection());
                }
                
                // 7. Point-in-time query (as of transaction time)
                logger.info("\n7. Point-in-time query (before correction)...");
                
                Instant beforeCorrection = correctionEvent.getTransactionTime().minus(1, ChronoUnit.SECONDS);
                BiTemporalEvent<OrderEvent> eventBeforeCorrection = eventStore.getAsOfTransactionTime(
                    event1.getEventId(), beforeCorrection
                ).join();
                
                if (eventBeforeCorrection != null) {
                    logger.info("Event before correction: {} (amount: {})", 
                              eventBeforeCorrection.getEventId(), 
                              eventBeforeCorrection.getPayload().getAmount());
                } else {
                    logger.info("No event found before correction time");
                }
                
                // 8. Temporal range query
                logger.info("\n8. Temporal range query...");
                
                Instant rangeStart = baseTime.minus(30, ChronoUnit.MINUTES);
                Instant rangeEnd = baseTime.plus(30, ChronoUnit.MINUTES);
                
                List<BiTemporalEvent<OrderEvent>> rangeEvents = eventStore.query(
                    EventQuery.builder()
                        .validTimeRange(new TemporalRange(rangeStart, rangeEnd))
                        .sortOrder(EventQuery.SortOrder.VALID_TIME_ASC)
                        .build()
                ).join();
                
                logger.info("Found {} events in time range:", rangeEvents.size());
                for (BiTemporalEvent<OrderEvent> event : rangeEvents) {
                    logger.info("  Event: {} at {}", 
                              event.getPayload().getOrderId(), event.getValidTime());
                }
                
                // 9. Get statistics
                logger.info("\n9. Getting event store statistics...");
                
                EventStore.EventStoreStats stats = eventStore.getStats().join();
                logger.info("Event Store Statistics:");
                logger.info("  Total events: {}", stats.getTotalEvents());
                logger.info("  Total corrections: {}", stats.getTotalCorrections());
                logger.info("  Event counts by type: {}", stats.getEventCountsByType());
                logger.info("  Oldest event time: {}", stats.getOldestEventTime());
                logger.info("  Newest event time: {}", stats.getNewestEventTime());
                logger.info("  Storage size: {} bytes", stats.getStorageSizeBytes());
                
                logger.info("\n======================= Bi-Temporal Event Store Example Completed Successfully ========================");
                
            } catch (Exception e) {
                logger.error("Error in bi-temporal example: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }
}
