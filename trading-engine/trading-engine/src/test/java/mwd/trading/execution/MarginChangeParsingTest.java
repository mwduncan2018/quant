package mwd.trading.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the guard on {@code OrderState.initMarginChange()}, the string the
 * what-if response carries and the only input to every stored margin rate.
 *
 * <p>
 * The unguarded {@code Double.parseDouble} this replaces had two failure modes,
 * and only one of them announced itself. A null or blank field threw out of the
 * IBKR callback, where the reader loop catches the exception and breaks the
 * message pump, forcing a reconnect. IBKR's {@link Double#MAX_VALUE} "unset"
 * sentinel parsed perfectly well and was divided by the notional into a margin
 * rate of roughly 1e304, which then fed position sizing.
 */
class MarginChangeParsingTest {

	@Test
	void aNormalFigureIsRead() {
		assertEquals(2500.0, OrderLifecycleHandler.parseMarginChange("2500.0"), 1e-9);
		assertEquals(2500.0, OrderLifecycleHandler.parseMarginChange("  2500.0  "), 1e-9);
	}

	@Test
	void anAbsentFieldIsRejectedRatherThanThrown() {
		// This is the case that used to break the reader thread's message pump.
		assertTrue(Double.isNaN(OrderLifecycleHandler.parseMarginChange(null)));
		assertTrue(Double.isNaN(OrderLifecycleHandler.parseMarginChange("")));
		assertTrue(Double.isNaN(OrderLifecycleHandler.parseMarginChange("   ")));
	}

	@Test
	void theUnsetSentinelIsRejected() {
		// IBKR reports Double.MAX_VALUE when it has no figure. It parses cleanly,
		// which is exactly why it has to be caught by value.
		assertTrue(Double.isNaN(
				OrderLifecycleHandler.parseMarginChange(String.valueOf(Double.MAX_VALUE))));
		assertTrue(Double.isNaN(
				OrderLifecycleHandler.parseMarginChange("1.7976931348623157E308")));
	}

	@Test
	void anUnparseableFieldIsRejected() {
		assertTrue(Double.isNaN(OrderLifecycleHandler.parseMarginChange("n/a")));
		assertTrue(Double.isNaN(OrderLifecycleHandler.parseMarginChange("1,234.00")));
	}

	@Test
	void aNonPositiveFigureIsRejected() {
		// A rate of zero makes calculateMarginRequirement return zero, which reads
		// as "this position needs no margin" and removes the ceiling from sizing.
		assertTrue(Double.isNaN(OrderLifecycleHandler.parseMarginChange("0")));
		assertTrue(Double.isNaN(OrderLifecycleHandler.parseMarginChange("-100")));
	}

	@Test
	void nonFiniteValuesAreRejected() {
		assertTrue(Double.isNaN(OrderLifecycleHandler.parseMarginChange("Infinity")));
		assertTrue(Double.isNaN(OrderLifecycleHandler.parseMarginChange("NaN")));
	}
}
