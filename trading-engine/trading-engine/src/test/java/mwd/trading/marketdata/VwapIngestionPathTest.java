package mwd.trading.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ib.client.Bar;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;

import mwd.trading.broker.ibkr.EWrapperAbstractBase;
import mwd.trading.broker.ibkr.EWrapperRaptor;
import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.indicator.DailyVwapTracker;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

/**
 * Guards the wiring between the minute-bar subscription and the VWAP tracker.
 *
 * <p>
 * The VWAP defect these tests exist for was not a logic error — the arithmetic
 * had no home at all. IBKR sends no VWAP price tick, the handler watched for one
 * anyway, and every layer treated the resulting absence as "not ready yet". The
 * unit tests stayed green because they wrote {@code Stock.dailyVWAP} directly
 * and never crossed the ingestion boundary.
 *
 * <p>
 * These two tests cross it. One asserts the subscription actually asks for the
 * VWAP consumer; the other asserts a bar carrying that consumer reaches the
 * tracker through the real {@code EWrapper} fan-out. Either failing means the
 * strategies are silently unable to enter, which is the outcome worth catching.
 */
class VwapIngestionPathTest {
	private static final String TICKER = "AAPL";
	private static final LocalDate SESSION = LocalDate.of(2026, 7, 27);

	private static Blackboard newBlackboard() {
		return new Blackboard(
				new IdManager(), new TimeManager(), new OrderRegistry(),
				new TestConfig(3, 10_000L, Set.of(TICKER)));
	}

	private static Bar bar(int hour, int minute, double price, long volume) {
		String time = String.format("%s  %02d:%02d:00",
				SESSION.format(DateTimeFormatter.BASIC_ISO_DATE), hour, minute);
		return new Bar(time, price, price, price, price,
				Decimal.get(volume), 10, Decimal.get(price));
	}

	@Test
	void theMinuteHistorySubscriptionRegistersTheVwapConsumer() throws Exception {
		Blackboard blackboard = newBlackboard();
		RequestRegistry registry = new RequestRegistry();
		MarketDataInputStore inputStore = new MarketDataInputStore(30_000L);

		// An unconnected socket is enough: the registry entry is written before
		// the request reaches the wire.
		EClientSocket client = new EClientSocket(new EWrapperAbstractBase() {
		}, new EJavaSignal());
		MarketDataSubscriptionManager subscriptions = new MarketDataSubscriptionManager(
				client, blackboard, registry, List.of(TICKER), inputStore);

		subscriptions.initializeIfNeeded();

		EnumSet<RequestRegistry.DataConsumer> minuteHistoryConsumers = null;
		for (int requestId = 0; requestId < 16; requestId++) {
			EnumSet<RequestRegistry.DataConsumer> consumers = registry.getConsumersFor(requestId);
			if (consumers.contains(RequestRegistry.DataConsumer.VWAP)) {
				assertEquals(TICKER, registry.getTickerFor(requestId));
				minuteHistoryConsumers = consumers;
			}
		}

		assertNotNull(minuteHistoryConsumers,
				"no subscription asks for VWAP, so no bar can ever reach DailyVwapTracker");
		assertTrue(minuteHistoryConsumers.contains(RequestRegistry.DataConsumer.VOLUME),
				"VWAP must ride the minute-bar request, which is the only stream carrying wap");
	}

	@Test
	void aMinuteBarReachesTheTrackerThroughTheWrapperFanOut() {
		Blackboard blackboard = newBlackboard();
		RequestRegistry registry = new RequestRegistry();
		MarketDataInputStore inputStore = new MarketDataInputStore(30_000L);
		int requestId = 4;
		registry.register(requestId, TICKER, RequestRegistry.DataConsumer.VWAP);

		// Only the VWAP consumer is registered, so the fan-out reaches no other
		// handler and the rest may be absent.
		EWrapperRaptor wrapper = new EWrapperRaptor(
				registry, null, null, null, null, null, null, null, null, null, null, null, null,
				null, new DailyVwapTracker(blackboard, registry, inputStore));

		wrapper.historicalData(requestId, bar(9, 30, 100.0, 1_000));
		wrapper.historicalDataUpdate(requestId, bar(9, 31, 110.0, 1_000));

		assertEquals(105.0, blackboard.getStock(TICKER).getDailyVWAP(), 1e-6);
		assertTrue(inputStore.isFresh(TICKER, MarketDataInput.DAILY_VWAP),
				"DAILY_VWAP must be recorded, or every strategy stays gated out of entry");
	}
}
