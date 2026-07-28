package mwd.trading.optionsproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import mwd.trading.optionsproxy.proto.IndicatorFrame;

class OptionsIndicatorFrameReceiverTest {
    private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 27);
    private static final long MAX_AGE_MS = 5000L;

    private OptionsIndicatorFrameReceiver receiver;

    @AfterEach
    void stopReceiver() {
        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }
    }

    private static IndicatorFrame.Builder frame(long sequence) {
        return IndicatorFrame.newBuilder()
                .setSequence(sequence)
                .setEmittedAtUnixMs(System.currentTimeMillis())
                .setTicker("AAPL")
                .setTradingDate(TRADING_DATE.toString())
                .setStaticDailyImpliedMove(6.272)
                .setStaticDailyImpliedMoveValid(true)
                .setSpyGammaFlip(601.25)
                .setSpyGammaFlipValid(true);
    }

    private static void send(int port, byte[] payload) throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.send(new DatagramPacket(
                    payload, payload.length, InetAddress.getLoopbackAddress(), port));
        }
    }

    @Test
    void aFrameSentOverLoopbackReachesTheStore() throws Exception {
        OptionsIndicatorStore store = new OptionsIndicatorStore(Set.of("AAPL"), MAX_AGE_MS);
        CountDownLatch accepted = new CountDownLatch(1);
        AtomicReference<IndicatorFrame> observed = new AtomicReference<>();
        receiver = new OptionsIndicatorFrameReceiver(store, "127.0.0.1", 0, receivedFrame -> {
            observed.set(receivedFrame);
            accepted.countDown();
        });
        receiver.start();
        assertNotEquals(-1, receiver.getBoundPort());

        send(receiver.getBoundPort(), frame(1).build().toByteArray());

        assertTrue(accepted.await(5, TimeUnit.SECONDS), "the frame was never accepted");
        assertEquals("AAPL", observed.get().getTicker());
        assertEquals(OptionalDouble.of(6.272), store.lastKnownImpliedMove("AAPL"));
        assertEquals(OptionalDouble.of(601.25),
                store.gammaFlipForNewEntry(TRADING_DATE, System.currentTimeMillis()));
        assertEquals(1L, receiver.getReceivedDatagramCount());
        assertEquals(0L, receiver.getMalformedDatagramCount());
    }

    @Test
    void garbageAndOutOfOrderDatagramsNeverCorruptTheStore() throws Exception {
        OptionsIndicatorStore store = new OptionsIndicatorStore(Set.of("AAPL"), MAX_AGE_MS);
        CountDownLatch firstAccepted = new CountDownLatch(1);
        receiver = new OptionsIndicatorFrameReceiver(
                store, "127.0.0.1", 0, receivedFrame -> firstAccepted.countDown());
        receiver.start();
        int port = receiver.getBoundPort();

        send(port, frame(2).build().toByteArray());
        assertTrue(firstAccepted.await(5, TimeUnit.SECONDS), "the first frame was never accepted");

        // Random bytes, a truncated frame, and a replayed sequence.
        send(port, "this is not a protobuf frame".getBytes(StandardCharsets.UTF_8));
        send(port, new byte[] {(byte) 0x0A, (byte) 0xFF});
        send(port, frame(1).setStaticDailyImpliedMove(99.9).build().toByteArray());

        long deadline = System.currentTimeMillis() + 5000;
        while (receiver.getReceivedDatagramCount() < 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(4L, receiver.getReceivedDatagramCount());
        assertEquals(1L, store.getAcceptedFrameCount());
        assertEquals(OptionalDouble.of(6.272), store.lastKnownImpliedMove("AAPL"));
    }

    @Test
    void stoppingClosesTheSocketAndIsSafeToRepeat() throws Exception {
        OptionsIndicatorStore store = new OptionsIndicatorStore(Set.of("AAPL"), MAX_AGE_MS);
        receiver = new OptionsIndicatorFrameReceiver(store, "127.0.0.1", 0);
        receiver.start();
        assertTrue(receiver.isRunning());
        int port = receiver.getBoundPort();

        receiver.stop();
        receiver.stop();

        assertFalse(receiver.isRunning());
        assertEquals(-1, receiver.getBoundPort());

        // The port is released, so the same address can be bound again.
        OptionsIndicatorFrameReceiver replacement =
                new OptionsIndicatorFrameReceiver(store, "127.0.0.1", port);
        try {
            replacement.start();
            assertEquals(port, replacement.getBoundPort());
        } finally {
            replacement.stop();
        }
    }
}
