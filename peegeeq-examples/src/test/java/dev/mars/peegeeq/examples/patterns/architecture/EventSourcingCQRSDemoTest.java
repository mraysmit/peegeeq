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
            
            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.ACCOUNT_OPENED,
                new JsonObject()
                    .put("accountNumber", accountNumber)
                    .put("customerId", customerId)
                    .put("initialDeposit", initialDeposit),
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
            
            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.MONEY_DEPOSITED,
                new JsonObject()
                    .put("amount", amount)
                    .put("previousBalance", balance)
                    .put("newBalance", balance + amount),
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
            
            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.MONEY_WITHDRAWN,
                new JsonObject()
                    .put("amount", amount)
                    .put("previousBalance", balance)
                    .put("newBalance", balance - amount),
                version + 1, commandId, commandId
            );
            
            applyEvent(event);
        }

        public void freeze(String commandId, String reason) {
            if (isFrozen) {
                throw new IllegalStateException("Account already frozen");
            }
            
            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.ACCOUNT_FROZEN,
                new JsonObject()
                    .put("reason", reason)
                    .put("frozenAt", Instant.now().toString()),
                version + 1, commandId, commandId
            );
            
            applyEvent(event);
        }

        // Event application
        private void applyEvent(DomainEvent event) {
            switch (event.eventType) {
                case ACCOUNT_OPENED:
                    this.balance = event.eventData.getDouble("initialDeposit");
                    break;
                case MONEY_DEPOSITED:
                    this.balance = event.eventData.getDouble("newBalance");
                    break;
                case MONEY_WITHDRAWN:
                    this.balance = event.eventData.getDouble("newBalance");
                    break;
                case ACCOUNT_FROZEN:
                    this.isFrozen = true;
                    break;
                case ACCOUNT_UNFROZEN:
                    this.isFrozen = false;
                    break;
                case INTEREST_CREDITED:
                    this.balance = event.eventData.getDouble("newBalance");
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
                firstEvent.eventData.getString("accountNumber"),
                firstEvent.eventData.getString("customerId"),
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
                    this.balance = event.eventData.getDouble("initialDeposit");
                    break;
                case MONEY_DEPOSITED:
                    this.balance = event.eventData.getDouble("newBalance");
                    break;
                case MONEY_WITHDRAWN:
                    this.balance = event.eventData.getDouble("newBalance");
                    break;
                case ACCOUNT_FROZEN:
                    this.isFrozen = true;
                    break;
                case ACCOUNT_UNFROZEN:
                    this.isFrozen = false;
                    break;
                case INTEREST_CREDITED:
                    this.balance = event.eventData.getDouble("newBalance");
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
                    this.currentBalance = event.eventData.getDouble("initialDeposit");
                    this.totalDeposits += event.eventData.getDouble("initialDeposit");
                    this.totalTransactions++;
                    break;
                case MONEY_DEPOSITED:
                    double depositAmount = event.eventData.getDouble("amount");
                    this.currentBalance = event.eventData.getDouble("newBalance");
                    this.totalDeposits += depositAmount;
                    this.totalTransactions++;
                    break;
                case MONEY_WITHDRAWN:
                    double withdrawalAmount = event.eventData.getDouble("amount");
                    this.currentBalance = event.eventData.getDouble("newBalance");
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
                    double interestAmount = event.eventData.getDouble("amount");
                    this.currentBalance = event.eventData.getDouble("newBalance");
                    this.totalDeposits += interestAmount;
                    this.totalTransactions++;
                    break;
            }
            
            this.lastTransactionTime = event.timestamp;
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

    @Test
    @Order(1)
    @DisplayName("Event Sourcing - Storing State Changes as Events")
    void testEventSourcing() throws Exception {
        System.out.println("\n📚 Testing Event Sourcing");

        String commandQueue = "eventsourcing-commands-queue";
        String eventQueue = "eventsourcing-events-queue";

        Map<String, List<DomainEvent>> eventStore = new HashMap<>();
        Map<String, BankAccountAggregate> aggregates = new HashMap<>();
        AtomicInteger commandsProcessed = new AtomicInteger(0);
        AtomicInteger eventsStored = new AtomicInteger(0);
        CountDownLatch commandLatch = new CountDownLatch(5);
        CountDownLatch eventLatch = new CountDownLatch(5);

        // Create producers and consumers
        MessageProducer<Command> commandProducer = queueFactory.createProducer(commandQueue, Command.class);
        MessageConsumer<Command> commandConsumer = queueFactory.createConsumer(commandQueue, Command.class);
        MessageProducer<DomainEvent> eventProducer = queueFactory.createProducer(eventQueue, DomainEvent.class);
        MessageConsumer<DomainEvent> eventConsumer = queueFactory.createConsumer(eventQueue, DomainEvent.class);

        // Command handler - processes commands and generates events
        commandConsumer.subscribe(message -> {
            Command command = message.getPayload();

            System.out.println("📚 Processing command: " + command.commandType + " for aggregate: " + command.aggregateId);

            try {
                // Get or create aggregate
                BankAccountAggregate aggregate = aggregates.computeIfAbsent(command.aggregateId,
                    id -> new BankAccountAggregate(id, "ACC-" + id.substring(0, 8), "CUST-" + id.substring(0, 8), 0.0));

                // Handle command
                switch (command.commandType) {
                    case "OpenAccount":
                        double initialDeposit = command.commandData.getDouble("initialDeposit");
                        aggregate.openAccount(command.commandId, initialDeposit);
                        break;
                    case "Deposit":
                        double depositAmount = command.commandData.getDouble("amount");
                        aggregate.deposit(command.commandId, depositAmount);
                        break;
                    case "Withdraw":
                        double withdrawAmount = command.commandData.getDouble("amount");
                        aggregate.withdraw(command.commandId, withdrawAmount);
                        break;
                    case "FreezeAccount":
                        String reason = command.commandData.getString("reason");
                        aggregate.freeze(command.commandId, reason);
                        break;
                }

                // Publish uncommitted events
                for (DomainEvent event : aggregate.getUncommittedEvents()) {
                    eventProducer.send(event);
                }

                // Mark events as committed
                aggregate.markEventsAsCommitted();

                commandsProcessed.incrementAndGet();

            } catch (Exception e) {
                System.err.println("❌ Error processing command " + command.commandId + ": " + e.getMessage());
            }

            commandLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Event store - stores events for replay
        eventConsumer.subscribe(message -> {
            DomainEvent event = message.getPayload();

            System.out.println("📚 Storing event: " + event.eventType.eventName +
                             " v" + event.version + " for aggregate: " + event.aggregateId);

            // Store event in event store
            eventStore.computeIfAbsent(event.aggregateId, k -> new ArrayList<>()).add(event);

            eventsStored.incrementAndGet();
            eventLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send commands to demonstrate event sourcing
        System.out.println("📤 Sending commands for event sourcing demonstration...");

        String accountId = "account-es-001";

        // Command 1: Open account
        Command openAccount = new Command(
            "cmd-001", "OpenAccount", accountId,
            new JsonObject().put("initialDeposit", 1000.0),
            "user-001"
        );
        commandProducer.send(openAccount);

        // Command 2: Deposit money
        Command deposit1 = new Command(
            "cmd-002", "Deposit", accountId,
            new JsonObject().put("amount", 500.0),
            "user-001"
        );
        commandProducer.send(deposit1);

        // Command 3: Withdraw money
        Command withdraw1 = new Command(
            "cmd-003", "Withdraw", accountId,
            new JsonObject().put("amount", 200.0),
            "user-001"
        );
        commandProducer.send(withdraw1);

        // Command 4: Another deposit
        Command deposit2 = new Command(
            "cmd-004", "Deposit", accountId,
            new JsonObject().put("amount", 750.0),
            "user-001"
        );
        commandProducer.send(deposit2);

        // Command 5: Freeze account
        Command freezeAccount = new Command(
            "cmd-005", "FreezeAccount", accountId,
            new JsonObject().put("reason", "Suspicious activity detected"),
            "admin-001"
        );
        commandProducer.send(freezeAccount);

        // Wait for all commands and events to be processed
        assertTrue(commandLatch.await(30, TimeUnit.SECONDS), "Should process all commands");
        assertTrue(eventLatch.await(30, TimeUnit.SECONDS), "Should store all events");

        // Verify event sourcing
        assertEquals(5, commandsProcessed.get(), "Should have processed 5 commands");
        assertEquals(5, eventsStored.get(), "Should have stored 5 events");

        List<DomainEvent> accountEvents = eventStore.get(accountId);
        assertNotNull(accountEvents, "Should have events for account");
        assertEquals(5, accountEvents.size(), "Should have 5 events stored");

        // Verify event sequence
        assertEquals(EventType.ACCOUNT_OPENED, accountEvents.get(0).eventType, "First event should be AccountOpened");
        assertEquals(EventType.MONEY_DEPOSITED, accountEvents.get(1).eventType, "Second event should be MoneyDeposited");
        assertEquals(EventType.MONEY_WITHDRAWN, accountEvents.get(2).eventType, "Third event should be MoneyWithdrawn");
        assertEquals(EventType.MONEY_DEPOSITED, accountEvents.get(3).eventType, "Fourth event should be MoneyDeposited");
        assertEquals(EventType.ACCOUNT_FROZEN, accountEvents.get(4).eventType, "Fifth event should be AccountFrozen");

        // Test event replay
        System.out.println("🔄 Testing event replay...");
        BankAccountAggregate replayedAggregate = BankAccountAggregate.fromEvents(accountId, accountEvents);

        assertEquals(2050.0, replayedAggregate.balance, 0.01, "Replayed balance should be correct");
        assertTrue(replayedAggregate.isFrozen, "Replayed account should be frozen");
        assertEquals(5, replayedAggregate.version, "Replayed version should be 5");

        System.out.println("📊 Event Sourcing Results:");
        System.out.println("  Commands processed: " + commandsProcessed.get());
        System.out.println("  Events stored: " + eventsStored.get());
        System.out.println("  Final balance: $" + replayedAggregate.balance);
        System.out.println("  Account frozen: " + replayedAggregate.isFrozen);

        // Cleanup
        commandConsumer.close();
        eventConsumer.close();

        System.out.println("✅ Event Sourcing test completed successfully");
    }

    @Test
    @Order(2)
    @DisplayName("CQRS - Command Query Responsibility Segregation")
    void testCQRS() throws Exception {
        System.out.println("\n🔍 Testing CQRS");

        String commandQueue = "cqrs-commands-queue";
        String eventQueue = "cqrs-events-queue";
        String queryQueue = "cqrs-queries-queue";

        Map<String, BankAccountAggregate> writeModel = new HashMap<>();
        Map<String, AccountReadModel> readModel = new HashMap<>();
        AtomicInteger commandsProcessed = new AtomicInteger(0);
        AtomicInteger eventsProcessed = new AtomicInteger(0);
        CountDownLatch commandLatch = new CountDownLatch(4);
        CountDownLatch eventLatch = new CountDownLatch(4);

        // Create producers and consumers
        MessageProducer<Command> commandProducer = queueFactory.createProducer(commandQueue, Command.class);
        MessageConsumer<Command> commandConsumer = queueFactory.createConsumer(commandQueue, Command.class);
        MessageProducer<DomainEvent> eventProducer = queueFactory.createProducer(eventQueue, DomainEvent.class);
        MessageConsumer<DomainEvent> eventConsumer = queueFactory.createConsumer(eventQueue, DomainEvent.class);

        // Command side - handles writes
        commandConsumer.subscribe(message -> {
            Command command = message.getPayload();

            System.out.println("🔍 WRITE SIDE - Processing command: " + command.commandType);

            try {
                // Get or create aggregate (write model)
                BankAccountAggregate aggregate = writeModel.computeIfAbsent(command.aggregateId,
                    id -> new BankAccountAggregate(id, "ACC-" + id.substring(0, 8), "CUST-" + id.substring(0, 8), 0.0));

                // Handle command
                switch (command.commandType) {
                    case "OpenAccount":
                        double initialDeposit = command.commandData.getDouble("initialDeposit");
                        aggregate.openAccount(command.commandId, initialDeposit);
                        break;
                    case "Deposit":
                        double depositAmount = command.commandData.getDouble("amount");
                        aggregate.deposit(command.commandId, depositAmount);
                        break;
                    case "Withdraw":
                        double withdrawAmount = command.commandData.getDouble("amount");
                        aggregate.withdraw(command.commandId, withdrawAmount);
                        break;
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

        // Query side - handles reads
        eventConsumer.subscribe(message -> {
            DomainEvent event = message.getPayload();

            System.out.println("🔍 READ SIDE - Processing event: " + event.eventType.eventName +
                             " for read model update");

            // Update read model
            AccountReadModel readModelAggregate = readModel.computeIfAbsent(event.aggregateId,
                id -> {
                    if (event.eventType == EventType.ACCOUNT_OPENED) {
                        return new AccountReadModel(id,
                            event.eventData.getString("accountNumber"),
                            event.eventData.getString("customerId"));
                    }
                    return new AccountReadModel(id, "ACC-" + id.substring(0, 8), "CUST-" + id.substring(0, 8));
                });

            readModelAggregate.applyEvent(event);
            eventsProcessed.incrementAndGet();
            eventLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send commands for CQRS demonstration
        System.out.println("📤 Sending commands for CQRS demonstration...");

        String accountId = "account-cqrs-001";

        // Command 1: Open account
        Command openAccount = new Command(
            "cqrs-cmd-001", "OpenAccount", accountId,
            new JsonObject().put("initialDeposit", 2000.0),
            "user-002"
        );
        commandProducer.send(openAccount);

        // Command 2: Multiple deposits
        Command deposit1 = new Command(
            "cqrs-cmd-002", "Deposit", accountId,
            new JsonObject().put("amount", 300.0),
            "user-002"
        );
        commandProducer.send(deposit1);

        Command deposit2 = new Command(
            "cqrs-cmd-003", "Deposit", accountId,
            new JsonObject().put("amount", 150.0),
            "user-002"
        );
        commandProducer.send(deposit2);

        // Command 3: Withdrawal
        Command withdraw1 = new Command(
            "cqrs-cmd-004", "Withdraw", accountId,
            new JsonObject().put("amount", 400.0),
            "user-002"
        );
        commandProducer.send(withdraw1);

        // Wait for processing
        assertTrue(commandLatch.await(30, TimeUnit.SECONDS), "Should process all commands");
        assertTrue(eventLatch.await(30, TimeUnit.SECONDS), "Should process all events");

        // Verify CQRS separation
        assertEquals(4, commandsProcessed.get(), "Should have processed 4 commands");
        assertEquals(4, eventsProcessed.get(), "Should have processed 4 events");

        // Verify write model
        BankAccountAggregate writeAggregate = writeModel.get(accountId);
        assertNotNull(writeAggregate, "Write model should exist");
        assertEquals(2050.0, writeAggregate.balance, 0.01, "Write model balance should be correct");

        // Verify read model
        AccountReadModel readAggregate = readModel.get(accountId);
        assertNotNull(readAggregate, "Read model should exist");
        assertEquals(2050.0, readAggregate.currentBalance, 0.01, "Read model balance should be correct");
        assertEquals(4, readAggregate.totalTransactions, "Read model should track transactions");
        assertEquals(2450.0, readAggregate.totalDeposits, 0.01, "Read model should track total deposits");
        assertEquals(400.0, readAggregate.totalWithdrawals, 0.01, "Read model should track total withdrawals");

        System.out.println("📊 CQRS Results:");
        System.out.println("  Write Model Balance: $" + writeAggregate.balance);
        System.out.println("  Read Model Balance: $" + readAggregate.currentBalance);
        System.out.println("  Read Model Transactions: " + readAggregate.totalTransactions);
        System.out.println("  Read Model Total Deposits: $" + readAggregate.totalDeposits);
        System.out.println("  Read Model Total Withdrawals: $" + readAggregate.totalWithdrawals);

        // Cleanup
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
