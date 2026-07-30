package mwd.trading.strategy;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Decimal;

import mwd.trading.config.Config;
import mwd.trading.domain.Stock;
import mwd.trading.domain.TradeDirection;
import mwd.trading.execution.BracketOrderExecutor;
import mwd.trading.execution.BracketOrder;
import mwd.trading.execution.BracketOrderGateway;
import mwd.trading.execution.UncertainOrderSubmissionException;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.marketdata.MarketDataFreshness;
import mwd.trading.marketdata.MarketDataInput;
import mwd.trading.marketdata.MarketSnapshot;
import mwd.trading.marketdata.TickStreamController;
import mwd.trading.state.StrategyBlackboard;

public abstract class AbstractStrategy implements Runnable {
    private record PendingEntry(long submittedAtMillis) {}

    protected final Logger logger = LogManager.getLogger(getClass());
    protected final StrategyBlackboard blackboard;
    protected final BracketOrderGateway bracketOrderGateway;
    protected final TickStreamController tickStreamController;
    protected final Config config;
    protected final TradingGate tradingGate;
    protected final MarketDataFreshness marketDataFreshness;

    private final EntryAdmission entryAdmission;
    private final Set<String> universe;
    private final Clock clock;
    private final ConcurrentMap<String, PendingEntry> pendingEntries = new ConcurrentHashMap<>();
    private final Set<String> escalatedPendingEntries = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, String> lastUnreadyReason = new ConcurrentHashMap<>();
    /**
     * The broker status this strategy has already reacted to, per ticker.
     *
     * <p>
     * The lifecycle state is derived now, so the lag between the reader thread
     * updating a bracket and this strategy noticing no longer exists to serve as
     * an unread marker. Some work has to happen exactly once per transition -
     * releasing the engine-wide lock, escalating a partial fill - and on every
     * later poll the derived state looks identical. This records what has been
     * acted on, making the edge explicit instead of implicit in a timing gap.
     */
    private final ConcurrentMap<String, BracketOrder.Status> acknowledgedStatus =
            new ConcurrentHashMap<>();

    protected AbstractStrategy(
            StrategyBlackboard blackboard,
            BracketOrderGateway bracketOrderGateway,
            TickStreamController tickStreamController,
            Config config,
            TradingGate tradingGate,
            MarketDataFreshness marketDataFreshness,
            Set<String> universe) {
        this(
                blackboard,
                bracketOrderGateway,
                tickStreamController,
                config,
                tradingGate,
                marketDataFreshness,
                universe,
                Clock.systemUTC());
    }

