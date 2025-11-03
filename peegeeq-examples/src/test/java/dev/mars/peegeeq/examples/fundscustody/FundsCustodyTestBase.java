package dev.mars.peegeeq.examples.fundscustody;

import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.examples.fundscustody.events.NAVEvent;
import dev.mars.peegeeq.examples.fundscustody.events.TradeCancelledEvent;
import dev.mars.peegeeq.examples.fundscustody.events.TradeEvent;
import dev.mars.peegeeq.examples.fundscustody.service.NAVService;
import dev.mars.peegeeq.examples.fundscustody.service.PositionService;
import dev.mars.peegeeq.examples.fundscustody.service.RegulatoryReportingService;
import dev.mars.peegeeq.examples.fundscustody.service.TradeAuditService;
import dev.mars.peegeeq.examples.fundscustody.service.TradeService;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

/**
 * Base test class for funds & custody examples.
 *
 * <p>Provides:
 * <ul>
 *   <li>PostgreSQL test container</li>
 *   <li>PeeGeeQ manager and event stores</li>
 *   <li>Service instances (TradeService, PositionService, NAVService, etc.)</li>
 *   <li>Database cleanup between tests</li>
 * </ul>
 *
 * <p>No Spring dependencies - plain JUnit 5 with TestContainers.
 *
 * <p><strong>IMPORTANT:</strong> This test base uses system properties for configuration,
 * which are not thread-safe. Tests extending this class must run sequentially to avoid
 * system property conflicts during parallel execution.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
public abstract class FundsCustodyTestBase {

    // Get fresh container reference in setUp() instead of static initialization
    // to avoid stale port numbers when container is restarted between test classes
    protected PostgreSQLContainer<?> postgres;
    
    protected PeeGeeQManager manager;
    protected BiTemporalEventStoreFactory factory;
    protected EventStore<TradeEvent> tradeEventStore;
    protected EventStore<TradeCancelledEvent> cancellationEventStore;
    protected EventStore<NAVEvent> navEventStore;
    protected TradeService tradeService;
    protected PositionService positionService;
    protected NAVService navService;
    protected TradeAuditService auditService;
    protected RegulatoryReportingService regulatoryService;
    
    /**
     * Clean up the database before and after tests.
     * Ensures proper test isolation by removing all events.
     */
    protected void cleanupDatabase() {
        try {
            PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

            Pool cleanupPool = PgBuilder.pool()
                .connectingTo(connectOptions)
                .build();

            // Delete all events
            cleanupPool.withConnection(conn -> 
                conn.query("DELETE FROM bitemporal_event_log").execute()
                    .compose(deleteResult -> 
                        conn.query("SELECT COUNT(*) as count FROM bitemporal_event_log").execute()
                    )
                    .map(countResult -> {
                        int remainingRows = countResult.iterator().next().getInteger("count");
                        if (remainingRows > 0) {
                            System.out.println("WARNING: Database cleanup incomplete - " + 
                                remainingRows + " rows remaining");
                        }
                        return null;
                    })
                    .onFailure(throwable -> {
                        // Table might not exist yet, which is fine
                        if (!throwable.getMessage().contains("does not exist")) {
                            System.out.println("Cleanup operation warning: " + throwable.getMessage());
                        }
                    })
            ).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            cleanupPool.close().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
            
            // Wait for async operations to complete
            Thread.sleep(200);

        } catch (Exception e) {
            // Cleanup failures are often expected (table doesn't exist yet)
            String message = e.getMessage();
            if (message == null || !message.contains("does not exist")) {
                System.out.println("Database cleanup info: " + e.getClass().getSimpleName() + 
                    " - " + (message != null ? message : "No message available"));
            }
        }
    }
    
    @BeforeEach
    void setUp() throws Exception {
        // CRITICAL: Clear any system properties set by previous tests to avoid stale configuration
        clearSystemProperties();

        // CRITICAL: Clear cached connection pools from previous tests to avoid stale port connections
        dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore.clearCachedPools();

        // Get fresh container reference to avoid stale port numbers
        postgres = SharedTestContainers.getSharedPostgreSQLContainer();

        // Clean database before each test
        cleanupDatabase();

        // Initialize database schema FIRST (before setting properties)
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);



        // Configure system properties for TestContainers PostgreSQL connection
        // (Following the exact pattern from BiTemporalEventStoreExampleTest)
        System.setProperty("db.host", postgres.getHost());
        System.setProperty("db.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("db.database", postgres.getDatabaseName());
        System.setProperty("db.username", postgres.getUsername());
        System.setProperty("db.password", postgres.getPassword());

        // Configure PeeGeeQ to use the TestContainer
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Disable queue health checks since we only have bitemporal_event_log table
        System.setProperty("peegeeq.health-check.queue-checks-enabled", "false");

        // Initialize PeeGeeQ manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration();
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create event stores
        factory = new BiTemporalEventStoreFactory(manager);
        tradeEventStore = factory.createEventStore(TradeEvent.class);
        cancellationEventStore = factory.createEventStore(TradeCancelledEvent.class);
        navEventStore = factory.createEventStore(NAVEvent.class);

        // Create services (no Spring - plain constructor injection)
        tradeService = new TradeService(tradeEventStore, cancellationEventStore);
        positionService = new PositionService(tradeEventStore);
        navService = new NAVService(navEventStore);
        auditService = new TradeAuditService(tradeEventStore, cancellationEventStore);
        regulatoryService = new RegulatoryReportingService(
            tradeEventStore, navEventStore, positionService, navService);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        // Close event stores
        if (tradeEventStore != null) {
            try {
                tradeEventStore.close();
            } catch (Exception e) {
                System.err.println("Error closing trade event store: " + e.getMessage());
            }
        }

        if (cancellationEventStore != null) {
            try {
                cancellationEventStore.close();
            } catch (Exception e) {
                System.err.println("Error closing cancellation event store: " + e.getMessage());
            }
        }

        if (navEventStore != null) {
            try {
                navEventStore.close();
            } catch (Exception e) {
                System.err.println("Error closing NAV event store: " + e.getMessage());
            }
        }

        // Close PeeGeeQ manager to ensure all pools are properly closed
        // This is critical to prevent shared pool reuse across test classes
        if (manager != null) {
            try {
                manager.close();
            } catch (Exception e) {
                System.err.println("Error closing PeeGeeQ manager: " + e.getMessage());
            }
        }

        // Clear system properties (following BiTemporalEventStoreExampleTest pattern)
        clearSystemProperties();

        // Clean database after test
        cleanupDatabase();
    }

    /**
     * Clear system properties after test completion
     */
    private void clearSystemProperties() {
        System.clearProperty("db.host");
        System.clearProperty("db.port");
        System.clearProperty("db.database");
        System.clearProperty("db.username");
        System.clearProperty("db.password");
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.health-check.queue-checks-enabled");
    }
}

