package mwd.trading.domain;

public class Account {
    private volatile String accountId;
    
    private volatile double netLiquidation;
    private volatile double totalCashValue;
    private volatile double settledCash;
    private volatile double buyingPower;
    private volatile double availableFunds;
    private volatile double excessMargin;
    private volatile double realizedPnL;
    private volatile double unrealizedPnL;
    private volatile double cushion;

    /**
     * When IBKR last finished sending a batch of account values, from
     * {@code updateAccountTime}. Zero until the first batch arrives.
     *
     * <p>
     * This is what lets a strategy tell a figure that reflects its last
     * submitted order from one that predates it. IBKR charges initial margin
     * when an order is accepted rather than when it fills, so a snapshot taken
     * after the previous acknowledgement already accounts for it.
     */
    private volatile long lastRefreshedAtMillis;

    // Getters and Setters
    public String getAccountId() { return accountId; }
    public void setAccountId(String id) { this.accountId = id; }

    public long getLastRefreshedAtMillis() { return lastRefreshedAtMillis; }
    public void setLastRefreshedAtMillis(long millis) { this.lastRefreshedAtMillis = millis; }

    public double getNetLiquidation() { return netLiquidation; }
    public void setNetLiquidation(double val) { this.netLiquidation = val; }

    public double getTotalCashValue() { return totalCashValue; }
    public void setTotalCashValue(double val) { this.totalCashValue = val; }

    public double getSettledCash() { return settledCash; }
    public void setSettledCash(double val) { this.settledCash = val; }

    public double getBuyingPower() { return buyingPower; }
    public void setBuyingPower(double val) { this.buyingPower = val; }

    public double getAvailableFunds() { return availableFunds; }
    public void setAvailableFunds(double val) { this.availableFunds = val; }

    public double getExcessMargin() { return excessMargin; }
    public void setExcessMargin(double val) { this.excessMargin = val; }

    public double getRealizedPnL() { return realizedPnL; }
    public void setRealizedPnL(double val) { this.realizedPnL = val; }

    public double getUnrealizedPnL() { return unrealizedPnL; }
    public void setUnrealizedPnL(double val) { this.unrealizedPnL = val; }

    public double getCushion() { return cushion; }
    public void setCushion(double val) { this.cushion = val; }

}