    protected AbstractStrategy(
            StrategyBlackboard blackboard,
            BracketOrderGateway bracketOrderGateway,
            TickStreamController tickStreamController,
            Config config,
            TradingGate tradingGate,
            MarketDataFreshness marketDataFreshness,
            Set<String> universe,
            Clock clock) {
        this.blackboard = Objects.requireNonNull(blackboard, "blackboard");
        this.bracketOrderGateway = Objects.requireNonNull(bracketOrderGateway, "bracketOrderGateway");
        this.tickStreamController = Objects.requireNonNull(tickStreamController, "tickStreamController");
        this.config = Objects.requireNonNull(config, "config");
        this.tradingGate = Objects.requireNonNull(tradingGate, "tradingGate");
        this.marketDataFreshness = Objects.requireNonNull(marketDataFreshness, "marketDataFreshness");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.entryAdmission = new EntryAdmission(this.blackboard);

        Set<String> normalizedUniverse = new LinkedHashSet<>();
        for (String ticker : Objects.requireNonNull(universe, "universe")) {
            if (ticker != null && !ticker.isBlank()) {
                normalizedUniverse.add(ticker.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (normalizedUniverse.isEmpty()) {
            throw new IllegalArgumentException("A strategy universe must contain at least one ticker");
        }
        this.universe = Set.copyOf(normalizedUniverse);
    }

    @Override
    public final void run() {
        while (!Thread.currentThread().isInterrupted()) {
            runOneCycle();
            try {
                Thread.sleep(config.getStrategyPollRateMs());
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    final void runOneCycle() {
        for (String ticker : universe) {
            processSymbolSafely(blackboard.getStock(ticker));
        }
    }

    protected final void executeLifecycle(Stock stock) {
        String strategyId = strategyId();
        String ticker = stock.getTicker();
        String owner = blackboard.getPositionOwner(ticker);

        switch (stock.positionState(owner != null)) {
            case FLAT -> {
                if (owner != null) {
                    if (strategyId.equals(owner)) {
                        handleFlatWithLocalOwnership(stock);
                    }
                    return;
                }
                evaluateNewEntry(stock, strategyId);
            }
            case PENDING -> {
                if (strategyId.equals(owner)) {
                    acknowledgeStatusChange(stock, strategyId);
                    handlePendingEntry(stock);
                } else if (owner == null) {
                    escalate(stock, "Pending position has no strategy owner");
                }
            }
            case OPEN -> {
                if (!strategyId.equals(owner)) {
                    if (owner == null) {
                        escalate(stock, "Open position has no strategy owner");
                    }
                    return;
                }
                acknowledgeStatusChange(stock, strategyId);
                if (automatedOrderChangesAllowed(stock)) {
                    manageOpenPosition(stock, snapshot(stock));
                }
            }
        }
    }

    /**
     * Runs the once-per-transition work for whatever the broker last reported.
     *
     * <p>
     * Every branch here used to sit inside {@code handlePendingEntry}, fired by
     * observing a status while the stored state still read {@code PENDING}. With
     * the state derived, {@code POSITION_OPEN} reads as {@code OPEN} on the very
     * first poll and that branch would never be entered, so the work moved here
     * and is edge-triggered explicitly against the last status acted on.
     */
    private void acknowledgeStatusChange(Stock stock, String strategyId) {
        BracketOrder bracketOrder = stock.getActiveBracket();
        String ticker = stock.getTicker();
        BracketOrder.Status status = bracketOrder == null ? null : bracketOrder.getStatus();

        if (status == acknowledgedStatus.get(ticker)) {
            return;
        }
        if (status == null) {
            acknowledgedStatus.remove(ticker);
            return;
        }
        acknowledgedStatus.put(ticker, status);

        switch (status) {
            case WORKING_PARENT ->
                // IBKR has acknowledged the order and it is live at the exchange,
                // which is the confirmation the serialization lock waits for. It is
                // released here so a resting limit cannot park every strategy;
                // there is no timeout on this state.
                //
                // Ownership of the ticker is deliberately NOT released. The
                // position stays reserved and keeps counting against
                // MAX_ACTIVE_POSITIONS until the order fills or terminates.
                blackboard.releaseGlobalPending(strategyId, ticker);
            case PARTIAL_PARENT -> {
                blackboard.releaseGlobalPending(strategyId, ticker);
                escalate(stock, "Entry received a partial fill; verify position and protective exits");
            }
            case POSITION_OPEN -> {
                blackboard.releaseGlobalPending(strategyId, ticker);
                pendingEntries.remove(ticker);
                escalatedPendingEntries.remove(ticker);
            }
            case CANCELLED, REJECTED -> {
                if (!isZero(bracketOrder.getFilledQuantity())) {
                    blackboard.releaseGlobalPending(strategyId, ticker);
                    escalate(stock, "Terminal parent status followed a fill; verify the live position");
                }
                // A clean terminal derives FLAT, so cleanup runs through
                // handleFlatWithLocalOwnership on this same cycle.
            }
            case INITIALIZED, FILLED -> {
                // INITIALIZED is the status the entry was admitted in, and FILLED
                // derives FLAT; neither carries once-only work of its own.
            }
        }
    }

    private void evaluateNewEntry(Stock stock, String strategyId) {
        // Checked before the serialization lock is taken, not inside it. A stale
        // account would otherwise make every strategy acquire and release the
        // lock on each poll for no purpose.
        if (!tradingGate.allowsNewEntries()
                || !stock.isTradeable()
                || !blackboard.isAccountCurrentForNewEntry()
                || !entryInputsReady(stock)) {
            return;
        }

        // Screening only. This view is thrown away if it survives to the lock,
        // because by then the tape has moved and the decision has to be made
        // again on what is true at that point.
        MarketSnapshot screening = snapshot(stock);
        if (!isEntryConditionMet(screening)) {
            return;
        }

        double entryPrice = calculateEntryPrice(screening);
        evaluateTickStreamNeed(screening, entryPrice);
        if (!tradeDirection().acceptsEntryPrice(screening.lastPrice(), entryPrice)) {
            return;
        }
        // Everything above is advisory: it can be recomputed freely because
        // nothing has been claimed yet. From here the entry holds engine-wide
        // state, so every exit path must resolve the reservation exactly once.
        // Closing without keeping releases, so a path nobody considered frees
        // the lock instead of parking every strategy.
        try (EntryAdmission.Reservation reservation = entryAdmission.tryAdmit(strategyId, stock)) {
            if (reservation == null) {
                return;
            }

            if (!tradingGate.allowsNewEntries()
                    || !marketDataFreshness.areAllFresh(
                            stock.getTicker(), requiredEntryInputs())) {
                rollbackEntryReservation(stock, reservation);
                return;
            }

            // Taken after the lock, and the only view the order is built from.
            // The entry test, the limit price, and the exit levels all read it,
            // so what the gate proved about this data still holds of the order
            // that carries it.
            MarketSnapshot market = snapshot(stock);
            if (!isEntryConditionMet(market)) {
                rollbackEntryReservation(stock, reservation);
                return;
            }

            entryPrice = calculateEntryPrice(market);
            if (!tradeDirection().acceptsEntryPrice(market.lastPrice(), entryPrice)) {
                rollbackEntryReservation(stock, reservation);
                return;
            }

            List<BracketOrderExecutor.SliceIntent> sliceIntents =
                    calculateSliceIntents(market, entryPrice);
            Decimal totalQuantity = totalQuantity(sliceIntents);
            if (totalQuantity.compareTo(Decimal.ZERO) <= 0) {
                rollbackEntryReservation(stock, reservation);
                return;
            }

            try {
                pendingEntries.put(stock.getTicker(), new PendingEntry(clock.millis()));
                // Recorded before the send. If the submission outcome is uncertain
                // the order may still have reached IBKR, so the next entry must
                // wait for a fresh account snapshot either way.
                blackboard.recordEntrySubmitted(clock.millis());
                BracketOrder bracketOrder = bracketOrderGateway.placeTripleThreat(
                        strategyId,
                        tradeDirection(),
                        stock.getTicker(),
                        totalQuantity,
                        entryPrice,
                        sliceIntents);
                if (bracketOrder == null || stock.getActiveBracket() != bracketOrder) {
                    throw new IllegalStateException(
                            "Order gateway did not install the returned bracket");
                }
                // The order is away. Ownership passes to the pending-entry
                // lifecycle, which releases the engine-wide lock on
                // acknowledgement and the ticker when the trade resolves.
                reservation.keep();
            } catch (UncertainOrderSubmissionException exception) {
                // The order may be live at IBKR. Giving the reservation back
                // would let a second entry size against a position that exists.
                reservation.keep();
                escalate(stock, "Entry submission is unresolved and requires broker reconciliation");
                throw exception;
            } catch (RuntimeException exception) {
                if (stock.getActiveBracket() == null) {
                    rollbackEntryReservation(stock, reservation);
                } else {
                    reservation.keep();
                    escalate(stock, "Entry submission outcome is unresolved");
                }
                throw exception;
            }
        }
    }

    /**
     * The time-driven half of a pending entry. Status-driven work is
     * edge-triggered in {@link #acknowledgeStatusChange}; what is left here has to
     * be re-evaluated every poll because it depends on the clock rather than on
     * anything the broker sent.
     */
    private void handlePendingEntry(Stock stock) {
        PendingEntry pendingEntry = pendingEntries.get(stock.getTicker());
        boolean timedOut = pendingEntry == null
                || acknowledgementTimedOut(pendingEntry.submittedAtMillis());
        if (!timedOut) {
            return;
        }

        BracketOrder bracketOrder = stock.getActiveBracket();
        if (bracketOrder == null) {
            escalate(stock, "Pending entry has no local bracket and cannot be resolved safely");
        } else if (bracketOrder.getStatus() == BracketOrder.Status.INITIALIZED) {
            escalate(stock, "IBKR did not acknowledge the entry before the configured timeout");
        }
    }

    private void handleFlatWithLocalOwnership(Stock stock) {
        BracketOrder bracketOrder = stock.getActiveBracket();
        if (bracketOrder == null || isConfirmedTerminal(bracketOrder.getStatus())) {
            cleanupOwnedLifecycle(stock, strategyId(), bracketOrder);
        } else {
            escalate(stock, "Stock is FLAT while its local bracket is still non-terminal");
        }
    }

    private void cleanupOwnedLifecycle(
            Stock stock, String strategyId, BracketOrder bracketOrder) {
        onPositionClosed(stock);
        blackboard.releaseGlobalPending(strategyId, stock.getTicker());
        blackboard.releasePosition(stock.getTicker(), strategyId);
        pendingEntries.remove(stock.getTicker());
        escalatedPendingEntries.remove(stock.getTicker());
        acknowledgedStatus.remove(stock.getTicker());
        if (tickStreamController.isStreamActive(stock.getTicker())) {
            tickStreamController.cancelStream(stock.getTicker());
        }
        if (bracketOrder == null || isConfirmedTerminal(bracketOrder.getStatus())) {
            stock.setActiveBracket(null);
        }
    }

    /**
     * Gives an admitted entry back. The three engine-wide holdings are unwound by
     * the reservation; what remains here is this strategy's own bookkeeping.
     */
    private void rollbackEntryReservation(Stock stock, EntryAdmission.Reservation reservation) {
        reservation.release();
        pendingEntries.remove(stock.getTicker());
        escalatedPendingEntries.remove(stock.getTicker());
        acknowledgedStatus.remove(stock.getTicker());
        if (tickStreamController.isStreamActive(stock.getTicker())) {
            tickStreamController.cancelStream(stock.getTicker());
        }
    }

    private void processSymbolSafely(Stock stock) {
        try {
            executeLifecycle(stock);
        } catch (RuntimeException exception) {
            stock.setTradeable(false);
            if (blackboard.getPositionOwner(stock.getTicker()) != null
                    || stock.getActiveBracket() != null) {
                escalate(stock, "Strategy failure while a trade lifecycle is active");
            }
            logger.error("[{}] Strategy cycle failed; this symbol has been disabled",
                    stock.getTicker(), exception);
        }
    }

    protected final void updateExits(
            Stock stock,
            BracketOrder bracketOrder,
            BracketOrder.ExitSlice exitSlice,
            double takeProfitPrice,
            double stopLossPrice,
            long timeExitValue) {
        if (stock.positionState(true) != Stock.PositionState.OPEN
                || !blackboard.isPositionOwnedBy(stock.getTicker(), strategyId())
                || !automatedOrderChangesAllowed(stock)) {
            return;
        }
        bracketOrderGateway.updateTripleThreatExits(
                stock,
                bracketOrder,
                exitSlice,
                takeProfitPrice,
                stopLossPrice,
                timeExitValue);
    }

    public final Set<String> getUniverse() {
        return universe;
    }

    /**
     * Whether the inputs needed to open a position are all usable, logging only
     * when the answer changes. A strategy that quietly stops trading is
     * indistinguishable from a broken one, but logging every poll would bury the
     * transition that matters.
     */
    /** One coherent read of everything a decision about this symbol needs. */
    protected final MarketSnapshot snapshot(Stock stock) {
        return MarketSnapshot.of(stock, clock.millis());
    }

    /**
     * The same for a symbol this strategy watches but does not trade, so a
     * reference reading is as coherent as the traded one.
     */
    protected final MarketSnapshot snapshot(String ticker) {
        return snapshot(blackboard.getStock(ticker));
    }

    private boolean entryInputsReady(Stock stock) {
        Optional<String> unready =
                marketDataFreshness.describeUnready(stock.getTicker(), requiredEntryInputs());
        String ticker = stock.getTicker();

        if (unready.isEmpty()) {
            if (lastUnreadyReason.remove(ticker) != null) {
                logger.info("[{}] Every required market-data input is ready; entries are live", ticker);
            }
            return true;
        }

        String reason = unready.get();
        if (!reason.equals(lastUnreadyReason.put(ticker, reason))) {
            logger.info("[{}] Entries are blocked on market data: {}", ticker, reason);
        }
        return false;
    }

    /**
     * Managing an open position needs its own input set. It is narrower than the
     * entry set, so a position is never stranded by an input it does not use, and
     * it is enforced rather than assumed: exits are repriced from live values, and
     * acting on a frozen one is worse than not acting at all. The resting broker
     * exits continue to protect the position while this is false.
     */
    private boolean automatedOrderChangesAllowed(Stock stock) {
        if (!tradingGate.allowsAutomatedOrderChanges()) {
            return false;
        }
        Optional<String> unready = marketDataFreshness.describeUnready(
                stock.getTicker(), requiredManagementInputs());
        if (unready.isEmpty()) {
            return true;
        }
        logger.warn("[{}] Leaving the resting exits untouched because management inputs "
                + "are not usable: {}", stock.getTicker(), unready.get());
        return false;
    }

    private boolean acknowledgementTimedOut(long submittedAtMillis) {
        return clock.millis() - submittedAtMillis >= config.getEntryAcknowledgementTimeoutMs();
    }

    private void escalate(Stock stock, String message) {
        String reason = "[" + strategyId() + "/" + stock.getTicker() + "] " + message;
        blackboard.setSystemHalted(true);
        if (escalatedPendingEntries.add(stock.getTicker())) {
            tradingGate.requireManualIntervention(reason);
            logger.error(reason);
        }
    }

    private String strategyId() {
        String strategyId = Objects.requireNonNull(getStrategyName(), "strategyName").trim();
        if (strategyId.isEmpty()) {
            throw new IllegalStateException("strategyName must not be blank");
        }
        return strategyId;
    }

    private TradeDirection tradeDirection() {
        return Objects.requireNonNull(getTradeDirection(), "tradeDirection");
    }

    private static Decimal totalQuantity(List<BracketOrderExecutor.SliceIntent> sliceIntents) {
        if (sliceIntents == null || sliceIntents.isEmpty()) {
            return Decimal.ZERO;
        }
        Decimal totalQuantity = Decimal.ZERO;
        for (BracketOrderExecutor.SliceIntent intent : sliceIntents) {
            if (intent == null
                    || intent.quantity == null
                    || intent.quantity.compareTo(Decimal.ZERO) <= 0) {
                return Decimal.ZERO;
            }
            totalQuantity = totalQuantity.add(intent.quantity);
        }
        return totalQuantity;
    }

    private static boolean isZero(Decimal quantity) {
        return quantity == null || quantity.isZero();
    }

    private static boolean isConfirmedTerminal(BracketOrder.Status status) {
        return status == BracketOrder.Status.FILLED
                || status == BracketOrder.Status.CANCELLED
                || status == BracketOrder.Status.REJECTED;
    }

    /**
     * Called once when a ticker this strategy owned returns to flat, before its
     * ownership is released. Subclasses override it to reset per-position state
     * such as a re-entry cooldown or a message budget.
     */
    protected void onPositionClosed(Stock stock) {
        // Most strategies keep no per-position state.
    }

    /**
     * The market-data inputs that must be usable before an entry is evaluated.
     * Declaring the set is what makes coverage checkable; a strategy that reads a
     * field it did not declare is reading a value nobody verified arrived.
     */
    protected abstract Set<MarketDataInput> requiredEntryInputs();

    /** The market-data inputs that must be usable before exits are repriced. */
    protected abstract Set<MarketDataInput> requiredManagementInputs();

    protected abstract boolean isEntryConditionMet(MarketSnapshot market);

    protected abstract double calculateEntryPrice(MarketSnapshot market);

    protected abstract List<BracketOrderExecutor.SliceIntent> calculateSliceIntents(
            MarketSnapshot market, double entryPrice);

    protected abstract void evaluateTickStreamNeed(MarketSnapshot market, double entryPrice);

    protected abstract void manageOpenPosition(Stock stock, MarketSnapshot market);

    protected abstract String getStrategyName();

    protected abstract TradeDirection getTradeDirection();
}
