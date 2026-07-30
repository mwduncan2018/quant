package mwd.trading.strategy;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import com.ib.client.Bar;
import com.ib.client.Decimal;
import mwd.trading.domain.Account;
import mwd.trading.state.StrategyBlackboard;
import mwd.trading.execution.BracketOrderExecutor;
import mwd.trading.execution.BracketOrder;
import mwd.trading.execution.BracketOrderGateway;
import mwd.trading.domain.Stock;
import mwd.trading.domain.TradeDirection;
import mwd.trading.calendar.MarketCalendarStore;
import mwd.trading.config.Config;
import mwd.trading.earnings.EarningsSnapshot;
import mwd.trading.earnings.EarningsStore;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.marketdata.MarketDataFreshness;
import mwd.trading.marketdata.MarketDataInput;
import mwd.trading.marketdata.MarketSnapshot;
import mwd.trading.marketdata.TickStreamController;
import mwd.trading.optionsproxy.OptionsIndicatorStore;

public class TwoSigmaDownsideMeanReversionStrategy extends AbstractStrategy {
    public static final String STRATEGY_ID = "TWO_SIGMA_DOWNSIDE";
    private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");

    /** The market index this strategy reads before allowing any entry. */
    private static final String MARKET_INDEX_TICKER = "SPY";

    /**
     * No new position may be opened inside this window before the close. A
     * position needs room to reach its target, and the time exit below would
     * otherwise fire almost immediately after entry.
     */
    private static final Duration NO_ENTRY_BEFORE_CLOSE = Duration.ofMinutes(60);

    /**
     * Every position carries a time exit this far before the close, which is
     * what keeps the book flat overnight.
     */
    private static final Duration EXIT_BEFORE_CLOSE = Duration.ofMinutes(15);

    /** Every IBKR-sourced value {@link #isEntryConditionMet} reads. */
    private static final Set<MarketDataInput> ENTRY_INPUTS = Set.of(
            MarketDataInput.LAST_PRICE,
            MarketDataInput.PREVIOUS_CLOSE,
            MarketDataInput.DAILY_VWAP,
            MarketDataInput.MINUTE_BAR,
            MarketDataInput.MINUTE_VOLUME_BASELINE);

    /**
     * Every IBKR-sourced value {@link #manageOpenPosition} reads. Deliberately
     * narrower than the entry set: the volume baseline plays no part in managing
     * a position, so its absence must never strand one.
     */
    private static final Set<MarketDataInput> MANAGEMENT_INPUTS = Set.of(
            MarketDataInput.LAST_PRICE,
            MarketDataInput.PREVIOUS_CLOSE,
            MarketDataInput.DAILY_VWAP);

    private final OptionsIndicatorStore optionsIndicatorStore;
    private final EarningsStore earningsStore;
    private final MarketCalendarStore marketCalendarStore;
    private final Clock newYorkClock;

    public TwoSigmaDownsideMeanReversionStrategy(
            StrategyBlackboard blackboard,
            BracketOrderGateway bracketOrderGateway,
            TickStreamController tickStreamController,
            Config config,
            TradingGate tradingGate,
            MarketDataFreshness marketDataFreshness,
            OptionsIndicatorStore optionsIndicatorStore,
            EarningsStore earningsStore,
            MarketCalendarStore marketCalendarStore) {
        this(
                blackboard,
                bracketOrderGateway,
                tickStreamController,
                config,
                tradingGate,
                marketDataFreshness,
                optionsIndicatorStore,
                earningsStore,
                marketCalendarStore,
                Clock.systemUTC());
    }

