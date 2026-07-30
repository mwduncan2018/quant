package mwd.trading.strategy;

import static mwd.trading.support.ProxyReferenceFixtures.earlyCloseSession;
import static mwd.trading.support.ProxyReferenceFixtures.earningsDates;
import static mwd.trading.support.ProxyReferenceFixtures.earningsLastOn;
import static mwd.trading.support.ProxyReferenceFixtures.earningsOn;
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

import com.ib.client.Bar;
import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.calendar.MarketCalendarStore;
import mwd.trading.domain.Stock;
import mwd.trading.domain.TradeDirection;
import mwd.trading.earnings.EarningsStore;
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
 * Covers the two policy rules: an earnings blackout of one market day either
 * side of the report, and entry and exit timing measured from the real session
 * close rather than a fixed clock time.
 *
 * <p>
 * Monday 2026-07-27 is the trading date throughout, so its previous session is
 * Friday the 24th and its next is Tuesday the 28th.
 */
class TwoSigmaDownsidePolicyTest {
    private static final String TICKER = "AAPL";

    /** Margin is configuration now; this is the rate these assertions assume. */
    private static final UniverseReference UNIVERSE_REFERENCE = UniverseReference.parse(
            java.util.List.of("AAPL,INFORMATION_TECHNOLOGY,0.25,0.25,0.25,0.25"),
            MarginMethodology.REG_T, 0.50, 0.50);

    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 27);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 7, 24);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 7, 28);
    private static final double IMPLIED_MOVE = 2.0;
    private static final double GAMMA_FLIP = 601.25;

    /** 10:00 New York, comfortably inside a standard session. */
    private static final Instant MID_MORNING = newYork(MONDAY, 10, 0);

    private Blackboard blackboard;

    private TwoSigmaDownsideMeanReversionStrategy strategy(
            Instant now, MarketCalendarStore calendar, EarningsStore earnings) {
        TestConfig config = new TestConfig(5, 10_000L, Set.of(TICKER));
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(), config);
        TradingGate tradingGate = new TradingGate();
        tradingGate.transitionTo(EngineMode.READY, "test");

        OptionsIndicatorStore optionsStore = new OptionsIndicatorStore(Set.of(TICKER, "SPY"), 5000L);
        optionsStore.accept(IndicatorFrame.newBuilder()
                .setSequence(1)
                .setEmittedAtUnixMs(now.toEpochMilli())
                .setTicker(TICKER)
                .setTradingDate(MONDAY.toString())
                .setStaticDailyImpliedMove(IMPLIED_MOVE)
                .setStaticDailyImpliedMoveValid(true)
                .setSpyGammaFlip(GAMMA_FLIP)
                .setSpyGammaFlipValid(true)
                .build(), now.toEpochMilli());

        MarketDataInputStore inputStore =
                new MarketDataInputStore(30_000L, Clock.fixed(now, ZoneOffset.UTC));
        for (MarketDataInput input : MarketDataInput.values()) {
            inputStore.record(TICKER, input);
        }
        inputStore.record("SPY", MarketDataInput.LAST_PRICE);

        TwoSigmaDownsideMeanReversionStrategy built =
                new TwoSigmaDownsideMeanReversionStrategy(
                        blackboard,
                        new NoopGateway(),
                        new NoopTickStreams(),
                        config,
                        tradingGate,
                        inputStore,
                        UNIVERSE_REFERENCE,
                        optionsStore,
                        earnings,
                        calendar,
                        Clock.fixed(now, ZoneOffset.UTC));
        primeCapitulationState();
        return built;
    }

    /** Every non-policy entry lock is satisfied by this state. */
    private void primeCapitulationState() {
        // Sizing needs real account state; zero net liquidation legitimately
        // produces no shares, which would mask what these tests assert.
        blackboard.getAccount().setNetLiquidation(100_000.0);
        blackboard.getAccount().setAvailableFunds(50_000.0);

        Stock stock = blackboard.getStock(TICKER);
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

    /** Earnings far enough away that the blackout plays no part. */
    private static EarningsStore earningsFarOff() {
        return earningsOn(TICKER, MONDAY, LocalDate.of(2026, 8, 20));
    }

    // ----------------------------------------------------------------
    // Earnings blackout
    // ----------------------------------------------------------------

    @Test
    void aReportSeveralWeeksOutDoesNotBlockEntry() {
        assertTrue(strategy(MID_MORNING, standardSession(MONDAY), earningsFarOff())
                .isEntryConditionMet(market()));
    }

    @Test
    void theReportDayItselfIsBlacked() {
        assertFalse(strategy(MID_MORNING, standardSession(MONDAY),
                earningsOn(TICKER, MONDAY, MONDAY)).isEntryConditionMet(market()));
    }

    @Test
    void theMarketDayBeforeTheReportIsBlacked() {
        assertFalse(strategy(MID_MORNING, standardSession(MONDAY),
                earningsOn(TICKER, MONDAY, TUESDAY)).isEntryConditionMet(market()));
    }

    @Test
    void theMarketDayAfterTheReportIsBlacked() {
        // Friday is Monday's previous session, so a Friday report blacks out
        // Monday even though three calendar days separate them. The date comes
        // from the proxy's retained history, because a ticker that reported on
        // Friday may already show next quarter as its upcoming date.
        assertFalse(strategy(MID_MORNING, standardSession(MONDAY),
                earningsLastOn(TICKER, MONDAY, FRIDAY)).isEntryConditionMet(market()));
    }

    @Test
    void aRetainedDateBlacksOutEvenWhenTheSourceHasRolledForward() {
        // The case the retention exists for: the upcoming date is three months
        // out and tells us nothing, while the retained one says we are a single
        // market day after the report.
        assertFalse(strategy(MID_MORNING, standardSession(MONDAY),
                earningsDates(TICKER, MONDAY, LocalDate.of(2026, 10, 29), FRIDAY))
                .isEntryConditionMet(market()));
    }

    @Test
    void aRetainedDateOutsideTheWindowDoesNotBlock() {
        // Retention holds a month of history; only the adjacent market day
        // matters. A report two weeks ago must not keep a ticker untradeable.
        assertTrue(strategy(MID_MORNING, standardSession(MONDAY),
                earningsDates(TICKER, MONDAY, LocalDate.of(2026, 10, 29),
                        LocalDate.of(2026, 7, 13)))
                .isEntryConditionMet(market()));
    }

    @Test
    void aTickerWithOnlyAPastDateIsStillTradeable() {
        // Some tickers linger on the date that just passed, so no upcoming date
        // is known. That is knowledge, not absence, and must not fail closed.
        assertTrue(strategy(MID_MORNING, standardSession(MONDAY),
                earningsLastOn(TICKER, MONDAY, LocalDate.of(2026, 7, 13)))
                .isEntryConditionMet(market()));
    }

    @Test
    void theWeekendBetweenDoesNotConsumeTheBlackoutWindow() {
        // Saturday and Sunday are not market days, so a Saturday date is
        // neither the previous nor the next session and does not black out.
        assertTrue(strategy(MID_MORNING, standardSession(MONDAY),
                earningsOn(TICKER, MONDAY, LocalDate.of(2026, 7, 25)))
                .isEntryConditionMet(market()));
    }

    @Test
    void twoMarketDaysOutIsOutsideTheWindow() {
        assertTrue(strategy(MID_MORNING, standardSession(MONDAY),
                earningsOn(TICKER, MONDAY, LocalDate.of(2026, 7, 29)))
                .isEntryConditionMet(market()));
    }

    @Test
    void anUnknownEarningsDateBlocksEntry() {
        // Fails closed: an unknown report date is not evidence of its absence.
        EarningsStore empty = new EarningsStore(Set.of(TICKER));

        assertFalse(strategy(MID_MORNING, standardSession(MONDAY), empty)
                .isEntryConditionMet(market()));
    }

    @Test
    void aBlackoutSkipsAHolidayBetweenSessions() {
        // With Tuesday closed, Monday's next session is Wednesday, so a
        // Wednesday report blacks out Monday.
        MarketCalendarStore calendar = mwd.trading.support.ProxyReferenceFixtures
                .sessionClosingAt(MONDAY, "open", newYork(MONDAY, 16, 0),
                        Set.of(TUESDAY), MONDAY.plusYears(1));

        assertFalse(strategy(MID_MORNING, calendar,
                earningsOn(TICKER, MONDAY, LocalDate.of(2026, 7, 29)))
                .isEntryConditionMet(market()));
    }

    // ----------------------------------------------------------------
    // Close-relative timing
    // ----------------------------------------------------------------

    @Test
    void entryIsAllowedMoreThanAnHourBeforeClose() {
        // 14:59 against a 16:00 close: 61 minutes remain.
        assertTrue(strategy(newYork(MONDAY, 14, 59), standardSession(MONDAY), earningsFarOff())
                .isEntryConditionMet(market()));
    }

    @Test
    void entryIsBlockedInsideTheLastHour() {
        assertFalse(strategy(newYork(MONDAY, 15, 0), standardSession(MONDAY), earningsFarOff())
                .isEntryConditionMet(market()));
        assertFalse(strategy(newYork(MONDAY, 15, 30), standardSession(MONDAY), earningsFarOff())
                .isEntryConditionMet(market()));
    }

    @Test
    void anEarlyCloseShortensTheEntryWindow() {
        // The bug this replaces: 12:30 was a valid entry time under a hardcoded
        // 15:00 cutoff, half an hour before a 13:00 close.
        assertFalse(strategy(newYork(MONDAY, 12, 30), earlyCloseSession(MONDAY), earningsFarOff())
                .isEntryConditionMet(market()));
        assertTrue(strategy(newYork(MONDAY, 11, 30), earlyCloseSession(MONDAY), earningsFarOff())
                .isEntryConditionMet(market()));
    }

    @Test
    void anUnknownCloseBlocksEntry() {
        assertFalse(strategy(MID_MORNING, new MarketCalendarStore(), earningsFarOff())
                .isEntryConditionMet(market()));
    }

    @Test
    void aClosedSessionBlocksEntry() {
        MarketCalendarStore closed = new MarketCalendarStore();
        closed.accept(new mwd.trading.calendar.MarketSession(
                MONDAY, "closed", null, null, "closed", Set.of(), MONDAY, MONDAY.plusYears(1)),
                MONDAY);

        assertFalse(strategy(MID_MORNING, closed, earningsFarOff())
                .isEntryConditionMet(market()));
    }

    @Test
    void theTimeExitSitsFifteenMinutesBeforeAStandardClose() {
        TwoSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, standardSession(MONDAY), earningsFarOff());

        assertEquals(newYork(MONDAY, 15, 45).toEpochMilli(), built.calculateTimeExit());
    }

    @Test
    void theTimeExitFollowsAnEarlyCloseForward() {
        // The reason the hardcoded 15:45 had to go: it sat two and three
        // quarter hours after a 13:00 close.
        TwoSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, earlyCloseSession(MONDAY), earningsFarOff());

        assertEquals(newYork(MONDAY, 12, 45).toEpochMilli(), built.calculateTimeExit());
    }

    @Test
    void everySliceCarriesTheCloseRelativeTimeExit() {
        TwoSigmaDownsideMeanReversionStrategy built =
                strategy(MID_MORNING, earlyCloseSession(MONDAY), earningsFarOff());

        List<BracketOrderExecutor.SliceIntent> intents =
                built.calculateSliceIntents(market(), 96.0);

        assertFalse(intents.isEmpty());
        for (BracketOrderExecutor.SliceIntent intent : intents) {
            assertEquals(newYork(MONDAY, 12, 45).toEpochMilli(), intent.timeExit);
        }
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
