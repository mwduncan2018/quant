package mwd.trading.optionsproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;

import mwd.trading.optionsproxy.proto.IndicatorFrame;

/**
 * Proves the Java engine decodes bytes the Python options proxy actually
 * produced. The fixtures come from the proxy's own serializer; regenerate them
 * with {@code options-proxy/tools/generate_java_fixtures.py} whenever the
 * contract in {@code src/main/proto/market_state.proto} changes.
 */
class PythonIndicatorFrameInteropTest {
    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 27);

    private static byte[] fixture(String name) throws Exception {
        try (InputStream stream = PythonIndicatorFrameInteropTest.class
                .getResourceAsStream("/optionsproxy/" + name)) {
            assertNotNull(stream, "missing golden fixture " + name);
            return stream.readAllBytes();
        }
    }

    @Test
    void aPythonProducedFrameDecodesFieldForField() throws Exception {
        IndicatorFrame frame = IndicatorFrame.parseFrom(fixture("python-indicator-frame.bin"));

        assertEquals("AAPL", frame.getTicker());
        assertEquals("2026-07-27", frame.getTradingDate());
        assertEquals(1L, frame.getSequence());
        assertTrue(frame.getStaticDailyImpliedMoveValid());
        assertEquals(6.272, frame.getStaticDailyImpliedMove(), 1.0e-9);
        assertTrue(frame.getSpyGammaFlipValid());
        assertEquals(601.25, frame.getSpyGammaFlip(), 1.0e-9);
        assertTrue(frame.getEmittedAtUnixMs() > 1_700_000_000_000L,
                "emitted_at_unix_ms should be epoch milliseconds");
    }

    @Test
    void aPythonProducedFrameFlowsThroughTheStore() throws Exception {
        IndicatorFrame frame = IndicatorFrame.parseFrom(fixture("python-indicator-frame.bin"));
        OptionsIndicatorStore store = new OptionsIndicatorStore(Set.of("AAPL", "SPY"), 5000L);

        // Anchoring "now" to the frame's own emission keeps the assertion about
        // decoding rather than about how old the checked-in fixture happens to be.
        long now = frame.getEmittedAtUnixMs();
        assertTrue(store.accept(frame, now));

        assertEquals(OptionalDouble.of(6.272),
                store.impliedMoveForNewEntry("AAPL", TRADING_DATE, now));
        assertEquals(OptionalDouble.of(601.25),
                store.gammaFlipForNewEntry(TRADING_DATE, now));
    }

    @Test
    void pythonEncodesAnAbsentImpliedMoveAsInvalidRatherThanZero() throws Exception {
        IndicatorFrame frame = IndicatorFrame.parseFrom(
                fixture("python-indicator-frame-no-implied-move.bin"));

        assertFalse(frame.getStaticDailyImpliedMoveValid());
        assertEquals(0.0, frame.getStaticDailyImpliedMove(), 0.0);
        assertTrue(frame.getSpyGammaFlipValid());
        assertEquals(601.25, frame.getSpyGammaFlip(), 1.0e-9);

        OptionsIndicatorStore store = new OptionsIndicatorStore(Set.of("AAPL", "SPY"), 5000L);
        long now = frame.getEmittedAtUnixMs();
        assertTrue(store.accept(frame, now));

        assertTrue(store.impliedMoveForNewEntry("AAPL", TRADING_DATE, now).isEmpty());
        assertEquals(OptionalDouble.of(601.25),
                store.gammaFlipForNewEntry(TRADING_DATE, now));
    }
}