    TwoSigmaDownsideMeanReversionStrategy(
            StrategyBlackboard blackboard,
            BracketOrderGateway bracketOrderGateway,
            TickStreamController tickStreamController,
            Config config,
            TradingGate tradingGate,
            MarketDataFreshness marketDataFreshness,
            OptionsIndicatorStore optionsIndicatorStore,
            EarningsStore earningsStore,
            MarketCalendarStore marketCalendarStore,
            Clock clock) {
        super(
                blackboard,
                bracketOrderGateway,
                tickStreamController,
                config,
                tradingGate,
                marketDataFreshness,
                config.getStrategyUniverse(STRATEGY_ID),
                clock);
        this.optionsIndicatorStore = Objects.requireNonNull(
                optionsIndicatorStore, "optionsIndicatorStore");
        this.earningsStore = Objects.requireNonNull(earningsStore, "earningsStore");
        this.marketCalendarStore = Objects.requireNonNull(
                marketCalendarStore, "marketCalendarStore");
        this.newYorkClock = Objects.requireNonNull(clock, "clock").withZone(NEW_YORK_ZONE);
    }

    /** The session the options proxy must have stamped on its newest frames. */
    private LocalDate currentTradingDate() {
        return LocalDate.now(newYorkClock);
    }

    @Override
    protected Set<MarketDataInput> requiredEntryInputs() {
        return ENTRY_INPUTS;
    }

    @Override
    protected Set<MarketDataInput> requiredManagementInputs() {
        return MANAGEMENT_INPUTS;
    }

