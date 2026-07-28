package mwd.trading.broker.ibkr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdManagerTest {
	private IdManager idManager;

	@BeforeEach
	void setUp() throws Exception {
		idManager = new IdManager();
	}

	@Test
	@DisplayName("Initialize request")
	void testInitializeRequest() {
		int startValue = 2000;
		idManager.initializeRequestId(startValue);

		assertEquals(startValue, idManager.getNextRequestId());
	}

	@Test
	@DisplayName("Initialize order")
	void testInitializeOrder() {
		int startValue = 3000;
		idManager.initializeOrderId(startValue);

		assertEquals(startValue, idManager.getNextOrderId());
	}

	@Test
	@DisplayName("A stale broker ID cannot move either sequence backwards")
	void testInitializationIsMonotonic() {
		idManager.initializeRequestId(2000);
		assertEquals(2000, idManager.getNextRequestId());
		idManager.initializeRequestId(1500);
		assertEquals(2001, idManager.getNextRequestId());

		idManager.initializeOrderId(3000);
		assertEquals(3000, idManager.getNextOrderId());
		idManager.initializeOrderId(2500);
		assertEquals(3001, idManager.getNextOrderId());
	}

	@Test
	@DisplayName("Request should increment each time getNextRequestId is called")
	void testGetNextRequest() {
		int startValue = 1000;
		idManager.initializeRequestId(startValue);

		for (int i = 0; i <= 9999; i++) {
			assertEquals(startValue + i, idManager.getNextRequestId());
		}
	}

	@Test
	@DisplayName("Order should increment each time getNextOrderId is called")
	void testGetNextOrder() {
		int startValue = 1000;
		idManager.initializeOrderId(startValue);

		for (int i = 0; i <= 9999; i++) {
			assertEquals(startValue + i, idManager.getNextOrderId());
		}
	}

	@Test
	@DisplayName("Should be thread-safe when accessed by multiple threads")
	void testThreadSafety() throws InterruptedException {
		int startValue = 1000;
		int threadCount = 100;
		int calls = 100;
		idManager.initializeRequestId(startValue);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch latch = new CountDownLatch(1);
		AtomicInteger totalCalls = new AtomicInteger(0);

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> { // The submit method puts this function in a task queue
				try {
					latch.await(); // Every task holds right here until the countDown method is called
					for (int j = 0; j < calls; j++) { // Each thread will make this many calls
						idManager.getNextRequestId();
						totalCalls.incrementAndGet();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		}

		latch.countDown(); // Release the hounds!
		executor.shutdown(); // The shutdown method prevents new functions from being added and will cleanup
								// when all threads are done.

		while (!executor.isTerminated()) { // Wait for threads to finish

			Thread.sleep(10);
		}

		int expectedFinalValue = startValue + (threadCount * calls);
		assertEquals(expectedFinalValue, idManager.getNextRequestId());
	}

}
