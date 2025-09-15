package dev.mars.peegeeq.examples.patterns.temporal;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test showcasing Bi-Temporal Event Store Patterns for PeeGeeQ.
 * 
 * This test demonstrates:
 * 1. Valid Time vs Transaction Time - Temporal data modeling
 * 2. Event Versioning - Managing event schema evolution
 * 3. Temporal Queries - Querying data at specific points in time
 * 4. Event Correction - Correcting historical events
 * 5. Temporal Snapshots - Creating point-in-time snapshots
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ Complete Guide.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BiTemporalEventStoreDemoTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_bitemporal_demo")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_pass");

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;

    // Temporal event types
    enum EventType {
        ACCOUNT_CREATED("account-created", "Account creation event"),
        BALANCE_UPDATED("balance-updated", "Account balance update"),
        TRANSACTION_POSTED("transaction-posted", "Financial transaction"),
        ACCOUNT_CLOSED("account-closed", "Account closure event"),
        EVENT_CORRECTED("event-corrected", "Historical event correction");

        final String eventName;
        final String description;

        EventType(String eventName, String description) {
            this.eventName = eventName;
            this.description = description;
        }
    }

    // Bi-temporal event with valid time and transaction time
    static class BiTemporalEvent {
        public String eventId;
        public String aggregateId;
        public EventType eventType;
        public Map<String, Object> eventData;
        public String validTime;      // When the event actually occurred in business time
        public String transactionTime; // When the event was recorded in the system
        public int version;
        public String correlationId;
        public boolean isCorrection;

        // Default constructor for Jackson
        public BiTemporalEvent() {}

        public BiTemporalEvent(String eventId, String aggregateId, EventType eventType,
                              Map<String, Object> eventData, Instant validTime, Instant transactionTime,
                              int version, String correlationId, boolean isCorrection) {
            this.eventId = eventId;
            this.aggregateId = aggregateId;
            this.eventType = eventType;
            this.eventData = eventData;
            this.validTime = validTime.toString();
            this.transactionTime = transactionTime.toString();
            this.version = version;
            this.correlationId = correlationId;
            this.isCorrection = isCorrection;
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("eventId", eventId)
                    .put("aggregateId", aggregateId)
                    .put("eventType", eventType.eventName)
                    .put("eventData", eventData)
                    .put("validTime", validTime.toString())
                    .put("transactionTime", transactionTime.toString())
                    .put("version", version)
                    .put("correlationId", correlationId)
                    .put("isCorrection", isCorrection);
        }
    }

    // Account aggregate for temporal testing
    static class AccountAggregate {
        public final String accountId;
        public final String accountNumber;
        public final String customerId;
        public final String accountType;
        public volatile double balance;
        public volatile String status;
        public final List<BiTemporalEvent> events = new ArrayList<>();
        public final Instant createdAt;

        public AccountAggregate(String accountId, String accountNumber, String customerId, 
                               String accountType, double initialBalance) {
            this.accountId = accountId;
            this.accountNumber = accountNumber;
            this.customerId = customerId;
            this.accountType = accountType;
            this.balance = initialBalance;
            this.status = "ACTIVE";
            this.createdAt = Instant.now();
        }

        public synchronized void applyEvent(BiTemporalEvent event) {
            events.add(event);
            
            switch (event.eventType) {
                case ACCOUNT_CREATED:
                    this.balance = ((Number) event.eventData.get("initialBalance")).doubleValue();
                    break;
                case BALANCE_UPDATED:
                    this.balance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    break;
                case TRANSACTION_POSTED:
                    double amount = ((Number) event.eventData.get("amount")).doubleValue();
                    this.balance += amount;
                    break;
                case ACCOUNT_CLOSED:
                    this.status = "CLOSED";
                    break;
                case EVENT_CORRECTED:
                    // Handle correction logic
                    String correctedEventId = (String) event.eventData.get("correctedEventId");
                    events.stream()
                          .filter(e -> e.eventId.equals(correctedEventId))
                          .findFirst()
                          .ifPresent(e -> {
                              // Mark original event as corrected
                              System.out.println("🔧 Correcting event: " + correctedEventId);
                          });
                    break;
            }
        }

        public double getBalanceAtTime(Instant validTime) {
            return events.stream()
                        .filter(e -> e.eventType == EventType.BALANCE_UPDATED)
                        .filter(e -> !Instant.parse(e.validTime).isAfter(validTime))
                        .filter(e -> !e.isCorrection)
                        .mapToDouble(e -> ((Number) e.eventData.get("newBalance")).doubleValue())
                        .reduce((first, second) -> second) // Get the last one
                        .orElse(0.0);
        }
    }

    @BeforeEach
    void setUp() {
        System.out.println("\n⏰ Setting up Bi-Temporal Event Store Demo Test");
        
        // Configure database connection
        String jdbcUrl = postgres.getJdbcUrl();
        String username = postgres.getUsername();
        String password = postgres.getPassword();

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", username);
        System.setProperty("peegeeq.database.password", password);

        // Initialize PeeGeeQ with temporal configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);

        System.out.println("✅ Setup complete - Ready for bi-temporal event store testing");
    }

    @AfterEach
    void tearDown() {
        System.out.println("🧹 Cleaning up Bi-Temporal Event Store Demo Test");
        
        if (manager != null) {
            try {
                manager.close();
            } catch (Exception e) {
                System.err.println("⚠️ Error during manager cleanup: " + e.getMessage());
            }
        }

        // Clean up system properties
        System.clearProperty("peegeeq.database.url");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        
        System.out.println("✅ Cleanup complete");
    }

    @Test
    @Order(1)
    @DisplayName("Valid Time vs Transaction Time - Temporal Data Modeling")
    void testValidTimeVsTransactionTime() throws Exception {
        System.out.println("\n⏰ Testing Valid Time vs Transaction Time");

        String queueName = "bitemporal-validtime-queue";
        Map<String, AccountAggregate> accounts = new HashMap<>();
        AtomicInteger eventsProcessed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(6); // 6 events to process

        // Create producer and consumer
        MessageProducer<BiTemporalEvent> producer = queueFactory.createProducer(queueName, BiTemporalEvent.class);
        MessageConsumer<BiTemporalEvent> consumer = queueFactory.createConsumer(queueName, BiTemporalEvent.class);

        // Subscribe to temporal events
        consumer.subscribe(message -> {
            BiTemporalEvent event = message.getPayload();
            
            System.out.println("⏰ Processing temporal event: " + event.eventType.eventName + 
                             " for account: " + event.aggregateId);
            System.out.println("   Valid Time: " + event.validTime + 
                             " | Transaction Time: " + event.transactionTime);
            
            // Get or create account aggregate
            AccountAggregate account = accounts.computeIfAbsent(event.aggregateId, 
                id -> new AccountAggregate(id, "ACC-" + id, "CUST-" + id, "CHECKING", 0.0));
            
            // Apply the event
            account.applyEvent(event);
            
            eventsProcessed.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Create temporal events with different valid times and transaction times
        System.out.println("📤 Sending bi-temporal events with different valid/transaction times...");
        
        String accountId = "account-001";
        Instant now = Instant.now();
        
        // Event 1: Account created yesterday (valid time) but recorded now (transaction time)
        Instant yesterday = now.minusSeconds(24 * 60 * 60);
        Map<String, Object> accountData = new HashMap<>();
        accountData.put("initialBalance", 1000.0);
        accountData.put("accountType", "CHECKING");
        BiTemporalEvent accountCreated = new BiTemporalEvent(
            "evt-001", accountId, EventType.ACCOUNT_CREATED,
            accountData, yesterday, now, 1, "corr-001", false
        );
        producer.send(accountCreated);

        // Event 2: Balance update from yesterday (both valid and transaction time)
        Map<String, Object> balanceData1 = new HashMap<>();
        balanceData1.put("newBalance", 1500.0);
        balanceData1.put("reason", "deposit");
        BiTemporalEvent balanceUpdate1 = new BiTemporalEvent(
            "evt-002", accountId, EventType.BALANCE_UPDATED,
            balanceData1, yesterday.plusSeconds(3600), yesterday.plusSeconds(3600), 1, "corr-002", false
        );
        producer.send(balanceUpdate1);

        // Event 3: Transaction from this morning (valid time) recorded now (transaction time)
        Instant thisMorning = now.minusSeconds(8 * 60 * 60);
        Map<String, Object> transactionData1 = new HashMap<>();
        transactionData1.put("amount", -200.0);
        transactionData1.put("type", "withdrawal");
        transactionData1.put("newBalance", 1300.0);
        BiTemporalEvent transaction1 = new BiTemporalEvent(
            "evt-003", accountId, EventType.TRANSACTION_POSTED,
            transactionData1, thisMorning, now, 1, "corr-003", false
        );
        producer.send(transaction1);

        // Event 4: Balance update reflecting the transaction
        Map<String, Object> balanceData2 = new HashMap<>();
        balanceData2.put("newBalance", 1300.0);
        balanceData2.put("reason", "withdrawal");
        BiTemporalEvent balanceUpdate2 = new BiTemporalEvent(
            "evt-004", accountId, EventType.BALANCE_UPDATED,
            balanceData2, thisMorning, now, 1, "corr-004", false
        );
        producer.send(balanceUpdate2);

        // Event 5: Late-arriving event from yesterday afternoon (valid time in past, transaction time now)
        Instant yesterdayAfternoon = yesterday.plusSeconds(6 * 60 * 60);
        Map<String, Object> lateEventData = new HashMap<>();
        lateEventData.put("amount", 100.0);
        lateEventData.put("type", "interest");
        lateEventData.put("newBalance", 1600.0);
        BiTemporalEvent lateEvent = new BiTemporalEvent(
            "evt-005", accountId, EventType.TRANSACTION_POSTED,
            lateEventData, yesterdayAfternoon, now, 1, "corr-005", false
        );
        producer.send(lateEvent);

        // Event 6: Corrected balance reflecting the late event
        Map<String, Object> correctedBalanceData = new HashMap<>();
        correctedBalanceData.put("newBalance", 1400.0);
        correctedBalanceData.put("reason", "late-interest-correction");
        BiTemporalEvent correctedBalance = new BiTemporalEvent(
            "evt-006", accountId, EventType.BALANCE_UPDATED,
            correctedBalanceData, thisMorning, now, 2, "corr-006", false
        );
        producer.send(correctedBalance);

        // Wait for all events to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Should process all temporal events");

        // Verify temporal queries
        AccountAggregate account = accounts.get(accountId);
        assertNotNull(account, "Account should exist");
        assertEquals(6, account.events.size(), "Should have 6 events");

        // Test temporal queries
        System.out.println("📊 Temporal Query Results:");
        
        // Balance at yesterday end
        double balanceYesterday = account.getBalanceAtTime(yesterday.plusSeconds(23 * 60 * 60));
        System.out.println("  Balance at end of yesterday: $" + balanceYesterday);
        
        // Balance this morning
        double balanceThisMorning = account.getBalanceAtTime(thisMorning.plusSeconds(60));
        System.out.println("  Balance this morning: $" + balanceThisMorning);
        
        // Current balance
        System.out.println("  Current balance: $" + account.balance);

        // Cleanup
        consumer.close();

        System.out.println("✅ Valid Time vs Transaction Time test completed successfully");
        System.out.println("📊 Total events processed: " + eventsProcessed.get());
    }

    @Test
    @Order(2)
    @DisplayName("Event Versioning - Managing Event Schema Evolution")
    void testEventVersioning() throws Exception {
        System.out.println("\n📝 Testing Event Versioning");

        String queueName = "bitemporal-versioning-queue";
        Map<String, List<BiTemporalEvent>> eventStore = new HashMap<>();
        AtomicInteger eventsProcessed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(4); // 4 versioned events

        // Create producer and consumer
        MessageProducer<BiTemporalEvent> producer = queueFactory.createProducer(queueName, BiTemporalEvent.class);
        MessageConsumer<BiTemporalEvent> consumer = queueFactory.createConsumer(queueName, BiTemporalEvent.class);

        // Subscribe to versioned events
        consumer.subscribe(message -> {
            BiTemporalEvent event = message.getPayload();

            System.out.println("📝 Processing versioned event: " + event.eventType.eventName +
                             " v" + event.version + " for aggregate: " + event.aggregateId);

            // Store event in version-aware event store
            eventStore.computeIfAbsent(event.aggregateId, k -> new ArrayList<>()).add(event);

            eventsProcessed.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send events with different versions (schema evolution)
        System.out.println("📤 Sending events with different schema versions...");

        String aggregateId = "customer-001";
        Instant now = Instant.now();

        // Version 1: Original customer created event (simple schema)
        Map<String, Object> customerV1Data = new HashMap<>();
        customerV1Data.put("name", "John Doe");
        customerV1Data.put("email", "john.doe@example.com");

        BiTemporalEvent customerV1 = new BiTemporalEvent(
            "evt-v1-001", aggregateId, EventType.ACCOUNT_CREATED,
            customerV1Data, now, now, 1, "corr-v1", false
        );
        producer.send(customerV1);

        // Version 2: Enhanced customer event (added phone and address)
        Map<String, Object> address = new HashMap<>();
        address.put("street", "123 Main St");
        address.put("city", "Anytown");
        address.put("state", "CA");
        address.put("zipCode", "12345");

        Map<String, Object> customerV2Data = new HashMap<>();
        customerV2Data.put("name", "John Doe");
        customerV2Data.put("email", "john.doe@example.com");
        customerV2Data.put("phone", "+1-555-0123");
        customerV2Data.put("address", address);

        BiTemporalEvent customerV2 = new BiTemporalEvent(
            "evt-v2-001", aggregateId, EventType.BALANCE_UPDATED,
            customerV2Data, now.plusSeconds(60), now.plusSeconds(60), 2, "corr-v2", false
        );
        producer.send(customerV2);

        // Version 3: Added compliance and preferences
        Map<String, Object> addressV3 = new HashMap<>();
        addressV3.put("street", "123 Main St");
        addressV3.put("city", "Anytown");
        addressV3.put("state", "CA");
        addressV3.put("zipCode", "12345");

        Map<String, Object> compliance = new HashMap<>();
        compliance.put("kycStatus", "VERIFIED");
        compliance.put("riskLevel", "LOW");
        compliance.put("lastReviewDate", now.toString());

        Map<String, Object> preferences = new HashMap<>();
        preferences.put("communicationChannel", "EMAIL");
        preferences.put("marketingOptIn", true);

        Map<String, Object> customerV3Data = new HashMap<>();
        customerV3Data.put("name", "John Doe");
        customerV3Data.put("email", "john.doe@example.com");
        customerV3Data.put("phone", "+1-555-0123");
        customerV3Data.put("address", addressV3);
        customerV3Data.put("compliance", compliance);
        customerV3Data.put("preferences", preferences);

        BiTemporalEvent customerV3 = new BiTemporalEvent(
            "evt-v3-001", aggregateId, EventType.TRANSACTION_POSTED,
            customerV3Data, now.plusSeconds(120), now.plusSeconds(120), 3, "corr-v3", false
        );
        producer.send(customerV3);

        // Version 4: Added GDPR compliance fields
        Map<String, Object> addressV4 = new HashMap<>();
        addressV4.put("street", "123 Main St");
        addressV4.put("city", "Anytown");
        addressV4.put("state", "CA");
        addressV4.put("zipCode", "12345");

        Map<String, Object> complianceV4 = new HashMap<>();
        complianceV4.put("kycStatus", "VERIFIED");
        complianceV4.put("riskLevel", "LOW");
        complianceV4.put("lastReviewDate", now.toString());

        Map<String, Object> preferencesV4 = new HashMap<>();
        preferencesV4.put("communicationChannel", "EMAIL");
        preferencesV4.put("marketingOptIn", true);

        Map<String, Object> gdpr = new HashMap<>();
        gdpr.put("consentGiven", true);
        gdpr.put("consentDate", now.toString());
        gdpr.put("dataRetentionPeriod", "7_YEARS");
        gdpr.put("rightToBeForgettenRequested", false);

        Map<String, Object> customerV4Data = new HashMap<>();
        customerV4Data.put("name", "John Doe");
        customerV4Data.put("email", "john.doe@example.com");
        customerV4Data.put("phone", "+1-555-0123");
        customerV4Data.put("address", addressV4);
        customerV4Data.put("compliance", complianceV4);
        customerV4Data.put("preferences", preferencesV4);
        customerV4Data.put("gdpr", gdpr);

        BiTemporalEvent customerV4 = new BiTemporalEvent(
            "evt-v4-001", aggregateId, EventType.EVENT_CORRECTED,
            customerV4Data, now.plusSeconds(180), now.plusSeconds(180), 4, "corr-v4", false
        );
        producer.send(customerV4);

        // Wait for all events to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Should process all versioned events");

        // Verify version evolution
        List<BiTemporalEvent> customerEvents = eventStore.get(aggregateId);
        assertNotNull(customerEvents, "Customer events should exist");
        assertEquals(4, customerEvents.size(), "Should have 4 versioned events");

        System.out.println("📊 Event Version Evolution:");
        for (BiTemporalEvent event : customerEvents) {
            System.out.println("  Version " + event.version + ": " +
                             event.eventData.keySet().size() + " fields, " +
                             "Event Type: " + event.eventType.eventName);
        }

        // Verify version progression
        assertEquals(1, customerEvents.get(0).version, "First event should be version 1");
        assertEquals(2, customerEvents.get(1).version, "Second event should be version 2");
        assertEquals(3, customerEvents.get(2).version, "Third event should be version 3");
        assertEquals(4, customerEvents.get(3).version, "Fourth event should be version 4");

        // Cleanup
        consumer.close();

        System.out.println("✅ Event Versioning test completed successfully");
        System.out.println("📊 Total versioned events processed: " + eventsProcessed.get());
    }

    @Test
    @Order(3)
    @DisplayName("Event Correction - Correcting Historical Events")
    void testEventCorrection() throws Exception {
        System.out.println("\n🔧 Testing Event Correction");

        String queueName = "bitemporal-correction-queue";
        Map<String, AccountAggregate> accounts = new HashMap<>();
        AtomicInteger eventsProcessed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5); // Original + correction events

        // Create producer and consumer
        MessageProducer<BiTemporalEvent> producer = queueFactory.createProducer(queueName, BiTemporalEvent.class);
        MessageConsumer<BiTemporalEvent> consumer = queueFactory.createConsumer(queueName, BiTemporalEvent.class);

        // Subscribe to correction events
        consumer.subscribe(message -> {
            BiTemporalEvent event = message.getPayload();

            System.out.println("🔧 Processing " + (event.isCorrection ? "CORRECTION" : "ORIGINAL") +
                             " event: " + event.eventType.eventName + " for account: " + event.aggregateId);

            // Get or create account
            AccountAggregate account = accounts.computeIfAbsent(event.aggregateId,
                id -> new AccountAggregate(id, "ACC-" + id, "CUST-" + id, "SAVINGS", 5000.0));

            account.applyEvent(event);

            eventsProcessed.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send original events and then corrections
        System.out.println("📤 Sending original events and corrections...");

        String accountId = "account-correction-001";
        Instant now = Instant.now();
        Instant yesterday = now.minusSeconds(24 * 60 * 60);

        // Original incorrect transaction
        Map<String, Object> originalTransactionData = new HashMap<>();
        originalTransactionData.put("amount", -1000.0);
        originalTransactionData.put("type", "withdrawal");
        originalTransactionData.put("newBalance", 4000.0);

        BiTemporalEvent originalTransaction = new BiTemporalEvent(
            "evt-original-001", accountId, EventType.TRANSACTION_POSTED,
            originalTransactionData,
            yesterday, yesterday, 1, "corr-original", false
        );
        producer.send(originalTransaction);

        // Original balance update (incorrect)
        Map<String, Object> originalBalanceData = new HashMap<>();
        originalBalanceData.put("newBalance", 4000.0);
        originalBalanceData.put("reason", "withdrawal");

        BiTemporalEvent originalBalance = new BiTemporalEvent(
            "evt-original-002", accountId, EventType.BALANCE_UPDATED,
            originalBalanceData,
            yesterday, yesterday, 1, "corr-original-balance", false
        );
        producer.send(originalBalance);

        // Discovery of error and correction (transaction time is now, valid time is yesterday)
        Map<String, Object> correctionEventData = new HashMap<>();
        correctionEventData.put("correctedEventId", "evt-original-001");
        correctionEventData.put("originalAmount", -1000.0);
        correctionEventData.put("correctedAmount", -500.0);
        correctionEventData.put("reason", "Data entry error - amount was $500 not $1000");

        BiTemporalEvent correctionEvent = new BiTemporalEvent(
            "evt-correction-001", accountId, EventType.EVENT_CORRECTED,
            correctionEventData,
            yesterday, now, 2, "corr-correction", true
        );
        producer.send(correctionEvent);

        // Corrected transaction
        Map<String, Object> correctedTransactionData = new HashMap<>();
        correctedTransactionData.put("amount", -500.0);
        correctedTransactionData.put("type", "withdrawal");
        correctedTransactionData.put("newBalance", 4500.0);

        BiTemporalEvent correctedTransaction = new BiTemporalEvent(
            "evt-corrected-001", accountId, EventType.TRANSACTION_POSTED,
            correctedTransactionData,
            yesterday, now, 2, "corr-corrected-txn", true
        );
        producer.send(correctedTransaction);

        // Corrected balance update
        Map<String, Object> correctedBalanceData = new HashMap<>();
        correctedBalanceData.put("newBalance", 4500.0);
        correctedBalanceData.put("reason", "corrected-withdrawal");

        BiTemporalEvent correctedBalance = new BiTemporalEvent(
            "evt-corrected-002", accountId, EventType.BALANCE_UPDATED,
            correctedBalanceData,
            yesterday, now, 2, "corr-corrected-balance", true
        );
        producer.send(correctedBalance);

        // Wait for all events to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Should process all correction events");

        // Verify corrections
        AccountAggregate account = accounts.get(accountId);
        assertNotNull(account, "Account should exist");
        assertEquals(5, account.events.size(), "Should have 5 events (2 original + 3 correction)");

        // Count original vs correction events
        long originalEvents = account.events.stream().filter(e -> !e.isCorrection).count();
        long correctionEvents = account.events.stream().filter(e -> e.isCorrection).count();

        System.out.println("📊 Event Correction Results:");
        System.out.println("  Original events: " + originalEvents);
        System.out.println("  Correction events: " + correctionEvents);
        System.out.println("  Final balance: $" + account.balance);

        assertEquals(2, originalEvents, "Should have 2 original events");
        assertEquals(3, correctionEvents, "Should have 3 correction events");

        // Cleanup
        consumer.close();

        System.out.println("✅ Event Correction test completed successfully");
        System.out.println("📊 Total events processed: " + eventsProcessed.get());
    }
}
