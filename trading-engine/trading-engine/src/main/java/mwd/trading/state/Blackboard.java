package mwd.trading.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import mwd.trading.config.Config;
import mwd.trading.domain.Account;
import mwd.trading.domain.Stock;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;

public class Blackboard implements StrategyBlackboard {
	public record EntryOwner(String strategyName, String ticker) {
		public EntryOwner {
			strategyName = requireIdentifier(strategyName, "strategyName");
			ticker = requireIdentifier(ticker, "ticker");
		}
	}

	private final IdManager idManager;
	private final TimeManager timeManager;
	private final OrderRegistry orderRegistry;
	private final Config config;
	private final Account account;

	public Blackboard(IdManager idManager, TimeManager timeManager, OrderRegistry orderRegistry, Config config) {
		this.idManager = Objects.requireNonNull(idManager);
		this.timeManager = Objects.requireNonNull(timeManager);
		this.orderRegistry = Objects.requireNonNull(orderRegistry);
		this.config = Objects.requireNonNull(config);
		this.account = new Account();
	}
	
	
	// Global Market Sentiment Variables (Market Tide)
    private volatile double netCallPremium;
    private volatile double netPutPremium;
    public double getNetCallPremium() { return netCallPremium; }
    public void setNetCallPremium(double netCallPremium) { this.netCallPremium = netCallPremium; }
    public double getNetPutPremium() { return netPutPremium; }
    public void setNetPutPremium(double netPutPremium) { this.netPutPremium = netPutPremium; }
    
	
	// Flag that is set to true when all trading must halt
	private volatile boolean systemHalted = false;
	// Flag that is set to true when an update is required
	private volatile boolean systemUpdateRequired = false;
	// Flag that is set to true when IBKR is done sending messages about orders
	private volatile boolean openOrderEnd = false;
	// A symbol is counted exactly once while a named strategy owns its lifecycle.
	private final Map<String, String> activePositionOwners = new HashMap<>();

	// Collection of all stocks being tracked
	private final Map<String, Stock> stocks = new ConcurrentHashMap<>();

	// Flag that is set to true to block multiple orders from being submitted concurrently
	// This prevents the need to track "virtual buying power"
	private final AtomicReference<EntryOwner> globalPendingOwner = new AtomicReference<>();

	/**
	 * When any strategy last sent an entry to IBKR. Global rather than
	 * per-strategy: the constraint being protected is account-wide margin.
	 */
	private final java.util.concurrent.atomic.AtomicLong lastEntrySubmittedAtMillis =
			new java.util.concurrent.atomic.AtomicLong();

	/** Records that an entry has been sent, closing the gate below. */
	public void recordEntrySubmitted(long atMillis) {
		lastEntrySubmittedAtMillis.updateAndGet(previous -> Math.max(previous, atMillis));
	}

	/**
	 * Whether the account snapshot is new enough to size another entry against.
	 *
	 * <p>
	 * IBKR charges initial margin when an order is accepted, not when it fills,
	 * so a snapshot taken after the previous submission already reflects it.
	 * Requiring one prevents several entries being sized against the same
	 * pre-order balance, which the position cap alone would not stop.
	 *
	 * <p>
	 * Returns false until the first account batch arrives, so a strategy cannot
	 * size against defaults at startup.
	 */
	public boolean isAccountCurrentForNewEntry() {
		long refreshedAt = account.getLastRefreshedAtMillis();
		return refreshedAt > 0 && refreshedAt > lastEntrySubmittedAtMillis.get();
	}
	public boolean tryAcquireGlobalPending(String strategyName, String ticker) {
		return globalPendingOwner.compareAndSet(null, new EntryOwner(strategyName, ticker));
	}

	public boolean releaseGlobalPending(String strategyName, String ticker) {
		EntryOwner expectedOwner = new EntryOwner(strategyName, ticker);
		while (true) {
			EntryOwner currentOwner = globalPendingOwner.get();
			if (!expectedOwner.equals(currentOwner)) {
				return false;
			}
			if (globalPendingOwner.compareAndSet(currentOwner, null)) {
				return true;
			}
		}
	}

