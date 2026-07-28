package mwd.trading.marketdata;

import java.util.Optional;
import java.util.Set;

/**
 * Per-input readiness of one symbol's IBKR-sourced market data.
 *
 * <p>
 * Readiness is deliberately asked for one input at a time. A single arriving
 * tick says nothing about whether the other inputs a strategy needs have ever
 * been delivered, so a strategy states the set it requires and every member of
 * that set is checked independently.
 */
public interface MarketDataFreshness {

    /** Whether one input currently holds a usable, unexpired value. */
    boolean isFresh(String ticker, MarketDataInput input);

    /** Whether every required input currently holds a usable, unexpired value. */
    boolean areAllFresh(String ticker, Set<MarketDataInput> required);

    /**
     * Describes why the required inputs are not ready, or an empty optional when
     * they are. Intended for logging: a strategy that silently declines to trade
     * is indistinguishable from one that is broken.
     */
    Optional<String> describeUnready(String ticker, Set<MarketDataInput> required);
}
