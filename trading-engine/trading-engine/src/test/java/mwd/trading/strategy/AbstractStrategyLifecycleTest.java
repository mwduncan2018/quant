package mwd.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.domain.Stock;
import mwd.trading.domain.TradeDirection;
import mwd.trading.execution.BracketOrderExecutor;
import mwd.trading.execution.BracketOrder;
import mwd.trading.execution.BracketOrderGateway;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.execution.UncertainOrderSubmissionException;
import mwd.trading.lifecycle.EngineMode;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.marketdata.TickStreamController;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

class AbstractStrategyLifecycleTest {
    @Test
    void oneCycleManagesEveryOpenPositionWithoutBlocking() {
        Fixture fixture = fixture(Set.of("AAPL", "MSFT"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.LONG);
        openOwnedPosition(fixture.blackboard, "AAPL");
        openOwnedPosition(fixture.blackboard, "MSFT");

        assertTimeoutPreemptively(Duration.ofMillis(250), strategy::runOneCycle);

        assertEquals(1, strategy.managementCounts.get("AAPL"));
        assertEquals(1, strategy.managementCounts.get("MSFT"));
        assertEquals(2, fixture.blackboard.getActivePositionCount());
    }

    @Test
    void aSymbolFailureIsContainedAndDoesNotFlattenIt() {
        Fixture fixture = fixture(Set.of("AAPL", "MSFT"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.LONG);
        openOwnedPosition(fixture.blackboard, "AAPL");
        strategy.managementFailures.add("AAPL");

        strategy.runOneCycle();

        Stock stock = fixture.blackboard.getStock("AAPL");
        assertEquals(Stock.PositionState.OPEN, stock.getState().get());
        assertEquals(TestStrategy.ID, fixture.blackboard.getPositionOwner("AAPL"));
        assertEquals(1, fixture.blackboard.getActivePositionCount());
        assertEquals(EngineMode.MANUAL_INTERVENTION, fixture.tradingGate.getMode());
    }

    @Test
    void flatSymbolExceptionDoesNotPreventOtherSymbolsFromBeingEvaluated() {
        Fixture fixture = fixture(Set.of("AAPL", "MSFT"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.LONG);
        strategy.entryFailures.add("AAPL");

        strategy.runOneCycle();

        assertTrue(strategy.entryEvaluationCounts.containsKey("AAPL"));
        assertTrue(strategy.entryEvaluationCounts.containsKey("MSFT"));
        assertFalse(fixture.blackboard.getStock("AAPL").isTradeable());
        assertTrue(fixture.blackboard.getStock("MSFT").isTradeable());
    }

    @Test
    void strategyOnlyScansItsOwnUniverse() {
        Fixture fixture = fixture(Set.of("AAPL"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.LONG);
        fixture.blackboard.getStock("MSFT").setLastPrice(99.0);

        strategy.runOneCycle();

        assertTrue(strategy.entryEvaluationCounts.containsKey("AAPL"));
        assertFalse(strategy.entryEvaluationCounts.containsKey("MSFT"));
        assertEquals(Set.of("AAPL"), strategy.getUniverse());
    }

    @Test
    void acknowledgementTimeoutKeepsPendingOwnershipAndRequiresReview() {
        Fixture fixture = fixture(Set.of("AAPL"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.LONG);
        strategy.entryTickers.add("AAPL");
        fixture.blackboard.getStock("AAPL").setLastPrice(99.0);

        strategy.runOneCycle();
        BracketOrder bracket = fixture.blackboard.getStock("AAPL").getActiveBracket();
        fixture.clock.advanceMillis(1001);
        strategy.runOneCycle();

        assertSame(bracket, fixture.blackboard.getStock("AAPL").getActiveBracket());
        assertEquals(BracketOrder.Status.INITIALIZED, bracket.getStatus());
        assertEquals(Stock.PositionState.PENDING,
                fixture.blackboard.getStock("AAPL").getState().get());
        assertEquals(TestStrategy.ID, fixture.blackboard.getPositionOwner("AAPL"));
        assertTrue(fixture.blackboard.isGlobalPendingOwnedBy(TestStrategy.ID, "AAPL"));
        assertEquals(1, fixture.blackboard.getActivePositionCount());
        assertEquals(EngineMode.MANUAL_INTERVENTION, fixture.tradingGate.getMode());
    }

    @Test
    void acknowledgedRestingParentDoesNotTriggerAcknowledgementTimeout() {
        Fixture fixture = fixture(Set.of("AAPL"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.LONG);
        strategy.entryTickers.add("AAPL");
        fixture.blackboard.getStock("AAPL").setLastPrice(99.0);
        strategy.runOneCycle();

        fixture.blackboard.getStock("AAPL").getActiveBracket()
                .setStatus(BracketOrder.Status.WORKING_PARENT);
        fixture.clock.advanceMillis(5000);
        strategy.runOneCycle();

        assertEquals(Stock.PositionState.PENDING,
                fixture.blackboard.getStock("AAPL").getState().get());
        assertEquals(EngineMode.READY, fixture.tradingGate.getMode());
        // Acknowledgement is the confirmation the serialization lock waits for,
        // so it is released here even though the order has not filled.
        assertNull(fixture.blackboard.getGlobalPendingOwner());
        // The ticker stays reserved and keeps counting against the position cap.
        assertEquals(TestStrategy.ID, fixture.blackboard.getPositionOwner("AAPL"));
        assertEquals(1, fixture.blackboard.getActivePositionCount());
    }

    @Test
    void anAcknowledgedRestingEntryDoesNotBlockTheNextSymbol() {
        // Without this the engine parks: there is no timeout on a resting
        // parent, so one unfilled limit would stop every strategy entering.
        Fixture fixture = fixture(Set.of("AAPL", "MSFT"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.LONG);
        strategy.entryTickers.add("AAPL");
        strategy.entryTickers.add("MSFT");
        fixture.blackboard.getStock("AAPL").setLastPrice(99.0);
        fixture.blackboard.getStock("MSFT").setLastPrice(99.0);

        strategy.runOneCycle();
        assertEquals(1, submittedCount(fixture),
                "only one submission may be unacknowledged at a time");

        acknowledgeSubmitted(fixture);
        fixture.refreshAccount();
        strategy.runOneCycle();

        assertEquals(2, submittedCount(fixture));
        assertEquals(2, fixture.blackboard.getActivePositionCount());
    }

    private static long submittedCount(Fixture fixture) {
        return java.util.stream.Stream.of("AAPL", "MSFT")
                .filter(ticker -> fixture.blackboard.getStock(ticker).getActiveBracket() != null)
                .count();
    }

    /** Marks whichever entry won the lock as acknowledged and resting. */
    private static void acknowledgeSubmitted(Fixture fixture) {
        java.util.stream.Stream.of("AAPL", "MSFT")
                .map(ticker -> fixture.blackboard.getStock(ticker).getActiveBracket())
                .filter(java.util.Objects::nonNull)
                .forEach(bracket -> bracket.setStatus(BracketOrder.Status.WORKING_PARENT));
    }

    @Test
    void uncertainSubmissionNeverRollsBackToFlat() {
        Fixture fixture = fixture(Set.of("AAPL"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.LONG);
        strategy.entryTickers.add("AAPL");
        fixture.gateway.throwUncertain = true;
        fixture.blackboard.getStock("AAPL").setLastPrice(99.0);

        strategy.runOneCycle();

        assertEquals(Stock.PositionState.PENDING,
                fixture.blackboard.getStock("AAPL").getState().get());
        assertEquals(TestStrategy.ID, fixture.blackboard.getPositionOwner("AAPL"));
        assertTrue(fixture.blackboard.isGlobalPendingOwnedBy(TestStrategy.ID, "AAPL"));
        assertEquals(EngineMode.MANUAL_INTERVENTION, fixture.tradingGate.getMode());
    }

    @Test
    void brokerConfirmedZeroFillCancellationCleansUpExactlyOnce() {
        Fixture fixture = fixture(Set.of("AAPL"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.LONG);
        strategy.entryTickers.add("AAPL");
        fixture.blackboard.getStock("AAPL").setLastPrice(99.0);
        strategy.runOneCycle();

        BracketOrder bracket = fixture.blackboard.getStock("AAPL").getActiveBracket();
        bracket.setStatus(BracketOrder.Status.CANCELLED);
        strategy.entryTickers.remove("AAPL");
        strategy.runOneCycle();
        strategy.runOneCycle();

        assertEquals(Stock.PositionState.FLAT,
                fixture.blackboard.getStock("AAPL").getState().get());
        assertNull(fixture.blackboard.getStock("AAPL").getActiveBracket());
        assertNull(fixture.blackboard.getPositionOwner("AAPL"));
        assertNull(fixture.blackboard.getGlobalPendingOwner());
        assertEquals(0, fixture.blackboard.getActivePositionCount());
    }

    @Test
    void shortStrategyUsesSellSidePriceAcceptanceAndExplicitIdentity() {
        Fixture fixture = fixture(Set.of("AAPL"), 1000);
        TestStrategy strategy = fixture.strategy(TradeDirection.SHORT);
        strategy.entryTickers.add("AAPL");
        fixture.blackboard.getStock("AAPL").setLastPrice(101.0);

        strategy.runOneCycle();

        assertEquals(1, fixture.gateway.submissionCount.get());
        assertEquals(TestStrategy.ID, fixture.gateway.lastStrategyName);
        assertEquals(TradeDirection.SHORT, fixture.gateway.lastDirection);
    }

    private static Fixture fixture(Set<String> universe, long timeoutMs) {
        TestConfig config = new TestConfig(5, timeoutMs, universe);
        Blackboard blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(), config);
        TradingGate tradingGate = new TradingGate();
        tradingGate.transitionTo(EngineMode.READY, "test");
        MutableClock clock = new MutableClock();
        FakeGateway gateway = new FakeGateway(blackboard);
        FakeTickStreams tickStreams = new FakeTickStreams();
        Fixture fixture = new Fixture(
                blackboard, config, tradingGate, clock, gateway, tickStreams, universe);
        // A connected engine has received at least one account batch before the
        // gate opens, so start every fixture in that state.
        fixture.refreshAccount();
        return fixture;
    }

    private static void openOwnedPosition(Blackboard blackboard, String ticker) {
        assertTrue(blackboard.tryReservePosition(ticker, TestStrategy.ID));
        blackboard.getStock(ticker).getState().set(Stock.PositionState.OPEN);
    }

    private record Fixture(
            Blackboard blackboard,
            TestConfig config,
            TradingGate tradingGate,
            MutableClock clock,
            FakeGateway gateway,
            FakeTickStreams tickStreams,
            Set<String> universe) {

        /**
         * Stands in for IBKR pushing a batch of account values. Entries require
         * a snapshot newer than the last submission, so a test that expects a
         * second entry has to model the broker refreshing in between.
         */
        void refreshAccount() {
            clock.advanceMillis(1);
            blackboard.getAccount().setLastRefreshedAtMillis(clock.millis());
        }

        TestStrategy strategy(TradeDirection direction) {
            return new TestStrategy(
                    blackboard,
                    gateway,
                    tickStreams,
                    config,
                    tradingGate,
                    new mwd.trading.support.ReadyMarketData(),
                    universe,
                    clock,
                    direction);
        }
    }

    private static final class TestStrategy extends AbstractStrategy {
        private static final String ID = "TEST_STRATEGY";
        private final TradeDirection direction;
        private final Set<String> entryTickers = ConcurrentHashMap.newKeySet();
        private final Set<String> entryFailures = ConcurrentHashMap.newKeySet();
        private final Set<String> managementFailures = ConcurrentHashMap.newKeySet();
        private final Map<String, Integer> entryEvaluationCounts = new ConcurrentHashMap<>();
        private final Map<String, Integer> managementCounts = new ConcurrentHashMap<>();

        private TestStrategy(
                Blackboard blackboard,
                BracketOrderGateway gateway,
                TickStreamController tickStreams,
                TestConfig config,
                TradingGate tradingGate,
                mwd.trading.marketdata.MarketDataFreshness freshness,
                Set<String> universe,
                Clock clock,
                TradeDirection direction) {
            super(
                    blackboard,
                    gateway,
                    tickStreams,
                    config,
                    tradingGate,
                    freshness,
                    universe,
                    clock);
            this.direction = direction;
        }

        @Override
        protected Set<mwd.trading.marketdata.MarketDataInput> requiredEntryInputs() {
            return Set.of(mwd.trading.marketdata.MarketDataInput.LAST_PRICE);
        }

        @Override
        protected Set<mwd.trading.marketdata.MarketDataInput> requiredManagementInputs() {
            return Set.of(mwd.trading.marketdata.MarketDataInput.LAST_PRICE);
        }

        @Override
        protected boolean isEntryConditionMet(Stock stock) {
            entryEvaluationCounts.merge(stock.getTicker(), 1, Integer::sum);
            if (entryFailures.contains(stock.getTicker())) {
                throw new IllegalStateException("test entry failure");
            }
            return entryTickers.contains(stock.getTicker());
        }

        @Override protected double calculateEntryPrice(Stock stock) { return 100.0; }

        @Override
        protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(
                Stock stock, double entryPrice) {
            return List.of(new BracketOrderExecutor.SliceIntent(
                    Decimal.get(1), 110.0, 90.0, 1L));
        }

        @Override
        protected void evaluateTickStreamNeed(Stock stock, double entryPrice) {}

        @Override
        protected void manageOpenPosition(Stock stock) {
            managementCounts.merge(stock.getTicker(), 1, Integer::sum);
            if (managementFailures.contains(stock.getTicker())) {
                throw new IllegalStateException("test management failure");
            }
        }

        @Override protected String getStrategyName() { return ID; }
        @Override protected TradeDirection getTradeDirection() { return direction; }
    }

    private static final class FakeGateway implements BracketOrderGateway {
        private final Blackboard blackboard;
        private final AtomicInteger submissionCount = new AtomicInteger();
        private volatile boolean throwUncertain;
        private volatile String lastStrategyName;
        private volatile TradeDirection lastDirection;

        private FakeGateway(Blackboard blackboard) {
            this.blackboard = blackboard;
        }

        @Override
        public BracketOrder placeTripleThreat(
                String strategyName,
                TradeDirection tradeDirection,
                String tickerSymbol,
                Decimal totalOrderQuantity,
                double entryLimitPrice,
                List<BracketOrderExecutor.SliceIntent> sliceIntents) {
            int sequence = submissionCount.incrementAndGet();
            lastStrategyName = strategyName;
            lastDirection = tradeDirection;
            BracketOrder bracket = new BracketOrder(
                    "trade-" + sequence,
                    strategyName,
                    "DU123456",
                    1,
                    tradeDirection.entryAction(),
                    tickerSymbol,
                    sequence,
                    totalOrderQuantity);
            bracket.setEntryPrice(entryLimitPrice);
            blackboard.getStock(tickerSymbol).setActiveBracket(bracket);
            if (throwUncertain) {
                throw new UncertainOrderSubmissionException(
                        "test uncertain submission", new IllegalStateException("test"));
            }
            return bracket;
        }

        @Override
        public void updateTripleThreatExits(
                Stock stock,
                BracketOrder bracketOrder,
                BracketOrder.ExitSlice exitSlice,
                double newTakeProfitPrice,
                double newStopLossPrice,
                long newExitTime) {}
    }

    private static final class FakeTickStreams implements TickStreamController {
        private final Set<String> active = ConcurrentHashMap.newKeySet();

        @Override public boolean isStreamActive(String ticker) { return active.contains(ticker); }
        @Override public void tryRequestStream(String ticker) { active.add(ticker); }
        @Override public void cancelStream(String ticker) { active.remove(ticker); }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-07-26T12:00:00Z");

        void advanceMillis(long milliseconds) {
            instant = instant.plusMillis(milliseconds);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
