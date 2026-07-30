package mwd.trading.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ib.client.TickType;

import mwd.trading.broker.ibkr.EWrapperRaptor;
import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.broker.ibkr.TickMap;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

/**
 * Covers the path IBKR's session VWAP actually travels.
 *
 * <p>
 * VWAP is the one quoted value IBKR does not send as a price tick. It arrives in
 * {@code RT_VOLUME} (tick type 48), a semicolon-delimited string enabled by
 * generic tick {@code 233}. The engine previously watched for price-tick fields
 * 459 and 533, neither of which exists — {@code TickType} defines ids 0 to 104 —
 * so {@code DAILY_VWAP} was never recorded and every strategy stayed gated out
 * of entry against a real feed. Nothing failed, because the strategy tests wrote
 * {@code Stock.dailyVWAP} directly and never crossed this boundary.
 *
 * <p>
 * These tests cross it: the payload is parsed as IBKR formats it, and the tick
 * is delivered through the real {@code EWrapper} fan-out rather than by calling
 * the handler directly. A dropped {@code tickString} override or a mis-registered
 * consumer fails the build instead of silencing the engine.
 */
class VwapIngestionPathTest {
	private static final String TICKER = "AAPL";
	private static final int REQUEST_ID = 4;
	private static final int RT_VOLUME = TickType.RT_VOLUME.index();
	private static final double TOLERANCE = 1e-6;

	private Blackboard blackboard;
	private RequestRegistry registry;
	private MarketDataInputStore inputStore;
	private EWrapperRaptor wrapper;

	@BeforeEach
	void setUp() {
		TestConfig config = new TestConfig(3, 10_000L, Set.of(TICKER));
		blackboard = new Blackboard(
				new IdManager(), new TimeManager(), new OrderRegistry(), config);
		registry = new RequestRegistry();
		// The same consumer set requestLiveMarketData registers, which is the
		// subscription that carries generic tick 233.
		registry.register(REQUEST_ID, TICKER,
				RequestRegistry.DataConsumer.TICK_PRICE,
				RequestRegistry.DataConsumer.TICK_SIZE,
				RequestRegistry.DataConsumer.MOVING_AVERAGE);
		inputStore = new MarketDataInputStore(30_000L);

		PriceTickHandler priceTickHandler =
				new PriceTickHandler(blackboard, registry, new TickMap(config), inputStore);
		// Only the price handler is reachable from tickString, so the remaining
		// collaborators are never dereferenced.
		wrapper = new EWrapperRaptor(
				registry, null, null, null, null, null, null, null, null, null,
				priceTickHandler, null, null, null);
	}

	private double vwap() {
		return blackboard.getStock(TICKER).getDailyVWAP();
	}

	private boolean vwapIsReady() {
		return inputStore.isFresh(TICKER, MarketDataInput.DAILY_VWAP);
	}

	@Test
	void anRtVolumeTickReachesStockThroughTheWrapperFanOut() {
		// price;size;time;totalVolume;VWAP;singleTradeFlag
		wrapper.tickString(REQUEST_ID, RT_VOLUME, "701.28;1;1348075471534;67854;701.46918464;true");

		assertEquals(701.46918464, vwap(), TOLERANCE);
		assertTrue(vwapIsReady(),
				"DAILY_VWAP must be recorded, or every strategy stays gated out of entry");
	}

	@Test
	void aLaterTickReplacesTheEarlierValue() {
		wrapper.tickString(REQUEST_ID, RT_VOLUME, "701.28;1;1348075471534;67854;701.46;true");
		wrapper.tickString(REQUEST_ID, RT_VOLUME, "702.10;5;1348075481534;69000;701.92;true");

		assertEquals(701.92, vwap(), TOLERANCE);
	}

	@Test
	void anEmptyPayloadIsIgnored() {
		// IBKR sends an empty RTVolume string when nothing has traded yet.
		wrapper.tickString(REQUEST_ID, RT_VOLUME, "");

		assertFalse(vwapIsReady(), "an empty payload must not read as a usable VWAP");
	}

	@Test
	void aTickThatIsNotRtVolumeIsIgnored() {
		wrapper.tickString(REQUEST_ID, TickType.LAST_TIMESTAMP.index(), "1348075471");

		assertFalse(vwapIsReady());
	}

	@Test
	void aTruncatedPayloadIsIgnoredRatherThanMisread() {
		// Reading a shorter payload positionally would publish the total volume
		// as if it were a price.
		wrapper.tickString(REQUEST_ID, RT_VOLUME, "701.28;1;1348075471534;67854");

		assertFalse(vwapIsReady());
	}

	@Test
	void anUnparseableVwapFieldIsIgnored() {
		wrapper.tickString(REQUEST_ID, RT_VOLUME, "701.28;1;1348075471534;67854;;true");
		assertFalse(vwapIsReady());

		wrapper.tickString(REQUEST_ID, RT_VOLUME, "701.28;1;1348075471534;67854;abc;true");
		assertFalse(vwapIsReady());
	}

	@Test
	void aNonPositiveVwapIsIgnored() {
		wrapper.tickString(REQUEST_ID, RT_VOLUME, "701.28;1;1348075471534;67854;0;true");

		assertFalse(vwapIsReady(), "a zero VWAP must never read as a usable price");
	}

	@Test
	void anUnknownRequestIdIsIgnored() {
		wrapper.tickString(REQUEST_ID + 99, RT_VOLUME, "701.28;1;1348075471534;67854;701.46;true");

		assertFalse(vwapIsReady());
	}

	@Test
	void theParserReadsTheFifthFieldRegardlessOfTrailingFields() {
		assertEquals(701.46918464,
				PriceTickHandler.parseRealTimeVolumeVwap("701.28;1;1348075471534;67854;701.46918464;true"),
				TOLERANCE);
		// A payload with no trailing flag still carries the VWAP in position five.
		assertEquals(12.5,
				PriceTickHandler.parseRealTimeVolumeVwap("12.4;100;1348075471534;900;12.5"),
				TOLERANCE);
	}
}
