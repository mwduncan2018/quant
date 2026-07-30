package mwd.trading.broker.ibkr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TickMapTest {

	@Test
	void testLiveIBKR() {
		TickMap tickMap = new TickMap(true);
		
		assertTrue(tickMap.isBid(1));
		assertFalse(tickMap.isBid(66));
	}
	
	@Test
	void testPaperIBKR() {
		TickMap tickMap = new TickMap(false);
		
		assertFalse(tickMap.isBid(1));
		assertTrue(tickMap.isBid(66));
	}

	/**
	 * IBKR publishes no delayed mark price. 78 is CREDITMAN_MARK_PRICE, an
	 * unrelated field the delayed branch used to claim as the mark.
	 */
	@Test
	void delayedDataHasNoMarkPrice() {
		TickMap tickMap = new TickMap(false);

		assertFalse(tickMap.isMarkPrice(78));
		assertFalse(tickMap.isMarkPrice(37));
		assertTrue(new TickMap(true).isMarkPrice(37));
	}

	/**
	 * IBKR publishes no delayed average volume either. 77 is RT_TRD_VOLUME, a
	 * real-time figure.
	 */
	@Test
	void delayedDataHasNoAverageVolume() {
		TickMap tickMap = new TickMap(false);

		assertFalse(tickMap.isAverageVolume(77));
		assertFalse(tickMap.isAverageVolume(21));
		assertTrue(new TickMap(true).isAverageVolume(21));
	}

}
