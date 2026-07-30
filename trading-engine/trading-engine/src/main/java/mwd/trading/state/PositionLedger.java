package mwd.trading.state;

/**
 * The two claims that stand between a strategy and a live order.
 *
 * <p>
 * An entry has to take an engine-wide lock, so only one submission is
 * outstanding at IBKR at a time, and a per-ticker reservation, which is also
 * what enforces {@code MAX_ACTIVE_POSITIONS}. Either can lose a race with
 * another strategy thread or with the IBKR reader thread, and whatever was
 * already taken has to be given back.
 *
 * <p>
 * That protocol is the most concurrency-sensitive thing the engine does, and it
 * is worth naming on its own. {@code EntryAdmission} implements it against this
 * interface and nothing else, so the class that has to be read carefully has a
 * surface small enough to read carefully. Both threads that participate reach
 * these methods; the implementation is what makes them safe.
 */
public interface PositionLedger {

    /** Takes the engine-wide entry lock, or returns {@code false} if it is held. */
    boolean tryAcquireGlobalPending(String strategyName, String ticker);

    /** Releases the engine-wide lock, but only if this strategy and ticker hold it. */
    boolean releaseGlobalPending(String strategyName, String ticker);

    /** Reserves the ticker unless it is taken or the active-position cap is reached. */
    boolean tryReservePosition(String ticker, String strategyName);

    /** Releases the ticker, but only if the recorded owner is this strategy. */
    boolean releasePosition(String ticker, String strategyName);

    /** The strategy holding this ticker, or {@code null} when it is unclaimed. */
    String getPositionOwner(String ticker);

    boolean isPositionOwnedBy(String ticker, String strategyName);
}