    @Override
    protected boolean isEntryConditionMet(MarketSnapshot market) {
        // ---------------------------------------------------------
        // 1. PENALTY BOX & SYSTEM CHECKS
        // ---------------------------------------------------------
        /*
    	if (blackboard.isTickerBanned(market.ticker())) {
            return false;
        }
        */

        if (!market.longMarginRateVerified()) { 
            return false; 
        }
        
        // ---------------------------------------------------------
        // 2. TIME & BROAD MARKET CHECKS
        // No hardcoded clock times. The session close comes from the proxy's
        // calendar, so an early-close day shortens the entry window on its own
        // rather than leaving entries open past the bell.
        // ---------------------------------------------------------
        LocalDate tradingDate = currentTradingDate();
        if (marketCalendarStore.isWithinOfClose(
                tradingDate, newYorkClock.instant(), NO_ENTRY_BEFORE_CLOSE)) {
            return false;
        }
        // An unknown close means the entry window cannot be bounded at all.
        if (marketCalendarStore.sessionClose(tradingDate).isEmpty()) {
            return false;
        }

        // ---------------------------------------------------------
        // 2b. OPTIONS-PROXY READINESS
        // A new entry needs both indicators explicitly valid for today's session
        // and delivered recently. A silent or stale proxy blocks entries only;
        // manageOpenPosition() keeps working from the last accepted value.
        // ---------------------------------------------------------
        long nowUnixMs = newYorkClock.millis();

        // ---------------------------------------------------------
        // 2c. EARNINGS BLACKOUT
        // A two-sigma drop on an earnings day is plausibly a repricing of the
        // business rather than the panic this strategy fades, and an
        // intraday-only exit gives no protection against entering one. The
        // window is one market day either side of the report, measured in
        // sessions so a weekend or holiday does not shorten it.
        // ---------------------------------------------------------
        if (isInEarningsBlackout(market.ticker(), tradingDate)) {
            return false;
        }

        OptionalDouble impliedMoveForEntry = optionsIndicatorStore.impliedMoveForNewEntry(
                market.ticker(), tradingDate, nowUnixMs);
        if (impliedMoveForEntry.isEmpty()) {
            return false;
        }
        OptionalDouble gammaFlipForEntry = optionsIndicatorStore.gammaFlipForNewEntry(
                tradingDate, nowUnixMs);
        if (gammaFlipForEntry.isEmpty()) {
            return false;
        }

        // SPY is a reference symbol, not a member of this strategy's universe, so
        // its own readiness is checked here rather than by the per-symbol gate.
        Stock marketIndexStock = blackboard.getStock(MARKET_INDEX_TICKER);
        if (!marketDataFreshness.isFresh(MARKET_INDEX_TICKER, MarketDataInput.LAST_PRICE)
                || marketIndexStock.getLastPrice() < gammaFlipForEntry.getAsDouble()) {
            return false;
        }

        // ---------------------------------------------------------
        // 3. THE "TRIPLE LOCK" CAPITULATION FILTER
        // ---------------------------------------------------------
        double currentPrice = market.lastPrice();
        double impliedMove = impliedMoveForEntry.getAsDouble();
        double structuralTarget = market.previousClose() - (impliedMove * 2.0);

        Bar bar = market.lastMinuteBar();
        Decimal minVolDec = market.lastMinuteVolume();
        Decimal avgVolDec = market.averageLast15MinuteVolume();

        // A zero volume baseline is never a real reading; it means the rolling
        // 15-minute window has not filled for this session yet.
        if (minVolDec == null || avgVolDec == null || bar == null || avgVolDec.isZero()) {
            return false;
        }

        // Lock A: The Bounce Execution Ceiling
        double executionCeiling = structuralTarget + (impliedMove * 0.10); 

        // Requirement 1: Did the panic ACTUALLY reach the structural -2.0 line?
        if (bar.low() > structuralTarget || bar.low() <= 0.0) {
            return false; 
        }

        // Requirement 2: Is the current bounced price still below our -1.90 execution ceiling?
        if (currentPrice > executionCeiling) {
            return false;
        }

        // Lock B: Volume Climax (Panic Verification: >= 3.0x RVOL)
        // minVol is the minute currently forming and avgVol is the mean of the 15
        // completed minute bars before it, both from MinuteVolumeTracker. Because
        // the forming minute is excluded from its own baseline, 3.0x means exactly
        // that. The forming minute starts near zero and grows, so this fires the
        // moment the climax is real rather than at the end of the minute.
        // A zero baseline means the 15-bar window is not full yet -- roughly the
        // first 15 minutes of the session -- and is rejected by the guard above.
        double minVol = minVolDec.value().doubleValue();
        double avgVol = avgVolDec.value().doubleValue();
        if (minVol < (avgVol * 3.0)) {
            return false;
        }

        // Lock C: Structural Rejection (Absorption Verification: Wick close >= 50%)
        double range = bar.high() - bar.low();
        if (range <= 0.0001) {
            return false;
        }
        double closePosition = (bar.close() - bar.low()) / range;
        if (closePosition < 0.50) {
            return false;
        }

        // ---------------------------------------------------------
        // 4. THE VWAP ENTRY GATE (With Relative Risk Anchor)
        // ---------------------------------------------------------
        double assumedStopLoss = currentPrice - (impliedMove * 0.5);
        double actualRisk = currentPrice - assumedStopLoss; 
        
        double currentVWAP = market.dailyVWAP();
        double actualReward = currentVWAP - currentPrice;

        if (actualReward < (actualRisk * 2.5)) {
            return false; 
        }

        return true; 
    }

    @Override
    protected double calculateEntryPrice(MarketSnapshot market) {
        OptionalDouble dailyImpliedMove =
                optionsIndicatorStore.lastKnownImpliedMove(market.ticker());
        if (dailyImpliedMove.isEmpty()) {
            // Without a move there is no priceable entry. NaN fails
            // TradeDirection.acceptsEntryPrice, so the lifecycle rolls back.
            return Double.NaN;
        }
        return market.previousClose() - (dailyImpliedMove.getAsDouble() * 2.0);
    }

