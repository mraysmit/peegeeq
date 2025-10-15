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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
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
 */
@Testcontainers
public abstract class FundsCustodyTestBase {
    
    @Container
    protected static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");
    
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
        // Clean database before each test
        cleanupDatabase();

        // Set system properties for PeeGeeQ configuration
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Disable queue health checks since we only have bitemporal_event_log table
        System.setProperty("peegeeq.health-check.queue-checks-enabled", "false");

        // Initialize database schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        // Configure and start PeeGeeQ
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
                System.out.println("Error closing trade event store: " + e.getMessage());
            }
        }

        if (cancellationEventStore != null) {
            try {
                cancellationEventStore.close();
            } catch (Exception e) {
                System.out.println("Error closing cancellation event store: " + e.getMessage());
            }
        }

        if (navEventStore != null) {
            try {
                navEventStore.close();
            } catch (Exception e) {
                System.out.println("Error closing NAV event store: " + e.getMessage());
            }
        }

        // Stop PeeGeeQ manager
        if (manager != null) {
            try {
                manager.stop();
            } catch (Exception e) {
                System.out.println("Error stopping PeeGeeQ manager: " + e.getMessage());
            }
        }

        // Clean database after test
        cleanupDatabase();
        
        // Wait for cleanup to complete
        Thread.sleep(500);
    }
}

