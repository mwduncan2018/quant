package mwd.trading.broker.ibkr;

import mwd.trading.config.Config;

public class TickMap {
	private final boolean liveIBKRData;

	public TickMap(Config config) {
		this(config.isLiveIBKRData());
	}

	TickMap(boolean liveIBKRData) {
		this.liveIBKRData = liveIBKRData;
	}

	/*
	 * Tick Price
	 */

	private boolean liveIBKRData() {
		return liveIBKRData;
	}

	public boolean isBid(int field) {
		return liveIBKRData() ? (field == 1) : (field == 66);
	}

	public boolean isAsk(int field) {
		return liveIBKRData() ? (field == 2) : (field == 67);
	}

	public boolean isLast(int field) {
		return liveIBKRData() ? (field == 4) : (field == 68);
	}

	/**
	 * IBKR publishes no delayed mark price. 78 is {@code CREDITMAN_MARK_PRICE},
	 * an unrelated field this used to claim, so the delayed branch matches
	 * nothing rather than mislabelling another value as the mark.
	 */
	public boolean isMarkPrice(int field) {
		return liveIBKRData() && field == 37;
	}

	public boolean isOpen(int field) {
		return liveIBKRData() ? (field == 14) : (field == 76);
	}

	public boolean isClose(int field) {
		return liveIBKRData() ? (field == 9) : (field == 75);
	}

	public boolean isHigh(int field) {
		return liveIBKRData() ? (field == 6) : (field == 72);
	}

	public boolean isLow(int field) {
		return liveIBKRData() ? (field == 7) : (field == 73);
	}

	// There is no isVwap. IBKR defines no VWAP price tick in either the live or
	// the delayed field set — TickType runs 0 to 104 and neither 459 nor 533,
	// which this method used to test for, exists. VWAP arrives as the RT_VOLUME
	// string tick and is read by PriceTickHandler.onTickString.

	/*
	 * Tick Size
	 */

	public boolean isBidSize(int field) {
		return liveIBKRData() ? (field == 0) : (field == 69);
	}

	public boolean isAskSize(int field) {
		return liveIBKRData() ? (field == 3) : (field == 70);
	}

	public boolean isLastSize(int field) {
		return liveIBKRData() ? (field == 5) : (field == 71);
	}

	public boolean isVolume(int field) {
		return liveIBKRData() ? (field == 8) : (field == 74);
	}

	/**
	 * IBKR publishes no delayed average volume. 77 is {@code RT_TRD_VOLUME}, a
	 * real-time figure this used to claim, so the delayed branch matches nothing.
	 */
	public boolean isAverageVolume(int field) {
		return liveIBKRData() && field == 21;
	}

}
