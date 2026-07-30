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
 * Covers the per-input market-data gate. Every options-proxy and capitulation
 * condition below is satisfied, so each assertion isolates one IBKR input.
 */
class MarketDataReadinessGatingTest {
    private static final String TICKER = "AAPL";
    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 27);
    private static final Instant NOW = Instant.parse("2026-07-27T14:00:00Z");
    private static final long MARKET_DATA_MAX_AGE_MS = 30_000L;
    private static final double IMPLIED_MOVE = 2.0;
    private static final double GAMMA_FLIP = 601.25;

    private Blackboard blackboard;
    private MarketDataInputStore inputStore;
    private TwoSigmaDownsideMeanReversionStrategy strategy;

    @BeforeEach
    void setUp() {
        TestConfig config = new TestConfig(5, 10_000L, Set.of(TICKER));
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(), config);
        TradingGate tradingGate = new TradingGate();
        tradingGate.transitionTo(EngineMode.READY, "test");

        OptionsIndicatorStore optionsStore = new OptionsIndicatorStore(Set.of(TICKER, "SPY"), 5000L);
        optionsStore.accept(IndicatorFrame.newBuilder()
                .setSequence(1)
                .setEmittedAtUnixMs(NOW.toEpochMilli())
                .setTicker(TICKER)
                .setTradingDate(TRADING_DATE.toString())
                .setStaticDailyImpliedMove(IMPLIED_MOVE)
                .setStaticDailyImpliedMoveValid(true)
                .setSpyGammaFlip(GAMMA_FLIP)
                .setSpyGammaFlipValid(true)
                .build(), NOW.toEpochMilli());

        inputStore = new MarketDataInputStore(
                MARKET_DATA_MAX_AGE_MS, Clock.fixed(NOW, ZoneOffset.UTC));
        strategy = new TwoSigmaDownsideMeanReversionStrategy(
                blackboard,
                new NoopGateway(),
                new NoopTickStreams(),
                config,
                tradingGate,
                inputStore,
                optionsStore,
                // Far-off earnings and a standard session, so each assertion
                // isolates the market-data condition under test.
                earningsOn(TICKER, TRADING_DATE, LocalDate.of(2026, 8, 20)),
                standardSession(TRADING_DATE),
                Clock.fixed(NOW, ZoneOffset.UTC));
        primeCapitulationState();
    }

    /** Every non-market-data entry lock is satisfied by this state. */
    private void primeCapitulationState() {
        Stock stock = blackboard.getStock(TICKER);
        stock.setLongMarginRateVerified(true);
        stock.setPreviousClose(100.0);
        stock.setLastPrice(96.0);
        stock.setDailyVWAP(101.0);
        stock.setLastMinuteVolume(Decimal.get(5000));
        stock.setAverageLast15MinuteVolume(Decimal.get(1000));
        stock.setLastMinuteBar(new Bar(
                "20260727  10:00:00", 96.8, 97.0, 95.5, 96.5,
                Decimal.get(5000), 42, Decimal.get(96)));
        blackboard.getStock("SPY").setLastPrice(610.0);
    }

    /** Records every input the strategy declares, plus SPY's last price. */
    private void recordAllInputs() {
        for (MarketDataInput input : MarketDataInput.values()) {
            inputStore.record(TICKER, input);
        }
        inputStore.record("SPY", MarketDataInput.LAST_PRICE);
    }

    private Stock stock() {
        return blackboard.getStock(TICKER);
    }

    @Test
    void everyRecordedInputAllowsEntry() {
        recordAllInputs();

        assertTrue(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void aPopulatedStockFieldIsNotEnoughWithoutARecordedSpyPrice() {
        // SPY's price is set on the Blackboard and satisfies the gamma-flip test,
        // but nothing recorded that IBKR delivered it. The old rule would have
        // accepted this the moment any SPY tick of any kind arrived.
        recordAllInputs();
        inputStore.markAllStale();
        for (MarketDataInput input : MarketDataInput.values()) {
            inputStore.record(TICKER, input);
        }

        assertFalse(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void aRecordedBidSizeCannotStandInForTheInputsTheStrategyReads() {
        // The whole point of per-input tracking: LAST_PRICE arriving says nothing
        // about VWAP, minute bars, or the volume baseline.
        inputStore.record(TICKER, MarketDataInput.LAST_PRICE);
        inputStore.record("SPY", MarketDataInput.LAST_PRICE);

        assertFalse(inputStore.areAllFresh(TICKER, Set.of(
                MarketDataInput.LAST_PRICE,
                MarketDataInput.PREVIOUS_CLOSE,
                MarketDataInput.DAILY_VWAP,
                MarketDataInput.MINUTE_BAR,
                MarketDataInput.MINUTE_VOLUME_BASELINE)));
    }

    @Test
    void aResubscribeRevokesReadinessUntilEachInputArrivesAgain() {
        recordAllInputs();
        assertTrue(strategy.isEntryConditionMet(stock()));

        inputStore.markAllStale();

        assertFalse(strategy.isEntryConditionMet(stock()));
    }

    @Test
    void managementNeedsFewerInputsThanEntry() {
        // A position must never be stranded by an input it does not use. The
        // volume baseline is unavailable for the first quarter hour of a session.
        inputStore.record(TICKER, MarketDataInput.LAST_PRICE);
        inputStore.record(TICKER, MarketDataInput.PREVIOUS_CLOSE);
        inputStore.record(TICKER, MarketDataInput.DAILY_VWAP);

        assertTrue(inputStore.areAllFresh(TICKER, Set.of(
                MarketDataInput.LAST_PRICE,
                MarketDataInput.PREVIOUS_CLOSE,
                MarketDataInput.DAILY_VWAP)));
        assertFalse(inputStore.isFresh(TICKER, MarketDataInput.MINUTE_VOLUME_BASELINE));
    }

    @Test
    void aStaleVwapNoLongerTriggersASpuriousRipcordLiquidation() {
        // manageOpenPosition computes reward as VWAP minus last price. A VWAP left
        // at its default reads as a hugely negative reward, which satisfies the
        // ripcord and dumps the position 1.5% below market. Management must be
        // blocked instead, leaving the resting broker exits in force.
        Stock stock = stock();
        stock.setDailyVWAP(0.0);
        inputStore.record(TICKER, MarketDataInput.LAST_PRICE);
        inputStore.record(TICKER, MarketDataInput.PREVIOUS_CLOSE);

        BracketOrder bracketOrder = openBracket(stock);
        double stopBefore = bracketOrder.getSlices().get(0).getStopLossPrice();

        strategy.runOneCycle();

        for (BracketOrder.ExitSlice slice : bracketOrder.getSlices()) {
            assertEquals(stopBefore, slice.getStopLossPrice(), 1.0e-9,
                    "a position must not be liquidated on an unusable VWAP");
        }
    }

    @Test
    void theSameStateWithARecordedVwapDoesFireTheRipcord() {
        // The control for the test above: with VWAP recorded, the identical state
        // reaches the ripcord and rewrites both exits to the deep-sweep price. That
        // is what the missing input was suppressing.
        Stock stock = stock();
        stock.setDailyVWAP(0.0);
        recordAllInputs();

        BracketOrder bracketOrder = openBracket(stock);
        double deepSweepPrice = Math.round(stock.getLastPrice() * 0.985 * 100.0) / 100.0;

        strategy.runOneCycle();

        for (BracketOrder.ExitSlice slice : bracketOrder.getSlices()) {
            assertEquals(deepSweepPrice, slice.getStopLossPrice(), 1.0e-9);
            assertEquals(deepSweepPrice, slice.getTakeProfitPrice(), 1.0e-9);
        }
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
            slice.setTakeProfitPrice(98.0);
        }
        bracketOrder.setStatus(BracketOrder.Status.POSITION_OPEN);
        stock.setActiveBracket(bracketOrder);
        blackboard.tryReservePosition(TICKER, TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID);
        return bracketOrder;
    }

    private static final class NoopGateway implements BracketOrderGateway {
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
