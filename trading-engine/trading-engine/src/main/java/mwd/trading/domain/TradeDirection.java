package mwd.trading.domain;

public enum TradeDirection {
    LONG("BUY", "SELL") {
        @Override
        public boolean acceptsEntryPrice(double marketPrice, double limitPrice) {
            return validPrices(marketPrice, limitPrice) && marketPrice <= limitPrice;
        }
    },
    SHORT("SELL", "BUY") {
        @Override
        public boolean acceptsEntryPrice(double marketPrice, double limitPrice) {
            return validPrices(marketPrice, limitPrice) && marketPrice >= limitPrice;
        }
    };

    private final String entryAction;
    private final String exitAction;

    TradeDirection(String entryAction, String exitAction) {
        this.entryAction = entryAction;
        this.exitAction = exitAction;
    }

    public String entryAction() {
        return entryAction;
    }

    public String exitAction() {
        return exitAction;
    }

    public abstract boolean acceptsEntryPrice(double marketPrice, double limitPrice);

    private static boolean validPrices(double marketPrice, double limitPrice) {
        return Double.isFinite(marketPrice) && marketPrice > 0.0
                && Double.isFinite(limitPrice) && limitPrice > 0.0;
    }
}
