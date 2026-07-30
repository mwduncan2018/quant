package mwd.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

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
import mwd.trading.marketdata.MarketSnapshot;
import mwd.trading.marketdata.TickStreamController;
import mwd.trading.risk.ConcentrationLimits;
import mwd.trading.risk.MarginMethodology;
import mwd.trading.risk.UniverseReference;
import mwd.trading.state.Blackboard;
import mwd.trading.support.ReadyMarketData;
import mwd.trading.support.TestConfig;

/**
 * What happens to a strategy's own per-ticker bookkeeping when the IBKR reader
 * thread finishes a lifecycle first.
 *
 * <p>
 * {@code OrderLifecycleHandler.completeConfirmedFlat} clears the bracket and
 * releases the ticker in one call on the reader thread. The owning strategy's
 * next poll therefore reads FLAT with no owner and goes to
 * {@code evaluateNewEntry}, never to {@code handleFlatWithLocalOwnership} - so
 * {@code cleanupOwnedLifecycle} does not run and neither does anything it does.
 */
class ReaderCompletedLifecycleTest {
    private static final UniverseReference REFERENCE = UniverseReference.parse(
            List.of("AAPL,INFORMATION_TECHNOLOGY,0.25,0.25,0.25,0.25"),
            MarginMethodology.REG_T, 0.50, 0.50);

    @Test
    void aSecondEntryOnTheSameTickerStillReleasesTheEngineWideLock() {
        Fixture fixture = new Fixture(Set.of("AAPL"));
        ProbeStrategy strategy = fixture.strategy();
        strategy.entryTickers.add("AAPL");
        // A long only accepts a limit at or above the market.
        fixture.blackboard.getStock("AAPL").setLastPrice(99.0);

        // --- trade one, through acknowledgement -------------------------------
        strategy.runOneCycle();
        BracketOrder first = fixture.blackboard.getStock("AAPL").getActiveBracket();
        assertEquals("AAPL", fixture.blackboard.getGlobalPendingOwner().ticker());

        first.setStatus(BracketOrder.Status.WORKING_PARENT);
        strategy.runOneCycle();
        assertNull(fixture.blackboard.getGlobalPendingOwner(),
                "acknowledgement should release the engine-wide lock");

        // --- IBKR cancels it with no fill, on the reader thread ---------------
        first.setStatus(BracketOrder.Status.CANCELLED);
        first.setFilledQuantity(Decimal.ZERO);
        completeConfirmedFlat(fixture.blackboard, first);

        // The strategy now sees FLAT with no owner, so it re-enters rather than
        // cleaning up. This is the ordinary path, not a contrived one.
        fixture.refreshAccount();
        strategy.runOneCycle();
        BracketOrder second = fixture.blackboard.getStock("AAPL").getActiveBracket();
        assertTrue(second != first, "a second entry should have been submitted");
        assertEquals("AAPL", fixture.blackboard.getGlobalPendingOwner().ticker());

        // --- IBKR acknowledges the second order before the next poll ----------
        second.setStatus(BracketOrder.Status.WORKING_PARENT);
        strategy.runOneCycle();

        assertNull(fixture.blackboard.getGlobalPendingOwner(),
                "the second acknowledgement must release the engine-wide lock too; "
                        + "a resting limit otherwise parks every strategy in the engine");
    }

    @Test
    void aTradeCompletedByTheReaderThreadStillNotifiesTheStrategy() {
        Fixture fixture = new Fixture(Set.of("AAPL"));
        ProbeStrategy strategy = fixture.strategy();

        assertTrue(fixture.blackboard.tryReservePosition("AAPL", ProbeStrategy.ID));
        BracketOrder bracket = new BracketOrder(
                "trade-1", ProbeStrategy.ID, "DU123456", 1, "BUY", "AAPL", 1, Decimal.get(10));
        bracket.setEntryPrice(100.0);
        bracket.setStatus(BracketOrder.Status.POSITION_OPEN);
        fixture.blackboard.getStock("AAPL").setActiveBracket(bracket);
        fixture.blackboard.getStock("AAPL").setLastPrice(100.0);
        fixture.tickStreams.tryRequestStream("AAPL");

        // The strategy manages the open position at least once, which is what
        // leaves it a record that this ticker is its trade. Skipping this would
        // model a position the strategy never took, and cleanup would rightly
        // not fire for one of those.
        strategy.runOneCycle();

        // Every exit slice fills: the reader thread marks the bracket FILLED and
        // completes the flat itself, clearing the bracket and releasing the
        // ticker before the strategy's next poll.
        bracket.setStatus(BracketOrder.Status.FILLED);
        completeConfirmedFlat(fixture.blackboard, bracket);

        strategy.runOneCycle();

        assertEquals(1, strategy.positionClosedCount.get(),
                "onPositionClosed is the hook the re-entry cooldown and the take-profit "
                        + "budget reset hang off; a completed trade has to reach it");
        assertTrue(!fixture.tickStreams.active.contains("AAPL"),
                "the tick stream should have been cancelled when the trade finished");
    }

