package dev.mars.peegeeq.examples.springbootbitemporal;

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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring Boot Application demonstrating Basic Bi-Temporal Event Store Patterns.
 * 
 * <h2>BUSINESS SCENARIO: Financial Audit System</h2>
 * 
 * This application demonstrates a financial audit system that tracks account transactions
 * with complete historical accuracy using bi-temporal event storage:
 * 
 * <ul>
 *   <li><b>Valid Time</b> - When the transaction actually occurred in the real world</li>
 *   <li><b>Transaction Time</b> - When the transaction was recorded in the system</li>
 * </ul>
 * 
 * <h2>CORE PATTERNS DEMONSTRATED</h2>
 * 
 * <h3>1. Append-Only Event Storage</h3>
 * <ul>
 *   <li>Store events with both valid time and transaction time</li>
 *   <li>Immutable event log - events are never deleted or modified</li>
 *   <li>Complete audit trail for regulatory compliance</li>
 *   <li>Type-safe event handling with Jackson serialization</li>
 * </ul>
 * 
 * <h3>2. Historical Point-in-Time Queries</h3>
 * <ul>
 *   <li>Query events as they were known at any point in time</li>
 *   <li>Reconstruct account balances at specific dates</li>
 *   <li>Support for regulatory reporting and audits</li>
 *   <li>Temporal range queries for analysis</li>
 * </ul>
 * 
 * <h3>3. Event Corrections Without Losing History</h3>
 * <ul>
 *   <li>Correct historical events while preserving original records</li>
 *   <li>Same valid time, different transaction time</li>
 *   <li>Complete audit trail of all corrections</li>
 *   <li>Regulatory compliance for financial corrections</li>
 * </ul>
 * 
 * <h3>4. Real-Time Event Subscriptions</h3>
 * <ul>
 *   <li>Subscribe to new events via PostgreSQL LISTEN/NOTIFY</li>
 *   <li>Real-time notifications for event processing</li>
 *   <li>Event-driven architecture patterns</li>
 *   <li>Integration with downstream systems</li>
 * </ul>
 * 
 * <h2>TECHNICAL ARCHITECTURE</h2>
 * 
 * <h3>Event Store</h3>
 * <ul>
 *   <li><b>BiTemporalEventStoreFactory</b> - Creates typed event stores</li>
 *   <li><b>EventStore&lt;T&gt;</b> - Type-safe event storage and retrieval</li>
 *   <li><b>BiTemporalEvent&lt;T&gt;</b> - Event wrapper with temporal dimensions</li>
 *   <li><b>EventQuery</b> - Fluent API for temporal queries</li>
 * </ul>
 * 
 * <h3>Spring Boot Integration</h3>
 * <ul>
 *   <li><b>Configuration Management</b> - Spring Boot properties and profiles</li>
 *   <li><b>Dependency Injection</b> - Event store as Spring bean</li>
 *   <li><b>REST API</b> - RESTful endpoints for event operations</li>
 *   <li><b>Lifecycle Management</b> - Proper startup and shutdown</li>
 * </ul>
 * 
 * <h3>Database</h3>
 * <ul>
 *   <li><b>PostgreSQL</b> - Bi-temporal storage with JSONB</li>
 *   <li><b>Vert.x 5.x Reactive Client</b> - Non-blocking database operations</li>
 *   <li><b>Connection Pooling</b> - Efficient resource management</li>
 *   <li><b>LISTEN/NOTIFY</b> - Real-time event notifications</li>
 * </ul>
 * 
 * <h2>BUSINESS USE CASES</h2>
 * 
 * <h3>1. Record Financial Transaction</h3>
 * <p>Record a transaction with the time it actually occurred:</p>
 * <ul>
 *   <li>Transaction amount and type (debit/credit)</li>
 *   <li>Valid time (when transaction occurred)</li>
 *   <li>Transaction time (when recorded in system)</li>
 *   <li>Complete transaction metadata</li>
 * </ul>
 * 
 * <h3>2. Query Historical Balance</h3>
 * <p>Calculate account balance as it was known at any point in time:</p>
 * <ul>
 *   <li>Point-in-time balance reconstruction</li>
 *   <li>Support for regulatory audits</li>
 *   <li>Historical reporting and analysis</li>
 *   <li>Dispute resolution with historical evidence</li>
 * </ul>
 * 
 * <h3>3. Correct Historical Transaction</h3>
 * <p>Correct a transaction error while preserving audit trail:</p>
 * <ul>
 *   <li>Discover transaction error (wrong amount, wrong account)</li>
 *   <li>Record correction with same valid time</li>
 *   <li>Preserve original transaction for audit</li>
 *   <li>Maintain regulatory compliance</li>
 * </ul>
 * 
 * <h3>4. Real-Time Transaction Monitoring</h3>
 * <p>Monitor transactions in real-time for fraud detection:</p>
 * <ul>
 *   <li>Subscribe to transaction events</li>
 *   <li>Real-time fraud detection rules</li>
 *   <li>Immediate alerts and notifications</li>
 *   <li>Integration with monitoring systems</li>
 * </ul>
 * 
 * <h2>REGULATORY COMPLIANCE</h2>
 * 
 * <ul>
 *   <li><b>SOX Compliance</b> - Complete audit trail for financial transactions</li>
 *   <li><b>GDPR Compliance</b> - Data correction capabilities with history</li>
 *   <li><b>Basel III</b> - Historical data for risk calculations</li>
 *   <li><b>MiFID II</b> - Transaction reporting with temporal accuracy</li>
 * </ul>
 * 
 * <h2>GETTING STARTED</h2>
 * 
 * <pre>
 * # Start the application
 * mvn spring-boot:run -pl peegeeq-examples \
 *   -Dspring-boot.run.main-class=dev.mars.peegeeq.examples.springbootbitemporal.SpringBootBitemporalApplication
 * 
 * # Record a transaction
 * curl -X POST http://localhost:8083/api/transactions \
 *   -H "Content-Type: application/json" \
 *   -d '{"accountId":"ACC-001","amount":"1000.00","type":"CREDIT","description":"Initial deposit"}'
 * 
 * # Query account history
 * curl http://localhost:8083/api/accounts/ACC-001/history
 * 
 * # Query balance at specific point in time
 * curl http://localhost:8083/api/accounts/ACC-001/balance?asOf=2025-10-01T12:00:00Z
 * 
 * # Correct a transaction
 * curl -X POST http://localhost:8083/api/transactions/TXN-001/correct \
 *   -H "Content-Type: application/json" \
 *   -d '{"correctedAmount":"1050.00","reason":"Amount correction"}'
 * </pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-10-06
 * @version 1.0
 */
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,  // Exclude JDBC DataSource - using Vert.x reactive
    R2dbcAutoConfiguration.class        // Exclude R2DBC - using Vert.x reactive PostgreSQL client
})
public class SpringBootBitemporalApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(SpringBootBitemporalApplication.class);
    
    public static void main(String[] args) {
        logger.info("=== Starting Spring Boot Bi-Temporal Event Store Example ===");
        logger.info("This example demonstrates basic bi-temporal event store patterns");
        logger.info("for financial audit systems with complete historical accuracy.");
        logger.info("");
        logger.info("Key Features:");
        logger.info("- Append-only event storage with bi-temporal dimensions");
        logger.info("- Historical point-in-time queries");
        logger.info("- Event corrections without losing history");
        logger.info("- Real-time event subscriptions via LISTEN/NOTIFY");
        logger.info("");
        logger.info("Business Domain:");
        logger.info("- Financial transaction tracking and audit");
        logger.info("- Account balance reconstruction");
        logger.info("- Regulatory compliance and reporting");
        logger.info("");
        
        SpringApplication.run(SpringBootBitemporalApplication.class, args);
        
        logger.info("=== Spring Boot Bi-Temporal Application Started Successfully ===");
        logger.info("Application is ready to demonstrate bi-temporal patterns!");
        logger.info("Access the REST API at: http://localhost:8083/api/");
    }
}