	public boolean isGlobalPendingOwnedBy(String strategyName, String ticker) {
		return new EntryOwner(strategyName, ticker).equals(globalPendingOwner.get());
	}

	public EntryOwner getGlobalPendingOwner() {
		return globalPendingOwner.get();
	}

	/*
	 * Active Position Count
	 */
	public synchronized boolean tryReservePosition(String ticker, String strategyName) {
		String normalizedTicker = requireIdentifier(ticker, "ticker");
		String normalizedStrategy = requireIdentifier(strategyName, "strategyName");
		if (activePositionOwners.containsKey(normalizedTicker)
				|| activePositionOwners.size() >= config.getMaxActivePositions()) {
			return false;
		}
		activePositionOwners.put(normalizedTicker, normalizedStrategy);
		return true;
	}

	public synchronized boolean releasePosition(String ticker, String strategyName) {
		String normalizedTicker = requireIdentifier(ticker, "ticker");
		String normalizedStrategy = requireIdentifier(strategyName, "strategyName");
		return activePositionOwners.remove(normalizedTicker, normalizedStrategy);
	}

	public synchronized boolean isPositionOwnedBy(String ticker, String strategyName) {
		return requireIdentifier(strategyName, "strategyName").equals(
				activePositionOwners.get(requireIdentifier(ticker, "ticker")));
	}

	public synchronized String getPositionOwner(String ticker) {
		return activePositionOwners.get(requireIdentifier(ticker, "ticker"));
	}

	public synchronized int getActivePositionCount() {
		return activePositionOwners.size();
	}

	public synchronized Map<String, String> getPositionOwnersSnapshot() {
		return Map.copyOf(activePositionOwners);
	}

	/*
	 * Account
	 */
	public Account getAccount() {
		return this.account;
	}

	/*
	 * Market Time
	 */
	public TimeManager getTimeManager() {
		return this.timeManager;
	}

	public long getMarketTime() {
		return this.timeManager.getMarketTime();
	}

	/*
	 * Order Registry
	 */
	public OrderRegistry getOrderRegistry() {
		return this.orderRegistry;
	}

	/*
	 * System Halted
	 */
	public boolean getSystemHalted() {
		return this.systemHalted;
	}

	public void setSystemHalted(boolean bool) {
		this.systemHalted = bool;
	}

	/*
	 * Open Order End
	 */
	public boolean getOpenOrderEnd() {
		return this.openOrderEnd;
	}

	public void setOpenOrderEnd(boolean bool) {
		this.openOrderEnd = bool;
	}

	/*
	 * IDs
	 */
	public void initializeOrderId(int orderId) {
		this.idManager.initializeOrderId(orderId);
	}

	public void initializeRequestId(int requestId) {
		this.idManager.initializeRequestId(requestId);
	}

	public int getNextOrderId() {
		return idManager.getNextOrderId();
	}

	public int getNextRequestId() {
		return idManager.getNextRequestId();
	}

	/*
	 * System Update Required
	 */
	public boolean getSystemUpdateRequired() {
		return systemUpdateRequired;
	}

	public void setSystemUpdateRequired(boolean systemUpdateRequired) {
		this.systemUpdateRequired = systemUpdateRequired;
	}

	/*
	 * Access stocks
	 */
	public Stock getStock(String ticker) {
		return stocks.computeIfAbsent(ticker, Stock::new);
	}

	// Collection is unmodifiable, but the Stock objects are modifiable
	public Collection<Stock> getAllStocksUnmodifiable() {
		return Collections.unmodifiableCollection(stocks.values());
	}

	// Will increase garbage collection
	public List<Stock> getAllStocksSnapshot() {
		return new ArrayList<>(stocks.values());
	}

	public void forEachStock(Consumer<Stock> action) {
		stocks.values().forEach(action);
	}

	public Stream<Stock> streamStocks() {
		return stocks.values().stream();
	}

	private static String requireIdentifier(String value, String name) {
		String normalized = Objects.requireNonNull(value, name).trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return normalized;
	}

}
