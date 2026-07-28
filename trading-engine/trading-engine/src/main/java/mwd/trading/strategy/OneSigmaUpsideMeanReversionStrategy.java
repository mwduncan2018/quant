package mwd.trading.strategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ib.client.Decimal;

import mwd.trading.calendar.MarketCalendarStore;
import mwd.trading.config.Config;
import mwd.trading.domain.Account;
import mwd.trading.domain.Stock;
import mwd.trading.domain.TradeDirection;
import mwd.trading.execution.BracketOrder;
import mwd.trading.execution.BracketOrderExecutor;
import mwd.trading.execution.BracketOrderGateway;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.marketdata.MarketDataFreshness;
import mwd.trading.marketdata.MarketDataInput;
import mwd.trading.marketdata.TickStreamController;
import mwd.trading.optionsproxy.OptionsIndicatorStore;
import mwd.trading.state.Blackboard;

/**
 * The short mirror of {@link OneSigmaDownsideMeanReversionStrategy}, built to
 * exercise the order path in PAPER rather than to make money.
 *
 * <p>
 * It shorts whenever a ticker trades at or above one implied move over its
 * previous close, with no confirmation filters: no volume climax, no wick
 * rejection, no SPY gamma-flip check and no earnings blackout. It will short
 * strength that keeps going, which is exactly what a real short strategy would
 * filter for. **Never run this on a live account.**
 *
 * <p>
 * Deliberately implemented standalone rather than sharing a base class with the
 * downside strategy. The two look alike today, but a change made for one must
 * never silently alter the other's behaviour.
 *
 * <table>
 * <caption>Rules</caption>
 * <tr><td>Entry</td><td>limit at previous close plus one implied move, when
 * price is already at or above it</td></tr>
 * <tr><td>Take profit</td><td>the daily VWAP, tracked while the position is
 * open</td></tr>
 * <tr><td>Stop loss</td><td>previous close plus 1.25 implied moves</td></tr>
 * <tr><td>Time exit</td><td>two hours after submission, or fifteen minutes
 * before the close, whichever comes first</td></tr>
 * <tr><td>Vetoes</td><td>VWAP already above +0.75 moves; inside the last hour
 * of the session; within the re-entry cooldown</td></tr>
 * </table>
 */
public class OneSigmaUpsideMeanReversionStrategy extends AbstractStrategy {
    public static final String STRATEGY_ID = "ONE_SIGMA_UPSIDE";
    private static final ZoneId NEW_YORK_ZONE = ZoneId.of("America/New_York");

    /** Entry sits one implied move above the previous close. */
    private static final double ENTRY_MOVE_MULTIPLE = 1.0;

    /** The stop sits a quarter move further up, so risk is 0.25 moves. */
    private static final double STOP_MOVE_MULTIPLE = 1.25;

    /**
     * VWAP must be no higher than this, which leaves reward at least equal to
     * the 0.25-move risk. Anything worse than one-to-one is skipped.
     */
    private static final double MAXIMUM_VWAP_MOVE_MULTIPLE = 0.75;

    /** Maximum hold, measured from submission rather than fill. */
    private static final Duration MAX_HOLD = Duration.ofHours(2);

    /** No new position may open inside this window before the close. */
    private static final Duration NO_ENTRY_BEFORE_CLOSE = Duration.ofMinutes(60);

    /** Every position carries a time exit at least this far before the close. */
    private static final Duration EXIT_BEFORE_CLOSE = Duration.ofMinutes(15);

    /**
     * How long a ticker rests after an exit before it may be shorted again.
     * Without it a ticker parked above the entry level round-trips
     * continuously, which is churn rather than verification.
     */
    private static final Duration REENTRY_COOLDOWN = Duration.ofMinutes(15);

    /**
     * Take-profit tracking is bounded to protect the Order Efficiency Ratio,
     * which IBKR expects to stay near 20 or below across submissions,
     * modifications and cancellations divided by executions.
     *
     * <p>
     * One slice means a completed round trip is three messages to place and two
     * executions. Ten modifications puts the worst case near 6.5.
     */
    private static final int MAX_TAKE_PROFIT_UPDATES = 10;

    /** Minimum gap between take-profit updates. */
    private static final Duration TAKE_PROFIT_UPDATE_INTERVAL = Duration.ofSeconds(60);

    /** VWAP must move at least this far before a message is worth spending. */
    private static final double TAKE_PROFIT_UPDATE_THRESHOLD = 0.05;

    private final OptionsIndicatorStore optionsIndicatorStore;
    private final MarketCalendarStore marketCalendarStore;
    private final Clock newYorkClock;

    /** Per-ticker exit instants, for the re-entry cooldown. */
    private final Map<String, Instant> lastExitByTicker = new ConcurrentHashMap<>();

    /** Per-ticker take-profit update counts and instants, for the OER budget. */
    private final Map<String, Integer> takeProfitUpdates = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastTakeProfitUpdate = new ConcurrentHashMap<>();

