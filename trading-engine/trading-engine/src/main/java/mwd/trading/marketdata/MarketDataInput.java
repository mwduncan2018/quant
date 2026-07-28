package mwd.trading.marketdata;

/**
 * An individually tracked IBKR-sourced market-data input.
 *
 * <p>
 * Each input is recorded only when a handler has accepted a value that passed
 * that handler's own sanity rules, so a recorded input means "a usable value was
 * written", never "a callback fired". Nothing here interprets a default
 * {@code 0.0} as a delivered value.
 *
 * <p>
 * Inputs expire differently because IBKR delivers them differently, and applying
 * one rule to all of them would either strand a once-per-session field or let a
 * frozen stream look current.
 */
public enum MarketDataInput {
    /** Last traded price, from ticks and any active tick-by-tick stream. */
    LAST_PRICE("last price", Expiry.AGED),
    /**
     * Previous session's close. IBKR sends this once when the subscription opens
     * and then effectively never again, so an age limit would discard a value
     * that is still perfectly correct. It stays usable for the session that
     * received it and is dropped on a session change or a resubscribe.
     */
    PREVIOUS_CLOSE("previous close", Expiry.SESSION_SCOPED),
    /** Daily volume-weighted average price. */
    DAILY_VWAP("daily VWAP", Expiry.AGED),
    /** Newest one-minute bar, refreshed while the minute forms. */
    MINUTE_BAR("one-minute bar", Expiry.AGED),
    /** Rolling 15-minute volume baseline, recorded only once its window is full. */
    MINUTE_VOLUME_BASELINE("rolling 15-minute volume baseline", Expiry.AGED);

    /** How an input stops being usable. */
    public enum Expiry {
        /** Unusable once older than the configured maximum age. */
        AGED,
        /** Unusable once the trading session changes, regardless of age. */
        SESSION_SCOPED
    }

    private final String description;
    private final Expiry expiry;

    MarketDataInput(String description, Expiry expiry) {
        this.description = description;
        this.expiry = expiry;
    }

    public String description() {
        return description;
    }

    public Expiry expiry() {
        return expiry;
    }
}
