package dev.mars.peegeeq.examples.patterns.architecture;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demo test showcasing Event Sourcing & CQRS Patterns for PeeGeeQ.
 * 
 * This test demonstrates:
 * 1. Event Sourcing - Storing state changes as events
 * 2. CQRS - Command Query Responsibility Segregation
 * 3. Event Store - Immutable event storage and replay
 * 4. Read Models - Optimized query models from events
 * 5. Snapshots - Performance optimization for event replay
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ Complete Guide.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventSourcingCQRSDemoTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;

    // Event types for event sourcing
    enum EventType {
        ACCOUNT_OPENED("AccountOpened", "Account creation event"),
        MONEY_DEPOSITED("MoneyDeposited", "Deposit transaction"),
        MONEY_WITHDRAWN("MoneyWithdrawn", "Withdrawal transaction"),
        ACCOUNT_FROZEN("AccountFrozen", "Account freeze event"),
        ACCOUNT_UNFROZEN("AccountUnfrozen", "Account unfreeze event"),
        INTEREST_CREDITED("InterestCredited", "Interest credit event"),
        SNAPSHOT_CREATED("SnapshotCreated", "Aggregate snapshot event");

        final String eventName;
        final String description;

        EventType(String eventName, String description) {
            this.eventName = eventName;
            this.description = description;
        }
    }

    // Domain event for event sourcing
    static class DomainEvent {
        public final String eventId;
        public final String aggregateId;
        public final EventType eventType;
        public final JsonObject eventData;
        public final long version;
        public final String timestamp;
        public final String causationId;
        public final String correlationId;

        public DomainEvent(String eventId, String aggregateId, EventType eventType,
                          JsonObject eventData, long version, String causationId, String correlationId) {
            this.eventId = eventId;
            this.aggregateId = aggregateId;
            this.eventType = eventType;
            this.eventData = eventData;
            this.version = version;
            this.timestamp = Instant.now().toString();
            this.timestamp = Instant.now().toString();
            this.causationId = causationId;
            this.correlationId = correlationId;
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("eventId", eventId)
                    .put("aggregateId", aggregateId)
                    .put("eventType", eventType.eventName)
                    .put("eventData", eventData)
                    .put("version", version)
                    .put("timestamp", timestamp.toString())
                    .put("causationId", causationId)
                    .put("correlationId", correlationId);
        }
    }

    // Command for CQRS
    static class Command {
        public final String commandId;
        public final String commandType;
        public final String aggregateId;
        public final JsonObject commandData;
        public final String timestamp;
        public final String userId;

        public Command(String commandId, String commandType, String aggregateId,
                      JsonObject commandData, String userId) {
            this.commandId = commandId;
            this.commandType = commandType;
            this.aggregateId = aggregateId;
            this.commandData = commandData;
            this.timestamp = Instant.now().toString();
            this.timestamp = Instant.now().toString();
            this.userId = userId;
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("commandId", commandId)
                    .put("commandType", commandType)
                    .put("aggregateId", aggregateId)
                    .put("commandData", commandData)
                    .put("timestamp", timestamp.toString())
                    .put("userId", userId);
        }
    }

    // Bank account aggregate for event sourcing
    static class BankAccountAggregate {
        public final String accountId;
        public final String accountNumber;
        public final String customerId;
        public volatile double balance;
        public volatile boolean isFrozen;
        public volatile long version;
        public final List<DomainEvent> uncommittedEvents = new ArrayList<>();
        public final String createdAt;

        public BankAccountAggregate(String accountId, String accountNumber, String customerId, double initialBalance) {
            this.accountId = accountId;
            this.accountNumber = accountNumber;
            this.customerId = customerId;
            this.balance = initialBalance;
            this.isFrozen = false;
            this.version = 0;
            this.createdAt = Instant.now().toString();
        }

        // Command handlers
        public void openAccount(String commandId, double initialDeposit) {
            if (version > 0) {
                throw new IllegalStateException("Account already opened");
            }
            
            JsonObject eventData = new JsonObject()
                .put("accountNumber", accountNumber)
                .put("customerId", customerId)
                .put("initialDeposit", initialDeposit);

            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.ACCOUNT_OPENED,
                eventData.getMap(),
                version + 1, commandId, commandId
            );
            
            applyEvent(event);
        }

        public void deposit(String commandId, double amount) {
            if (amount <= 0) {
                throw new IllegalArgumentException("Deposit amount must be positive");
            }
            if (isFrozen) {
                throw new IllegalStateException("Cannot deposit to frozen account");
            }
            
            JsonObject eventData = new JsonObject()
                .put("amount", amount)
                .put("previousBalance", balance)
                .put("newBalance", balance + amount);

            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.MONEY_DEPOSITED,
                eventData.getMap(),
                version + 1, commandId, commandId
            );
            
            applyEvent(event);
        }

        public void withdraw(String commandId, double amount) {
            if (amount <= 0) {
                throw new IllegalArgumentException("Withdrawal amount must be positive");
            }
            if (isFrozen) {
                throw new IllegalStateException("Cannot withdraw from frozen account");
            }
            if (balance < amount) {
                throw new IllegalStateException("Insufficient funds");
            }
            
            JsonObject eventData = new JsonObject()
                .put("amount", amount)
                .put("previousBalance", balance)
                .put("newBalance", balance - amount);

            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.MONEY_WITHDRAWN,
                eventData.getMap(),
                version + 1, commandId, commandId
            );
            
            applyEvent(event);
        }

        public void freeze(String commandId, String reason) {
            if (isFrozen) {
                throw new IllegalStateException("Account already frozen");
            }
            
            JsonObject eventData = new JsonObject()
                .put("reason", reason)
                .put("frozenAt", Instant.now().toString());

            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.ACCOUNT_FROZEN,
                eventData.getMap(),
                version + 1, commandId, commandId
            );
            
            applyEvent(event);
        }

        // Event application
        private void applyEvent(DomainEvent event) {
            switch (event.eventType) {
                case ACCOUNT_OPENED:
                    this.balance = ((Number) event.eventData.get("initialDeposit")).doubleValue();
                    break;
                case MONEY_DEPOSITED:
                    this.balance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    break;
                case MONEY_WITHDRAWN:
                    this.balance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    break;
                case ACCOUNT_FROZEN:
                    this.isFrozen = true;
                    break;
                case ACCOUNT_UNFROZEN:
                    this.isFrozen = false;
                    break;
                case INTEREST_CREDITED:
                    this.balance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    break;
                case SNAPSHOT_CREATED:
                    // Handle snapshot creation if needed
                    break;
            }
            
            this.version = event.version;
            this.uncommittedEvents.add(event);
        }

        public List<DomainEvent> getUncommittedEvents() {
            return new ArrayList<>(uncommittedEvents);
        }

        public void markEventsAsCommitted() {
            uncommittedEvents.clear();
        }

        // Replay events for event sourcing
        public static BankAccountAggregate fromEvents(String accountId, List<DomainEvent> events) {
            if (events.isEmpty()) {
                throw new IllegalArgumentException("Cannot create aggregate from empty event stream");
            }
            
            DomainEvent firstEvent = events.get(0);
            if (firstEvent.eventType != EventType.ACCOUNT_OPENED) {
                throw new IllegalArgumentException("First event must be AccountOpened");
            }
            
            BankAccountAggregate aggregate = new BankAccountAggregate(
                accountId,
                (String) firstEvent.eventData.get("accountNumber"),
                (String) firstEvent.eventData.get("customerId"),
                0.0
            );
            
            // Clear uncommitted events since we're replaying
            aggregate.uncommittedEvents.clear();
            
            // Apply all events
            for (DomainEvent event : events) {
                aggregate.applyEventFromHistory(event);
            }
            
            return aggregate;
        }

        private void applyEventFromHistory(DomainEvent event) {
            switch (event.eventType) {
                case ACCOUNT_OPENED:
                    this.balance = ((Number) event.eventData.get("initialDeposit")).doubleValue();
                    break;
                case MONEY_DEPOSITED:
                    this.balance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    break;
                case MONEY_WITHDRAWN:
                    this.balance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    break;
                case ACCOUNT_FROZEN:
                    this.isFrozen = true;
                    break;
                case ACCOUNT_UNFROZEN:
                    this.isFrozen = false;
                    break;
                case INTEREST_CREDITED:
                    this.balance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    break;
                case SNAPSHOT_CREATED:
                    // Handle snapshot creation if needed
                    break;
            }
            
            this.version = event.version;
        }
    }

    // Read model for CQRS queries
    static class AccountReadModel {
        public final String accountId;
        public final String accountNumber;
        public final String customerId;
        public volatile double currentBalance;
        public volatile boolean isFrozen;
        public volatile int totalTransactions;
        public volatile double totalDeposits;
        public volatile double totalWithdrawals;
        public volatile String lastTransactionTime;
        public volatile long lastProcessedVersion;

        public AccountReadModel(String accountId, String accountNumber, String customerId) {
            this.accountId = accountId;
            this.accountNumber = accountNumber;
            this.customerId = customerId;
            this.currentBalance = 0.0;
            this.isFrozen = false;
            this.totalTransactions = 0;
            this.totalDeposits = 0.0;
            this.totalWithdrawals = 0.0;
            this.lastTransactionTime = Instant.now().toString();
            this.lastProcessedVersion = 0;
        }

        public void applyEvent(DomainEvent event) {
            if (event.version <= lastProcessedVersion) {
                return; // Already processed
            }
            
            switch (event.eventType) {
                case ACCOUNT_OPENED:
                    this.currentBalance = ((Number) event.eventData.get("initialDeposit")).doubleValue();
                    this.totalDeposits += ((Number) event.eventData.get("initialDeposit")).doubleValue();
                    this.totalTransactions++;
                    break;
                case MONEY_DEPOSITED:
                    double depositAmount = ((Number) event.eventData.get("amount")).doubleValue();
                    this.currentBalance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    this.totalDeposits += depositAmount;
                    this.totalTransactions++;
                    break;
                case MONEY_WITHDRAWN:
                    double withdrawalAmount = ((Number) event.eventData.get("amount")).doubleValue();
                    this.currentBalance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    this.totalWithdrawals += withdrawalAmount;
                    this.totalTransactions++;
                    break;
                case ACCOUNT_FROZEN:
                    this.isFrozen = true;
                    break;
                case ACCOUNT_UNFROZEN:
                    this.isFrozen = false;
                    break;
                case INTEREST_CREDITED:
                    double interestAmount = ((Number) event.eventData.get("amount")).doubleValue();
                    this.currentBalance = ((Number) event.eventData.get("newBalance")).doubleValue();
                    this.totalDeposits += interestAmount;
                    this.totalTransactions++;
                    break;
                case SNAPSHOT_CREATED:
                    // Handle snapshot creation if needed
                    break;
            }
            
            this.lastTransactionTime = Instant.parse(event.timestamp);
            this.lastProcessedVersion = event.version;
        }

        public JsonObject toJson() {
            return new JsonObject()
                    .put("accountId", accountId)
                    .put("accountNumber", accountNumber)
                    .put("customerId", customerId)
                    .put("currentBalance", currentBalance)
                    .put("isFrozen", isFrozen)
                    .put("totalTransactions", totalTransactions)
                    .put("totalDeposits", totalDeposits)
                    .put("totalWithdrawals", totalWithdrawals)
                    .put("lastTransactionTime", lastTransactionTime.toString())
                    .put("lastProcessedVersion", lastProcessedVersion);
        }
    }

    @BeforeEach
    void setUp() {
        System.out.println("\n🏗️ Setting up Event Sourcing & CQRS Demo Test");

        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer(postgres);

        // Initialize PeeGeeQ with event sourcing configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("development");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        queueFactory = provider.createFactory("native", databaseService);

        System.out.println("✅ Setup complete - Ready for event sourcing & CQRS pattern testing");
    }

    @AfterEach
    void tearDown() {
        System.out.println("🧹 Cleaning up Event Sourcing & CQRS Demo Test");
        
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

    /**
     * Demonstrates Event Sourcing pattern using PeeGeeQ message queues.
     *
     * Event Sourcing stores all changes to application state as a sequence of events.
     * These events can be replayed to reconstruct the current state of any aggregate.
     *
     * This test demonstrates:
     * 1. Command processing that generates domain events
     * 2. Event storage in an event store
     * 3. Event replay to reconstruct aggregate state
     * 4. Proper event versioning and ordering
     *
     * ⚠️ IMPORTANT: This test includes several workarounds for demo purposes that would NOT
     * be used in production systems:
     *
     * - Thread.sleep() calls to ensure command ordering (production would use proper ordering mechanisms)
     * - Manual event sorting by version (production event stores handle this automatically)
     * - Simplified aggregate lifecycle management (production would use proper repositories)
     * - In-memory storage (production would use persistent event stores)
     * - CountDownLatch for test coordination (production would use proper async handling)
     */
    @Test
    @Order(1)
    @DisplayName("Event Sourcing - Storing State Changes as Events")
    void testEventSourcing() throws Exception {
        System.err.println("=== TEST METHOD STARTED: testEventSourcing ===");
        System.err.flush();
        System.out.println("\n📚 Testing Event Sourcing");

        // Queue names for command and event streams
        String commandQueue = "eventsourcing-commands-queue";
        String eventQueue = "eventsourcing-events-queue";

        // In-memory event store and aggregate cache for demo purposes
        // 🚨 PRODUCTION NOTE: These would be replaced with proper persistence layers
        Map<String, List<DomainEvent>> eventStore = new HashMap<>();
        Map<String, BankAccountAggregate> aggregates = new HashMap<>();

        // Counters and latches for test coordination
        // 🚨 TEST-ONLY: These are test-specific constructs for synchronization
        AtomicInteger commandsProcessed = new AtomicInteger(0);
        AtomicInteger eventsStored = new AtomicInteger(0);
        CountDownLatch commandLatch = new CountDownLatch(5);  // Expecting 5 commands
        CountDownLatch eventLatch = new CountDownLatch(5);    // Expecting 5 events

        // Create producers and consumers
        MessageProducer<Command> commandProducer = queueFactory.createProducer(commandQueue, Command.class);
        MessageConsumer<Command> commandConsumer = queueFactory.createConsumer(commandQueue, Command.class);
        MessageProducer<DomainEvent> eventProducer = queueFactory.createProducer(eventQueue, DomainEvent.class);
        MessageConsumer<DomainEvent> eventConsumer = queueFactory.createConsumer(eventQueue, DomainEvent.class);

        // Command handler - processes commands and generates events
        // This simulates a command handler in an event-sourced system
        commandConsumer.subscribe(message -> {
            Command command = message.getPayload();

            System.out.println("📚 Processing command: " + command.commandType + " for aggregate: " + command.aggregateId);

            try {
                BankAccountAggregate aggregate;

                // 🚨 WORKAROUND: Handle OpenAccount command specially to avoid race conditions
                // PRODUCTION NOTE: In real systems, this would be handled by proper aggregate repositories
                // and command ordering mechanisms, not manual checks like this
                if ("OpenAccount".equals(command.commandType)) {
                    // For OpenAccount, create a new aggregate if it doesn't exist
                    if (aggregates.containsKey(command.aggregateId)) {
                        throw new IllegalStateException("Account already opened");
                    }
                    // Create new aggregate with initial state
                    aggregate = new BankAccountAggregate(command.aggregateId,
                        "ACC-" + command.aggregateId.substring(0, 8),
                        "CUST-" + command.aggregateId.substring(0, 8), 0.0);
                    aggregates.put(command.aggregateId, aggregate);

                    // Process the OpenAccount command
                    double initialDeposit = (Double) command.commandData.get("initialDeposit");
                    aggregate.openAccount(command.commandId, initialDeposit);
                } else {
                    // For other commands, get existing aggregate
                    // 🚨 PRODUCTION NOTE: Real systems would load aggregates from event store
                    // by replaying all events for the aggregate, not from an in-memory cache
                    aggregate = aggregates.get(command.aggregateId);
                    if (aggregate == null) {
                        throw new IllegalStateException("Account not found: " + command.aggregateId);
                    }

                    // Handle business commands - each generates domain events
                    switch (command.commandType) {
                        case "Deposit":
                            double depositAmount = (Double) command.commandData.get("amount");
                            aggregate.deposit(command.commandId, depositAmount);
                            break;
                        case "Withdraw":
                            double withdrawAmount = (Double) command.commandData.get("amount");
                            aggregate.withdraw(command.commandId, withdrawAmount);
                            break;
                        case "FreezeAccount":
                            String reason = (String) command.commandData.get("reason");
                            aggregate.freeze(command.commandId, reason);
                            break;
                    }
                }

                // Publish uncommitted events to the event stream
                // In event sourcing, commands generate events that represent state changes
                for (DomainEvent event : aggregate.getUncommittedEvents()) {
                    eventProducer.send(event);
                }

                // Mark events as committed (they've been published)
                // 🚨 PRODUCTION NOTE: In real systems, this would be part of a transaction
                // ensuring events are both stored and published atomically
                aggregate.markEventsAsCommitted();

                commandsProcessed.incrementAndGet();

            } catch (Exception e) {
                System.err.println("❌ Error processing command " + command.commandId + ": " + e.getMessage());
                // 🚨 PRODUCTION NOTE: Real systems would have proper error handling,
                // dead letter queues, and retry mechanisms
            }

            // 🚨 TEST-ONLY: Count down latch for test synchronization
            commandLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Event store - stores events for replay (the heart of event sourcing)
        // This simulates an event store that persists all domain events
        eventConsumer.subscribe(message -> {
            DomainEvent event = message.getPayload();

            System.out.println("📚 Storing event: " + event.eventType.eventName +
                             " v" + event.version + " for aggregate: " + event.aggregateId);

            // Store event in event store
            // 🚨 PRODUCTION NOTE: Real event stores would:
            // - Ensure atomic writes with proper transactions
            // - Handle concurrency with optimistic locking
            // - Provide efficient querying by aggregate ID
            // - Support event snapshots for performance
            eventStore.computeIfAbsent(event.aggregateId, k -> new ArrayList<>()).add(event);

            eventsStored.incrementAndGet();
            // 🚨 TEST-ONLY: Count down latch for test synchronization
            eventLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send commands to demonstrate event sourcing
        System.out.println("📤 Sending commands for event sourcing demonstration...");

        String accountId = "account-es-001";

        // Command 1: Open account (must be first to create the aggregate)
        Map<String, Object> openAccountData = new HashMap<>();
        openAccountData.put("initialDeposit", 1000.0);

        Command openAccount = new Command(
            "cmd-001", "OpenAccount", accountId,
            openAccountData,
            "user-001"
        );
        commandProducer.send(openAccount);

        // 🚨 WORKAROUND: Small delay to ensure OpenAccount is processed first
        // PRODUCTION NOTE: Real systems would use:
        // - Proper command ordering mechanisms (sequence numbers, timestamps)
        // - Saga patterns for complex workflows
        // - Event-driven state machines
        // - NOT Thread.sleep() which is unreliable and blocks threads
        Thread.sleep(100);

        // Command 2: Deposit money (first business transaction)
        Map<String, Object> deposit1Data = new HashMap<>();
        deposit1Data.put("amount", 500.0);

        Command deposit1 = new Command(
            "cmd-002", "Deposit", accountId,
            deposit1Data,
            "user-001"
        );
        commandProducer.send(deposit1);

        // 🚨 WORKAROUND: Small delay between commands for ordering
        Thread.sleep(50);

        // Command 3: Withdraw money (test withdrawal logic)
        Map<String, Object> withdraw1Data = new HashMap<>();
        withdraw1Data.put("amount", 200.0);

        Command withdraw1 = new Command(
            "cmd-003", "Withdraw", accountId,
            withdraw1Data,
            "user-001"
        );
        commandProducer.send(withdraw1);

        // 🚨 WORKAROUND: Small delay between commands for ordering
        Thread.sleep(50);

        // Command 4: Another deposit (test multiple deposits)
        Map<String, Object> deposit2Data = new HashMap<>();
        deposit2Data.put("amount", 750.0);

        Command deposit2 = new Command(
            "cmd-004", "Deposit", accountId,
            deposit2Data,
            "user-001"
        );
        commandProducer.send(deposit2);

        // 🚨 WORKAROUND: Small delay between commands for ordering
        Thread.sleep(50);

        // Command 5: Freeze account (administrative action)
        Map<String, Object> freezeAccountData = new HashMap<>();
        freezeAccountData.put("reason", "Suspicious activity detected");

        Command freezeAccount = new Command(
            "cmd-005", "FreezeAccount", accountId,
            freezeAccountData,
            "admin-001"
        );
        commandProducer.send(freezeAccount);

        // Wait for all commands and events to be processed
        // 🚨 TEST-ONLY: Using CountDownLatch for synchronization in tests
        assertTrue(commandLatch.await(30, TimeUnit.SECONDS), "Should process all commands");
        assertTrue(eventLatch.await(30, TimeUnit.SECONDS), "Should store all events");

        // Verify event sourcing metrics
        assertEquals(5, commandsProcessed.get(), "Should have processed 5 commands");
        assertEquals(5, eventsStored.get(), "Should have stored 5 events");

        // Retrieve events from the event store for verification
        List<DomainEvent> accountEvents = eventStore.get(accountId);
        assertNotNull(accountEvents, "Should have events for account");
        assertEquals(5, accountEvents.size(), "Should have 5 events stored");

        // 🚨 WORKAROUND: Sort events by version to ensure correct order
        // PRODUCTION NOTE: Real event stores guarantee ordering automatically
        // through sequence numbers, timestamps, or append-only logs
        accountEvents.sort((e1, e2) -> Long.compare(e1.version, e2.version));

        // Verify event sequence matches the expected business flow
        // This demonstrates that events capture the complete history of state changes
        assertEquals(EventType.ACCOUNT_OPENED, accountEvents.get(0).eventType, "First event should be AccountOpened");
        assertEquals(EventType.MONEY_DEPOSITED, accountEvents.get(1).eventType, "Second event should be MoneyDeposited");
        assertEquals(EventType.MONEY_WITHDRAWN, accountEvents.get(2).eventType, "Third event should be MoneyWithdrawn");
        assertEquals(EventType.MONEY_DEPOSITED, accountEvents.get(3).eventType, "Fourth event should be MoneyDeposited");
        assertEquals(EventType.ACCOUNT_FROZEN, accountEvents.get(4).eventType, "Fifth event should be AccountFrozen");

        // Test event replay - the core benefit of event sourcing
        // This demonstrates how we can reconstruct aggregate state from events
        System.out.println("🔄 Testing event replay...");
        BankAccountAggregate replayedAggregate = BankAccountAggregate.fromEvents(accountId, accountEvents);

        // Verify that replaying events produces the same final state
        // This proves that events contain all necessary information to reconstruct state
        assertEquals(2050.0, replayedAggregate.balance, 0.01, "Replayed balance should be correct");
        assertTrue(replayedAggregate.isFrozen, "Replayed account should be frozen");
        assertEquals(5, replayedAggregate.version, "Replayed version should be 5");

        // Display results to show the complete event sourcing flow
        System.out.println("📊 Event Sourcing Results:");
        System.out.println("  Commands processed: " + commandsProcessed.get());
        System.out.println("  Events stored: " + eventsStored.get());
        System.out.println("  Final balance: $" + replayedAggregate.balance);
        System.out.println("  Account frozen: " + replayedAggregate.isFrozen);

        // Cleanup resources
        commandConsumer.close();
        eventConsumer.close();

        System.out.println("✅ Event Sourcing test completed successfully");

        // 🎯 KEY BENEFITS DEMONSTRATED:
        // 1. Complete audit trail - every state change is recorded as an event
        // 2. Time travel - can reconstruct state at any point in time by replaying events
        // 3. Debugging - can replay events to understand exactly what happened
        // 4. Analytics - can analyze historical patterns and trends from event history
        // 5. Scalability - events can be processed by multiple read models independently
    }

    /**
     * Demonstrates CQRS (Command Query Responsibility Segregation) pattern using PeeGeeQ.
     *
     * CQRS separates read and write operations into different models:
     * - Write Model: Optimized for handling commands and business logic
     * - Read Model: Optimized for queries and data presentation
     *
     * This test demonstrates:
     * 1. Command side processing (write model)
     * 2. Event-driven read model updates
     * 3. Separation of concerns between writes and reads
     * 4. Different data structures for different purposes
     *
     * ⚠️ IMPORTANT: This test includes the same workarounds as the Event Sourcing test
     * that would NOT be used in production systems. See Event Sourcing test comments
     * for detailed explanations of these workarounds.
     */
    @Test
    @Order(2)
    @DisplayName("CQRS - Command Query Responsibility Segregation")
    void testCQRS() throws Exception {
        System.out.println("\n🔍 Testing CQRS");

        // Queue names for command and event streams
        String commandQueue = "cqrs-commands-queue";
        String eventQueue = "cqrs-events-queue";

        // Separate models for writes and reads - the core of CQRS
        // 🚨 PRODUCTION NOTE: These would be backed by different databases
        // optimized for their specific use cases (e.g., normalized vs denormalized)
        Map<String, BankAccountAggregate> writeModel = new HashMap<>();  // Optimized for business logic
        Map<String, AccountReadModel> readModel = new HashMap<>();       // Optimized for queries

        // Test coordination constructs
        AtomicInteger commandsProcessed = new AtomicInteger(0);
        AtomicInteger eventsProcessed = new AtomicInteger(0);
        CountDownLatch commandLatch = new CountDownLatch(4);  // Expecting 4 commands
        CountDownLatch eventLatch = new CountDownLatch(4);    // Expecting 4 events

        // Create producers and consumers
        MessageProducer<Command> commandProducer = queueFactory.createProducer(commandQueue, Command.class);
        MessageConsumer<Command> commandConsumer = queueFactory.createConsumer(commandQueue, Command.class);
        MessageProducer<DomainEvent> eventProducer = queueFactory.createProducer(eventQueue, DomainEvent.class);
        MessageConsumer<DomainEvent> eventConsumer = queueFactory.createConsumer(eventQueue, DomainEvent.class);

        // WRITE SIDE - Command processing (handles business logic and state changes)
        // In CQRS, the write side is optimized for handling commands and enforcing business rules
        commandConsumer.subscribe(message -> {
            Command command = message.getPayload();

            System.out.println("🔍 WRITE SIDE - Processing command: " + command.commandType);

            try {
                BankAccountAggregate aggregate;

                // 🚨 WORKAROUND: Same aggregate creation logic as Event Sourcing test
                // PRODUCTION NOTE: See Event Sourcing test for detailed comments on this approach
                if ("OpenAccount".equals(command.commandType)) {
                    // For OpenAccount, create a new aggregate if it doesn't exist
                    if (writeModel.containsKey(command.aggregateId)) {
                        throw new IllegalStateException("Account already opened");
                    }
                    // Create aggregate in write model (optimized for business logic)
                    aggregate = new BankAccountAggregate(command.aggregateId,
                        "ACC-" + command.aggregateId.substring(0, 8),
                        "CUST-" + command.aggregateId.substring(0, 8), 0.0);
                    writeModel.put(command.aggregateId, aggregate);

                    double initialDeposit = (Double) command.commandData.get("initialDeposit");
                    aggregate.openAccount(command.commandId, initialDeposit);
                } else {
                    // For other commands, get existing aggregate from write model
                    aggregate = writeModel.get(command.aggregateId);
                    if (aggregate == null) {
                        throw new IllegalStateException("Account not found: " + command.aggregateId);
                    }

                    // Handle business commands (write model focuses on business logic)
                    switch (command.commandType) {
                        case "Deposit":
                            double depositAmount = (Double) command.commandData.get("amount");
                            aggregate.deposit(command.commandId, depositAmount);
                            break;
                        case "Withdraw":
                            double withdrawAmount = (Double) command.commandData.get("amount");
                            aggregate.withdraw(command.commandId, withdrawAmount);
                            break;
                    }
                }

                // Publish events
                for (DomainEvent event : aggregate.getUncommittedEvents()) {
                    eventProducer.send(event);
                }

                aggregate.markEventsAsCommitted();
                commandsProcessed.incrementAndGet();

            } catch (Exception e) {
                System.err.println("❌ Command processing error: " + e.getMessage());
            }

            commandLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // READ SIDE - Event processing (updates read models for queries)
        // In CQRS, the read side is optimized for queries and data presentation
        eventConsumer.subscribe(message -> {
            DomainEvent event = message.getPayload();

            System.out.println("🔍 READ SIDE - Processing event: " + event.eventType.eventName +
                             " for read model update");

            // Update read model based on event
            // 🎯 KEY CONCEPT: Read models are denormalized and optimized for specific queries
            // They can have completely different structure than the write model
            AccountReadModel readModelAggregate = readModel.computeIfAbsent(event.aggregateId,
                id -> {
                    if (event.eventType == EventType.ACCOUNT_OPENED) {
                        // Extract data from the AccountOpened event for read model initialization
                        return new AccountReadModel(id,
                            (String) event.eventData.get("accountNumber"),
                            (String) event.eventData.get("customerId"));
                    }
                    // Fallback for other event types (shouldn't happen in normal flow)
                    return new AccountReadModel(id, "ACC-" + id.substring(0, 8), "CUST-" + id.substring(0, 8));
                });

            // Apply event to read model (may update multiple denormalized fields)
            // 🚨 PRODUCTION NOTE: Read models can be updated asynchronously
            // and may have eventual consistency with the write model
            readModelAggregate.applyEvent(event);
            eventsProcessed.incrementAndGet();
            eventLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send commands for CQRS demonstration
        // Note: No Thread.sleep() needed here as CQRS focuses on separation of concerns,
        // not strict event ordering like the Event Sourcing test
        System.out.println("📤 Sending commands for CQRS demonstration...");

        String accountId = "account-cqrs-001";

        // Command 1: Open account (processed by write side, creates events for read side)
        Map<String, Object> openAccountData = new HashMap<>();
        openAccountData.put("initialDeposit", 2000.0);

        Command openAccount = new Command(
            "cqrs-cmd-001", "OpenAccount", accountId,
            openAccountData,
            "user-002"
        );
        commandProducer.send(openAccount);

        // Command 2: Multiple deposits (write side processes, read side gets updated via events)
        Map<String, Object> deposit1Data = new HashMap<>();
        deposit1Data.put("amount", 300.0);

        Command deposit1 = new Command(
            "cqrs-cmd-002", "Deposit", accountId,
            deposit1Data,
            "user-002"
        );
        commandProducer.send(deposit1);

        // Command 3: Another deposit (demonstrates multiple transactions)
        Map<String, Object> deposit2Data = new HashMap<>();
        deposit2Data.put("amount", 150.0);

        Command deposit2 = new Command(
            "cqrs-cmd-003", "Deposit", accountId,
            deposit2Data,
            "user-002"
        );
        commandProducer.send(deposit2);

        // Command 4: Withdrawal (final transaction to test read model calculations)
        Map<String, Object> withdraw1Data = new HashMap<>();
        withdraw1Data.put("amount", 400.0);

        Command withdraw1 = new Command(
            "cqrs-cmd-004", "Withdraw", accountId,
            withdraw1Data,
            "user-002"
        );
        commandProducer.send(withdraw1);

        // Wait for processing (test synchronization)
        assertTrue(commandLatch.await(30, TimeUnit.SECONDS), "Should process all commands");
        assertTrue(eventLatch.await(30, TimeUnit.SECONDS), "Should process all events");

        // Verify CQRS separation - both sides processed the same number of operations
        assertEquals(4, commandsProcessed.get(), "Should have processed 4 commands");
        assertEquals(4, eventsProcessed.get(), "Should have processed 4 events");

        // Verify write model (optimized for business logic and commands)
        BankAccountAggregate writeAggregate = writeModel.get(accountId);
        assertNotNull(writeAggregate, "Write model should exist");
        assertEquals(2050.0, writeAggregate.balance, 0.01, "Write model balance should be correct");

        // Verify read model (optimized for queries and reporting)
        // 🎯 KEY CONCEPT: Read model has different structure and additional calculated fields
        AccountReadModel readAggregate = readModel.get(accountId);
        assertNotNull(readAggregate, "Read model should exist");
        assertEquals(2050.0, readAggregate.currentBalance, 0.01, "Read model balance should match write model");
        assertEquals(4, readAggregate.totalTransactions, "Read model should track transaction count");
        assertEquals(2450.0, readAggregate.totalDeposits, 0.01, "Read model should track total deposits");
        assertEquals(400.0, readAggregate.totalWithdrawals, 0.01, "Read model should track total withdrawals");

        // Display results showing the separation between write and read models
        System.out.println("📊 CQRS Results:");
        System.out.println("  Write Model Balance: $" + writeAggregate.balance);
        System.out.println("  Read Model Balance: $" + readAggregate.currentBalance);
        System.out.println("  Read Model Transactions: " + readAggregate.totalTransactions);
        System.out.println("  Read Model Total Deposits: $" + readAggregate.totalDeposits);
        System.out.println("  Read Model Total Withdrawals: $" + readAggregate.totalWithdrawals);

        // Cleanup resources
        commandConsumer.close();
        eventConsumer.close();

        System.out.println("✅ CQRS test completed successfully");
    }

    /**
     * Configures system properties to use the TestContainer database.
     */
    private void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
    }
}