    public OneSigmaUpsideMeanReversionStrategy(
            Blackboard blackboard,
            BracketOrderGateway bracketOrderGateway,
            TickStreamController tickStreamController,
            Config config,
            TradingGate tradingGate,
            MarketDataFreshness marketDataFreshness,
            OptionsIndicatorStore optionsIndicatorStore,
            MarketCalendarStore marketCalendarStore) {
        this(blackboard, bracketOrderGateway, tickStreamController, config, tradingGate,
                marketDataFreshness, optionsIndicatorStore, marketCalendarStore,
                Clock.systemUTC());
    }

    OneSigmaUpsideMeanReversionStrategy(
            Blackboard blackboard,
            BracketOrderGateway bracketOrderGateway,
            TickStreamController tickStreamController,
            Config config,
            TradingGate tradingGate,
            MarketDataFreshness marketDataFreshness,
            OptionsIndicatorStore optionsIndicatorStore,
            MarketCalendarStore marketCalendarStore,
            Clock clock) {
        super(blackboard, bracketOrderGateway, tickStreamController, config, tradingGate,
                marketDataFreshness, config.getStrategyUniverse(STRATEGY_ID), clock);
        this.optionsIndicatorStore = Objects.requireNonNull(
                optionsIndicatorStore, "optionsIndicatorStore");
        this.marketCalendarStore = Objects.requireNonNull(
                marketCalendarStore, "marketCalendarStore");
        this.newYorkClock = Objects.requireNonNull(clock, "clock").withZone(NEW_YORK_ZONE);
    }

    private LocalDate currentTradingDate() {
        return LocalDate.now(newYorkClock);
    }

    /**
     * Only the inputs this strategy actually reads. No gamma flip and no volume
     * baseline, so it can trade from the opening bell.
     */
    @Override
    protected Set<MarketDataInput> requiredEntryInputs() {
        return Set.of(
                MarketDataInput.LAST_PRICE,
                MarketDataInput.PREVIOUS_CLOSE,
                MarketDataInput.DAILY_VWAP);
    }

    @Override
    protected Set<MarketDataInput> requiredManagementInputs() {
        return Set.of(MarketDataInput.LAST_PRICE, MarketDataInput.DAILY_VWAP);
    }

    @Override
    protected boolean isEntryConditionMet(Stock stock) {
        // A short is sized against the short margin rate, so the SELL what-if
        // is what must have been priced. The pacer requests BUY first, so the
        // long side is verified a pacing interval earlier.
        if (!stock.isShortMarginRateVerified()) {
            return false;
        }

        LocalDate tradingDate = currentTradingDate();
        Instant now = newYorkClock.instant();

        if (marketCalendarStore.sessionClose(tradingDate).isEmpty()
                || marketCalendarStore.isWithinOfClose(tradingDate, now, NO_ENTRY_BEFORE_CLOSE)) {
            return false;
        }

        Instant lastExit = lastExitByTicker.get(stock.getTicker());
        if (lastExit != null && now.isBefore(lastExit.plus(REENTRY_COOLDOWN))) {
            return false;
        }

        OptionalDouble impliedMove = optionsIndicatorStore.impliedMoveForNewEntry(
                stock.getTicker(), tradingDate, newYorkClock.millis());
        if (impliedMove.isEmpty()) {
            return false;
        }
        double move = impliedMove.getAsDouble();
        double previousClose = stock.getPreviousClose();

        // Price must already be at or above the entry level; this strategy does
        // not rest an order and wait for the market to come to it.
        double entryLevel = previousClose + (move * ENTRY_MOVE_MULTIPLE);
        if (stock.getLastPrice() < entryLevel) {
            return false;
        }

        // Covering at VWAP must leave reward at least equal to risk.
        double maximumVwap = previousClose + (move * MAXIMUM_VWAP_MOVE_MULTIPLE);
        return stock.getDailyVWAP() <= maximumVwap;
    }

    @Override
    protected double calculateEntryPrice(Stock stock) {
        OptionalDouble impliedMove =
                optionsIndicatorStore.lastKnownImpliedMove(stock.getTicker());
        if (impliedMove.isEmpty()) {
            // NaN fails TradeDirection.acceptsEntryPrice, rolling back cleanly.
            return Double.NaN;
        }
        return stock.getPreviousClose() + (impliedMove.getAsDouble() * ENTRY_MOVE_MULTIPLE);
    }

