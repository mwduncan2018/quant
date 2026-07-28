package mwd.trading.risk;

import mwd.trading.state.Blackboard;
import mwd.trading.domain.Stock;
import mwd.trading.lifecycle.TradingGate;

import java.time.Duration;
import java.util.Objects;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.Order;

public class MarginPacer implements Runnable {
	private final Blackboard blackboard;
	private final EClientSocket client;
	private final TradingGate tradingGate;
	private final int wait = 250;

	public MarginPacer(Blackboard blackboard, EClientSocket socket, TradingGate tradingGate) {
		this.blackboard = Objects.requireNonNull(blackboard, "blackboard");
		this.client = Objects.requireNonNull(socket, "socket");
		this.tradingGate = Objects.requireNonNull(tradingGate, "tradingGate");
	}

	@Override
	public void run() {
		while (!Thread.currentThread().isInterrupted()) {

			if (!client.isConnected() || !tradingGate.allowsNewEntries()) {
				try {
					Thread.sleep(1000);
					continue;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			} else {
				blackboard.forEachStock(stock -> {
					if (Thread.currentThread().isInterrupted()) {
						return;
					}

					requestWhatIf(stock, "BUY");
					try {
						Thread.sleep(wait);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}

					requestWhatIf(stock, "SELL");
					try {
						Thread.sleep(wait);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				});
			}
			try {
				Thread.sleep(Duration.ofMinutes(5));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void requestWhatIf(Stock stock, String action) {
		Contract contract = new Contract();
		contract.symbol(stock.getTicker());
		contract.secType("STK");
		contract.exchange("SMART");
		contract.currency("USD");

		Order mOrder = new Order();
		mOrder.action(action);
		mOrder.orderType("MKT");
		mOrder.totalQuantity(Decimal.get(100));
		mOrder.whatIf(true);

		int reqId = blackboard.getNextOrderId();

		client.placeOrder(reqId, contract, mOrder);
	}
}
