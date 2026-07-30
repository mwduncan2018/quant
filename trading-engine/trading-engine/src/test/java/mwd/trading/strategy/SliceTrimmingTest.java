package mwd.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ib.client.Decimal;

import mwd.trading.execution.BracketOrderExecutor.SliceIntent;

/**
 * The arithmetic a concentration limit runs through when it reduces an entry.
 *
 * <p>
 * {@code BracketOrderExecutor.validateEntryIntent} requires the slice quantities
 * to sum exactly to the parent quantity, so an off-by-one here is not a rounding
 * nuisance - it is a rejected bundle and an escalation.
 */
class SliceTrimmingTest {

    private static List<SliceIntent> slices(double... quantities) {
        return java.util.Arrays.stream(quantities)
                .mapToObj(quantity -> new SliceIntent(Decimal.get(quantity), 110.0, 90.0, 1L))
                .toList();
    }

    private static double total(List<SliceIntent> intents) {
        return intents.stream()
                .mapToDouble(intent -> intent.quantity.value().doubleValue())
                .sum();
    }

    @Test
    void anEvenSplitScalesProportionally() {
        List<SliceIntent> trimmed = AbstractStrategy.trimToTotal(
                slices(100, 100), Decimal.get(200), Decimal.get(100));

        assertEquals(2, trimmed.size());
        assertEquals(50.0, trimmed.get(0).quantity.value().doubleValue(), 1.0e-9);
        assertEquals(50.0, trimmed.get(1).quantity.value().doubleValue(), 1.0e-9);
    }

    @Test
    void anUnevenSplitKeepsItsProportionsAndItsTotal() {
        // 300 and 100 scaled to 200 is 150 and 50.
        List<SliceIntent> trimmed = AbstractStrategy.trimToTotal(
                slices(300, 100), Decimal.get(400), Decimal.get(200));

        assertEquals(150.0, trimmed.get(0).quantity.value().doubleValue(), 1.0e-9);
        assertEquals(50.0, trimmed.get(1).quantity.value().doubleValue(), 1.0e-9);
        assertEquals(200.0, total(trimmed), 1.0e-9);
    }

    @Test
    void roundingLossGoesToTheFirstSliceSoThePartsStillSumToTheParent() {
        // 100 and 100 scaled to 101 floors to 50 and 50; the spare share has to
        // land somewhere or the executor rejects the bundle.
        List<SliceIntent> trimmed = AbstractStrategy.trimToTotal(
                slices(100, 100), Decimal.get(200), Decimal.get(101));

        assertEquals(101.0, total(trimmed), 1.0e-9,
                "the slices must sum exactly to the allowed parent quantity");
        assertEquals(51.0, trimmed.get(0).quantity.value().doubleValue(), 1.0e-9);
        assertEquals(50.0, trimmed.get(1).quantity.value().doubleValue(), 1.0e-9);
    }

    @Test
    void aSingleSliceTakesTheWholeAllowance() {
        List<SliceIntent> trimmed = AbstractStrategy.trimToTotal(
                slices(250), Decimal.get(250), Decimal.get(137));

        assertEquals(1, trimmed.size());
        assertEquals(137.0, total(trimmed), 1.0e-9);
    }

    @Test
    void aSliceThatWouldRoundAwayRefusesRatherThanReshapingTheExit() {
        // Scaling 400 and 4 down to 20 leaves the second slice at zero. Shipping
        // one exit where the strategy asked for two would hand it a shape it
        // never chose, and the two-slice strategy manages its slices by position.
        List<SliceIntent> trimmed = AbstractStrategy.trimToTotal(
                slices(400, 4), Decimal.get(404), Decimal.get(20));

        assertTrue(trimmed.isEmpty());
    }

    @Test
    void aZeroAllowanceRefuses() {
        assertTrue(AbstractStrategy.trimToTotal(
                slices(100, 100), Decimal.get(200), Decimal.ZERO).isEmpty());
    }

    @Test
    void theOriginalIntentsAreNotMutated() {
        List<SliceIntent> original = slices(100, 100);

        AbstractStrategy.trimToTotal(original, Decimal.get(200), Decimal.get(101));

        assertEquals(200.0, total(original), 1.0e-9,
                "trimming builds new intents; a rejected entry must leave the strategy's own "
                        + "sizing untouched");
    }
}
