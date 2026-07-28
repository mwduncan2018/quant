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

	public boolean isMarkPrice(int field) {
		return liveIBKRData() ? (field == 37) : (field == 78);
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

	public boolean isVwap(int field) {
		return liveIBKRData() ? (field == 459) : (field == 533);
	}

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

	public boolean isAverageVolume(int field) {
		return liveIBKRData() ? (field == 21) : (field == 77);
	}

}
