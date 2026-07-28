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

}
