package mwd.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ib.client.Bar;
import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;
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

import static mwd.trading.support.ProxyReferenceFixtures.earningsOn;
import static mwd.trading.support.ProxyReferenceFixtures.standardSession;

/**
 * Covers the options-proxy readiness gate. The market state below satisfies
 * every pre-existing capitulation lock, so each assertion isolates the proxy
 * condition under test.
 */
class TwoSigmaDownsideOptionsProxyGatingTest {
    private static final String TICKER = "AAPL";
    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 27);
    private static final LocalDate PREVIOUS_TRADING_DATE = LocalDate.of(2026, 7, 24);
    // 10:00 in New York on the trading date, comfortably inside the entry window.
    private static final Instant NOW = Instant.parse("2026-07-27T14:00:00Z");
    private static final long NOW_MS = NOW.toEpochMilli();
    private static final long MAX_AGE_MS = 5000L;
    private static final long MARKET_DATA_MAX_AGE_MS = 30_000L;
    private static final double IMPLIED_MOVE = 2.0;
    private static final double GAMMA_FLIP = 601.25;

    private Blackboard blackboard;
    private OptionsIndicatorStore store;
    private TwoSigmaDownsideMeanReversionStrategy strategy;

    @BeforeEach
    void setUp() {
        TestConfig config = new TestConfig(5, 10_000L, Set.of(TICKER));
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(), config);
        TradingGate tradingGate = new TradingGate();
        tradingGate.transitionTo(EngineMode.READY, "test");
        store = new OptionsIndicatorStore(Set.of(TICKER, "SPY"), MAX_AGE_MS);
        strategy = new TwoSigmaDownsideMeanReversionStrategy(
                blackboard,
                new NoopGateway(),
                new NoopTickStreams(),
                config,
                tradingGate,
                marketDataReadyAt(NOW),
                store,
                // Far-off earnings and a standard session, so each assertion
                // isolates the options-proxy condition under test.
                earningsOn(TICKER, TRADING_DATE, LocalDate.of(2026, 8, 20)),
                standardSession(TRADING_DATE),
                Clock.fixed(NOW, ZoneOffset.UTC));
        primeCapitulationState();
    }

    /** Every non-proxy entry lock is satisfied by this state. */
    private void primeCapitulationState() {
        Stock stock = blackboard.getStock(TICKER);
        stock.setLongMarginRateVerified(true);
        stock.setPreviousClose(100.0);
        stock.setLastPrice(96.0);
        stock.setDailyVWAP(101.0);
        stock.setLastMinuteVolume(Decimal.get(5000));
        stock.setAverageLast15MinuteVolume(Decimal.get(1000));
        stock.setLastMinuteBar(new Bar(
                "20260727 10:00:00", 96.8, 97.0, 95.5, 96.5,
                Decimal.get(5000), 42, Decimal.get(96)));

        Stock marketIndex = blackboard.getStock("SPY");
        marketIndex.setLastPrice(610.0);
    }

    private static IndicatorFrame.Builder frame(long sequence) {
        return IndicatorFrame.newBuilder()
                .setSequence(sequence)
                .setEmittedAtUnixMs(NOW_MS)
                .setTicker(TICKER)
                .setTradingDate(TRADING_DATE.toString())
                .setStaticDailyImpliedMove(IMPLIED_MOVE)
                .setStaticDailyImpliedMoveValid(true)
                .setSpyGammaFlip(GAMMA_FLIP)
                .setSpyGammaFlipValid(true);
    }

    private Stock stock() {
        return blackboard.getStock(TICKER);
    }

    @Test
    void aFullyReadyProxyAllowsTheEntry() {
        assertTrue(store.accept(frame(1).build(), NOW_MS));

        assertTrue(strategy.isEntryConditionMet(stock()));
        assertEquals(96.0, strategy.calculateEntryPrice(stock()), 1.0e-9);
    }

    @Test
    void noProxyDataAtAllBlocksTheEntry() {
        assertFalse(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void anInvalidImpliedMoveBlocksTheEntry() {
        assertTrue(store.accept(frame(1)
                .setStaticDailyImpliedMove(0.0)
                .setStaticDailyImpliedMoveValid(false)
                .build(), NOW_MS));

        assertFalse(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void anInvalidGammaFlipBlocksTheEntry() {
        assertTrue(store.accept(frame(1)
                .setSpyGammaFlip(0.0)
                .setSpyGammaFlipValid(false)
                .build(), NOW_MS));

        assertFalse(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void yesterdaysIndicatorsBlockTheEntry() {
        assertTrue(store.accept(
                frame(1).setTradingDate(PREVIOUS_TRADING_DATE.toString()).build(), NOW_MS));

        assertFalse(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void aStaleFrameBlocksTheEntry() {
        long tooOld = NOW_MS - MAX_AGE_MS - 1;
        assertTrue(store.accept(frame(1).setEmittedAtUnixMs(tooOld).build(), tooOld));

        assertFalse(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void gammaFlipArrivingAfterTheOpenEnablesEntriesWithoutARestart() {
        assertTrue(store.accept(frame(1)
                .setSpyGammaFlip(0.0)
                .setSpyGammaFlipValid(false)
                .build(), NOW_MS));
        assertFalse(strategy.isEntryConditionMet(stock()));

        // The proxy hot-reloads the manual JSON file, so the next frame is valid.
        assertTrue(store.accept(frame(2).build(), NOW_MS));

        assertTrue(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void spyTradingBelowTheProxyGammaFlipBlocksTheEntry() {
        assertTrue(store.accept(frame(1).build(), NOW_MS));
        blackboard.getStock("SPY").setLastPrice(GAMMA_FLIP - 0.01);

        assertFalse(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void aStaleStockFieldCannotSubstituteForTheProxy() {
        // The Swing monitor's copy on Stock must never satisfy the gate.
        stock().setDailyImpliedMove(IMPLIED_MOVE);
        blackboard.getStock("SPY").setGammaFlip(GAMMA_FLIP);

        assertFalse(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void sliceIntentsAreEmptyWithoutARetainedMove() {
        assertTrue(strategy.calculateSliceIntents(stock(), 96.0).isEmpty());
        assertTrue(Double.isNaN(strategy.calculateEntryPrice(stock())));
        assertFalse(TradeDirection.LONG.acceptsEntryPrice(
                96.0, strategy.calculateEntryPrice(stock())));
    }

    @Test
    void aSilentProxyBlocksEntriesWhileAnOpenPositionIsStillManaged() {
        assertTrue(store.accept(frame(1).build(), NOW_MS));
        assertTrue(strategy.isEntryConditionMet(stock()));

        // The proxy goes quiet: the retained move outlives the freshness window.
        TwoSigmaDownsideMeanReversionStrategy laterStrategy = strategyAt(
                NOW.plusMillis(MAX_AGE_MS + 1));
        assertFalse(laterStrategy.isEntryConditionMet(stock()));

        Stock stock = stock();
        stock.getState().set(Stock.PositionState.OPEN);
        BracketOrder bracketOrder = openBracket(stock);
        assertEquals(95.0, bracketOrder.getSlices().get(0).getStopLossPrice(), 1.0e-9);

        // Price has run past entry + (move * 0.5), so the break-even trigger must
        // still fire off the retained Static Daily Implied Move.
        stock.setLastPrice(97.5);
        laterStrategy.manageOpenPosition(stock);

        assertEquals(96.02, bracketOrder.getSlices().get(0).getStopLossPrice(), 1.0e-9);
    }

    @Test
    void theRipcordStillFiresWhenNoProxyDataWasEverReceived() {
        Stock stock = stock();
        stock.getState().set(Stock.PositionState.OPEN);
        BracketOrder bracketOrder = openBracket(stock);

        // Reward has collapsed below 1.2x risk, and nothing was ever received
        // from the proxy. Liquidation must not depend on options data.
        stock.setLastPrice(97.5);
        stock.setDailyVWAP(99.0);
        assertTrue(store.lastKnownImpliedMove(TICKER).isEmpty());

        strategy.manageOpenPosition(stock);

        double deepSweepPrice = Math.round(97.5 * 0.985 * 100.0) / 100.0;
        for (BracketOrder.ExitSlice slice : bracketOrder.getSlices()) {
            assertEquals(deepSweepPrice, slice.getStopLossPrice(), 1.0e-9);
            assertEquals(deepSweepPrice, slice.getTakeProfitPrice(), 1.0e-9);
        }
    }

    private TwoSigmaDownsideMeanReversionStrategy strategyAt(Instant instant) {
        TestConfig config = new TestConfig(5, 10_000L, Set.of(TICKER));
        TradingGate tradingGate = new TradingGate();
        tradingGate.transitionTo(EngineMode.READY, "test");
        return new TwoSigmaDownsideMeanReversionStrategy(
                blackboard,
                new NoopGateway(),
                new NoopTickStreams(),
                config,
                tradingGate,
                marketDataReadyAt(instant),
                store,
                // Far-off earnings and a standard session, so each assertion
                // isolates the options-proxy condition under test.
                earningsOn(TICKER, TRADING_DATE, LocalDate.of(2026, 8, 20)),
                standardSession(TRADING_DATE),
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    /**
     * A real input store with every required input recorded at {@code instant}, so
     * IBKR readiness is satisfied and each assertion isolates the proxy condition
     * under test. Market-data readiness itself is covered by
     * {@link MarketDataReadinessGatingTest}.
     */
    private static MarketDataInputStore marketDataReadyAt(Instant instant) {
        MarketDataInputStore inputStore = new MarketDataInputStore(
                MARKET_DATA_MAX_AGE_MS, Clock.fixed(instant, ZoneOffset.UTC));
        for (MarketDataInput input : MarketDataInput.values()) {
            inputStore.record(TICKER, input);
        }
        inputStore.record("SPY", MarketDataInput.LAST_PRICE);
        return inputStore;
    }

    private BracketOrder openBracket(Stock stock) {
        BracketOrder bracketOrder = new BracketOrder(
                "trade-1", TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID,
                "DU123456", 1, "BUY", TICKER, 1, Decimal.get(200));
        bracketOrder.setEntryPrice(96.0);
        bracketOrder.addSlice(new BracketOrder.ExitSlice(
                11, 12, 13, Decimal.get(100), "oca-1"));
        bracketOrder.addSlice(new BracketOrder.ExitSlice(
                21, 22, 23, Decimal.get(100), "oca-2"));
        for (BracketOrder.ExitSlice slice : bracketOrder.getSlices()) {
            slice.setStopLossPrice(95.0);
            slice.setTakeProfitPrice(99.0);
        }
        stock.setActiveBracket(bracketOrder);
        assertTrue(blackboard.tryReservePosition(
                TICKER, TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID));
        return bracketOrder;
    }

    private static final class NoopGateway implements BracketOrderGateway {
        @Override
        public BracketOrder placeTripleThreat(
                String strategyName,
                TradeDirection tradeDirection,
                String tickerSymbol,
                Decimal totalOrderQuantity,
                double entryLimitPrice,
                List<BracketOrderExecutor.SliceIntent> sliceIntents) {
            throw new UnsupportedOperationException("no entry is submitted by this test");
        }

        @Override
        public void updateTripleThreatExits(
                Stock stock,
                BracketOrder bracketOrder,
                BracketOrder.ExitSlice exitSlice,
                double newTakeProfitPrice,
                double newStopLossPrice,
                long newExitTime) {
            exitSlice.setTakeProfitPrice(newTakeProfitPrice);
            exitSlice.setStopLossPrice(newStopLossPrice);
            exitSlice.setTimeExit(newExitTime);
        }
    }

    private static final class NoopTickStreams implements TickStreamController {
        private final Set<String> active = ConcurrentHashMap.newKeySet();

        @Override public boolean isStreamActive(String ticker) { return active.contains(ticker); }
        @Override public void tryRequestStream(String ticker) { active.add(ticker); }
        @Override public void cancelStream(String ticker) { active.remove(ticker); }
    }
}
