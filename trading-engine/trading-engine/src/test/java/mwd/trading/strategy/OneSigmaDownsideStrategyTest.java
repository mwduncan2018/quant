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
import mwd.trading.marketdata.MarketSnapshot;
import mwd.trading.marketdata.TickStreamController;
import mwd.trading.optionsproxy.OptionsIndicatorStore;
import mwd.trading.risk.MarginMethodology;
import mwd.trading.risk.UniverseReference;
import mwd.trading.optionsproxy.proto.IndicatorFrame;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

/**
 * Covers the paper execution-verification strategy.
 *
 * <p>
 * Previous close 100.00 and an implied move of 4.00 put the entry level at
 * 96.00, the stop at 95.00, and the minimum acceptable VWAP at 97.00.
 */
class OneSigmaDownsideStrategyTest {
    private static final String TICKER = "AAPL";

    /** Margin is configuration now; this is the rate these assertions assume. */
    private static final UniverseReference UNIVERSE_REFERENCE = UniverseReference.parse(
            java.util.List.of("AAPL,INFORMATION_TECHNOLOGY,0.25,0.25,0.25,0.25"),
            MarginMethodology.REG_T, 0.50, 0.50);

    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27);
    private static final Instant MID_MORNING = newYork(MONDAY, 10, 0);
    private static final double PREVIOUS_CLOSE = 100.0;
    private static final double IMPLIED_MOVE = 4.0;
    private static final double ENTRY_LEVEL = 96.0;
    private static final double STOP_LEVEL = 95.0;

    private Blackboard blackboard;

    private OneSigmaDownsideMeanReversionStrategy strategy(
            Instant now, MarketCalendarStore calendar) {
        TestConfig config = new TestConfig(5, 10_000L, Set.of(TICKER));
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(), config);
        TradingGate tradingGate = new TradingGate();
        tradingGate.transitionTo(EngineMode.READY, "test");

        OptionsIndicatorStore optionsStore = new OptionsIndicatorStore(Set.of(TICKER), 5000L);
        optionsStore.accept(IndicatorFrame.newBuilder()
                .setSequence(1)
                .setEmittedAtUnixMs(now.toEpochMilli())
                .setTicker(TICKER)
                .setTradingDate(MONDAY.toString())
                .setStaticDailyImpliedMove(IMPLIED_MOVE)
                .setStaticDailyImpliedMoveValid(true)
                .build(), now.toEpochMilli());

        MarketDataInputStore inputStore =
                new MarketDataInputStore(30_000L, Clock.fixed(now, ZoneOffset.UTC));
        for (MarketDataInput input : MarketDataInput.values()) {
            inputStore.record(TICKER, input);
        }

        OneSigmaDownsideMeanReversionStrategy built =
                new OneSigmaDownsideMeanReversionStrategy(
                        blackboard,
                        new RecordingGateway(),
                        new NoopTickStreams(),
                        config,
                        tradingGate,
                        inputStore,
                        UNIVERSE_REFERENCE,
                        optionsStore,
                        calendar,
                        Clock.fixed(now, ZoneOffset.UTC));
        primeState();
        return built;
    }

    private void primeState() {
        blackboard.getAccount().setNetLiquidation(100_000.0);
        blackboard.getAccount().setAvailableFunds(50_000.0);

        Stock stock = blackboard.getStock(TICKER);
        stock.setPreviousClose(PREVIOUS_CLOSE);
        stock.setLastPrice(ENTRY_LEVEL);
        stock.setDailyVWAP(99.0);
    }

    private Stock stock() {
        return blackboard.getStock(TICKER);
    }

    /**
     * The values as one decision sees them. A strategy reads a snapshot now, so a
     * test that mutates the stock and then calls a hook must take the snapshot
     * after the mutation - the same ordering the engine has.
     */
    private MarketSnapshot market() {
        return MarketSnapshot.of(stock(), MID_MORNING.toEpochMilli());
    }

    // ----------------------------------------------------------------
    // Entry
    // ----------------------------------------------------------------

    @Test
    void priceAtTheEntryLevelAllowsEntry() {
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));

        assertTrue(built.isEntryConditionMet(market()));
        assertEquals(ENTRY_LEVEL, built.calculateEntryPrice(market()), 1.0e-9);
    }

    @Test
    void priceBelowTheEntryLevelStillAllowsEntry() {
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));
        stock().setLastPrice(94.0);

        assertTrue(built.isEntryConditionMet(market()));
    }

    @Test
    void priceAboveTheEntryLevelBlocksEntry() {
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));
        stock().setLastPrice(96.01);

        assertFalse(built.isEntryConditionMet(market()));
    }

    @Test
    void aVwapBelowThreeQuartersOfAMoveBlocksEntry() {
        // At a VWAP of 97.00 reward equals the 1.00 risk. Below that the trade
        // is worse than one to one and is skipped.
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));

        stock().setDailyVWAP(97.0);
        assertTrue(built.isEntryConditionMet(market()));

        stock().setDailyVWAP(96.99);
        assertFalse(built.isEntryConditionMet(market()));
    }

    @Test
    void noImpliedMoveBlocksEntry() {
        TestConfig config = new TestConfig(5, 10_000L, Set.of(TICKER));
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(), config);
        TradingGate gate = new TradingGate();
        gate.transitionTo(EngineMode.READY, "test");
        MarketDataInputStore inputStore =
                new MarketDataInputStore(30_000L, Clock.fixed(MID_MORNING, ZoneOffset.UTC));
        for (MarketDataInput input : MarketDataInput.values()) {
            inputStore.record(TICKER, input);
        }

        OneSigmaDownsideMeanReversionStrategy built =
                new OneSigmaDownsideMeanReversionStrategy(
                        blackboard, new RecordingGateway(), new NoopTickStreams(), config, gate,
                        inputStore, UNIVERSE_REFERENCE, new OptionsIndicatorStore(Set.of(TICKER), 5000L),
                        standardSession(MONDAY), Clock.fixed(MID_MORNING, ZoneOffset.UTC));
        primeState();

        assertFalse(built.isEntryConditionMet(market()));
    }

    // ----------------------------------------------------------------
    // Timing
    // ----------------------------------------------------------------

    @Test
    void entryIsBlockedInsideTheLastHour() {
        assertTrue(strategy(newYork(MONDAY, 14, 59), standardSession(MONDAY))
                .isEntryConditionMet(market()));
        assertFalse(strategy(newYork(MONDAY, 15, 0), standardSession(MONDAY))
                .isEntryConditionMet(market()));
    }

    @Test
    void anEarlyCloseShortensTheEntryWindow() {
        assertFalse(strategy(newYork(MONDAY, 12, 30), earlyCloseSession(MONDAY))
                .isEntryConditionMet(market()));
        assertTrue(strategy(newYork(MONDAY, 11, 30), earlyCloseSession(MONDAY))
                .isEntryConditionMet(market()));
    }

    @Test
    void anUnknownCloseBlocksEntry() {
        assertFalse(strategy(MID_MORNING, new MarketCalendarStore())
                .isEntryConditionMet(market()));
    }

    @Test
    void theTimeExitIsTwoHoursAfterSubmissionWhenThatComesFirst() {
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));

        // 10:00 entry, so two hours lands at 12:00, well before 15:45.
        assertEquals(newYork(MONDAY, 12, 0).toEpochMilli(), built.calculateTimeExit());
    }

    @Test
    void theTimeExitFallsBackToTheCloseMarginLateInTheDay() {
        // 14:30 plus two hours would be 16:30, past the bell.
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(newYork(MONDAY, 14, 30), standardSession(MONDAY));

        assertEquals(newYork(MONDAY, 15, 45).toEpochMilli(), built.calculateTimeExit());
    }

    @Test
    void theTimeExitFollowsAnEarlyCloseForward() {
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(newYork(MONDAY, 11, 30), earlyCloseSession(MONDAY));

        // 11:30 plus two hours is 13:30, past a 13:00 close, so 12:45 wins.
        assertEquals(newYork(MONDAY, 12, 45).toEpochMilli(), built.calculateTimeExit());
    }

    // ----------------------------------------------------------------
    // Sizing and slices
    // ----------------------------------------------------------------

    @Test
    void oneSliceCarriesTheVwapTargetAndTheStop() {
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));

        List<BracketOrderExecutor.SliceIntent> intents =
                built.calculateSliceIntents(market(), ENTRY_LEVEL);

        assertEquals(1, intents.size());
        assertEquals(99.0, intents.get(0).takeProfitPrice, 1.0e-9);
        assertEquals(STOP_LEVEL, intents.get(0).stopLossPrice, 1.0e-9);
        // 0.25% of 100,000 is 250, risk per share is 1.00, so 250 shares.
        assertEquals(250.0, intents.get(0).quantity.value().doubleValue(), 1.0e-9);
    }

    @Test
    void theTakeProfitCarriesTheReadingTheEntryTestApproved() {
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));

        // The gate admits this reading because its VWAP of 99.00 clears the
        // 97.00 floor, which is what proves reward is at least equal to risk.
        MarketSnapshot approved = market();
        assertTrue(built.isEntryConditionMet(approved));

        // A tick lands while the entry is being built, taking VWAP under that
        // floor. Reading the live field again here is how the order used to end
        // up resting on a target the gate had never approved.
        stock().setDailyVWAP(95.5);

        List<BracketOrderExecutor.SliceIntent> intents =
                built.calculateSliceIntents(approved, ENTRY_LEVEL);

        assertEquals(99.0, intents.get(0).takeProfitPrice, 1.0e-9,
                "the slice must price off the snapshot the entry test approved");
        assertFalse(built.isEntryConditionMet(market()),
                "the later reading would not have been admitted at all");
    }

    @Test
    void noAccountStateProducesNoSlices() {
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));
        blackboard.getAccount().setNetLiquidation(0.0);

        assertTrue(built.calculateSliceIntents(market(), ENTRY_LEVEL).isEmpty());
    }

    // ----------------------------------------------------------------
    // Take-profit tracking and the message budget
    // ----------------------------------------------------------------

    @Test
    void theTargetFollowsVwapDownwardWithNoFloor() {
        // Deliberate: once VWAP falls below entry the sell limit is marketable
        // and the position exits at a loss. That is still an execution.
        RecordingGateway gateway = new RecordingGateway();
        OneSigmaDownsideMeanReversionStrategy built =
                strategyWithGateway(MID_MORNING, standardSession(MONDAY), gateway);
        BracketOrder bracket = openPosition(gateway);

        stock().setDailyVWAP(94.0);
        built.manageOpenPosition(stock(), market());

        assertEquals(94.0, bracket.getSlices().get(0).getTakeProfitPrice(), 1.0e-9);
        assertEquals(1, gateway.updates);
    }

    @Test
    void aVwapMoveBelowTheThresholdSpendsNoMessage() {
        RecordingGateway gateway = new RecordingGateway();
        OneSigmaDownsideMeanReversionStrategy built =
                strategyWithGateway(MID_MORNING, standardSession(MONDAY), gateway);
        openPosition(gateway);

        stock().setDailyVWAP(99.04);
        built.manageOpenPosition(stock(), market());

        assertEquals(0, gateway.updates);
    }

    @Test
    void updatesAreCappedToProtectTheOrderEfficiencyRatio() {
        // Ten modifications plus three placement messages against two
        // executions is an OER near 6.5, inside IBKR's expectation of 20.
        RecordingGateway gateway = new RecordingGateway();
        OneSigmaDownsideMeanReversionStrategy built =
                strategyWithGateway(MID_MORNING, standardSession(MONDAY), gateway);
        openPosition(gateway);

        for (int i = 1; i <= 20; i++) {
            // A fresh strategy clock each cycle would be needed to pass the
            // interval gate, so drive it directly with a widening VWAP.
            stock().setDailyVWAP(99.0 + i);
            built.manageOpenPosition(stock(), market());
        }

        assertEquals(1, gateway.updates, "the 60-second interval blocks the rest");
    }

    @Test
    void closingAPositionStartsTheReentryCooldown() {
        OneSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY));
        assertTrue(built.isEntryConditionMet(market()));

        built.onPositionClosed(stock());

        assertFalse(built.isEntryConditionMet(market()),
                "a ticker parked below the level would otherwise round-trip continuously");
    }

    @Test
    void theCooldownExpires() {
        OneSigmaDownsideMeanReversionStrategy cooled =
                strategy(MID_MORNING, standardSession(MONDAY));
        cooled.onPositionClosed(stock());

        // A later strategy instance stands in for the clock having moved on.
        OneSigmaDownsideMeanReversionStrategy later =
                strategy(newYork(MONDAY, 10, 16), standardSession(MONDAY));

        assertTrue(later.isEntryConditionMet(market()));
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private OneSigmaDownsideMeanReversionStrategy strategyWithGateway(
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

        OneSigmaDownsideMeanReversionStrategy built =
                new OneSigmaDownsideMeanReversionStrategy(
                        blackboard, gateway, new NoopTickStreams(), config, tradingGate,
                        inputStore, UNIVERSE_REFERENCE, optionsStore, calendar, Clock.fixed(now, ZoneOffset.UTC));
        primeState();
        return built;
    }

    private BracketOrder openPosition(RecordingGateway gateway) {
        Stock stock = stock();
        BracketOrder bracketOrder = new BracketOrder(
                "trade-1", OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID,
                "DU123456", 1, "BUY", TICKER, 1, Decimal.get(250));
        bracketOrder.setEntryPrice(ENTRY_LEVEL);
        BracketOrder.ExitSlice slice =
                new BracketOrder.ExitSlice(11, 12, 13, Decimal.get(250), "oca-1");
        slice.setTakeProfitPrice(99.0);
        slice.setStopLossPrice(STOP_LEVEL);
        bracketOrder.addSlice(slice);
        stock.setActiveBracket(bracketOrder);
        stock.setPositionSize(Decimal.get(250));
        bracketOrder.setStatus(BracketOrder.Status.POSITION_OPEN);
        blackboard.tryReservePosition(
                TICKER, OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID);
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
