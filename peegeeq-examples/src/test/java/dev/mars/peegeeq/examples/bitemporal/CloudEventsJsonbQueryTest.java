package dev.mars.peegeeq.examples.bitemporal;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.api.EventStore;
import dev.mars.peegeeq.bitemporal.BiTemporalEventStoreFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dev.mars.peegeeq.test.PostgreSQLTestConstants.POSTGRES_IMAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates advanced JSONB queries with CloudEvents stored in the bi-temporal event store.
 *
 * This test shows how to leverage PostgreSQL's JSONB operators to query CloudEvents by:
 * - CloudEvent metadata fields (type, source, subject)
 * - CloudEvent extension attributes (correlationid, causationid)
 * - CloudEvent time fields
 * - Data payload content (nested JSON queries)
 * - Combining JSONB queries with bi-temporal dimensions
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CloudEventsJsonbQueryTest {

    private static final Logger logger = LoggerFactory.getLogger(CloudEventsJsonbQueryTest.class);

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
        .withDatabaseName("peegeeq_test")
        .withUsername("test")
        .withPassword("test");

    private static PeeGeeQManager manager;
    private static EventStore<CloudEvent> eventStore;
    private static Pool pool;

    @BeforeAll
    static void setup() throws Exception {
        logger.info("Setting up CloudEvents JSONB query test with PostgreSQL container");

        // Configure system properties for PeeGeeQ
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getMappedPort(5432)));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Initialize schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        // Initialize PeeGeeQManager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();

        // Get Pool for direct SQL queries
        pool = manager.getClientFactory().getPool("peegeeq-main")
            .orElseThrow(() -> new IllegalStateException("Pool not found"));

        // Create bi-temporal event store for CloudEvents
        BiTemporalEventStoreFactory factory = new BiTemporalEventStoreFactory(manager);
        eventStore = factory.createEventStore(CloudEvent.class);

        logger.info("Setup complete - ready for CloudEvents JSONB query tests");
    }

    @AfterAll
    static void teardown() throws Exception {
        if (manager != null) {
            manager.close();
        }
        // Clear system properties
        System.getProperties().entrySet().removeIf(entry ->
            entry.getKey().toString().startsWith("peegeeq."));
        logger.info("Teardown complete");
    }

    /**
     * Test data model for backoffice trade lifecycle events
     */
    static class TradeLifecycleData {
        public String tradeId;
        public String symbol;
        public String side;
        public BigDecimal quantity;
        public BigDecimal price;
        public BigDecimal notionalAmount;
        public String currency;
        public String counterparty;
        public String trader;
        public String desk;
        public String status;  // NEW, AFFIRMED, ALLOCATED, SETTLED, CANCELLED
        public String settlementDate;
        public String bookingSystem;
        public String clearingHouse;

        public TradeLifecycleData() {}

        public TradeLifecycleData(String tradeId, String symbol, String side, BigDecimal quantity,
                        BigDecimal price, String currency, String counterparty, String trader,
                        String desk, String status, String settlementDate, String bookingSystem, String clearingHouse) {
            this.tradeId = tradeId;
            this.symbol = symbol;
            this.side = side;
            this.quantity = quantity;
            this.price = price;
            this.notionalAmount = quantity.multiply(price);
            this.currency = currency;
            this.counterparty = counterparty;
            this.trader = trader;
            this.desk = desk;
            this.status = status;
            this.settlementDate = settlementDate;
            this.bookingSystem = bookingSystem;
            this.clearingHouse = clearingHouse;
        }
    }

    @Test
    @Order(1)
    void testStoreTradeLifecycleEvents() throws Exception {
        logger.info("TEST 1: Storing backoffice trade lifecycle CloudEvents");

        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ObjectMapper mapper = new ObjectMapper();

        // Trade 1: Full lifecycle from execution to settlement
        TradeLifecycleData trade1New = new TradeLifecycleData("TRD-001", "AAPL", "BUY",
            new BigDecimal("100"), new BigDecimal("150.50"), "USD",
            "Goldman Sachs", "john.trader", "equity-trading", "NEW", "2025-10-17", "Murex", "DTCC");

        TradeLifecycleData trade1Affirmed = new TradeLifecycleData("TRD-001", "AAPL", "BUY",
            new BigDecimal("100"), new BigDecimal("150.50"), "USD",
            "Goldman Sachs", "john.trader", "equity-trading", "AFFIRMED", "2025-10-17", "Murex", "DTCC");

        TradeLifecycleData trade1Settled = new TradeLifecycleData("TRD-001", "AAPL", "BUY",
            new BigDecimal("100"), new BigDecimal("150.50"), "USD",
            "Goldman Sachs", "john.trader", "equity-trading", "SETTLED", "2025-10-17", "Murex", "DTCC");

        // Trade 2: Different counterparty and clearing house
        TradeLifecycleData trade2New = new TradeLifecycleData("TRD-002", "MSFT", "SELL",
            new BigDecimal("200"), new BigDecimal("380.25"), "USD",
            "Morgan Stanley", "jane.trader", "equity-trading", "NEW", "2025-10-18", "Calypso", "LCH");

        TradeLifecycleData trade2Affirmed = new TradeLifecycleData("TRD-002", "MSFT", "SELL",
            new BigDecimal("200"), new BigDecimal("380.25"), "USD",
            "Morgan Stanley", "jane.trader", "equity-trading", "AFFIRMED", "2025-10-18", "Calypso", "LCH");

        // Trade 3: Large notional trade
        TradeLifecycleData trade3New = new TradeLifecycleData("TRD-003", "GOOGL", "BUY",
            new BigDecimal("50"), new BigDecimal("2800.00"), "USD",
            "JP Morgan", "john.trader", "equity-trading", "NEW", "2025-10-19", "Murex", "DTCC");

        // Create CloudEvents for trade lifecycle stages
        CloudEvent event1New = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType("backoffice.trade.new.v1")
            .withSource(URI.create("https://backoffice.example.com/trade-capture"))
            .withSubject("TRD-001")
            .withTime(baseTime.atOffset(ZoneOffset.UTC))
            .withDataContentType("application/json")
            .withData(mapper.writeValueAsBytes(trade1New))
            .withExtension("correlationid", "TRD-001")
            .withExtension("causationid", "execution-001")
            .withExtension("bookingsystem", "Murex")
            .withExtension("clearinghouse", "DTCC")
            .build();

        CloudEvent event1Affirmed = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType("backoffice.trade.affirmed.v1")
            .withSource(URI.create("https://backoffice.example.com/affirmation"))
            .withSubject("TRD-001")
            .withTime(baseTime.plus(30, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC))
            .withDataContentType("application/json")
            .withData(mapper.writeValueAsBytes(trade1Affirmed))
            .withExtension("correlationid", "TRD-001")
            .withExtension("causationid", "affirmation-001")
            .withExtension("bookingsystem", "Murex")
            .withExtension("clearinghouse", "DTCC")
            .build();

        CloudEvent event1Settled = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType("backoffice.trade.settled.v1")
            .withSource(URI.create("https://backoffice.example.com/settlement"))
            .withSubject("TRD-001")
            .withTime(baseTime.plus(2, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))
            .withDataContentType("application/json")
            .withData(mapper.writeValueAsBytes(trade1Settled))
            .withExtension("correlationid", "TRD-001")
            .withExtension("causationid", "settlement-001")
            .withExtension("bookingsystem", "Murex")
            .withExtension("clearinghouse", "DTCC")
            .build();

        CloudEvent event2New = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType("backoffice.trade.new.v1")
            .withSource(URI.create("https://backoffice.example.com/trade-capture"))
            .withSubject("TRD-002")
            .withTime(baseTime.plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
            .withDataContentType("application/json")
            .withData(mapper.writeValueAsBytes(trade2New))
            .withExtension("correlationid", "TRD-002")
            .withExtension("causationid", "execution-002")
            .withExtension("bookingsystem", "Calypso")
            .withExtension("clearinghouse", "LCH")
            .build();

        CloudEvent event2Affirmed = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType("backoffice.trade.affirmed.v1")
            .withSource(URI.create("https://backoffice.example.com/affirmation"))
            .withSubject("TRD-002")
            .withTime(baseTime.plus(2, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
            .withDataContentType("application/json")
            .withData(mapper.writeValueAsBytes(trade2Affirmed))
            .withExtension("correlationid", "TRD-002")
            .withExtension("causationid", "affirmation-002")
            .withExtension("bookingsystem", "Calypso")
            .withExtension("clearinghouse", "LCH")
            .build();

        CloudEvent event3New = CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType("backoffice.trade.new.v1")
            .withSource(URI.create("https://backoffice.example.com/trade-capture"))
            .withSubject("TRD-003")
            .withTime(baseTime.plus(3, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
            .withDataContentType("application/json")
            .withData(mapper.writeValueAsBytes(trade3New))
            .withExtension("correlationid", "TRD-003")
            .withExtension("causationid", "execution-003")
            .withExtension("bookingsystem", "Murex")
            .withExtension("clearinghouse", "DTCC")
            .build();

        // Store events with appropriate valid times
        eventStore.append("TradeNew", event1New, baseTime).get(5, TimeUnit.SECONDS);
        eventStore.append("TradeAffirmed", event1Affirmed, baseTime.plus(30, ChronoUnit.MINUTES)).get(5, TimeUnit.SECONDS);
        eventStore.append("TradeSettled", event1Settled, baseTime.plus(2, ChronoUnit.DAYS)).get(5, TimeUnit.SECONDS);
        eventStore.append("TradeNew", event2New, baseTime.plus(1, ChronoUnit.HOURS)).get(5, TimeUnit.SECONDS);
        eventStore.append("TradeAffirmed", event2Affirmed, baseTime.plus(2, ChronoUnit.HOURS)).get(5, TimeUnit.SECONDS);
        eventStore.append("TradeNew", event3New, baseTime.plus(3, ChronoUnit.HOURS)).get(5, TimeUnit.SECONDS);

        logger.info("✅ Stored 6 trade lifecycle CloudEvents (3 trades in various stages)");
    }

    @Test
    @Order(2)
    void testQueryByCloudEventType() throws Exception {
        logger.info("TEST 2: Query CloudEvents by type using JSONB operators");

        String sql = "SELECT event_id, payload->>'type' as event_type, payload->>'subject' as trade_id " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->>'type' = $1 " +
                    "ORDER BY transaction_time";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("backoffice.trade.new.v1"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            assertEquals("backoffice.trade.new.v1", row.getString("event_type"));
            logger.info("Found NEW trade event: {} for trade: {}",
                row.getValue("event_id"), row.getString("trade_id"));
        }

        assertEquals(3, count, "Should find 3 NEW trade events");
        logger.info("✅ Successfully queried {} NEW trade events by CloudEvent type", count);
    }

    @Test
    @Order(3)
    void testQueryByExtensionAttribute() throws Exception {
        logger.info("TEST 3: Query CloudEvents by extension attributes (correlationid)");

        // Find all events for a specific trade using correlationid
        // NOTE: CloudEvents extensions are stored as top-level fields, not in an 'extensions' object
        String sql = "SELECT event_id, payload->>'type' as event_type, " +
                    "payload->>'correlationid' as correlation_id " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->>'correlationid' = $1 " +
                    "ORDER BY valid_time";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("TRD-001"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            assertEquals("TRD-001", row.getString("correlation_id"));
            logger.info("Found lifecycle event: {} type: {} for trade TRD-001",
                row.getValue("event_id"), row.getString("event_type"));
        }

        assertEquals(3, count, "Should find 3 lifecycle events for TRD-001 (NEW, AFFIRMED, SETTLED)");
        logger.info("✅ Successfully queried {} lifecycle events by correlationid", count);
    }

    @Test
    @Order(4)
    void testQueryByBookingSystem() throws Exception {
        logger.info("TEST 4: Query CloudEvents by booking system extension");

        // Find all trades processed through Murex
        String sql = "SELECT event_id, payload->>'subject' as trade_id, " +
                    "payload->>'bookingsystem' as booking_system " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->>'bookingsystem' = $1 " +
                    "ORDER BY transaction_time";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("Murex"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            assertEquals("Murex", row.getString("booking_system"));
            logger.info("Found Murex trade: {} event: {}",
                row.getString("trade_id"), row.getValue("event_id"));
        }

        assertEquals(4, count, "Should find 4 events processed through Murex (TRD-001: 3 events, TRD-003: 1 event)");
        logger.info("✅ Successfully queried {} events by booking system", count);
    }

    @Test
    @Order(5)
    void testQueryByDataPayloadField() throws Exception {
        logger.info("TEST 5: Query CloudEvents by data payload fields (notional amount)");

        // Find all trades with notional amount > 100,000
        String sql = "SELECT event_id, payload->>'subject' as trade_id, " +
                    "payload->>'type' as event_type, " +
                    "(payload->'data'->>'notionalAmount')::numeric as notional " +
                    "FROM bitemporal_event_log " +
                    "WHERE (payload->'data'->>'notionalAmount')::numeric > $1 " +
                    "ORDER BY (payload->'data'->>'notionalAmount')::numeric DESC";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(new BigDecimal("100000")))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            BigDecimal notional = row.getBigDecimal("notional");
            assertTrue(notional.compareTo(new BigDecimal("100000")) > 0);
            logger.info("Found large trade: {} notional: {} event: {}",
                row.getString("trade_id"), notional, row.getString("event_type"));
        }

        assertTrue(count >= 1, "Should find at least 1 trade with notional > 100,000");
        logger.info("✅ Successfully queried {} large trades by notional amount", count);
    }


    @Test
    @Order(6)
    void testQueryByCounterpartyAndStatus() throws Exception {
        logger.info("TEST 6: Query CloudEvents by multiple data payload fields");

        // Find all AFFIRMED trades with specific counterparty
        String sql = "SELECT event_id, payload->>'subject' as trade_id, " +
                    "payload->'data'->>'counterparty' as counterparty, " +
                    "payload->'data'->>'status' as status " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->'data'->>'counterparty' = $1 " +
                    "AND payload->'data'->>'status' = $2 " +
                    "ORDER BY transaction_time";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("Goldman Sachs", "AFFIRMED"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            assertEquals("Goldman Sachs", row.getString("counterparty"));
            assertEquals("AFFIRMED", row.getString("status"));
            logger.info("Found AFFIRMED Goldman Sachs trade: {} event: {}",
                row.getString("trade_id"), row.getValue("event_id"));
        }

        assertEquals(1, count, "Should find 1 AFFIRMED trade with Goldman Sachs");
        logger.info("✅ Successfully queried {} trades by counterparty and status", count);
    }

    @Test
    @Order(7)
    void testQueryByClearingHouseWithTimeRange() throws Exception {
        logger.info("TEST 7: Combine JSONB query with bi-temporal time range");

        Instant cutoffTime = Instant.now().plus(2, ChronoUnit.HOURS);

        // Find all DTCC trades that occurred before cutoff time
        String sql = "SELECT event_id, payload->>'subject' as trade_id, " +
                    "payload->>'clearinghouse' as clearing_house, " +
                    "valid_time " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->>'clearinghouse' = $1 " +
                    "AND valid_time < $2 " +
                    "ORDER BY valid_time";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("DTCC", cutoffTime.atOffset(java.time.ZoneOffset.UTC)))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            assertEquals("DTCC", row.getString("clearing_house"));
            logger.info("Found DTCC trade before cutoff: {} at valid_time: {}",
                row.getString("trade_id"), row.getOffsetDateTime("valid_time"));
        }

        assertTrue(count >= 1, "Should find at least 1 DTCC trade before cutoff");
        logger.info("✅ Successfully queried {} DTCC trades with time range", count);
    }

    @Test
    @Order(8)
    void testQueryByCloudEventSource() throws Exception {
        logger.info("TEST 8: Query CloudEvents by source system");

        // Find all events from the affirmation system
        String sql = "SELECT event_id, payload->>'type' as event_type, " +
                    "payload->>'source' as source, " +
                    "payload->>'subject' as trade_id " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->>'source' = $1 " +
                    "ORDER BY transaction_time";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("https://backoffice.example.com/affirmation"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            assertEquals("https://backoffice.example.com/affirmation", row.getString("source"));
            assertTrue(row.getString("event_type").contains("affirmed"));
            logger.info("Found affirmation event: {} for trade: {}",
                row.getValue("event_id"), row.getString("trade_id"));
        }

        assertEquals(2, count, "Should find 2 affirmation events");
        logger.info("✅ Successfully queried {} events by source system", count);
    }

    @Test
    @Order(9)
    void testQueryBySymbolAndSide() throws Exception {
        logger.info("TEST 9: Query CloudEvents by nested data payload fields");

        // Find all BUY trades for AAPL
        String sql = "SELECT event_id, payload->>'subject' as trade_id, " +
                    "payload->'data'->>'symbol' as symbol, " +
                    "payload->'data'->>'side' as side, " +
                    "payload->'data'->>'status' as status " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->'data'->>'symbol' = $1 " +
                    "AND payload->'data'->>'side' = $2 " +
                    "ORDER BY transaction_time";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("AAPL", "BUY"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            assertEquals("AAPL", row.getString("symbol"));
            assertEquals("BUY", row.getString("side"));
            logger.info("Found AAPL BUY trade: {} status: {}",
                row.getString("trade_id"), row.getString("status"));
        }

        assertEquals(3, count, "Should find 3 AAPL BUY events (NEW, AFFIRMED, SETTLED)");
        logger.info("✅ Successfully queried {} AAPL BUY trades", count);
    }

    @Test
    @Order(10)
    void testComplexAggregationQuery() throws Exception {
        logger.info("TEST 10: Complex aggregation query on CloudEvents data");

        // Aggregate notional amounts by counterparty
        String sql = "SELECT " +
                    "payload->'data'->>'counterparty' as counterparty, " +
                    "COUNT(*) as trade_count, " +
                    "SUM((payload->'data'->>'notionalAmount')::numeric) as total_notional " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->>'type' = $1 " +
                    "GROUP BY payload->'data'->>'counterparty' " +
                    "ORDER BY total_notional DESC";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("backoffice.trade.new.v1"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            String counterparty = row.getString("counterparty");
            Integer tradeCount = row.getInteger("trade_count");
            BigDecimal totalNotional = row.getBigDecimal("total_notional");

            logger.info("Counterparty: {} - Trades: {} - Total Notional: {}",
                counterparty, tradeCount, totalNotional);
        }

        assertEquals(3, count, "Should have 3 counterparties");
        logger.info("✅ Successfully executed aggregation query on {} counterparties", count);
    }

    @Test
    @Order(11)
    void testBiTemporalPointInTimeQuery() throws Exception {
        logger.info("TEST 11: Bi-temporal point-in-time query with JSONB filtering");

        Instant pointInTime = Instant.now().plus(1, ChronoUnit.HOURS);

        // Find all trades that were valid at a specific point in time, filtered by clearing house
        String sql = "SELECT event_id, payload->>'subject' as trade_id, " +
                    "payload->'data'->>'status' as status, " +
                    "payload->>'clearinghouse' as clearing_house, " +
                    "valid_time, transaction_time " +
                    "FROM bitemporal_event_log " +
                    "WHERE valid_time <= $1 " +
                    "AND payload->>'clearinghouse' = $2 " +
                    "ORDER BY valid_time DESC";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of(
                pointInTime.atOffset(java.time.ZoneOffset.UTC),
                "DTCC"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            assertEquals("DTCC", row.getString("clearing_house"));
            logger.info("Point-in-time DTCC trade: {} status: {} valid_time: {}",
                row.getString("trade_id"),
                row.getString("status"),
                row.getOffsetDateTime("valid_time"));
        }

        assertTrue(count >= 1, "Should find at least 1 DTCC trade at point in time");
        logger.info("✅ Successfully executed bi-temporal point-in-time query with {} results", count);
    }

    @Test
    @Order(12)
    void testTradeLifecycleReconstruction() throws Exception {
        logger.info("TEST 12: Reconstruct complete trade lifecycle using correlationid");

        // Get complete lifecycle for TRD-001
        String sql = "SELECT event_id, " +
                    "payload->>'type' as event_type, " +
                    "payload->>'subject' as trade_id, " +
                    "payload->'data'->>'status' as status, " +
                    "payload->'data'->>'notionalAmount' as notional, " +
                    "payload->>'correlationid' as correlation_id, " +
                    "valid_time, transaction_time " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->>'correlationid' = $1 " +
                    "ORDER BY valid_time ASC";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("TRD-001"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);

        logger.info("=== Trade Lifecycle for TRD-001 ===");
        int count = 0;
        String[] expectedStatuses = {"NEW", "AFFIRMED", "SETTLED"};

        for (Row row : rows) {
            assertEquals("TRD-001", row.getString("trade_id"));
            assertEquals("TRD-001", row.getString("correlation_id"));

            String status = row.getString("status");
            assertEquals(expectedStatuses[count], status);

            logger.info("Stage {}: {} - Status: {} - Notional: {} - Valid Time: {}",
                count + 1,
                row.getString("event_type"),
                status,
                row.getString("notional"),
                row.getOffsetDateTime("valid_time"));

            count++;
        }

        assertEquals(3, count, "Should have complete lifecycle: NEW -> AFFIRMED -> SETTLED");
        logger.info("✅ Successfully reconstructed complete trade lifecycle with {} stages", count);
    }

    @Test
    @Order(13)
    void testSettlementDateRangeQuery() throws Exception {
        logger.info("TEST 13: Query trades by settlement date range");

        // Find all trades settling on or after a specific date
        String sql = "SELECT event_id, " +
                    "payload->>'subject' as trade_id, " +
                    "payload->'data'->>'settlementDate' as settlement_date, " +
                    "payload->'data'->>'status' as status " +
                    "FROM bitemporal_event_log " +
                    "WHERE payload->'data'->>'settlementDate' >= $1 " +
                    "AND payload->>'type' = $2 " +
                    "ORDER BY payload->'data'->>'settlementDate'";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("2025-10-18", "backoffice.trade.new.v1"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            String settlementDate = row.getString("settlement_date");
            assertTrue(settlementDate.compareTo("2025-10-18") >= 0);
            logger.info("Trade settling on/after 2025-10-18: {} settlement: {} status: {}",
                row.getString("trade_id"),
                settlementDate,
                row.getString("status"));
        }

        assertTrue(count >= 1, "Should find at least 1 trade settling on or after 2025-10-18");
        logger.info("✅ Successfully queried {} trades by settlement date range", count);
    }

    @Test
    @Order(14)
    void testMultiSystemQuery() throws Exception {
        logger.info("TEST 14: Query across multiple booking systems and clearing houses");

        // Find all trades processed through Murex OR cleared through DTCC
        String sql = "SELECT event_id, " +
                    "payload->>'subject' as trade_id, " +
                    "payload->>'bookingsystem' as booking_system, " +
                    "payload->>'clearinghouse' as clearing_house, " +
                    "payload->'data'->>'status' as status " +
                    "FROM bitemporal_event_log " +
                    "WHERE (payload->>'bookingsystem' = $1 " +
                    "OR payload->>'clearinghouse' = $2) " +
                    "AND payload->>'type' = $3 " +
                    "ORDER BY transaction_time";

        CompletableFuture<RowSet<Row>> future = new CompletableFuture<>();
        pool.preparedQuery(sql)
            .execute(io.vertx.sqlclient.Tuple.of("Murex", "DTCC", "backoffice.trade.new.v1"))
            .onComplete(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

        RowSet<Row> rows = future.get(5, TimeUnit.SECONDS);
        int count = 0;
        for (Row row : rows) {
            count++;
            String bookingSystem = row.getString("booking_system");
            String clearingHouse = row.getString("clearing_house");

            assertTrue(
                "Murex".equals(bookingSystem) || "DTCC".equals(clearingHouse),
                "Trade should be processed through Murex OR cleared through DTCC"
            );

            logger.info("Multi-system trade: {} - Booking: {} - Clearing: {} - Status: {}",
                row.getString("trade_id"),
                bookingSystem,
                clearingHouse,
                row.getString("status"));
        }

        assertTrue(count >= 2, "Should find at least 2 trades matching criteria");
        logger.info("✅ Successfully queried {} trades across multiple systems", count);
    }
}



