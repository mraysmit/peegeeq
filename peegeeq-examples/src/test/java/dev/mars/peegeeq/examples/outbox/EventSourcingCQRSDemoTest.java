package dev.mars.peegeeq.examples.outbox;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demo test showcasing Event Sourcing & CQRS Patterns for PeeGeeQ.
 * 
 * This test demonstrates the classic bank account problem:
 * 1. Event Sourcing - Storing state changes as events
 * 2. CQRS - Command Query Responsibility Segregation
 * 3. Event Store - Immutable event storage and replay
 * 4. Read Models - Optimized query models from events
 * 5. Snapshots - Performance optimization for event replay
 * 
 * Based on Advanced Messaging Patterns from PeeGeeQ Complete Guide.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class EventSourcingCQRSDemoTest {
    private static final Logger logger = LoggerFactory.getLogger(EventSourcingCQRSDemoTest.class);


    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;
    private PgDatabaseService databaseService;

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

    // Domain event for event sourcing - following established POJO pattern
    static class DomainEvent {
        private String eventId;
        private String aggregateId;
        private EventType eventType;
        private Map<String, Object> eventData;
        private long version;
        private String timestamp;
        private String causationId;
        private String correlationId;

        // Default constructor for Jackson
        public DomainEvent() {}

        public DomainEvent(String eventId, String aggregateId, EventType eventType,
                          Map<String, Object> eventData, long version, String causationId, String correlationId) {
            this.eventId = eventId;
            this.aggregateId = aggregateId;
            this.eventType = eventType;
            this.eventData = eventData != null ? eventData : new HashMap<>();
            this.version = version;
            this.timestamp = Instant.now().toString();
            this.causationId = causationId;
            this.correlationId = correlationId;
        }

        // Getters and setters
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }

        public String getAggregateId() { return aggregateId; }
        public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

        public EventType getEventType() { return eventType; }
        public void setEventType(EventType eventType) { this.eventType = eventType; }

        public Map<String, Object> getEventData() { return eventData; }
        public void setEventData(Map<String, Object> eventData) { this.eventData = eventData; }

        public long getVersion() { return version; }
        public void setVersion(long version) { this.version = version; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getCausationId() { return causationId; }
        public void setCausationId(String causationId) { this.causationId = causationId; }

        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

        @Override
        public String toString() {
            return String.format("DomainEvent{eventId='%s', aggregateId='%s', eventType=%s, version=%d, timestamp='%s'}",
                eventId, aggregateId, eventType, version, timestamp);
        }
    }

    // Command for CQRS - following established POJO pattern
    static class Command {
        private String commandId;
        private String commandType;
        private String aggregateId;
        private Map<String, Object> commandData;
        private String timestamp;
        private String userId;

        // Default constructor for Jackson
        public Command() {}

        public Command(String commandId, String commandType, String aggregateId,
                      Map<String, Object> commandData, String userId) {
            this.commandId = commandId;
            this.commandType = commandType;
            this.aggregateId = aggregateId;
            this.commandData = commandData != null ? commandData : new HashMap<>();
            this.timestamp = Instant.now().toString();
            this.userId = userId;
        }

        // Getters and setters
        public String getCommandId() { return commandId; }
        public void setCommandId(String commandId) { this.commandId = commandId; }

        public String getCommandType() { return commandType; }
        public void setCommandType(String commandType) { this.commandType = commandType; }

        public String getAggregateId() { return aggregateId; }
        public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }

        public Map<String, Object> getCommandData() { return commandData; }
        public void setCommandData(Map<String, Object> commandData) { this.commandData = commandData; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        @Override
        public String toString() {
            return String.format("Command{commandId='%s', commandType='%s', aggregateId='%s', userId='%s', timestamp='%s'}",
                commandId, commandType, aggregateId, userId, timestamp);
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
            
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("accountNumber", accountNumber);
            eventData.put("customerId", customerId);
            eventData.put("initialDeposit", initialDeposit);

            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.ACCOUNT_OPENED,
                eventData,
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
            
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("amount", amount);
            eventData.put("previousBalance", balance);
            eventData.put("newBalance", balance + amount);

            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.MONEY_DEPOSITED,
                eventData,
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
            
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("amount", amount);
            eventData.put("previousBalance", balance);
            eventData.put("newBalance", balance - amount);

            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.MONEY_WITHDRAWN,
                eventData,
                version + 1, commandId, commandId
            );
            
            applyEvent(event);
        }

        public void freeze(String commandId, String reason) {
            if (isFrozen) {
                throw new IllegalStateException("Account already frozen");
            }
            
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("reason", reason);
            eventData.put("frozenAt", Instant.now().toString());

            DomainEvent event = new DomainEvent(
                UUID.randomUUID().toString(), accountId, EventType.ACCOUNT_FROZEN,
                eventData,
                version + 1, commandId, commandId
            );
            
            applyEvent(event);
        }

        // Event application
        private void applyEvent(DomainEvent event) {
            switch (event.getEventType()) {
                case ACCOUNT_OPENED:
                    this.balance = ((Number) event.getEventData().get("initialDeposit")).doubleValue();
                    break;
                case MONEY_DEPOSITED:
                    this.balance = ((Number) event.getEventData().get("newBalance")).doubleValue();
                    break;
                case MONEY_WITHDRAWN:
                    this.balance = ((Number) event.getEventData().get("newBalance")).doubleValue();
                    break;
                case ACCOUNT_FROZEN:
                    this.isFrozen = true;
                    break;
                case ACCOUNT_UNFROZEN:
                    this.isFrozen = false;
                    break;
                case INTEREST_CREDITED:
                    this.balance = ((Number) event.getEventData().get("newBalance")).doubleValue();
                    break;
                case SNAPSHOT_CREATED:
                    // Handle snapshot creation if needed
                    break;
            }

            this.version = event.getVersion();
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
            if (firstEvent.getEventType() != EventType.ACCOUNT_OPENED) {
                throw new IllegalArgumentException("First event must be AccountOpened");
            }

            BankAccountAggregate aggregate = new BankAccountAggregate(
                accountId,
                (String) firstEvent.getEventData().get("accountNumber"),
                (String) firstEvent.getEventData().get("customerId"),
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
            switch (event.getEventType()) {
                case ACCOUNT_OPENED:
                    this.balance = ((Number) event.getEventData().get("initialDeposit")).doubleValue();
                    break;
                case MONEY_DEPOSITED:
                    this.balance = ((Number) event.getEventData().get("newBalance")).doubleValue();
                    break;
                case MONEY_WITHDRAWN:
                    this.balance = ((Number) event.getEventData().get("newBalance")).doubleValue();
                    break;
                case ACCOUNT_FROZEN:
                    this.isFrozen = true;
                    break;
                case ACCOUNT_UNFROZEN:
                    this.isFrozen = false;
                    break;
                case INTEREST_CREDITED:
                    this.balance = ((Number) event.getEventData().get("newBalance")).doubleValue();
                    break;
                case SNAPSHOT_CREATED:
                    // Handle snapshot creation if needed
                    break;
            }

            this.version = event.getVersion();
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
            // Idempotency fence: protects against at-least-once redelivery (rebalance,
            // stale-generation retry, consumer restart before commit). Under OFFSET_WATERMARK,
            // per-partition ordering is guaranteed by the broker; deduplication is the
            // consumer's job. Removing this guard would cause totalTransactions and totals
            // to double-count on any redelivery.
            if (event.getVersion() <= lastProcessedVersion) {
                return; // Already processed
            }

            switch (event.getEventType()) {
                case ACCOUNT_OPENED:
                    this.currentBalance = ((Number) event.getEventData().get("initialDeposit")).doubleValue();
                    this.totalDeposits += ((Number) event.getEventData().get("initialDeposit")).doubleValue();
                    this.totalTransactions++;
                    break;
                case MONEY_DEPOSITED:
                    double depositAmount = ((Number) event.getEventData().get("amount")).doubleValue();
                    this.currentBalance = ((Number) event.getEventData().get("newBalance")).doubleValue();
                    this.totalDeposits += depositAmount;
                    this.totalTransactions++;
                    break;
                case MONEY_WITHDRAWN:
                    double withdrawalAmount = ((Number) event.getEventData().get("amount")).doubleValue();
                    this.currentBalance = ((Number) event.getEventData().get("newBalance")).doubleValue();
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
                    double interestAmount = ((Number) event.getEventData().get("amount")).doubleValue();
                    this.currentBalance = ((Number) event.getEventData().get("newBalance")).doubleValue();
                    this.totalDeposits += interestAmount;
                    this.totalTransactions++;
                    break;
                case SNAPSHOT_CREATED:
                    // Handle snapshot creation if needed
                    break;
            }

            this.lastTransactionTime = event.getTimestamp();
            this.lastProcessedVersion = event.getVersion();
        }

        @Override
        public String toString() {
            return String.format("AccountReadModel{accountId='%s', accountNumber='%s', customerId='%s', currentBalance=%.2f, isFrozen=%s, totalTransactions=%d, totalDeposits=%.2f, totalWithdrawals=%.2f, lastTransactionTime='%s', lastProcessedVersion=%d}",
                accountId, accountNumber, customerId, currentBalance, isFrozen, totalTransactions, totalDeposits, totalWithdrawals, lastTransactionTime, lastProcessedVersion);
        }
    }

    /**
     * Configure system properties for TestContainers PostgreSQL connection
     */
    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up Event Sourcing & CQRS Demo Test");

        // POLL INTERVAL — why this must be set explicitly
        //
        // OutboxConsumer polls the outbox table on a periodic Vert.x timer.  The default
        // poll interval from PeeGeeQConfiguration is 5 seconds, which is appropriate for
        // production but makes the command-ordering strategy in testEventSourcing fragile:
        // all five commands can be inserted within 250 ms and will therefore land in the
        // same 5-second poll batch regardless of inter-send delays.
        //
        // Setting the interval to 500 ms allows testEventSourcing to use 700 ms gaps
        // between sends (> one poll cycle) so each command is the only entry in its
        // batch.  The consumer then processes commands strictly in insertion order and
        // the FreezeAccount command can never reach the handler before Deposit/Withdraw.
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.polling-interval", "PT0.5S")
                .build();

        logger.info("Initializing database schema for event sourcing CQRS test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
            .map(v -> {
                // Create outbox factory. The OFFSET_WATERMARK partitioned consumer engine
                // (PartitionedConsumerEngine + PartitionedFetcher) reads from the `outbox` table,
                // so producers and consumers in this demo use the outbox factory end-to-end.
                this.databaseService = new PgDatabaseService(manager);
                QueueFactoryProvider provider = new PgQueueFactoryProvider();
                OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                queueFactory = provider.createFactory("outbox", databaseService);
                logger.info("Setup complete - Ready for event sourcing & CQRS pattern testing");
                return (Void) null;
            })
            .onComplete(testContext.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        logger.info("Tearing down: closing resources and manager");
        logger.info("Cleaning up Event Sourcing & CQRS Demo Test");

        if (manager == null) {
            testContext.completeNow();
            return;
        }

        logger.info("Closing PeeGeeQ manager...");
        manager.closeReactive()
            .onSuccess(v -> logger.info("PeeGeeQ manager closed successfully"))
            .onFailure(err -> logger.warn("Error during manager cleanup: {}", err.getMessage()))
            // CRITICAL: Wait for all resources to be fully released
            // This prevents connection pool exhaustion between tests.
            // NOTE: wall-clock settle delay (3s) is a deeper antipattern flagged for follow-up;
            // kept here to preserve existing inter-test isolation behaviour.
            .eventually(() -> vertx.timer(3000))
            .onSuccess(v -> {
                logger.info("Resource cleanup wait completed");
                logger.info("Cleanup complete");
                testContext.completeNow();
            })
            .onFailure(err -> {
                logger.warn("Cleanup wait error: {}", err.getMessage());
                testContext.completeNow();
            });
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
    @DisplayName("Event Sourcing - Storing State Changes as Events")
    void testEventSourcing(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: event sourcing");
        logger.info("=== TEST METHOD STARTED: testEventSourcing ===");
        System.err.flush();
        logger.info("Testing Event Sourcing");

        // Queue names for command and event streams
        String commandQueue = "eventsourcing-commands-queue-" + System.currentTimeMillis();
        String eventQueue = "eventsourcing-events-queue-" + System.currentTimeMillis();

        // In-memory event store and aggregate cache for demo purposes
        // 🚨 PRODUCTION NOTE: These would be replaced with proper persistence layers
        Map<String, List<DomainEvent>> eventStore = new HashMap<>();
        Map<String, BankAccountAggregate> aggregates = new HashMap<>();

        // Counters and checkpoints for test coordination
        // 🚨 TEST-ONLY: These are test-specific constructs for synchronization
        AtomicInteger commandsProcessed = new AtomicInteger(0);
        AtomicInteger eventsStored = new AtomicInteger(0);
        Checkpoint commandCheckpoint = testContext.checkpoint(5);  // Expecting 5 commands
        Checkpoint eventCheckpoint = testContext.checkpoint(5);    // Expecting 5 events

        // Create producers and consumers
        MessageProducer<Command> commandProducer = queueFactory.createProducer(commandQueue, Command.class);
        MessageConsumer<Command> commandConsumer = queueFactory.createConsumer(commandQueue, Command.class);
        MessageProducer<DomainEvent> eventProducer = queueFactory.createProducer(eventQueue, DomainEvent.class);
        MessageConsumer<DomainEvent> eventConsumer = queueFactory.createConsumer(eventQueue, DomainEvent.class);

        // Command handler - processes commands and generates events
        // This simulates a command handler in an event-sourced system
        commandConsumer.subscribe(message -> {
            Command command = message.getPayload();

            logger.info("Processing command: {} for aggregate: {}", command.commandType, command.aggregateId);

            try {
                BankAccountAggregate aggregate;

                // 🚨 WORKAROUND: Handle OpenAccount command specially to avoid race conditions
                // PRODUCTION NOTE: In real systems, this would be handled by proper aggregate repositories
                // and command ordering mechanisms, not manual checks like this
                if ("OpenAccount".equals(command.getCommandType())) {
                    // For OpenAccount, create a new aggregate if it doesn't exist
                    if (aggregates.containsKey(command.getAggregateId())) {
                        throw new IllegalStateException("Account already opened");
                    }
                    // Create new aggregate with initial state
                    aggregate = new BankAccountAggregate(command.getAggregateId(),
                        "ACC-" + command.getAggregateId().substring(0, 8),
                        "CUST-" + command.getAggregateId().substring(0, 8), 0.0);
                    aggregates.put(command.getAggregateId(), aggregate);

                    // Process the OpenAccount command
                    double initialDeposit = (Double) command.getCommandData().get("initialDeposit");
                    aggregate.openAccount(command.getCommandId(), initialDeposit);
                } else {
                    // For other commands, get existing aggregate
                    // 🚨 PRODUCTION NOTE: Real systems would load aggregates from event store
                    // by replaying all events for the aggregate, not from an in-memory cache
                    aggregate = aggregates.get(command.getAggregateId());
                    if (aggregate == null) {
                        throw new IllegalStateException("Account not found: " + command.getAggregateId());
                    }

                    // Handle business commands - each generates domain events
                    switch (command.getCommandType()) {
                        case "Deposit":
                            double depositAmount = (Double) command.getCommandData().get("amount");
                            aggregate.deposit(command.getCommandId(), depositAmount);
                            break;
                        case "Withdraw":
                            double withdrawAmount = (Double) command.getCommandData().get("amount");
                            aggregate.withdraw(command.getCommandId(), withdrawAmount);
                            break;
                        case "FreezeAccount":
                            String reason = (String) command.getCommandData().get("reason");
                            aggregate.freeze(command.getCommandId(), reason);
                            break;
                    }
                }

                // Publish uncommitted events to the event stream
                // In event sourcing, commands generate events that represent state changes
                for (DomainEvent event : aggregate.getUncommittedEvents()) {
                    eventProducer.send(event)
                            .onFailure(err -> logger.error("Failed to publish event {} for aggregate {}", event.eventType.eventName, event.aggregateId, err));
                }

                // Mark events as committed (they've been published)
                // 🚨 PRODUCTION NOTE: In real systems, this would be part of a transaction
                // ensuring events are both stored and published atomically
                aggregate.markEventsAsCommitted();

                commandsProcessed.incrementAndGet();

            } catch (Exception e) {
                logger.warn("Error processing command {}: {}", command.commandId, e.getMessage());
                // 🚨 PRODUCTION NOTE: Real systems would have proper error handling,
                // dead letter queues, and retry mechanisms
            }

            // 🚨 TEST-ONLY: Flag checkpoint for test synchronization
            commandCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Event store - stores events for replay (the heart of event sourcing)
        // This simulates an event store that persists all domain events
        eventConsumer.subscribe(message -> {
            DomainEvent event = message.getPayload();

            logger.info("Storing event: {} v{} for aggregate: {}", event.eventType.eventName, event.version, event.aggregateId);

            // Store event in event store
            // 🚨 PRODUCTION NOTE: Real event stores would:
            // - Ensure atomic writes with proper transactions
            // - Handle concurrency with optimistic locking
            // - Provide efficient querying by aggregate ID
            // - Support event snapshots for performance
            eventStore.computeIfAbsent(event.aggregateId, k -> new ArrayList<>()).add(event);

            eventsStored.incrementAndGet();
            // 🚨 TEST-ONLY: Flag checkpoint for test synchronization
            eventCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Send commands to demonstrate event sourcing
        logger.info("Sending commands for event sourcing demonstration...");

        String accountId = "account-es-001";

        // Command 1: Open account (must be first to create the aggregate)
        Map<String, Object> openAccountData = new HashMap<>();
        openAccountData.put("initialDeposit", 1000.0);

        Command openAccount = new Command(
            "cmd-001", "OpenAccount", accountId,
            openAccountData,
            "user-001"
        );

        // Command 2: Deposit money (first business transaction)
        Map<String, Object> deposit1Data = new HashMap<>();
        deposit1Data.put("amount", 500.0);
        Command deposit1 = new Command("cmd-002", "Deposit", accountId, deposit1Data, "user-001");

        // Command 3: Withdraw money (test withdrawal logic)
        Map<String, Object> withdraw1Data = new HashMap<>();
        withdraw1Data.put("amount", 200.0);
        Command withdraw1 = new Command("cmd-003", "Withdraw", accountId, withdraw1Data, "user-001");

        // Command 4: Another deposit (test multiple deposits)
        Map<String, Object> deposit2Data = new HashMap<>();
        deposit2Data.put("amount", 750.0);
        Command deposit2 = new Command("cmd-004", "Deposit", accountId, deposit2Data, "user-001");

        // Command 5: Freeze account (administrative action)
        Map<String, Object> freezeAccountData = new HashMap<>();
        freezeAccountData.put("reason", "Suspicious activity detected");
        Command freezeAccount = new Command("cmd-005", "FreezeAccount", accountId, freezeAccountData, "admin-001");

        // COMMAND ORDERING — why a composed timer chain is required
        //
        // The outbox pattern inserts messages into a database table; the consumer picks
        // them up on a periodic poll.  commandProducer.send() only guarantees that the
        // row exists in the table — it makes no guarantee about when the consumer will
        // read it.
        //
        // If all five commands are inserted back-to-back (within milliseconds of each
        // other) they will all appear in the same poll batch.  The batch is ordered by
        // created_at, but sub-millisecond clock resolution means that ordering is not
        // guaranteed.  In practice, FreezeAccount has been observed before Deposit or
        // Withdraw, causing the aggregate to reject those commands with
        // "Cannot deposit/withdraw from frozen account" and leaving two event checkpoints
        // permanently unflagged → the 30-second awaitCompletion() times out.
        //
        // THE STRATEGY
        // setUp sets peegeeq.queue.polling-interval=PT0.5S (500 ms).  Each send below
        // is delayed by 700 ms (200 ms margin over one poll cycle).  Because the timer
        // chain is composed — each vertx.timer() starts only after the previous send
        // Future completes — the timeline is:
        //
        //   t =    0 ms  openAccount inserted → immediate poll, processes it alone
        //   t =  700 ms  deposit1    inserted → poll at t≈1000 ms, processes it alone
        //   t = 1400 ms  withdraw1   inserted → poll at t≈1500 ms, processes it alone
        //   t = 2100 ms  deposit2    inserted → poll at t≈2500 ms, processes it alone
        //   t = 2800 ms  freezeAccount inserted → poll at t≈3000 ms, processes it alone
        //
        // Both checkpoints (5 commands, 5 events) complete in ≈ 3.5 seconds, well within
        // the 30-second awaitCompletion() budget.
        //
        // openAccount is sent as a separate fire-start (not inside the timer chain) so
        // that the immediate initial poll can process it while the timer chain waits.
        commandProducer.send(openAccount).onFailure(testContext::failNow);
        vertx.timer(700).compose(v -> commandProducer.send(deposit1))
            .compose(v -> vertx.timer(700)).compose(v -> commandProducer.send(withdraw1))
            .compose(v -> vertx.timer(700)).compose(v -> commandProducer.send(deposit2))
            .compose(v -> vertx.timer(700)).compose(v -> commandProducer.send(freezeAccount))
            .onFailure(testContext::failNow);

        // Wait for all commands and events to be processed
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Should process all commands and events");

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
        logger.info("Testing event replay...");
        BankAccountAggregate replayedAggregate = BankAccountAggregate.fromEvents(accountId, accountEvents);

        // Verify that replaying events produces the same final state
        // This proves that events contain all necessary information to reconstruct state
        assertEquals(2050.0, replayedAggregate.balance, 0.01, "Replayed balance should be correct");
        assertTrue(replayedAggregate.isFrozen, "Replayed account should be frozen");
        assertEquals(5, replayedAggregate.version, "Replayed version should be 5");

        // Display results to show the complete event sourcing flow
        logger.info("Event Sourcing Results:");
        logger.info("  Commands processed: {}", commandsProcessed.get());
        logger.info("  Events stored: {}", eventsStored.get());
        logger.info("  Final balance: ${}", replayedAggregate.balance);
        logger.info("  Account frozen: {}", replayedAggregate.isFrozen);

        // Cleanup resources
        commandConsumer.close();
        eventConsumer.close();

        logger.info("Event Sourcing test completed successfully");

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
    @DisplayName("CQRS - Command Query Responsibility Segregation (per-account ordering via OFFSET_WATERMARK)")
    void testCQRS(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: c q r s");
        logger.info("Testing CQRS with OFFSET_WATERMARK partitioned event consumption");

        // Queue names for command and event streams
        String commandQueue = "cqrs-commands-queue-" + System.currentTimeMillis();
        String eventQueue = "cqrs-events-queue-" + System.currentTimeMillis();
        String eventGroupName = "cqrs-read-models";

        // Configure the event topic for OFFSET_WATERMARK so per-aggregate ordering is enforced.
        configureOffsetWatermarkTopic(eventQueue, eventGroupName)
            .compose(cfg -> runCqrsScenario(vertx, testContext, commandQueue, eventQueue, eventGroupName))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    private Future<Void> runCqrsScenario(Vertx vertx, VertxTestContext testContext,
                                          String commandQueue, String eventQueue, String eventGroupName) {
        Map<String, BankAccountAggregate> writeModel = new HashMap<>();
        Map<String, AccountReadModel> readModel = new HashMap<>();

        AtomicInteger commandsProcessed = new AtomicInteger(0);
        AtomicInteger eventsProcessed = new AtomicInteger(0);
        Checkpoint commandCheckpoint = testContext.checkpoint(4);
        Checkpoint eventCheckpoint = testContext.checkpoint(4);
        // Promise completes when all 4 commands and all 4 events have been processed.
        Promise<Void> allDone = Promise.promise();
        Runnable maybeAllDone = () -> {
            if (commandsProcessed.get() >= 4 && eventsProcessed.get() >= 4) {
                allDone.tryComplete();
            }
        };
        // OFFSET_WATERMARK uses at-least-once delivery; dedupe redeliveries before flagging
        // the per-unique-event checkpoint. The read-model version fence is the same
        // idempotency guarantee a production consumer must provide.
        Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

        // Promise that completes once the OpenAccount event has been persisted, so the
        // partitioned consumer group can discover the partition at join time.
        Promise<Void> openPhaseDone = Promise.promise();

        // Create producers and consumers. The event consumer is a partitioned ConsumerGroup
        // (OFFSET_WATERMARK), not a simple MessageConsumer.
        MessageProducer<Command> commandProducer = queueFactory.createProducer(commandQueue, Command.class);
        MessageConsumer<Command> commandConsumer = queueFactory.createConsumer(commandQueue, Command.class);
        MessageProducer<DomainEvent> eventProducer = queueFactory.createProducer(eventQueue, DomainEvent.class);
        ConsumerGroup<DomainEvent> eventGroup = queueFactory.createConsumerGroup(
                eventGroupName, eventQueue, DomainEvent.class);

        // WRITE SIDE - Command processing
        commandConsumer.subscribe(message -> {
            Command command = message.getPayload();
            logger.info("WRITE SIDE - Processing command: {}", command.getCommandType());

            BankAccountAggregate aggregate;
            try {
                if ("OpenAccount".equals(command.getCommandType())) {
                    if (writeModel.containsKey(command.getAggregateId())) {
                        throw new IllegalStateException("Account already opened");
                    }
                    aggregate = new BankAccountAggregate(command.getAggregateId(),
                        "ACC-" + command.getAggregateId().substring(0, 8),
                        "CUST-" + command.getAggregateId().substring(0, 8), 0.0);
                    writeModel.put(command.getAggregateId(), aggregate);
                    double initialDeposit = (Double) command.getCommandData().get("initialDeposit");
                    aggregate.openAccount(command.getCommandId(), initialDeposit);
                } else {
                    aggregate = writeModel.get(command.getAggregateId());
                    if (aggregate == null) {
                        throw new IllegalStateException("Account not found: " + command.getAggregateId());
                    }
                    switch (command.getCommandType()) {
                        case "Deposit":
                            aggregate.deposit(command.getCommandId(),
                                    (Double) command.getCommandData().get("amount"));
                            break;
                        case "Withdraw":
                            aggregate.withdraw(command.getCommandId(),
                                    (Double) command.getCommandData().get("amount"));
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown command: " + command.getCommandType());
                    }
                }
            } catch (Exception e) {
                logger.warn("Command processing error: {}", e.getMessage());
                commandCheckpoint.flag();
                return Future.succeededFuture();
            }

            // Publish each event with messageGroup = aggregateId so OFFSET_WATERMARK routes
            // them to the same partition. Compose the futures so the command handler
            // completes only after every event is durably persisted.
            Future<Void> sendChain = Future.succeededFuture();
            for (DomainEvent event : aggregate.getUncommittedEvents()) {
                final DomainEvent e = event;
                sendChain = sendChain.compose(v ->
                        eventProducer.send(e, null, null, e.getAggregateId()));
            }
            final BankAccountAggregate finalAggregate = aggregate;
            return sendChain
                    .onSuccess(v -> {
                        finalAggregate.markEventsAsCommitted();
                        int processed = commandsProcessed.incrementAndGet();
                        commandCheckpoint.flag();
                        maybeAllDone.run();
                        // Once the OpenAccount event is persisted, signal that the partition
                        // exists so the event consumer group can join.
                        if ("OpenAccount".equals(command.getCommandType())) {
                            openPhaseDone.tryComplete();
                        }
                    })
                    .onFailure(err -> {
                        logger.warn("Failed to publish events for {}: {}",
                                command.getCommandId(), err.getMessage());
                        commandCheckpoint.flag();
                    });
        });

        // READ SIDE - per-partition ordered event consumption.
        eventGroup.setMessageHandler(message -> {
            DomainEvent event = message.getPayload();
            if (!seenEventIds.add(event.getEventId())) {
                logger.debug("Skipping duplicate event delivery: {} v{} for {}",
                        event.eventType.eventName, event.version, event.getAggregateId());
                return Future.succeededFuture();
            }
            logger.info("READ SIDE - Event {} v{} for {}",
                    event.eventType.eventName, event.version, event.getAggregateId());

            AccountReadModel readModelAggregate = readModel.computeIfAbsent(event.getAggregateId(),
                id -> {
                    if (event.getEventType() == EventType.ACCOUNT_OPENED) {
                        return new AccountReadModel(id,
                            (String) event.getEventData().get("accountNumber"),
                            (String) event.getEventData().get("customerId"));
                    }
                    return new AccountReadModel(id, "ACC-" + id.substring(0, 8), "CUST-" + id.substring(0, 8));
                });
            readModelAggregate.applyEvent(event);
            eventsProcessed.incrementAndGet();
            eventCheckpoint.flag();
            maybeAllDone.run();
            return Future.succeededFuture();
        });

        // Build commands.
        String accountId = "account-cqrs-001";
        Map<String, Object> openAccountData = new HashMap<>();
        openAccountData.put("initialDeposit", 2000.0);
        Command openAccount = new Command(
            "cqrs-cmd-001", "OpenAccount", accountId, openAccountData, "user-002");

        Map<String, Object> deposit1Data = new HashMap<>();
        deposit1Data.put("amount", 300.0);
        Command deposit1 = new Command("cqrs-cmd-002", "Deposit", accountId, deposit1Data, "user-002");

        Map<String, Object> deposit2Data = new HashMap<>();
        deposit2Data.put("amount", 150.0);
        Command deposit2 = new Command("cqrs-cmd-003", "Deposit", accountId, deposit2Data, "user-002");

        Map<String, Object> withdraw1Data = new HashMap<>();
        withdraw1Data.put("amount", 400.0);
        Command withdraw1 = new Command("cqrs-cmd-004", "Withdraw", accountId, withdraw1Data, "user-002");

        // Send the OpenAccount command first so the partition exists.
        commandProducer.send(openAccount).onFailure(testContext::failNow);

        // Once the open event is persisted, start the partitioned consumer group so it
        // discovers the partition, then send the remaining commands.
        SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();
        openPhaseDone.future()
                .compose(v -> eventGroup.start(options))
                .onSuccess(v -> {
                    logger.info("Event ConsumerGroup started; sending remaining commands");
                    commandProducer.send(deposit1).onFailure(testContext::failNow);
                    commandProducer.send(deposit2).onFailure(testContext::failNow);
                    commandProducer.send(withdraw1).onFailure(testContext::failNow);
                })
                .onFailure(err -> testContext.failNow(err));

        // Reactive completion: wait for allDone, then settle 500ms, then run final
        // assertions and shut down. The cqrsScenario Future is returned by this method
        // and chained to testContext.completeNow() / testContext.failNow().
        return allDone.future()
                .compose(v -> vertx.timer(500).<Void>mapEmpty())
                .compose(v -> {
                    testContext.verify(() -> {
                        assertEquals(4, commandsProcessed.get(), "Should have processed 4 commands");
                        assertEquals(4, eventsProcessed.get(), "Should have processed 4 events");

                        BankAccountAggregate writeAggregate = writeModel.get(accountId);
                        assertNotNull(writeAggregate, "Write model should exist");
                        assertEquals(2050.0, writeAggregate.balance, 0.01, "Write model balance should be correct");

                        AccountReadModel readAggregate = readModel.get(accountId);
                        assertNotNull(readAggregate, "Read model should exist");
                        assertEquals(2050.0, readAggregate.currentBalance, 0.01, "Read model balance should match write model");
                        assertEquals(4, readAggregate.totalTransactions, "Read model should track transaction count");
                        assertEquals(2450.0, readAggregate.totalDeposits, 0.01, "Read model should track total deposits");
                        assertEquals(400.0, readAggregate.totalWithdrawals, 0.01, "Read model should track total withdrawals");

                        logger.info("CQRS Results:");
                        logger.info("  Write Model Balance: ${}", writeAggregate.balance);
                        logger.info("  Read Model Balance: ${}", readAggregate.currentBalance);
                        logger.info("  Read Model Transactions: {}", readAggregate.totalTransactions);
                        logger.info("  Read Model Total Deposits: ${}", readAggregate.totalDeposits);
                        logger.info("  Read Model Total Withdrawals: ${}", readAggregate.totalWithdrawals);
                    });
                    // Cleanup
                    commandConsumer.close();
                    return eventGroup.stopGracefully()
                            .transform(ar -> {
                                if (ar.failed()) {
                                    logger.warn("eventGroup.stopGracefully failed: {}", ar.cause().getMessage());
                                }
                                return Future.<Void>succeededFuture();
                            });
                })
                .onSuccess(v -> logger.info("CQRS test completed successfully"));
    }

    /**
     * RED test (Phase 2 Decision 1): per-account ordering with multiple aggregates.
     *
     * <p>This test demonstrates the same out-of-order failure as {@link #testCQRS} but
     * across multiple aggregates concurrently. With a simple {@link MessageConsumer}
     * and no {@code messageGroup} on the producer, {@code FOR UPDATE SKIP LOCKED} can
     * deliver events for a single account out of version order. The version guard in
     * {@link AccountReadModel#applyEvent(DomainEvent)} silently skips earlier events
     * once a later one has been applied, so per-account {@code totalTransactions},
     * {@code totalDeposits} and {@code currentBalance} drift away from the expected
     * values.</p>
     *
     * <p>Per the OFFSET_WATERMARK ordering plan (see
     * {@code docs-design/analysis/GUARANTEED_ORDERING_CONCURRENT_CONSUMERS_ANALYSIS.md}),
     * this test is RED until Phase 2 GREEN steps are applied:
     * <ol>
     *   <li>Configure the event topic with {@code completion_tracking_mode = 'OFFSET_WATERMARK'}.</li>
     *   <li>Producer sends with {@code messageGroup = event.getAggregateId()}.</li>
     *   <li>Consumer is a {@code ConsumerGroup} started via {@code start(SubscriptionOptions)}.</li>
     * </ol>
     * After GREEN, this test must pass 10/10 consecutive runs.</p>
     */
    @Test
    @DisplayName("CQRS - Per-account ordering across multiple aggregates (OFFSET_WATERMARK)")
    void testCQRS_multipleAccounts_perAccountOrdering(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== TEST METHOD STARTED: testCQRS_multipleAccounts_perAccountOrdering ===");

        // Queue names for command and event streams
        String commandQueue = "cqrs-multi-commands-queue-" + System.currentTimeMillis();
        String eventQueue = "cqrs-multi-events-queue-" + System.currentTimeMillis();
        String eventGroupName = "cqrs-multi-read-models";

        // Configure the event topic for OFFSET_WATERMARK so per-aggregate ordering is enforced.
        configureOffsetWatermarkTopic(eventQueue, eventGroupName)
            .compose(cfg -> runMultiAccountScenario(vertx, testContext, commandQueue, eventQueue, eventGroupName))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    private Future<Void> runMultiAccountScenario(Vertx vertx, VertxTestContext testContext,
                                                  String commandQueue, String eventQueue, String eventGroupName) {
        List<String> accountIds = List.of(
            "account-cqrs-multi-A-" + System.currentTimeMillis(),
            "account-cqrs-multi-B-" + System.currentTimeMillis(),
            "account-cqrs-multi-C-" + System.currentTimeMillis()
        );

        int commandsPerAccount = 4;
        int totalCommands = accountIds.size() * commandsPerAccount;

        Map<String, BankAccountAggregate> writeModel = new HashMap<>();
        Map<String, AccountReadModel> readModel = new HashMap<>();

        AtomicInteger commandsProcessed = new AtomicInteger(0);
        AtomicInteger eventsProcessed = new AtomicInteger(0);
        AtomicInteger opensProcessed = new AtomicInteger(0);
        Checkpoint commandCheckpoint = testContext.checkpoint(totalCommands);
        Checkpoint eventCheckpoint = testContext.checkpoint(totalCommands);
        // Promise completes when every command AND every event has been processed.
        Promise<Void> allDone = Promise.promise();
        Runnable maybeAllDone = () -> {
            if (commandsProcessed.get() >= totalCommands && eventsProcessed.get() >= totalCommands) {
                allDone.tryComplete();
            }
        };
        // OFFSET_WATERMARK uses at-least-once delivery; dedupe redeliveries before flagging
        // the per-unique-event checkpoint.
        Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

        // Promise that completes once every OpenAccount event has been persisted, so
        // each per-aggregate partition exists before the consumer group joins.
        Promise<Void> openPhaseDone = Promise.promise();

        MessageProducer<Command> commandProducer = queueFactory.createProducer(commandQueue, Command.class);
        MessageConsumer<Command> commandConsumer = queueFactory.createConsumer(commandQueue, Command.class);
        MessageProducer<DomainEvent> eventProducer = queueFactory.createProducer(eventQueue, DomainEvent.class);
        ConsumerGroup<DomainEvent> eventGroup = queueFactory.createConsumerGroup(
                eventGroupName, eventQueue, DomainEvent.class);

        // WRITE SIDE
        commandConsumer.subscribe(message -> {
            Command command = message.getPayload();
            logger.info("WRITE SIDE - Processing command: {} for {}",
                    command.getCommandType(), command.getAggregateId());

            BankAccountAggregate aggregate;
            try {
                if ("OpenAccount".equals(command.getCommandType())) {
                    if (writeModel.containsKey(command.getAggregateId())) {
                        throw new IllegalStateException("Account already opened");
                    }
                    aggregate = new BankAccountAggregate(command.getAggregateId(),
                        "ACC-" + command.getAggregateId().substring(0, 8),
                        "CUST-" + command.getAggregateId().substring(0, 8), 0.0);
                    writeModel.put(command.getAggregateId(), aggregate);
                    double initialDeposit = (Double) command.getCommandData().get("initialDeposit");
                    aggregate.openAccount(command.getCommandId(), initialDeposit);
                } else {
                    aggregate = writeModel.get(command.getAggregateId());
                    if (aggregate == null) {
                        throw new IllegalStateException("Account not found: " + command.getAggregateId());
                    }
                    switch (command.getCommandType()) {
                        case "Deposit":
                            aggregate.deposit(command.getCommandId(),
                                    (Double) command.getCommandData().get("amount"));
                            break;
                        case "Withdraw":
                            aggregate.withdraw(command.getCommandId(),
                                    (Double) command.getCommandData().get("amount"));
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown command: " + command.getCommandType());
                    }
                }
            } catch (Exception e) {
                logger.warn("Command processing error", e);
                commandCheckpoint.flag();
                return Future.succeededFuture();
            }

            // Send each event with messageGroup = aggregateId to enforce per-aggregate
            // partition routing under OFFSET_WATERMARK. Compose all send futures so the
            // command handler completes only when all events are durable.
            Future<Void> sendChain = Future.succeededFuture();
            for (DomainEvent event : aggregate.getUncommittedEvents()) {
                final DomainEvent e = event;
                sendChain = sendChain.compose(v ->
                        eventProducer.send(e, null, null, e.getAggregateId()));
            }
            final BankAccountAggregate finalAggregate = aggregate;
            return sendChain
                    .onSuccess(v -> {
                        finalAggregate.markEventsAsCommitted();
                        commandsProcessed.incrementAndGet();
                        commandCheckpoint.flag();
                        maybeAllDone.run();
                        if ("OpenAccount".equals(command.getCommandType())
                                && opensProcessed.incrementAndGet() == accountIds.size()) {
                            openPhaseDone.tryComplete();
                        }
                    })
                    .onFailure(err -> {
                        logger.warn("Failed to publish events for {}: {}",
                                command.getCommandId(), err.getMessage());
                        commandCheckpoint.flag();
                    });
        });

        // READ SIDE - partitioned consumer group; per-partition ordering guaranteed.
        eventGroup.setMessageHandler(message -> {
            DomainEvent event = message.getPayload();
            if (!seenEventIds.add(event.getEventId())) {
                logger.debug("Skipping duplicate event delivery: {} v{} for {}",
                        event.eventType.eventName, event.version, event.getAggregateId());
                return Future.succeededFuture();
            }
            logger.info("READ SIDE - Event {} v{} for {}",
                    event.eventType.eventName, event.version, event.getAggregateId());

            AccountReadModel readModelAggregate = readModel.computeIfAbsent(event.getAggregateId(),
                id -> {
                    if (event.getEventType() == EventType.ACCOUNT_OPENED) {
                        return new AccountReadModel(id,
                            (String) event.getEventData().get("accountNumber"),
                            (String) event.getEventData().get("customerId"));
                    }
                    return new AccountReadModel(id, "ACC-" + id.substring(0, 8), "CUST-" + id.substring(0, 8));
                });
            readModelAggregate.applyEvent(event);
            eventsProcessed.incrementAndGet();
            eventCheckpoint.flag();
            maybeAllDone.run();
            return Future.succeededFuture();
        });

        // Send Open commands first so each per-aggregate partition exists.
        logger.info("Sending Open commands for {} accounts", accountIds.size());
        int openIdx = 1;
        for (String accountId : accountIds) {
            Map<String, Object> openData = new HashMap<>();
            openData.put("initialDeposit", 1000.0);
            commandProducer.send(new Command(
                "cqrs-multi-cmd-open-" + openIdx++, "OpenAccount", accountId, openData, "user-multi"));
        }

        // Once every Open event is persisted, start the consumer group (it discovers
        // partitions at join time), then fire the business commands in a tight burst.
        SubscriptionOptions options = SubscriptionOptions.builder()
                .startPosition(StartPosition.FROM_BEGINNING)
                .build();
        openPhaseDone.future()
                .compose(v -> eventGroup.start(options))
                .onSuccess(v -> {
                    logger.info("Event ConsumerGroup started; sending business commands");
                    int seq = 1;
                    for (String accountId : accountIds) {
                        Map<String, Object> dep1 = new HashMap<>();
                        dep1.put("amount", 200.0);
                        commandProducer.send(new Command(
                            "cqrs-multi-cmd-dep1-" + seq, "Deposit", accountId, dep1, "user-multi"));

                        Map<String, Object> dep2 = new HashMap<>();
                        dep2.put("amount", 300.0);
                        commandProducer.send(new Command(
                            "cqrs-multi-cmd-dep2-" + seq, "Deposit", accountId, dep2, "user-multi"));

                        Map<String, Object> wd1 = new HashMap<>();
                        wd1.put("amount", 100.0);
                        commandProducer.send(new Command(
                            "cqrs-multi-cmd-wd1-" + seq, "Withdraw", accountId, wd1, "user-multi"));
                        seq++;
                    }
                })
                .onFailure(err -> testContext.failNow(err));

        // Reactive completion: wait for allDone, then settle 500ms, then assertions + shutdown.
        return allDone.future()
                .compose(v -> vertx.timer(500).<Void>mapEmpty())
                .compose(v -> {
                    testContext.verify(() -> {
                        assertEquals(totalCommands, commandsProcessed.get(),
                                "Should have processed " + totalCommands + " commands");
                        assertEquals(totalCommands, eventsProcessed.get(),
                                "Should have processed " + totalCommands + " events");

                        // Per-account assertions on the READ model these are the assertions that
                        // fail when events are processed out of order.
                        for (String accountId : accountIds) {
                            BankAccountAggregate writeAggregate = writeModel.get(accountId);
                            assertNotNull(writeAggregate, "Write model should exist for " + accountId);
                            assertEquals(1400.0, writeAggregate.balance, 0.01,
                                    "Write model balance should be correct for " + accountId);

                            AccountReadModel readAggregate = readModel.get(accountId);
                            assertNotNull(readAggregate, "Read model should exist for " + accountId);
                            assertEquals(1400.0, readAggregate.currentBalance, 0.01,
                                    "Read model balance should match write model for " + accountId);
                            assertEquals(4, readAggregate.totalTransactions,
                                    "Read model should record 4 transactions for " + accountId);
                            assertEquals(1500.0, readAggregate.totalDeposits, 0.01,
                                    "Read model totalDeposits incorrect for " + accountId);
                            assertEquals(100.0, readAggregate.totalWithdrawals, 0.01,
                                    "Read model totalWithdrawals incorrect for " + accountId);
                            assertEquals(4L, readAggregate.lastProcessedVersion,
                                    "Read model lastProcessedVersion should be 4 for " + accountId);
                        }
                    });

                    commandConsumer.close();
                    return eventGroup.stopGracefully()
                            .transform(ar -> {
                                if (ar.failed()) {
                                    logger.warn("eventGroup.stopGracefully failed: {}", ar.cause().getMessage());
                                }
                                return Future.<Void>succeededFuture();
                            });
                })
                .onSuccess(v -> logger.info("Multi-account CQRS ordering test completed successfully"));
    }

    /**
     * Configures an event topic to use OFFSET_WATERMARK completion tracking and registers
     * a consumer-group subscription. This is the canonical setup for tests that exercise
     * partitioned, per-aggregate ordered consumption via {@link ConsumerGroup}.
     */
    private Future<Void> configureOffsetWatermarkTopic(String topic, String groupName) {
        return databaseService.getPool()
                .withConnection(conn -> conn.preparedQuery(
                        "INSERT INTO outbox_topics (topic, semantics, completion_tracking_mode) " +
                        "VALUES ($1, 'PUB_SUB', 'OFFSET_WATERMARK') " +
                        "ON CONFLICT (topic) DO UPDATE SET completion_tracking_mode = 'OFFSET_WATERMARK'")
                        .execute(Tuple.of(topic))
                        .compose(r -> conn.preparedQuery(
                                "INSERT INTO outbox_topic_subscriptions (topic, group_name, subscription_status) " +
                                "VALUES ($1, $2, 'ACTIVE') " +
                                "ON CONFLICT (topic, group_name) DO NOTHING")
                                .execute(Tuple.of(topic, groupName)))
                        .mapEmpty());
    }

}


