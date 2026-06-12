package dev.mars.peegeeq.examples.fundscustody;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.bitemporal.PgBiTemporalEventStore;
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
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Properties;

/**
 * Base test class for funds & custody examples.
 *
 * <p>Provides:
 * <ul>
 *   <li>PostgreSQL test container (shared)</li>
 *   <li>PeeGeeQ manager and event stores</li>
 *   <li>Service instances (TradeService, PositionService, NAVService, etc.)</li>
 *   <li>Database cleanup between tests</li>
 * </ul>
 *
 * <p>Plain JUnit 5 + Vert.x JUnit 5. All async setup/teardown is coordinated through
 * {@link VertxTestContext}  no blocking bridges, no {@code System.setProperty} side-effects.
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
public abstract class FundsCustodyTestBase {

    private static final Logger logger = LoggerFactory.getLogger(FundsCustodyTestBase.class);

    protected Vertx vertx;

    // Fresh reference acquired in setUp() to avoid stale port numbers when the
    // shared container is restarted between test classes.
    protected PostgreSQLContainer postgres;

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
     * Delete all bi-temporal events. Returns a Future that completes once the
     * cleanup pool has been closed. A "table does not exist" failure on first run
     * is logged and treated as success.
     */
    protected Future<Void> cleanupDatabase() {
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword())
            // The unqualified DELETE below must hit the explicit test schema, not the
            // connection default; a miss is silently tolerated by the first-run guard
            .setProperties(java.util.Map.of("search_path", PostgreSQLTestConstants.TEST_SCHEMA));

        Pool cleanupPool = PgBuilder.pool()
            .connectingTo(connectOptions)
            .build();

        return cleanupPool.withConnection(conn ->
                conn.query("DELETE FROM bitemporal_event_log").execute()
                    .compose(deleteResult ->
                        conn.query("SELECT COUNT(*) AS count FROM bitemporal_event_log").execute())
                    .map(countResult -> {
                        int remaining = countResult.iterator().next().getInteger("count");
                        if (remaining > 0) {
                            logger.warn("Database cleanup incomplete - {} rows remaining", remaining);
                        }
                        return (Void) null;
                    }))
            .transform(ar -> {
                if (ar.failed()) {
                    String msg = ar.cause().getMessage();
                    if (msg == null || !msg.contains("does not exist")) {
                        logger.info("Database cleanup info: {} - {}",
                            ar.cause().getClass().getSimpleName(),
                            msg != null ? msg : "no message");
                    }
                }
                // Always attempt to close the pool; surface a close failure if one occurs.
                return cleanupPool.close();
            });
    }

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        this.vertx = vertx;

        // Clear cached connection pools from previous test classes to avoid stale ports.
        PgBiTemporalEventStore.clearCachedPools();

        // Fresh container reference each test (in case of restart between classes).
        postgres = SharedTestContainers.getSharedPostgreSQLContainer();

        // Build config from the container  no System.setProperty side-effects.
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .property("peegeeq.health-check.queue-checks-enabled", "false")
            .build();

        // Initialise schema, clean DB, then start manager, then build event stores + services.
        Future.<Void>succeededFuture()
            .compose(v -> {
                PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.BITEMPORAL);
                return Future.<Void>succeededFuture();
            })
            .compose(v -> cleanupDatabase())
            .compose(v -> {
                PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
                manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
                return manager.start();
            })
            .map(v -> {
                factory = new BiTemporalEventStoreFactory(vertx, manager);
                tradeEventStore = factory.createEventStore(TradeEvent.class, "bitemporal_event_log");
                cancellationEventStore = factory.createEventStore(TradeCancelledEvent.class, "bitemporal_event_log");
                navEventStore = factory.createEventStore(NAVEvent.class, "bitemporal_event_log");

                tradeService = new TradeService(tradeEventStore, cancellationEventStore);
                positionService = new PositionService(tradeEventStore);
                navService = new NAVService(navEventStore);
                auditService = new TradeAuditService(tradeEventStore, cancellationEventStore);
                regulatoryService = new RegulatoryReportingService(
                    tradeEventStore, navEventStore, positionService, navService);
                return (Void) null;
            })
            .onComplete(ctx.succeedingThenComplete());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        logger.info("Tearing down funds-custody test: closing event stores, manager, and DB");

        Future<Void> closeStores = closeStoreQuietly("trade event store", tradeEventStore)
            .compose(v -> closeStoreQuietly("cancellation event store", cancellationEventStore))
            .compose(v -> closeStoreQuietly("NAV event store", navEventStore));

        Future<Void> chain = closeStores.compose(v -> (manager != null)
            ? manager.closeReactive().transform(ar -> {
                if (ar.failed()) {
                    logger.warn("Error closing PeeGeeQ manager: {}", ar.cause().getMessage());
                }
                return cleanupDatabase();
            })
            : cleanupDatabase());

        chain.onComplete(ctx.succeedingThenComplete());
    }

    private Future<Void> closeStoreQuietly(String label, dev.mars.peegeeq.api.EventStore<?> store) {
        if (store == null) return Future.succeededFuture();
        return store.close().transform(ar -> {
            if (ar.failed()) {
                logger.warn("Error closing {}: {}", label, ar.cause().getMessage());
            }
            return Future.succeededFuture();
        });
    }
}