    @Override
    protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(
            MarketSnapshot market, double entryPrice) {

        List<BracketOrderExecutor.SliceIntent> sliceIntents = new ArrayList<>();
        OptionalDouble storedImpliedMove =
                optionsIndicatorStore.lastKnownImpliedMove(market.ticker());
        if (storedImpliedMove.isEmpty()) {
            // An empty intent list produces a zero total quantity, which the
            // lifecycle treats as a clean rollback.
            return sliceIntents;
        }
        double dailyImpliedMove = storedImpliedMove.getAsDouble();

        // RELATIVE RISK ANCHOR
        double stopLossPrice = entryPrice - (dailyImpliedMove * 0.5);
        long timeExitValue = calculateTimeExit();

        Decimal totalQuantity = calculateTotalQuantity(market, entryPrice, stopLossPrice);
        if (totalQuantity.compareTo(Decimal.ZERO) <= 0) return sliceIntents;

        long totalShares = (long) totalQuantity.value().doubleValue();
        if (totalShares < 2) return sliceIntents;

        long sharesPerSlice = totalShares / 2;
        Decimal sliceQuantity = Decimal.get(sharesPerSlice);

        // Intention 1: Strategy A (Mathematical 1:2 Scalp)
        double takeProfitPriceA = entryPrice + (dailyImpliedMove * 1.0);
        sliceIntents.add(new BracketOrderExecutor.SliceIntent(sliceQuantity, takeProfitPriceA, stopLossPrice, timeExitValue));

        // Intention 2: Strategy B (VWAP Target Runner)
        double takeProfitPriceB = market.dailyVWAP();
        sliceIntents.add(new BracketOrderExecutor.SliceIntent(sliceQuantity, takeProfitPriceB, stopLossPrice, timeExitValue));

        return sliceIntents;
    }
    
    private Decimal calculateTotalQuantity(
            MarketSnapshot market, double entryPrice, double stopLossPrice) {
        double riskPerShare = entryPrice - stopLossPrice;
        if (riskPerShare <= 0) return Decimal.ZERO;

        Account account = blackboard.getAccount();
        // Both figures default to zero before IBKR sends its first account update.
        // Sizing off a default would silently produce no shares, which looks
        // identical to a strategy that simply found no opportunity.
        double netLiquidation = account.getNetLiquidation();
        double availableFunds = account.getAvailableFunds();
        if (!(netLiquidation > 0.0) || !(availableFunds > 0.0)) {
            logger.warn("[{}] No usable account state yet (net liquidation {}, available funds {}); "
                    + "sizing is impossible", market.ticker(), netLiquidation, availableFunds);
            return Decimal.ZERO;
        }

        // 0.25% of net liquidation is risked per trade. Deliberately hardcoded
        // here rather than shared or configured: each strategy owns its own risk
        // budget so a change to one can never move another's.
        double portfolioRiskAmount = netLiquidation * 0.0025;
        double idealShareCount = Math.floor(portfolioRiskAmount / riskPerShare);
        Decimal idealQuantity = Decimal.get(idealShareCount);
        
        double marginRequirement = market.marginRequirement("BUY", idealQuantity, entryPrice);
        double actualAvailableCash = availableFunds;

        if (marginRequirement > actualAvailableCash) {
            double marginRate = market.longMarginRate();
            double affordableShares = Math.floor(actualAvailableCash / (entryPrice * marginRate));
            if (affordableShares <= 0) return Decimal.ZERO;
            return Decimal.get(affordableShares);
        }
        return idealQuantity;
    }
    
