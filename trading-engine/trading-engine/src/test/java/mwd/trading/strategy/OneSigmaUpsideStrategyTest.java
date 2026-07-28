package mwd.trading.strategy;

import static mwd.trading.support.ProxyReferenceFixtures.earlyCloseSession;
import static mwd.trading.support.ProxyReferenceFixtures.newYork;
import static mwd.trading.support.ProxyReferenceFixtures.standardSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.calendar.MarketCalendarStore;
import mwd.trading.domain.Stock;
import mwd.trading.domain.TradeDirection;
import mwd.trading.execution.BracketOrder;
import mwd.trading.execution.BracketOrderExecutor;
import mwd.trading.execution.BracketOrderGateway;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.lifecycle.EngineMode;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.marketdata.MarketDataInput;
import mwd.trading.marketdata.MarketDataInputStore;
import mwd.trading.marketdata.TickStreamController;
import mwd.trading.optionsproxy.OptionsIndicatorStore;
import mwd.trading.optionsproxy.proto.IndicatorFrame;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

/**
 * Covers the short paper execution-verification strategy.
 *
 * <p>
 * Previous close 100.00 and an implied move of 4.00 put the entry level at
 * 104.00, the stop at 105.00, and the highest acceptable VWAP at 103.00. Every
 * level mirrors {@link OneSigmaDownsideStrategyTest} across the previous close.
 */
