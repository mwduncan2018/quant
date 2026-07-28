package mwd.trading.broker.ibkr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.AbstractMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestRegistryTest {
	private RequestRegistry registry;

	@BeforeEach
	void setUp() throws Exception {
		registry = new RequestRegistry();
	}

	@Test
	@DisplayName("Should get a valid ticker")
	void testGetTickerWithValidReqId() {
		Map.Entry<Integer, String> msft = new AbstractMap.SimpleEntry<>(1, "MSFT");
		Map.Entry<Integer, String> mcd = new AbstractMap.SimpleEntry<>(2, "MCD");
		Map.Entry<Integer, String> ko = new AbstractMap.SimpleEntry<>(3, "KO");
		
		registry.register(msft.getKey().intValue(), msft.getValue());
		registry.register(mcd.getKey().intValue(), mcd.getValue());
		registry.register(ko.getKey().intValue(), ko.getValue());
		
		assertEquals(msft.getValue(), registry.getTickerFor(msft.getKey().intValue()));
		assertEquals(mcd.getValue(), registry.getTickerFor(mcd.getKey().intValue()));
		assertEquals(ko.getValue(), registry.getTickerFor(ko.getKey().intValue()));
	}

	@Test
	@DisplayName("Should log when trying to get invalid reqId")
	void testGetTickerWithInvalidReqId() {
		Map.Entry<Integer, String> msft = new AbstractMap.SimpleEntry<>(1, "MSFT");
		Map.Entry<Integer, String> mcd = new AbstractMap.SimpleEntry<>(2, "MCD");
		Map.Entry<Integer, String> ko = new AbstractMap.SimpleEntry<>(3, "KO");
		
		registry.register(msft.getKey().intValue(), msft.getValue());
		registry.register(mcd.getKey().intValue(), mcd.getValue());
		registry.register(ko.getKey().intValue(), ko.getValue());
		
		registry.getTickerFor(9999);
		
	}

	@Test
	@DisplayName("Should unregister a valid ticker")
	void testUnregisterWithValidReqId() {
		Integer msftReqId= 1;
		String msftTicker = "MSFT";
		Integer mcdReqId = 2;
		String mcdTicker = "MCD";
		Integer koReqId = 3;
		String koTicker = "KO";

		registry.register(msftReqId, msftTicker);
		registry.register(mcdReqId, mcdTicker);
		registry.register(koReqId, koTicker);
		registry.unregister(msftReqId);
		
		assertEquals(null, registry.getTickerFor(msftReqId));
	}

}