    /** Exactly what OrderLifecycleHandler.completeConfirmedFlat does. */
    private static void completeConfirmedFlat(Blackboard blackboard, BracketOrder bracketOrder) {
        Stock stock = blackboard.getStock(bracketOrder.getTicker());
        if (stock.getActiveBracket() == bracketOrder) {
            stock.setActiveBracket(null);
        }
        blackboard.releaseGlobalPending(
                bracketOrder.getStrategyName(), bracketOrder.getTicker());
        blackboard.releasePosition(bracketOrder.getTicker(), bracketOrder.getStrategyName());
    }

    private static final class Fixture {
        private final Blackboard blackboard;
        private final TestConfig config;
        private final TradingGate tradingGate = new TradingGate();
        private final MutableClock clock = new MutableClock();
        private final FakeGateway gateway;
        private final FakeTickStreams tickStreams = new FakeTickStreams();
        private final Set<String> universe;

        private Fixture(Set<String> universe) {
            this.universe = universe;
            this.config = new TestConfig(5, 10_000L, universe);
            this.blackboard = new Blackboard(
                    new IdManager(), new TimeManager(), new OrderRegistry(), config);
            this.gateway = new FakeGateway(blackboard);
            tradingGate.transitionTo(EngineMode.READY, "test");
            refreshAccount();
        }

        void refreshAccount() {
            clock.advanceMillis(1);
            blackboard.getAccount().setNetLiquidation(100_000.0);
            blackboard.getAccount().setAvailableFunds(50_000.0);
            blackboard.getAccount().setLastRefreshedAtMillis(clock.millis());
        }

        ProbeStrategy strategy() {
            return new ProbeStrategy(
                    blackboard, gateway, tickStreams, config, tradingGate,
                    new ReadyMarketData(), universe, clock);
        }
    }

    private static final class ProbeStrategy extends AbstractStrategy {
        private static final String ID = "PROBE_STRATEGY";
        private final Set<String> entryTickers = ConcurrentHashMap.newKeySet();
        private final AtomicInteger positionClosedCount = new AtomicInteger();

        private ProbeStrategy(
                Blackboard blackboard,
                BracketOrderGateway gateway,
                TickStreamController tickStreams,
                TestConfig config,
                TradingGate tradingGate,
                mwd.trading.marketdata.MarketDataFreshness freshness,
                Set<String> universe,
                Clock clock) {
            super(blackboard, gateway, tickStreams, config, tradingGate, freshness,
                    REFERENCE,
                    new ConcentrationLimits(blackboard, REFERENCE, 100.0, 100.0, 0.0),
                    universe, clock);
        }

        @Override protected Set<MarketDataInput> requiredEntryInputs() {
            return Set.of(MarketDataInput.LAST_PRICE);
        }

        @Override protected Set<MarketDataInput> requiredManagementInputs() {
            return Set.of(MarketDataInput.LAST_PRICE);
        }

        @Override protected boolean isEntryConditionMet(MarketSnapshot market) {
            return entryTickers.contains(market.ticker());
        }

        @Override protected double calculateEntryPrice(MarketSnapshot market) { return 100.0; }

        @Override protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(
                MarketSnapshot market, double entryPrice) {
            return List.of(new BracketOrderExecutor.SliceIntent(Decimal.get(1), 110.0, 90.0, 1L));
        }

        @Override protected void evaluateTickStreamNeed(MarketSnapshot market, double entryPrice) {}

        @Override protected void manageOpenPosition(Stock stock, MarketSnapshot market) {}

        @Override protected void onPositionClosed(Stock stock) {
            positionClosedCount.incrementAndGet();
        }

        @Override protected String getStrategyName() { return ID; }
        @Override protected TradeDirection getTradeDirection() { return TradeDirection.LONG; }
    }

    private static final class FakeGateway implements BracketOrderGateway {
        private final Blackboard blackboard;
        private final AtomicInteger submissionCount = new AtomicInteger();

        private FakeGateway(Blackboard blackboard) {
            this.blackboard = blackboard;
        }

        @Override public BracketOrder placeTripleThreat(
                String strategyName, TradeDirection tradeDirection, String tickerSymbol,
                Decimal totalOrderQuantity, double entryLimitPrice,
                List<BracketOrderExecutor.SliceIntent> sliceIntents) {
            int sequence = submissionCount.incrementAndGet();
            BracketOrder bracket = new BracketOrder(
                    "trade-" + sequence, strategyName, "DU123456", 1,
                    tradeDirection.entryAction(), tickerSymbol, sequence, totalOrderQuantity);
            bracket.setEntryPrice(entryLimitPrice);
            blackboard.getStock(tickerSymbol).setActiveBracket(bracket);
            return bracket;
        }

        @Override public void updateTripleThreatExits(
                Stock stock, BracketOrder bracketOrder, BracketOrder.ExitSlice exitSlice,
                double newTakeProfitPrice, double newStopLossPrice, long newExitTime) {}
    }

    private static final class FakeTickStreams implements TickStreamController {
        private final Set<String> active = ConcurrentHashMap.newKeySet();

        @Override public boolean isStreamActive(String ticker) { return active.contains(ticker); }
        @Override public void tryRequestStream(String ticker) { active.add(ticker); }
        @Override public void cancelStream(String ticker) { active.remove(ticker); }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-07-26T12:00:00Z");

        void advanceMillis(long milliseconds) { instant = instant.plusMillis(milliseconds); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
