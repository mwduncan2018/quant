package mwd.trading.support;

import java.util.Optional;
import java.util.Set;

import mwd.trading.marketdata.MarketDataFreshness;
import mwd.trading.marketdata.MarketDataInput;

/**
 * Reports every input ready. For tests about lifecycle mechanics rather than
 * readiness itself; tests that exercise readiness use a real
 * {@link mwd.trading.marketdata.MarketDataInputStore}.
 */
public final class ReadyMarketData implements MarketDataFreshness {

    @Override
    public boolean isFresh(String ticker, MarketDataInput input) {
        return true;
    }

    @Override
    public boolean areAllFresh(String ticker, Set<MarketDataInput> required) {
        return true;
    }

    @Override
    public Optional<String> describeUnready(String ticker, Set<MarketDataInput> required) {
        return Optional.empty();
    }
}
