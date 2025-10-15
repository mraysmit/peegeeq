package dev.mars.peegeeq.examples.fundscustody;

import dev.mars.peegeeq.examples.fundscustody.domain.Currency;
import dev.mars.peegeeq.examples.fundscustody.domain.Position;
import dev.mars.peegeeq.examples.fundscustody.domain.TradeType;
import dev.mars.peegeeq.examples.fundscustody.model.PositionSnapshot;
import dev.mars.peegeeq.examples.fundscustody.model.TradeRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PositionService demonstrating position calculation from trade events.
 */
class PositionServiceTest extends FundsCustodyTestBase {
    
    @Test
    void testPositionCalculation() throws Exception {
        // Given: Multiple buy trades
        TradeRequest trade1 = new TradeRequest(
            "FUND-001", "AAPL", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("50.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );
        
        TradeRequest trade2 = new TradeRequest(
            "FUND-001", "AAPL", TradeType.BUY,
            new BigDecimal("50"), new BigDecimal("60.00"), Currency.USD,
            LocalDate.of(2024, 11, 16), LocalDate.of(2024, 11, 20),
            "Morgan Stanley"
        );
        
        tradeService.recordTrade(trade1).get();
        tradeService.recordTrade(trade2).get();
        
        // When: Calculating position as of Nov 16
        Position position = positionService.getPositionAsOf(
            "FUND-001", "AAPL", LocalDate.of(2024, 11, 16)
        ).get();
        
        // Then: Position is correct
        assertEquals("FUND-001", position.fundId());
        assertEquals("AAPL", position.securityId());
        assertEquals(new BigDecimal("150"), position.quantity());  // 100 + 50
        
        // Average price = (100 * 50 + 50 * 60) / 150 = 8000 / 150 = 53.333333
        BigDecimal expectedAvgPrice = new BigDecimal("53.333333");
        assertEquals(0, expectedAvgPrice.compareTo(position.averagePrice()));
        
        assertEquals(Currency.USD, position.currency());
        assertEquals(LocalDate.of(2024, 11, 16), position.asOfDate());
        
        assertTrue(position.isLong());
        assertFalse(position.isFlat());
        assertFalse(position.isShort());
    }
    
    @Test
    void testPositionAsOfDate() throws Exception {
        // Given: Trades on different dates
        TradeRequest trade1 = new TradeRequest(
            "FUND-001", "MSFT", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("380.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );
        
        TradeRequest trade2 = new TradeRequest(
            "FUND-001", "MSFT", TradeType.BUY,
            new BigDecimal("50"), new BigDecimal("385.00"), Currency.USD,
            LocalDate.of(2024, 11, 16), LocalDate.of(2024, 11, 20),
            "Morgan Stanley"
        );
        
        tradeService.recordTrade(trade1).get();
        tradeService.recordTrade(trade2).get();
        
        // When: Querying position as of Nov 15
        Position positionNov15 = positionService.getPositionAsOf(
            "FUND-001", "MSFT", LocalDate.of(2024, 11, 15)
        ).get();
        
        // Then: Only first trade is included
        assertEquals(new BigDecimal("100"), positionNov15.quantity());
        assertEquals(0, new BigDecimal("380.00").compareTo(positionNov15.averagePrice()));
        
        // When: Querying position as of Nov 16
        Position positionNov16 = positionService.getPositionAsOf(
            "FUND-001", "MSFT", LocalDate.of(2024, 11, 16)
        ).get();
        
        // Then: Both trades are included
        assertEquals(new BigDecimal("150"), positionNov16.quantity());
        
        // Average price = (100 * 380 + 50 * 385) / 150 = 57250 / 150 = 381.666667
        BigDecimal expectedAvgPrice = new BigDecimal("381.666667");
        assertEquals(0, expectedAvgPrice.compareTo(positionNov16.averagePrice()));
    }
    
    @Test
    void testBuyAndSell() throws Exception {
        // Given: Buy and sell trades
        TradeRequest buy = new TradeRequest(
            "FUND-001", "GOOGL", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("140.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );
        
        TradeRequest sell = new TradeRequest(
            "FUND-001", "GOOGL", TradeType.SELL,
            new BigDecimal("30"), new BigDecimal("145.00"), Currency.USD,
            LocalDate.of(2024, 11, 16), LocalDate.of(2024, 11, 20),
            "Morgan Stanley"
        );
        
        tradeService.recordTrade(buy).get();
        tradeService.recordTrade(sell).get();
        
        // When: Calculating position
        Position position = positionService.getPositionAsOf(
            "FUND-001", "GOOGL", LocalDate.of(2024, 11, 16)
        ).get();
        
        // Then: Net position is correct
        assertEquals(new BigDecimal("70"), position.quantity());  // 100 - 30
        
        // Average price = (100 * 140 - 30 * 145) / 70 = 9650 / 70 = 137.857143
        BigDecimal expectedAvgPrice = new BigDecimal("137.857143");
        assertEquals(0, expectedAvgPrice.compareTo(position.averagePrice()));
        
        assertTrue(position.isLong());
    }
    
    @Test
    void testFlatPosition() throws Exception {
        // Given: Equal buy and sell
        TradeRequest buy = new TradeRequest(
            "FUND-001", "TSLA", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("250.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );
        
        TradeRequest sell = new TradeRequest(
            "FUND-001", "TSLA", TradeType.SELL,
            new BigDecimal("100"), new BigDecimal("255.00"), Currency.USD,
            LocalDate.of(2024, 11, 16), LocalDate.of(2024, 11, 20),
            "Morgan Stanley"
        );
        
        tradeService.recordTrade(buy).get();
        tradeService.recordTrade(sell).get();
        
        // When: Calculating position
        Position position = positionService.getPositionAsOf(
            "FUND-001", "TSLA", LocalDate.of(2024, 11, 16)
        ).get();
        
        // Then: Position is flat
        assertEquals(BigDecimal.ZERO, position.quantity());
        assertNull(position.averagePrice());  // No average price for flat position
        assertTrue(position.isFlat());
        assertFalse(position.isLong());
        assertFalse(position.isShort());
    }
    
    @Test
    void testShortPosition() throws Exception {
        // Given: Sell without prior buy (short sale)
        TradeRequest sell = new TradeRequest(
            "FUND-001", "NVDA", TradeType.SELL,
            new BigDecimal("50"), new BigDecimal("500.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );
        
        tradeService.recordTrade(sell).get();
        
        // When: Calculating position
        Position position = positionService.getPositionAsOf(
            "FUND-001", "NVDA", LocalDate.of(2024, 11, 15)
        ).get();
        
        // Then: Position is short
        assertEquals(new BigDecimal("-50"), position.quantity());
        assertEquals(0, new BigDecimal("500.00").compareTo(position.averagePrice()));
        assertTrue(position.isShort());
        assertFalse(position.isLong());
        assertFalse(position.isFlat());
    }
    
    @Test
    void testGetPositionsByFund() throws Exception {
        // Given: Trades for multiple securities
        TradeRequest aaplTrade = new TradeRequest(
            "FUND-001", "AAPL", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("150.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );
        
        TradeRequest msftTrade = new TradeRequest(
            "FUND-001", "MSFT", TradeType.BUY,
            new BigDecimal("50"), new BigDecimal("380.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Morgan Stanley"
        );
        
        TradeRequest googlTrade = new TradeRequest(
            "FUND-001", "GOOGL", TradeType.BUY,
            new BigDecimal("75"), new BigDecimal("140.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "JP Morgan"
        );
        
        tradeService.recordTrade(aaplTrade).get();
        tradeService.recordTrade(msftTrade).get();
        tradeService.recordTrade(googlTrade).get();
        
        // When: Getting all positions for fund
        List<Position> positions = positionService.getPositionsByFund(
            "FUND-001", LocalDate.of(2024, 11, 15)
        ).get();
        
        // Then: All positions are returned
        assertEquals(3, positions.size());
        
        List<String> securities = positions.stream()
            .map(Position::securityId)
            .sorted()
            .toList();
        assertEquals(List.of("AAPL", "GOOGL", "MSFT"), securities);
        
        // Verify quantities
        Position aaplPosition = positions.stream()
            .filter(p -> "AAPL".equals(p.securityId()))
            .findFirst()
            .orElseThrow();
        assertEquals(new BigDecimal("100"), aaplPosition.quantity());
    }
    
    @Test
    void testPositionHistory() throws Exception {
        // Given: Trades over multiple days
        TradeRequest day1 = new TradeRequest(
            "FUND-001", "AAPL", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("150.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );
        
        TradeRequest day2 = new TradeRequest(
            "FUND-001", "AAPL", TradeType.BUY,
            new BigDecimal("50"), new BigDecimal("155.00"), Currency.USD,
            LocalDate.of(2024, 11, 16), LocalDate.of(2024, 11, 20),
            "Morgan Stanley"
        );
        
        TradeRequest day3 = new TradeRequest(
            "FUND-001", "AAPL", TradeType.SELL,
            new BigDecimal("30"), new BigDecimal("160.00"), Currency.USD,
            LocalDate.of(2024, 11, 17), LocalDate.of(2024, 11, 21),
            "JP Morgan"
        );
        
        tradeService.recordTrade(day1).get();
        tradeService.recordTrade(day2).get();
        tradeService.recordTrade(day3).get();
        
        // When: Getting position history
        List<PositionSnapshot> history = positionService.getPositionHistory(
            "FUND-001", "AAPL",
            LocalDate.of(2024, 11, 15),
            LocalDate.of(2024, 11, 17)
        ).get();
        
        // Then: Daily snapshots are returned
        assertEquals(3, history.size());
        
        // Nov 15: 100 shares
        PositionSnapshot nov15 = history.get(0);
        assertEquals(new BigDecimal("100"), nov15.quantity());
        assertEquals(LocalDate.of(2024, 11, 15), nov15.asOfDate());
        
        // Nov 16: 150 shares
        PositionSnapshot nov16 = history.get(1);
        assertEquals(new BigDecimal("150"), nov16.quantity());
        assertEquals(LocalDate.of(2024, 11, 16), nov16.asOfDate());
        
        // Nov 17: 120 shares (150 - 30)
        PositionSnapshot nov17 = history.get(2);
        assertEquals(new BigDecimal("120"), nov17.quantity());
        assertEquals(LocalDate.of(2024, 11, 17), nov17.asOfDate());
    }
    
    @Test
    void testMarketValue() throws Exception {
        // Given: A position
        TradeRequest trade = new TradeRequest(
            "FUND-001", "AAPL", TradeType.BUY,
            new BigDecimal("100"), new BigDecimal("150.00"), Currency.USD,
            LocalDate.of(2024, 11, 15), LocalDate.of(2024, 11, 19),
            "Goldman Sachs"
        );
        
        tradeService.recordTrade(trade).get();
        
        Position position = positionService.getPositionAsOf(
            "FUND-001", "AAPL", LocalDate.of(2024, 11, 15)
        ).get();
        
        // When: Calculating market value at current price
        BigDecimal currentPrice = new BigDecimal("160.00");
        BigDecimal marketValue = position.marketValue(currentPrice);
        
        // Then: Market value is correct
        assertEquals(new BigDecimal("16000.00"), marketValue);  // 100 * 160
        
        // Cost basis
        BigDecimal costBasis = position.costBasis();
        assertEquals(new BigDecimal("15000.000000"), costBasis);  // 100 * 150
        
        // Unrealized P&L
        BigDecimal unrealizedPnL = marketValue.subtract(costBasis);
        assertEquals(new BigDecimal("1000.00"), unrealizedPnL.setScale(2, RoundingMode.HALF_UP));
    }
}

