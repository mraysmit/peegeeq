package dev.mars.peegeeq.examples;

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Test to analyze the transactional behavior of the outbox pattern implementation.
 * This test demonstrates whether the outbox producer properly participates in 
 * database transactions with business data writes.
 */
@Testcontainers
public class TransactionalOutboxTest {

    private static final Logger logger = LoggerFactory.getLogger(TransactionalOutboxTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        // Configure PeeGeeQ to use the TestContainer
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration();

        // Initialize manager
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create outbox factory and producer
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith(provider);
        
        QueueFactory factory = provider.createFactory("outbox", databaseService);
        producer = factory.createProducer("transactional-test", String.class);
        
        // Get data source for direct database operations
        dataSource = databaseService.getConnectionProvider().getDataSource("peegeeq-main");
        
        // Create a test business table
        createBusinessTable();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (producer != null) producer.close();
        if (manager != null) manager.close();
    }

    private void createBusinessTable() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "CREATE TABLE IF NOT EXISTS business_orders (id SERIAL PRIMARY KEY, order_id VARCHAR(255), status VARCHAR(50))")) {
            stmt.execute();
        }
    }

    @Test
    @DisplayName("ANALYSIS: Outbox producer creates separate transaction - business data not guaranteed")
    void testOutboxProducerCreatesOwnTransaction() throws Exception {
        logger.info("=== TRANSACTIONAL ANALYSIS: Testing if outbox producer participates in business transaction ===");
        
        String testOrderId = "ORDER-" + System.currentTimeMillis();
        String testMessage = "Order created: " + testOrderId;
        
        // Test 1: Successful business transaction with outbox message
        logger.info("--- Test 1: Successful business transaction with outbox message ---");
        
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false); // Start transaction
            
            // Insert business data
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO business_orders (order_id, status) VALUES (?, ?)")) {
                stmt.setString(1, testOrderId);
                stmt.setString(2, "CREATED");
                stmt.executeUpdate();
                logger.info("✅ Business data inserted: {}", testOrderId);
            }
            
            // Send outbox message (this should participate in the same transaction)
            producer.send(testMessage).get(5, TimeUnit.SECONDS);
            logger.info("✅ Outbox message sent: {}", testMessage);
            
            // Commit the transaction
            conn.commit();
            logger.info("✅ Transaction committed");
        }
        
        // Verify both business data and outbox message exist
        verifyBusinessDataExists(testOrderId, true);
        verifyOutboxMessageExists(testMessage, true);
        
        logger.info("--- Test 1 Results: Both business data and outbox message exist (as expected) ---");
    }

    @Test
    @DisplayName("ANALYSIS: Business transaction rollback - outbox message may still be committed")
    void testBusinessTransactionRollbackWithOutboxMessage() throws Exception {
        logger.info("=== TRANSACTIONAL ANALYSIS: Testing business transaction rollback with outbox message ===");
        
        String testOrderId = "ORDER-ROLLBACK-" + System.currentTimeMillis();
        String testMessage = "Order created: " + testOrderId;
        
        logger.info("--- Test 2: Business transaction rollback - checking if outbox message is also rolled back ---");
        
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false); // Start transaction
            
            // Insert business data
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO business_orders (order_id, status) VALUES (?, ?)")) {
                stmt.setString(1, testOrderId);
                stmt.setString(2, "CREATED");
                stmt.executeUpdate();
                logger.info("✅ Business data inserted: {}", testOrderId);
            }
            
            // Send outbox message
            producer.send(testMessage).get(5, TimeUnit.SECONDS);
            logger.info("✅ Outbox message sent: {}", testMessage);
            
            // Rollback the transaction
            conn.rollback();
            logger.info("🔄 Transaction rolled back");
        }
        
        // Verify business data does not exist (rolled back)
        verifyBusinessDataExists(testOrderId, false);
        
        // Check if outbox message exists - this is the critical test
        boolean outboxMessageExists = verifyOutboxMessageExists(testMessage, false);
        
        if (outboxMessageExists) {
            logger.error("❌ TRANSACTIONAL ISSUE DETECTED: Outbox message exists despite business transaction rollback!");
            logger.error("❌ This indicates the outbox producer is NOT participating in the business transaction");
            logger.error("❌ The outbox pattern is NOT providing transactional guarantees");
        } else {
            logger.info("✅ Outbox message was also rolled back - transactional consistency maintained");
        }
        
        logger.info("--- Test 2 Results: Business data rolled back, outbox message status: {} ---", 
                   outboxMessageExists ? "EXISTS (PROBLEM)" : "ROLLED BACK (CORRECT)");
    }

    @Test
    @DisplayName("ANALYSIS: Multiple operations in same transaction")
    void testMultipleOperationsInSameTransaction() throws Exception {
        logger.info("=== TRANSACTIONAL ANALYSIS: Testing multiple operations in same transaction ===");
        
        String testOrderId1 = "ORDER-MULTI-1-" + System.currentTimeMillis();
        String testOrderId2 = "ORDER-MULTI-2-" + System.currentTimeMillis();
        String testMessage1 = "Order created: " + testOrderId1;
        String testMessage2 = "Order created: " + testOrderId2;
        
        logger.info("--- Test 3: Multiple business operations and outbox messages in one transaction ---");
        
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false); // Start transaction
            
            // First business operation
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO business_orders (order_id, status) VALUES (?, ?)")) {
                stmt.setString(1, testOrderId1);
                stmt.setString(2, "CREATED");
                stmt.executeUpdate();
                logger.info("✅ First business data inserted: {}", testOrderId1);
            }
            
            // First outbox message
            producer.send(testMessage1).get(5, TimeUnit.SECONDS);
            logger.info("✅ First outbox message sent: {}", testMessage1);
            
            // Second business operation
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO business_orders (order_id, status) VALUES (?, ?)")) {
                stmt.setString(1, testOrderId2);
                stmt.setString(2, "CREATED");
                stmt.executeUpdate();
                logger.info("✅ Second business data inserted: {}", testOrderId2);
            }
            
            // Second outbox message
            producer.send(testMessage2).get(5, TimeUnit.SECONDS);
            logger.info("✅ Second outbox message sent: {}", testMessage2);
            
            // Rollback the entire transaction
            conn.rollback();
            logger.info("🔄 Entire transaction rolled back");
        }
        
        // Verify all business data is rolled back
        verifyBusinessDataExists(testOrderId1, false);
        verifyBusinessDataExists(testOrderId2, false);
        
        // Check if any outbox messages exist
        boolean message1Exists = verifyOutboxMessageExists(testMessage1, false);
        boolean message2Exists = verifyOutboxMessageExists(testMessage2, false);
        
        if (message1Exists || message2Exists) {
            logger.error("❌ TRANSACTIONAL ISSUE DETECTED: Some outbox messages exist despite transaction rollback!");
            logger.error("❌ Message 1 exists: {}, Message 2 exists: {}", message1Exists, message2Exists);
        } else {
            logger.info("✅ All outbox messages were rolled back with business data");
        }
        
        logger.info("--- Test 3 Results: All business data rolled back, outbox messages: {} ---", 
                   (message1Exists || message2Exists) ? "SOME EXIST (PROBLEM)" : "ALL ROLLED BACK (CORRECT)");
    }

    private void verifyBusinessDataExists(String orderId, boolean shouldExist) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM business_orders WHERE order_id = ?")) {
            stmt.setString(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int count = rs.getInt(1);
                boolean exists = count > 0;
                
                if (shouldExist && !exists) {
                    logger.error("❌ Expected business data to exist for order: {}, but it doesn't", orderId);
                } else if (!shouldExist && exists) {
                    logger.error("❌ Expected business data NOT to exist for order: {}, but it does", orderId);
                } else {
                    logger.info("✅ Business data verification passed for order: {} (exists: {})", orderId, exists);
                }
            }
        }
    }

    private boolean verifyOutboxMessageExists(String message, boolean shouldExist) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM outbox WHERE payload::text LIKE ?")) {
            stmt.setString(1, "%" + message + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int count = rs.getInt(1);
                boolean exists = count > 0;
                
                if (shouldExist && !exists) {
                    logger.error("❌ Expected outbox message to exist: {}, but it doesn't", message);
                } else if (!shouldExist && exists) {
                    logger.error("❌ Expected outbox message NOT to exist: {}, but it does", message);
                } else {
                    logger.info("✅ Outbox message verification passed: {} (exists: {})", message, exists);
                }
                
                return exists;
            }
        }
    }
}