class OneSigmaUpsideStrategyTest {
    private static final String TICKER = "AAPL";
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27);
    private static final Instant MID_MORNING = newYork(MONDAY, 10, 0);
    private static final double PREVIOUS_CLOSE = 100.0;
    private static final double IMPLIED_MOVE = 4.0;
    private static final double ENTRY_LEVEL = 104.0;
    private static final double STOP_LEVEL = 105.0;

    private Blackboard blackboard;

    private OneSigmaUpsideMeanReversionStrategy strategy(
            Instant now, MarketCalendarStore calendar) {
        return strategyWithGateway(now, calendar, new RecordingGateway());
    }

    private OneSigmaUpsideMeanReversionStrategy strategyWithGateway(
            Instant now, MarketCalendarStore calendar, RecordingGateway gateway) {
        TestConfig config = new TestConfig(5, 10_000L, Set.of(TICKER));
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(), config);
        TradingGate tradingGate = new TradingGate();
        tradingGate.transitionTo(EngineMode.READY, "test");

        OptionsIndicatorStore optionsStore = new OptionsIndicatorStore(Set.of(TICKER), 5000L);
        optionsStore.accept(IndicatorFrame.newBuilder()
                .setSequence(1).setEmittedAtUnixMs(now.toEpochMilli()).setTicker(TICKER)
                .setTradingDate(MONDAY.toString())
                .setStaticDailyImpliedMove(IMPLIED_MOVE)
                .setStaticDailyImpliedMoveValid(true).build(), now.toEpochMilli());

        MarketDataInputStore inputStore =
                new MarketDataInputStore(30_000L, Clock.fixed(now, ZoneOffset.UTC));
        for (MarketDataInput input : MarketDataInput.values()) {
            inputStore.record(TICKER, input);
        }

        OneSigmaUpsideMeanReversionStrategy built =
                new OneSigmaUpsideMeanReversionStrategy(
                        blackboard, gateway, new NoopTickStreams(), config, tradingGate,
                        inputStore, optionsStore, calendar, Clock.fixed(now, ZoneOffset.UTC));
        primeState();
        return built;
    }

    private void primeState() {
        blackboard.getAccount().setNetLiquidation(100_000.0);
        blackboard.getAccount().setAvailableFunds(50_000.0);

        Stock stock = blackboard.getStock(TICKER);
        stock.setShortMarginRateVerified(true);
        stock.setShortMarginRate(0.30);
        stock.setPreviousClose(PREVIOUS_CLOSE);
        stock.setLastPrice(ENTRY_LEVEL);
        stock.setDailyVWAP(101.0);
    }

    private Stock stock() {
        return blackboard.getStock(TICKER);
    }

    // ----------------------------------------------------------------
    // Entry
    // ----------------------------------------------------------------

    @Test
    void priceAtTheEntryLevelAllowsEntry() {
        OneSigmaUpsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));

        assertTrue(built.isEntryConditionMet(stock()));
        assertEquals(ENTRY_LEVEL, built.calculateEntryPrice(stock()), 1.0e-9);
    }

    @Test
    void priceAboveTheEntryLevelStillAllowsEntry() {
        OneSigmaUpsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));
        stock().setLastPrice(106.0);

        assertTrue(built.isEntryConditionMet(stock()));
    }

    @Test
    void priceBelowTheEntryLevelBlocksEntry() {
        // Mirrored against the long strategy: strength is the trigger here.
        OneSigmaUpsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));
        stock().setLastPrice(103.99);

        assertFalse(built.isEntryConditionMet(stock()));
    }

    @Test
    void aVwapAboveThreeQuartersOfAMoveBlocksEntry() {
        // At a VWAP of 103.00 reward equals the 1.00 risk. Above that the trade
        // is worse than one to one and is skipped.
        OneSigmaUpsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));

        stock().setDailyVWAP(103.0);
        assertTrue(built.isEntryConditionMet(stock()));

        stock().setDailyVWAP(103.01);
        assertFalse(built.isEntryConditionMet(stock()));
    }

    @Test
    void anUnpricedShortMarginRateBlocksEntry() {
        // The margin pacer requests BUY then SELL, so there is a window where
        // the long side is priced and the short side still holds its 1.0
        // default. A single shared flag would let a short size against that
        // default and be silently reduced to cash-equivalent size.
        OneSigmaUpsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));
        stock().setShortMarginRateVerified(false);
        stock().setLongMarginRateVerified(true);

        assertFalse(built.isEntryConditionMet(stock()));
    }

    @Test
    void theDirectionIsShort() {
        assertEquals(TradeDirection.SHORT,
                strategy(MID_MORNING, standardSession(MONDAY)).getTradeDirection());
    }

    // ----------------------------------------------------------------
    // Timing
    // ----------------------------------------------------------------

    @Test
    void entryIsBlockedInsideTheLastHour() {
        assertTrue(strategy(newYork(MONDAY, 14, 59), standardSession(MONDAY))
                .isEntryConditionMet(stock()));
        assertFalse(strategy(newYork(MONDAY, 15, 0), standardSession(MONDAY))
                .isEntryConditionMet(stock()));
    }

    @Test
    void anEarlyCloseShortensTheEntryWindow() {
        assertFalse(strategy(newYork(MONDAY, 12, 30), earlyCloseSession(MONDAY))
                .isEntryConditionMet(stock()));
        assertTrue(strategy(newYork(MONDAY, 11, 30), earlyCloseSession(MONDAY))
                .isEntryConditionMet(stock()));
    }

    @Test
    void anUnknownCloseBlocksEntry() {
        assertFalse(strategy(MID_MORNING, new MarketCalendarStore())
                .isEntryConditionMet(stock()));
    }

    @Test
    void theTimeExitIsTwoHoursAfterSubmissionWhenThatComesFirst() {
        assertEquals(newYork(MONDAY, 12, 0).toEpochMilli(),
                strategy(MID_MORNING, standardSession(MONDAY)).calculateTimeExit());
    }

    @Test
    void theTimeExitFallsBackToTheCloseMarginLateInTheDay() {
        assertEquals(newYork(MONDAY, 15, 45).toEpochMilli(),
                strategy(newYork(MONDAY, 14, 30), standardSession(MONDAY)).calculateTimeExit());
    }

    @Test
    void theTimeExitFollowsAnEarlyCloseForward() {
        assertEquals(newYork(MONDAY, 12, 45).toEpochMilli(),
                strategy(newYork(MONDAY, 11, 30), earlyCloseSession(MONDAY)).calculateTimeExit());
    }

    // ----------------------------------------------------------------
    // Sizing and slices
    // ----------------------------------------------------------------

    @Test
    void oneSliceCarriesTheVwapTargetAndTheStopAboveEntry() {
        OneSigmaUpsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));

        List<BracketOrderExecutor.SliceIntent> intents =
                built.calculateSliceIntents(stock(), ENTRY_LEVEL);

        assertEquals(1, intents.size());
        assertEquals(101.0, intents.get(0).takeProfitPrice, 1.0e-9);
        assertEquals(STOP_LEVEL, intents.get(0).stopLossPrice, 1.0e-9,
                "a short stop sits above the entry");
        // 0.75% of 100,000 is 750, risk per share is 1.00, so 750 shares.
        assertEquals(750.0, intents.get(0).quantity.value().doubleValue(), 1.0e-9);
    }

    @Test
    void noAccountStateProducesNoSlices() {
        OneSigmaUpsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));
        blackboard.getAccount().setNetLiquidation(0.0);

        assertTrue(built.calculateSliceIntents(stock(), ENTRY_LEVEL).isEmpty());
    }

    // ----------------------------------------------------------------
    // Take-profit tracking and the message budget
    // ----------------------------------------------------------------

    @Test
    void theTargetFollowsVwapUpwardWithNoFloor() {
        // Deliberate: once VWAP rises above entry the buy-to-cover limit is
        // marketable and the position exits at a loss. Still an execution.
        RecordingGateway gateway = new RecordingGateway();
        OneSigmaUpsideMeanReversionStrategy built =
                strategyWithGateway(MID_MORNING, standardSession(MONDAY), gateway);
        BracketOrder bracket = openShortPosition();

        stock().setDailyVWAP(106.0);
        built.manageOpenPosition(stock());

        assertEquals(106.0, bracket.getSlices().get(0).getTakeProfitPrice(), 1.0e-9);
        assertEquals(1, gateway.updates);
    }

    @Test
    void aVwapMoveBelowTheThresholdSpendsNoMessage() {
        RecordingGateway gateway = new RecordingGateway();
        OneSigmaUpsideMeanReversionStrategy built =
                strategyWithGateway(MID_MORNING, standardSession(MONDAY), gateway);
        openShortPosition();

        stock().setDailyVWAP(101.04);
        built.manageOpenPosition(stock());

        assertEquals(0, gateway.updates);
    }

    @Test
    void updatesAreThrottledToProtectTheOrderEfficiencyRatio() {
        RecordingGateway gateway = new RecordingGateway();
        OneSigmaUpsideMeanReversionStrategy built =
                strategyWithGateway(MID_MORNING, standardSession(MONDAY), gateway);
        openShortPosition();

        for (int i = 1; i <= 20; i++) {
            stock().setDailyVWAP(101.0 + i);
            built.manageOpenPosition(stock());
        }

        assertEquals(1, gateway.updates, "the 60-second interval blocks the rest");
    }

    @Test
    void closingAPositionStartsTheReentryCooldown() {
        OneSigmaUpsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));
        assertTrue(built.isEntryConditionMet(stock()));

        built.onPositionClosed(stock());

        assertFalse(built.isEntryConditionMet(stock()));
    }

    @Test
    void theCooldownExpires() {
        strategy(MID_MORNING, standardSession(MONDAY)).onPositionClosed(stock());

        OneSigmaUpsideMeanReversionStrategy later =
                strategy(newYork(MONDAY, 10, 16), standardSession(MONDAY));

        assertTrue(later.isEntryConditionMet(stock()));
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /** A short position carries a negative size, which drives the exit action. */
    private BracketOrder openShortPosition() {
        Stock stock = stock();
        BracketOrder bracketOrder = new BracketOrder(
                "trade-1", OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID,
                "DU123456", 1, "SELL", TICKER, 1, Decimal.get(750));
        bracketOrder.setEntryPrice(ENTRY_LEVEL);
        BracketOrder.ExitSlice slice =
                new BracketOrder.ExitSlice(11, 12, 13, Decimal.get(750), "oca-1");
        slice.setTakeProfitPrice(101.0);
        slice.setStopLossPrice(STOP_LEVEL);
        bracketOrder.addSlice(slice);
        stock.setActiveBracket(bracketOrder);
        stock.setPositionSize(Decimal.get(-750));
        stock.getState().set(Stock.PositionState.OPEN);
        blackboard.tryReservePosition(
                TICKER, OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID);
        return bracketOrder;
    }

    private static final class RecordingGateway implements BracketOrderGateway {
        private int updates;

        @Override
        public BracketOrder placeTripleThreat(
                String strategyId, TradeDirection direction, String ticker,
                Decimal totalQuantity, double entryPrice,
                List<BracketOrderExecutor.SliceIntent> sliceIntents) {
            throw new AssertionError("No entry should be submitted in this test");
        }

        @Override
        public void updateTripleThreatExits(
                Stock stock, BracketOrder bracketOrder, BracketOrder.ExitSlice slice,
                double takeProfitPrice, double stopLossPrice, long timeExitValue) {
            updates++;
            slice.setTakeProfitPrice(takeProfitPrice);
            slice.setStopLossPrice(stopLossPrice);
        }
    }

    private static final class NoopTickStreams implements TickStreamController {
        @Override
        public boolean isStreamActive(String ticker) {
            return false;
        }

        @Override
        public void tryRequestStream(String ticker) {
        }

        @Override
        public void cancelStream(String ticker) {
        }
    }
}
