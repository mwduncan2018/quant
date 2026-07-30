package mwd.trading.state;

import mwd.trading.domain.Stock;

/**
 * Resolves a ticker to the {@link Stock} that holds its values.
 *
 * <p>
 * This is the whole dependency of every indicator tracker and tick handler in
 * the engine: they are handed a symbol by an IBKR callback and need somewhere to
 * put the number. Taking the {@link Blackboard} for that gave each of them the
 * order registry, the order-id counter, the halt flag, and every other symbol's
 * bracket as well - none of which they touch, all of which they could.
 */
public interface StockLookup {

    /** The stock for this ticker, created on first request. Never {@code null}. */
    Stock getStock(String ticker);
}
