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

package dev.mars.peegeeq.examples.springbootintegrated;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;

/**
 * Spring Boot Application demonstrating Integrated Outbox + Bi-Temporal Pattern.
 * 
 * <h2>BUSINESS SCENARIO: Order Management System</h2>
 * 
 * This application demonstrates a complete order management system that combines:
 * <ul>
 *   <li><b>Database Operations</b> - Store order data in PostgreSQL</li>
 *   <li><b>Outbox Pattern</b> - Send events for immediate real-time processing</li>
 *   <li><b>Bi-Temporal Event Store</b> - Maintain complete historical audit trail</li>
 * </ul>
 * 
 * <p>All three operations participate in a SINGLE database transaction, ensuring
 * complete consistency and reliability.
 * 
 * <h2>CORE PATTERNS DEMONSTRATED</h2>
 * 
 * <h3>1. Integrated Transaction Pattern</h3>
 * <ul>
 *   <li>Single transaction coordinates all operations</li>
 *   <li>Database save + outbox send + event store append</li>
 *   <li>All succeed together or all fail together</li>
 *   <li>No partial updates or inconsistent state</li>
 * </ul>
 * 
 * <h3>2. Dual-Purpose Events</h3>
 * <ul>
 *   <li>Same event goes to both outbox and event store</li>
 *   <li>Outbox: For immediate consumer processing</li>
 *   <li>Event Store: For historical queries and audit</li>
 *   <li>Complete consistency between real-time and historical views</li>
 * </ul>
 * 
 * <h3>3. Complete Audit Trail</h3>
 * <ul>
 *   <li>Every order change is recorded in bi-temporal store</li>
 *   <li>Query order state at any point in time</li>
 *   <li>Full history of all order events</li>
 *   <li>Regulatory compliance and reporting</li>
 * </ul>
 * 
 * <h3>4. Real-Time Processing</h3>
 * <ul>
 *   <li>Outbox consumers process events immediately</li>
 *   <li>Trigger downstream workflows (shipping, billing, etc.)</li>
 *   <li>Send notifications to customers</li>
 *   <li>Update external systems</li>
 * </ul>
 * 
 * <h2>SPRING BOOT INTEGRATION</h2>
 * 
 * <h3>Configuration</h3>
 * <ul>
 *   <li><b>PeeGeeQManager</b> - Foundation for all operations</li>
 *   <li><b>DatabaseService</b> - Connection management</li>
 *   <li><b>QueueFactory</b> - Outbox factory</li>
 *   <li><b>MessageProducer</b> - Send events to outbox</li>
 *   <li><b>EventStore</b> - Bi-temporal event storage</li>
 * </ul>
 * 
 * <h3>Service Layer</h3>
 * <ul>
 *   <li><b>ConnectionProvider.withTransaction()</b> - Single transaction</li>
 *   <li><b>Repository.save(order, connection)</b> - Database operation</li>
 *   <li><b>Producer.sendInTransaction(event, connection)</b> - Outbox operation</li>
 *   <li><b>EventStore.appendInTransaction(type, event, time, connection)</b> - Event store operation</li>
 * </ul>
 * 
 * <h3>Database</h3>
 * <ul>
 *   <li><b>PostgreSQL</b> - Single database for all operations</li>
 *   <li><b>Vert.x 5.x Reactive Client</b> - Non-blocking operations</li>
 *   <li><b>Connection Pooling</b> - Efficient resource management</li>
 *   <li><b>LISTEN/NOTIFY</b> - Real-time event notifications</li>
 * </ul>
 * 
 * <h2>BUSINESS USE CASES</h2>
 * 
 * <h3>1. Create Order</h3>
 * <p>Customer places an order:</p>
 * <ul>
 *   <li>Order saved to database</li>
 *   <li>Event sent to outbox → triggers shipping workflow</li>
 *   <li>Event appended to event store → audit trail</li>
 *   <li>All in single transaction</li>
 * </ul>
 * 
 * <h3>2. Query Order History</h3>
 * <p>View complete order history:</p>
 * <ul>
 *   <li>All events for an order</li>
 *   <li>Created, confirmed, shipped, delivered</li>
 *   <li>Complete audit trail with timestamps</li>
 * </ul>
 * 
 * <h3>3. Customer Order Report</h3>
 * <p>Generate customer order report:</p>
 * <ul>
 *   <li>All orders for a customer</li>
 *   <li>Historical queries</li>
 *   <li>Point-in-time analysis</li>
 * </ul>
 * 
 * <h3>4. Regulatory Compliance</h3>
 * <p>Meet regulatory requirements:</p>
 * <ul>
 *   <li>Complete audit trail</li>
 *   <li>Immutable event log</li>
 *   <li>Point-in-time queries</li>
 *   <li>SOX, GDPR, MiFID II compliance</li>
 * </ul>
 * 
 * <h2>GETTING STARTED</h2>
 * 
 * <pre>
 * # Start the application
 * mvn spring-boot:run -pl peegeeq-examples \
 *   -Dspring-boot.run.main-class=dev.mars.peegeeq.examples.springbootintegrated.SpringBootIntegratedApplication
 * 
 * # Create an order
 * curl -X POST http://localhost:8084/api/orders \
 *   -H "Content-Type: application/json" \
 *   -d '{"customerId":"CUST-001","amount":"1500.00","description":"Laptop purchase"}'
 * 
 * # Query order history
 * curl http://localhost:8084/api/orders/ORDER-ID/history
 * 
 * # Query customer orders
 * curl http://localhost:8084/api/customers/CUST-001/orders
 * 
 * # Query orders as of specific time
 * curl "http://localhost:8084/api/orders?asOf=2025-10-01T12:00:00Z"
 * </pre>
 * 
 * <h2>KEY BENEFITS</h2>
 * 
 * <ul>
 *   <li><b>Consistency</b> - Single transaction ensures all operations succeed or fail together</li>
 *   <li><b>Real-Time</b> - Outbox consumers process events immediately</li>
 *   <li><b>Historical</b> - Bi-temporal store provides complete audit trail</li>
 *   <li><b>Reliable</b> - No lost events, no partial updates</li>
 *   <li><b>Compliant</b> - Meets regulatory requirements</li>
 *   <li><b>Scalable</b> - PostgreSQL handles high throughput</li>
 * </ul>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-07
 * @version 1.0
 */
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,  // Exclude JDBC DataSource - using Vert.x reactive
    R2dbcAutoConfiguration.class        // Exclude R2DBC - using Vert.x reactive PostgreSQL client
})
public class SpringBootIntegratedApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringBootIntegratedApplication.class);
    
    public static void main(String[] args) {
        logger.info("=== Starting Spring Boot Integrated Outbox + Bi-Temporal Example ===");
        logger.info("This example demonstrates the complete integration pattern:");
        logger.info("- Database operations (order data)");
        logger.info("- Outbox pattern (real-time processing)");
        logger.info("- Bi-temporal event store (historical audit trail)");
        logger.info("- All in a SINGLE transaction for complete consistency");
        logger.info("");
        logger.info("Key Features:");
        logger.info("- Integrated transaction pattern");
        logger.info("- Dual-purpose events (outbox + event store)");
        logger.info("- Complete audit trail");
        logger.info("- Real-time processing");
        logger.info("- Regulatory compliance");
        logger.info("");
        
        SpringApplication.run(SpringBootIntegratedApplication.class, args);
        
        logger.info("=== Spring Boot Integrated Application Started Successfully ===");
        logger.info("Application is ready to demonstrate integrated patterns!");
        logger.info("Access the REST API at: http://localhost:8084/api/");
    }
}