    /**
     * Decides whether a ticker is inside its earnings blackout.
     *
     * <p>
     * Measured from today outwards rather than from the report date, so the
     * session walk only touches days adjacent to the current session. The
     * proxy's holiday feed is forward-looking, so dates near today are the ones
     * it can answer for.
     *
     * <p>
     * Fails closed. No earnings date, or no calendar to measure sessions with,
     * blocks the entry: an unknown report date is not evidence of its absence.
     */
    private boolean isInEarningsBlackout(String ticker, LocalDate tradingDate) {
        Optional<EarningsSnapshot.EarningsDate> earnings =
                earningsStore.earningsDate(ticker, tradingDate);
        if (earnings.isEmpty()) {
            return true;
        }

        // Both the upcoming report and the one the proxy retained are tested.
        // The retained date is what makes the day-after rule work at all: the
        // upstream source rolls some tickers to next quarter the moment they
        // report, leaving nothing to measure against.
        Optional<LocalDate> nextSession = marketCalendarStore.nextSession(tradingDate);
        Optional<LocalDate> previousSession =
                marketCalendarStore.previousSessionApproximate(tradingDate);
        if (nextSession.isEmpty() || previousSession.isEmpty()) {
            // Without adjacent sessions the window cannot be measured at all.
            return true;
        }

        for (LocalDate reportDate : earnings.get().knownDates()) {
            if (tradingDate.equals(reportDate)
                    || nextSession.get().equals(reportDate)
                    || previousSession.get().equals(reportDate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The time exit for a new position, a fixed margin before the session
     * close. Derived from the proxy's calendar rather than a fixed clock time,
     * so an early close pulls the exit forward with it.
     */
    protected long calculateTimeExit() {
        return marketCalendarStore.sessionClose(currentTradingDate())
                .map(close -> close.minus(EXIT_BEFORE_CLOSE).toEpochMilli())
                // Unreachable through the entry path, which already requires a
                // known close. Zero is rejected downstream rather than silently
                // becoming an epoch-dated exit.
                .orElse(0L);
    }
    
    @Override
    protected void evaluateTickStreamNeed(MarketSnapshot market, double entryPrice) {
        double lastPrice = market.lastPrice();
        String ticker = market.ticker();
        boolean isStreamActive = tickStreamController.isStreamActive(ticker);

        if (!isStreamActive && lastPrice <= entryPrice * 1.0025) {
            tickStreamController.tryRequestStream(ticker);
        } else if (isStreamActive && lastPrice > entryPrice * 1.0035) {
            tickStreamController.cancelStream(ticker);
        }
    }

    @Override
    protected void manageOpenPosition(Stock stock, MarketSnapshot market) {
        BracketOrder bracketOrder = stock.getActiveBracket();
        if (bracketOrder == null) return;

        List<BracketOrder.ExitSlice> slices = bracketOrder.getSlices();
        if (slices.size() < 2) return;

        double lastPrice = market.lastPrice();
        double entryPrice = bracketOrder.getEntryPrice();
        double currentVWAP = market.dailyVWAP();

        double executionCostBuffer = 0.02;
        double trueBreakEvenPrice = entryPrice + executionCostBuffer;

        // ---------------------------------------------------------
        // 1. GLOBAL RIPCORD CHECK (1.5% Deep Sweep Abort)
        // ---------------------------------------------------------
        double currentStopLossBase = slices.get(0).getStopLossPrice(); 
        double currentRisk = lastPrice - currentStopLossBase;
        double currentReward = currentVWAP - lastPrice;
        
        boolean ripcordPulled = (currentRisk > 0) && (currentReward < (currentRisk * 1.2));

        if (ripcordPulled) {
            logger.warn("[{}] Ripcord Pulled! Executing 1.5% Deep Sweep Abort.", market.ticker());
            
            // Calculate a marketable limit price 1.5% below current market to ensure a fill
            double deepSweepPrice = lastPrice * 0.985; 
            deepSweepPrice = Math.round(deepSweepPrice * 100.0) / 100.0;
            
            for (BracketOrder.ExitSlice slice : slices) {
                if (!slice.isFilled()) {
                    updateExits(stock, bracketOrder, slice, deepSweepPrice, deepSweepPrice, slice.getTimeExit());
                }
            }
            
            /*
            blackboard.banTickerForDay(market.ticker());
            */
            
            return;
        }

        // ---------------------------------------------------------
        // 1b. IMPLIED-MOVE DEPENDENT MANAGEMENT
        // The ripcord above needs no proxy data, so a dead proxy can never
        // trap a position. Everything below is priced off the move that was
        // in force when the position opened, retained by the store.
        // ---------------------------------------------------------
        OptionalDouble retainedImpliedMove =
                optionsIndicatorStore.lastKnownImpliedMove(market.ticker());
        if (retainedImpliedMove.isEmpty()) {
            logger.warn("[{}] No Static Daily Implied Move is retained; leaving the existing "
                    + "protective exits untouched", market.ticker());
            return;
        }
        double dailyImpliedMove = retainedImpliedMove.getAsDouble();

        // ---------------------------------------------------------
        // 2. BI-DIRECTIONAL RISK-FREE TRIGGER
        // ---------------------------------------------------------
        BracketOrder.ExitSlice sliceA = slices.get(0);
        BracketOrder.ExitSlice sliceB = slices.get(1);

        boolean eitherFilled = sliceA.isFilled() || sliceB.isFilled();

        // ---------------------------------------------------------
        // SLICE 1: Strategy A Management
        // ---------------------------------------------------------
        if (!sliceA.isFilled()) {
            double currentStopLossA = sliceA.getStopLossPrice();
            double breakEvenTriggerA = entryPrice + (dailyImpliedMove * 0.5);
            double nextStopLossA = currentStopLossA;

            if ((eitherFilled || lastPrice >= breakEvenTriggerA) && currentStopLossA < trueBreakEvenPrice) {
                nextStopLossA = trueBreakEvenPrice;
            }

            if (nextStopLossA != currentStopLossA) {
                updateExits(stock, bracketOrder, sliceA, sliceA.getTakeProfitPrice(), nextStopLossA, sliceA.getTimeExit());
            }
        }

        // ---------------------------------------------------------
        // SLICE 2: Strategy B Management (VWAP Trailing)
        // ---------------------------------------------------------
        if (!sliceB.isFilled()) {
            double currentStopLossB = sliceB.getStopLossPrice();
            double currentTakeProfitB = sliceB.getTakeProfitPrice();
            
            double breakEvenTriggerB = entryPrice + dailyImpliedMove;
            double nextStopLossB = currentStopLossB;

            if ((eitherFilled || lastPrice >= breakEvenTriggerB) && currentStopLossB < trueBreakEvenPrice) {
                nextStopLossB = trueBreakEvenPrice;
            }

            int volumeWeightedAveragePriceAdjustmentsMade = bracketOrder.getUpdateCount() - 7; 
            double floorPrice = market.previousClose() - (dailyImpliedMove * 1.2);
            boolean targetNeedsUpdate = false;
            double nextTakeProfitB = currentTakeProfitB;

            long currentTimeMilliseconds = System.currentTimeMillis();
            long cooldownMilliseconds = 15000; 

            if (currentTimeMilliseconds - sliceB.getLastModificationTime() > cooldownMilliseconds) {
                if (currentVWAP <= currentTakeProfitB && volumeWeightedAveragePriceAdjustmentsMade < 25 && currentTakeProfitB > floorPrice) {
                    int remainingSteps = 25 - volumeWeightedAveragePriceAdjustmentsMade;
                    double distanceToFloor = currentTakeProfitB - floorPrice;
                    double stepSize = distanceToFloor / (double) remainingSteps;

                    nextTakeProfitB = Math.round((currentTakeProfitB - stepSize) * 100.0) / 100.0;
                    if (nextTakeProfitB < floorPrice) {
                        nextTakeProfitB = Math.round(floorPrice * 100.0) / 100.0;
                    }
                    targetNeedsUpdate = true;
                }
            }

            if (targetNeedsUpdate || currentStopLossB != nextStopLossB) {
                updateExits(stock, bracketOrder, sliceB, nextTakeProfitB, nextStopLossB, sliceB.getTimeExit());
                sliceB.setLastModificationTime(currentTimeMilliseconds);
            }
        }
    }
    
    @Override
    protected String getStrategyName() {
        return STRATEGY_ID;
    }

    @Override
    protected TradeDirection getTradeDirection() {
        return TradeDirection.LONG;
    }
}
