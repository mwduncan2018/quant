package mwd.trading.broker.ibkr;

import java.util.concurrent.atomic.AtomicInteger;

public class IdManager {
	private final AtomicInteger orderId = new AtomicInteger();
	private final AtomicInteger requestId = new AtomicInteger();
	
	public void initializeRequestId(int requestId) {
		this.requestId.accumulateAndGet(requestId, Math::max);
	}
	
	public int getNextRequestId() {
		return requestId.getAndIncrement();
	}
	
	public void initializeOrderId(int orderId) {
		this.orderId.accumulateAndGet(orderId, Math::max);
	}
	
	public int getNextOrderId() {
		return orderId.getAndIncrement();
	}

}
