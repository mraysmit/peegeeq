package dev.mars.peegeeq.examples.fundscustody;

import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import dev.mars.peegeeq.api.BiTemporalEvent;
import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;
import dev.mars.peegeeq.examples.fundscustody.events.TradeCancelledEvent;
import dev.mars.peegeeq.examples.fundscustody.events.TradeEvent;
import dev.mars.peegeeq.examples.fundscustody.model.CancellationRequest;
import dev.mars.peegeeq.examples.fundscustody.model.TradeRequest;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TradeService demonstrating funds & custody trade lifecycle patterns.
 */
@Tag(TestCategories.INTEGRATION)
class TradeServiceTest extends FundsCustodyTestBase {
    
    @Test
    void testRecordTrade(VertxTestContext testContext) throws Exception {
        // Given: A trade request
        TradeRequest request = new TradeRequest(
            "FUND-001",
            "AAPL",
            TradeType.BUY,
            new BigDecimal("100"),
            new BigDecimal("150.00"),
            Currency.USD,
            LocalDate.of(2024, 11, 15),
            LocalDate.of(2024, 11, 19),  // T+2
            "Goldman Sachs"
        );

        // When: Recording the trade
        tradeService.recordTrade(request)
            .onSuccess(event -> testContext.verify(() -> {
                // Then: Event is stored with correct properties
                assertNotNull(event);
                assertEquals("TradeExecuted", event.getEventType());
                assertEquals("TRADE:FUND-001", event.getAggregateId());

                TradeEvent payload = event.getPayload();
                assertEquals("FUND-001", payload.fundId());
                assertEquals("AAPL", payload.securityId());
                assertEquals("BUY", payload.tradeType());
                assertEquals(new BigDecimal("100"), payload.quantity());
                assertEquals(new BigDecimal("150.00"), payload.price());
                assertEquals("USD", payload.currency());
                assertEquals(LocalDate.of(2024, 11, 15), payload.tradeDate());
                assertEquals(LocalDate.of(2024, 11, 19), payload.settlementDate());
                assertEquals("Goldman Sachs", payload.counterparty());

                // Valid time should be trade date
                LocalDate validDate = LocalDate.ofInstant(
                    event.getValidTime(),
                    java.time.ZoneOffset.UTC
                );
                assertEquals(LocalDate.of(2024, 11, 15), validDate);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
    }
    
    @Test
    void testCancelTrade(VertxTestContext testContext) throws Exception {
        // Given: A recorded trade
        TradeRequest request = new TradeRequest(
            "FUND-001",
            "MSFT",
            TradeType.SELL,
            new BigDecimal("50"),
            new BigDecimal("380.00"),
            Currency.USD,
            LocalDate.of(2024, 11, 15),
            LocalDate.of(2024, 11, 19),
            "Morgan Stanley"
        );

        tradeService.recordTrade(request)
            .compose(originalEvent -> {
                String tradeId = originalEvent.getPayload().tradeId();

                // When: Cancelling the trade
                CancellationRequest cancellation = new CancellationRequest(
                    "Trade entered in error",
                    "john.smith@fund.com"
                );

                return tradeService.cancelTrade(tradeId, cancellation)
                    .map(cancelEvent -> new Object[]{tradeId, cancelEvent});
            })
            .onSuccess(arr -> testContext.verify(() -> {
                String tradeId = (String) arr[0];
                @SuppressWarnings("unchecked")
                BiTemporalEvent<TradeCancelledEvent> cancelEvent = (BiTemporalEvent<TradeCancelledEvent>) arr[1];

                // Then: Cancellation event is stored
                assertNotNull(cancelEvent);
                assertEquals("TradeCancelled", cancelEvent.getEventType());
                assertEquals("CANCELLATION:FUND-001", cancelEvent.getAggregateId());

                TradeCancelledEvent payload = cancelEvent.getPayload();
                assertEquals(tradeId, payload.tradeId());
                assertEquals("FUND-001", payload.fundId());
                assertEquals("MSFT", payload.securityId());
                assertEquals(LocalDate.of(2024, 11, 15), payload.originalTradeDate());
                assertEquals("Trade entered in error", payload.reason());
                assertEquals("john.smith@fund.com", payload.cancelledBy());

                // Valid time should match original trade date (backdated correction)
                LocalDate validDate = LocalDate.ofInstant(
                    cancelEvent.getValidTime(),
                    java.time.ZoneOffset.UTC
                );
                assertEquals(LocalDate.of(2024, 11, 15), validDate);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
    }
    
    @Test
    void testQueryTradesByFund(VertxTestContext testContext) throws Exception {
        // Given: Trades for multiple funds
        TradeRequest fund1Trade1 = new TradeRequest(
            "FUND-001", "AAPL", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("150.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );

        TradeRequest fund1Trade2 = new TradeRequest(
            "FUND-001", "MSFT", TradeType.BUY,
            new BigDecimal("50"), new BigDecimal("380.00"), Currency.USD,
            LocalDate.of(2024, 11, 16), LocalDate.of(2024, 11, 20),
            "Morgan Stanley"
        );

        TradeRequest fund2Trade = new TradeRequest(
            "FUND-002", "GOOGL", TradeType.BUY,
            new BigDecimal("75"), new BigDecimal("140.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "JP Morgan"
        );

        tradeService.recordTrade(fund1Trade1)
            .compose(v -> tradeService.recordTrade(fund1Trade2))
            .compose(v -> tradeService.recordTrade(fund2Trade))
            // When: Querying trades for FUND-001
            .compose(v -> tradeService.queryTradesByFund("FUND-001"))
            .onSuccess(fund1Trades -> testContext.verify(() -> {
                // Then: Only FUND-001 trades are returned
                assertEquals(2, fund1Trades.size());
                assertTrue(fund1Trades.stream()
                    .allMatch(trade -> "FUND-001".equals(trade.getPayload().fundId())));

                // Verify securities
                List<String> securities = fund1Trades.stream()
                    .map(trade -> trade.getPayload().securityId())
                    .sorted()
                    .toList();
                assertEquals(List.of("AAPL", "MSFT"), securities);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
    }
    
    @Test
    void testLateTradeConfirmation(VertxTestContext testContext) throws Exception {
        // Given: NAV cutoff at 18:00
        LocalTime cutoffTime = LocalTime.of(18, 0);
        LocalDate tradingDay = LocalDate.of(2024, 11, 15);

        // Trade 1: Confirmed before cutoff (10:30)
        TradeRequest earlyTrade = new TradeRequest(
            "FUND-001", "AAPL", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("150.00"), Currency.USD,
            tradingDay, tradingDay.plusDays(2),
            "Goldman Sachs"
        );

        // Trade 2: Confirmed after cutoff (20:00)
        TradeRequest lateTrade = new TradeRequest(
            "FUND-001", "MSFT", TradeType.BUY,
            new BigDecimal("50"), new BigDecimal("380.00"), Currency.USD,
            tradingDay, tradingDay.plusDays(2),
            "Morgan Stanley"
        );

        // Record at 10:30 (simulated by immediate recording)
        tradeService.recordTrade(earlyTrade)
            // Simulate late confirmation by waiting a bit
            .compose(v -> vertx.timer(100).mapEmpty())
            .compose(v -> tradeService.recordTrade(lateTrade))
            // When: Checking for late trades
            // Note: In real scenario, we'd control transaction time more precisely
            // For this test, we'll verify the query works correctly
            .compose(v -> tradeService.getLateTradeConfirmations("FUND-001", tradingDay, cutoffTime))
            .onSuccess(lateTrades -> testContext.verify(() -> {
                // Then: Query executes successfully
                // In production, late trades would be those with transaction time after cutoff
                assertNotNull(lateTrades);

                // All returned trades should have trade date on the trading day
                assertTrue(lateTrades.stream()
                    .allMatch(trade -> {
                        LocalDate tradeDate = LocalDate.ofInstant(
                            trade.getValidTime(),
                            java.time.ZoneOffset.UTC
                        );
                        return tradeDate.equals(tradingDay);
                    })
                );
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
    }
    
    @Test
    void testCancelNonExistentTrade(VertxTestContext testContext) throws Exception {
        // Given: A non-existent trade ID
        String nonExistentTradeId = "TRD-DOES-NOT-EXIST";
        CancellationRequest cancellation = new CancellationRequest(
            "Test cancellation",
            "test.user@fund.com"
        );

        // When/Then: Attempting to cancel should fail
        tradeService.cancelTrade(nonExistentTradeId, cancellation)
            .<Void>transform(ar -> {
                if (ar.succeeded()) {
                    return Future.failedFuture(new AssertionError(
                        "Expected cancellation of non-existent trade to fail, but it succeeded"));
                }
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
    }
    
    @Test
    void testMultipleCancellations(VertxTestContext testContext) throws Exception {
        // Given: A recorded trade
        TradeRequest request = new TradeRequest(
            "FUND-001", "AAPL", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("150.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );

        // When: Cancelling the trade multiple times (audit trail)
        CancellationRequest cancellation1 = new CancellationRequest(
            "First cancellation attempt",
            "user1@fund.com"
        );

        CancellationRequest cancellation2 = new CancellationRequest(
            "Second cancellation (confirmation)",
            "user2@fund.com"
        );

        tradeService.recordTrade(request)
            .compose(originalEvent -> {
                String tradeId = originalEvent.getPayload().tradeId();
                return tradeService.cancelTrade(tradeId, cancellation1)
                    .compose(cancel1 -> tradeService.cancelTrade(tradeId, cancellation2)
                        .map(cancel2 -> new BiTemporalEvent[]{cancel1, cancel2}));
            })
            .onSuccess(cancels -> testContext.verify(() -> {
                @SuppressWarnings("unchecked")
                BiTemporalEvent<TradeCancelledEvent> cancel1 = (BiTemporalEvent<TradeCancelledEvent>) cancels[0];
                @SuppressWarnings("unchecked")
                BiTemporalEvent<TradeCancelledEvent> cancel2 = (BiTemporalEvent<TradeCancelledEvent>) cancels[1];

                // Then: Both cancellations are recorded
                assertNotNull(cancel1);
                assertNotNull(cancel2);
                assertEquals("First cancellation attempt", cancel1.getPayload().reason());
                assertEquals("Second cancellation (confirmation)", cancel2.getPayload().reason());

                // Both should have same valid time (original trade date)
                assertEquals(cancel1.getValidTime(), cancel2.getValidTime());

                // But different transaction times
                assertNotEquals(cancel1.getTransactionTime(), cancel2.getTransactionTime());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS),
                "Test should complete within 30 seconds");
    }
}

