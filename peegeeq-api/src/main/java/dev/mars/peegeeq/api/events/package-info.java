/**
 * Event sourcing contracts for the PeeGeeQ message queue system.
 * 
 * <p>This package contains interfaces and classes for bi-temporal event sourcing,
 * including event stores, bi-temporal events, and event querying capabilities.
 * It provides abstractions for append-only, bi-temporal event storage with
 * real-time processing capabilities.</p>
 * 
 * <h2>Key Interfaces:</h2>
 * <ul>
 *   <li>{@link dev.mars.peegeeq.api.events.EventStore} - Bi-temporal event store interface</li>
 *   <li>{@link dev.mars.peegeeq.api.events.BiTemporalEvent} - Bi-temporal event abstraction</li>
 *   <li>{@link dev.mars.peegeeq.api.events.EventQuery} - Event querying interface</li>
 *   <li>{@link dev.mars.peegeeq.api.events.TemporalRange} - Time range utilities</li>
 * </ul>
 * 
 * <h2>Bi-temporal Concepts:</h2>
 * <p>Bi-temporal events track two time dimensions:</p>
 * <ul>
 *   <li><strong>Valid Time:</strong> When the event actually happened in the real world</li>
 *   <li><strong>Transaction Time:</strong> When the event was recorded in the system</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Create an event store
 * EventStore<OrderEvent> eventStore = databaseService.createEventStore("orders", OrderEvent.class);
 * 
 * // Append an event
 * OrderEvent event = new OrderEvent("order-123", "CREATED", orderData);
 * eventStore.append("order-123", event).join();
 * 
 * // Query events
 * List<BiTemporalEvent<OrderEvent>> events = eventStore.getEvents("order-123").join();
 * }</pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 1.0
 * @version 1.0
 */
package dev.mars.peegeeq.api.events;
