package mwd.trading.state;

import mwd.trading.domain.Account;

/**
 * Everything a strategy may reach on the blackboard, and nothing else.
 *
 * <p>
 * {@link Blackboard} is the engine's shared state: stocks, the account, the
 * order registry, the IBKR id counters, the reservation protocol, and the
 * process-wide flags. A strategy needs four of those things. Taking the whole
 * object gave it all of them, so nothing but convention stopped a strategy from
 * allocating an order id, reading another symbol's bracket out of the registry,
 * or clearing {@code openOrderEnd} - and convention is not enforceable.
 *
 * <p>
 * Composing the two roles it genuinely plays says what it is instead of what it
 * has: a strategy claims positions ({@link PositionLedger}) and looks up symbols
 * ({@link StockLookup}). The three remaining methods are the account gate and
 * the halt switch, which have no other home. {@code Blackboard} satisfies this
 * as it stands, so wiring it up costs nothing at the call site - the narrowing
 * is entirely in what the strategy can see.
 */
public interface StrategyBlackboard extends PositionLedger, StockLookup {

    /**
     * Whether the account has been refreshed since the last entry was sent. A new
     * entry sized against pre-submission balances could double up on the same
     * capital.
     */
    boolean isAccountCurrentForNewEntry();

    /**
     * Stamps a submission so the next entry has to wait for a fresh account
     * batch. Recorded before the send, because an uncertain outcome may still
     * have reached IBKR.
     */
    void recordEntrySubmitted(long atMillis);

    /** Balances for position sizing. */
    Account getAccount();

    /**
     * Latches the engine-wide halt that the monitor renders. Escalation sets this
     * alongside driving {@code TradingGate} to manual intervention; nothing in
     * the engine clears it.
     */
    void setSystemHalted(boolean halted);
}