    @Override
    protected List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(
            Stock stock, double entryPrice) {
        List<BracketOrderExecutor.SliceIntent> sliceIntents = new ArrayList<>();
        OptionalDouble impliedMove =
                optionsIndicatorStore.lastKnownImpliedMove(stock.getTicker());
        if (impliedMove.isEmpty()) {
            return sliceIntents;
        }
        double move = impliedMove.getAsDouble();

        // The stop sits above a short entry, which inverts the risk arithmetic.
        double stopLossPrice = stock.getPreviousClose() + (move * STOP_MOVE_MULTIPLE);
        long timeExitValue = calculateTimeExit();
        if (timeExitValue <= 0L) {
            return sliceIntents;
        }

        Decimal totalQuantity = calculateTotalQuantity(stock, entryPrice, stopLossPrice);
        if (totalQuantity.compareTo(Decimal.ZERO) <= 0) {
            return sliceIntents;
        }

        // One slice: a single take-profit target means there is nothing to split.
        sliceIntents.add(new BracketOrderExecutor.SliceIntent(
                totalQuantity, stock.getDailyVWAP(), stopLossPrice, timeExitValue));
        return sliceIntents;
    }

    /**
     * Two hours from submission, or the session's fixed margin before the
     * close, whichever comes first. Measured from submission because the exit
     * is stamped onto the order before it is sent.
     */
    protected long calculateTimeExit() {
        return marketCalendarStore.sessionClose(currentTradingDate())
                .map(close -> Math.min(
                        newYorkClock.instant().plus(MAX_HOLD).toEpochMilli(),
                        close.minus(EXIT_BEFORE_CLOSE).toEpochMilli()))
                .orElse(0L);
    }

    private Decimal calculateTotalQuantity(Stock stock, double entryPrice, double stopLossPrice) {
        // Inverted against the long strategy: the stop is above the entry.
        double riskPerShare = stopLossPrice - entryPrice;
        if (riskPerShare <= 0) {
            return Decimal.ZERO;
        }

        Account account = blackboard.getAccount();
        double netLiquidation = account.getNetLiquidation();
        double availableFunds = account.getAvailableFunds();
        if (!(netLiquidation > 0.0) || !(availableFunds > 0.0)) {
            logger.warn("[{}] No usable account state yet (net liquidation {}, available funds {}); "
                    + "sizing is impossible", stock.getTicker(), netLiquidation, availableFunds);
            return Decimal.ZERO;
        }

        double portfolioRiskAmount = netLiquidation * 0.0075;
        double idealShareCount = Math.floor(portfolioRiskAmount / riskPerShare);
        if (idealShareCount <= 0) {
            return Decimal.ZERO;
        }
        Decimal idealQuantity = Decimal.get(idealShareCount);

        double marginRequirement =
                stock.calculateMarginRequirement("SELL", idealQuantity, entryPrice);
        if (marginRequirement > availableFunds) {
            double marginRate = stock.getShortMarginRate();
            if (marginRate <= 0.0) {
                return Decimal.ZERO;
            }
            double affordableShares = Math.floor(availableFunds / (entryPrice * marginRate));
            return affordableShares > 0 ? Decimal.get(affordableShares) : Decimal.ZERO;
        }
        return idealQuantity;
    }

    @Override
    protected void evaluateTickStreamNeed(Stock stock, double entryPrice) {
        // No tick-by-tick stream. Entries are taken at the prevailing price
        // rather than worked, and the streams are a limited account resource.
    }

    @Override
    protected void manageOpenPosition(Stock stock) {
        BracketOrder bracketOrder = stock.getActiveBracket();
        if (bracketOrder == null || bracketOrder.getSlices().isEmpty()) {
            return;
        }
        BracketOrder.ExitSlice slice = bracketOrder.getSlices().get(0);
        if (slice.isFilled()) {
            return;
        }

        String ticker = stock.getTicker();
        double vwap = stock.getDailyVWAP();
        if (!(vwap > 0.0)) {
            return;
        }

        // The target follows VWAP in both directions with no floor. Once VWAP
        // rises above the entry the buy-to-cover limit becomes marketable and
        // the position exits at a loss; that is still an execution.
        if (Math.abs(vwap - slice.getTakeProfitPrice()) < TAKE_PROFIT_UPDATE_THRESHOLD) {
            return;
        }

        Instant now = newYorkClock.instant();
        Instant last = lastTakeProfitUpdate.get(ticker);
        if (last != null && now.isBefore(last.plus(TAKE_PROFIT_UPDATE_INTERVAL))) {
            return;
        }
        int used = takeProfitUpdates.getOrDefault(ticker, 0);
        if (used >= MAX_TAKE_PROFIT_UPDATES) {
            return;
        }

        takeProfitUpdates.put(ticker, used + 1);
        lastTakeProfitUpdate.put(ticker, now);
        updateExits(stock, bracketOrder, slice, vwap, slice.getStopLossPrice(), slice.getTimeExit());
    }

    /** Starts the re-entry cooldown and releases this position's message budget. */
    @Override
    protected void onPositionClosed(Stock stock) {
        String ticker = stock.getTicker();
        lastExitByTicker.put(ticker, newYorkClock.instant());
        takeProfitUpdates.remove(ticker);
        lastTakeProfitUpdate.remove(ticker);
    }

    @Override
    protected String getStrategyName() {
        return STRATEGY_ID;
    }

    @Override
    protected TradeDirection getTradeDirection() {
        return TradeDirection.SHORT;
    }
}
