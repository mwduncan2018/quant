package mwd.trading.strategy;

import java.util.Objects;

import mwd.trading.domain.Stock;
import mwd.trading.state.Blackboard;

/**
 * The three-step gate every new entry passes through, and the only place that
 * sequence is written.
 *
 * <p>
 * Opening a position requires acquiring three things in order, each of which can
 * fail independently:
 *
 * <ol>
 * <li>the <b>engine-wide pending lock</b>, so only one entry is outstanding at
 * IBKR at a time;
 * <li>the <b>per-ticker reservation</b>, which also enforces
 * {@code MAX_ACTIVE_POSITIONS};
 * <li>the <b>position state</b>, moved {@code FLAT -> PENDING} by compare-and-set.
 * </ol>
 *
 * <p>
 * Any of the three can lose a race with another strategy thread or with the IBKR
 * reader thread, and whatever was already taken has to be given back in reverse.
 * That unwind used to live inline in {@code evaluateNewEntry} as two booleans
 * and a {@code finally} block, interleaved with sizing and submission — the most
 * concurrency-sensitive code in the engine sitting in the middle of its most
 * ordinary. Here it is the whole class, and it is testable on its own.
 *
 * <p>
 * A successful {@link #tryAdmit} hands back a {@link Reservation} holding all
 * three. The caller then does exactly one of two things: {@link Reservation#keep()}
 * once the order is submitted and ownership passes to the pending-entry
 * lifecycle, or {@link Reservation#release()} to give everything back. The
 * reservation is {@link AutoCloseable} and closing without keeping releases, so
 * a path nobody thought about frees the engine-wide lock rather than parking
 * every strategy indefinitely.
 */
public final class EntryAdmission {
    private final Blackboard blackboard;

    public EntryAdmission(Blackboard blackboard) {
        this.blackboard = Objects.requireNonNull(blackboard, "blackboard");
    }

    /**
     * Acquires all three, or returns {@code null} having given back anything it
     * managed to take. Never returns a partially held reservation.
     */
    public Reservation tryAdmit(String strategyId, Stock stock) {
        Objects.requireNonNull(strategyId, "strategyId");
        Objects.requireNonNull(stock, "stock");
        String ticker = stock.getTicker();

        if (!blackboard.tryAcquireGlobalPending(strategyId, ticker)) {
            return null;
        }

        if (!blackboard.tryReservePosition(ticker, strategyId)) {
            blackboard.releaseGlobalPending(strategyId, ticker);
            return null;
        }

        if (!stock.getState().compareAndSet(
                Stock.PositionState.FLAT, Stock.PositionState.PENDING)) {
            blackboard.releasePosition(ticker, strategyId);
            blackboard.releaseGlobalPending(strategyId, ticker);
            return null;
        }

        return new Reservation(blackboard, strategyId, stock);
    }

    /**
     * All three holdings of one admitted entry. Not thread-safe: it is created,
     * used, and resolved on the strategy thread that acquired it.
     */
    public static final class Reservation implements AutoCloseable {
        private final Blackboard blackboard;
        private final String strategyId;
        private final Stock stock;
        private boolean resolved;

        private Reservation(Blackboard blackboard, String strategyId, Stock stock) {
            this.blackboard = blackboard;
            this.strategyId = strategyId;
            this.stock = stock;
        }

        /**
         * Hands ownership to the pending-entry lifecycle. The ticker stays
         * reserved and keeps counting against {@code MAX_ACTIVE_POSITIONS} until
         * the order fills or terminates; the engine-wide lock is released later,
         * on acknowledgement, by the lifecycle rather than here.
         */
        public void keep() {
            resolved = true;
        }

        /**
         * Gives all three back and returns the position state to {@code FLAT}.
         * Idempotent, and a no-op once {@link #keep()} has been called.
         */
        public void release() {
            if (resolved) {
                return;
            }
            resolved = true;
            stock.getState().compareAndSet(
                    Stock.PositionState.PENDING, Stock.PositionState.FLAT);
            blackboard.releasePosition(stock.getTicker(), strategyId);
            blackboard.releaseGlobalPending(strategyId, stock.getTicker());
        }

        /** Releases unless the entry was kept, so an unconsidered path cannot leak the lock. */
        @Override
        public void close() {
            release();
        }
    }
}
