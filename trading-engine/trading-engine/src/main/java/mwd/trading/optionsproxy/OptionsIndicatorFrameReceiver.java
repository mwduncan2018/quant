package mwd.trading.optionsproxy;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.protobuf.InvalidProtocolBufferException;

import mwd.trading.optionsproxy.proto.IndicatorFrame;

/**
 * Receives {@code IndicatorFrame} datagrams from the Python options proxy.
 *
 * <p>The proxy broadcasts continuously, so start order between the two
 * processes does not matter: whichever starts second simply begins consuming
 * the next frame. A datagram that cannot be parsed is dropped and counted;
 * everything else is handed to {@link OptionsIndicatorStore}, which owns the
 * semantic validation.
 */
public final class OptionsIndicatorFrameReceiver {
    /** Notified for each frame the store accepted, on the receiver thread. */
    @FunctionalInterface
    public interface AcceptedFrameListener {
        void onAccepted(IndicatorFrame frame);
    }

    private static final Logger logger = LogManager.getLogger(OptionsIndicatorFrameReceiver.class);

    // The proxy caps its payload at UDP_MTU (1400 bytes by default). The extra
    // headroom lets an oversized datagram arrive intact and be rejected as
    // malformed rather than be silently truncated into something parseable.
    private static final int RECEIVE_BUFFER_BYTES = 8192;

    private final OptionsIndicatorStore store;
    private final String bindHost;
    private final int bindPort;
    private final AcceptedFrameListener acceptedFrameListener;

    private final AtomicLong receivedDatagrams = new AtomicLong();
    private final AtomicLong malformedDatagrams = new AtomicLong();
    private volatile long lastDatagramAtUnixMs;
    private volatile DatagramSocket socket;
    private volatile Thread receiverThread;
    private volatile boolean running;

    public OptionsIndicatorFrameReceiver(
            OptionsIndicatorStore store,
            String bindHost,
            int bindPort,
            AcceptedFrameListener acceptedFrameListener) {
        this.store = Objects.requireNonNull(store, "store");
        this.bindHost = Objects.requireNonNull(bindHost, "bindHost");
        this.bindPort = bindPort;
        this.acceptedFrameListener = acceptedFrameListener;
    }

    public OptionsIndicatorFrameReceiver(
            OptionsIndicatorStore store, String bindHost, int bindPort) {
        this(store, bindHost, bindPort, null);
    }

    /** Bind the socket and start the daemon receive loop. */
    public synchronized void start() throws SocketException {
        if (running) {
            return;
        }
        DatagramSocket boundSocket = new DatagramSocket(null);
        boundSocket.setReuseAddress(true);
        boundSocket.bind(new InetSocketAddress(bindHost, bindPort));
        this.socket = boundSocket;
        this.running = true;

        Thread thread = new Thread(this::receiveLoop, "Options-Proxy-UDP-Receiver");
        thread.setDaemon(true);
        this.receiverThread = thread;
        thread.start();
        logger.info("OptionsIndicatorFrameReceiver.start - Listening for options-proxy frames on {}:{}",
                bindHost, boundSocket.getLocalPort());
    }

    /** Close the socket and wait briefly for the receive loop to finish. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        DatagramSocket openSocket = this.socket;
        if (openSocket != null) {
            openSocket.close();
        }
        Thread thread = this.receiverThread;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        this.socket = null;
        this.receiverThread = null;
        logger.info("OptionsIndicatorFrameReceiver.stop - Stopped after {} datagrams ({} accepted, {} rejected)",
                receivedDatagrams.get(), store.getAcceptedFrameCount(), store.getRejectedFrameCount());
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        DatagramSocket openSocket = this.socket;
        while (running && openSocket != null && !openSocket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                openSocket.receive(packet);
            } catch (SocketException exception) {
                if (running) {
                    logger.error("OptionsIndicatorFrameReceiver.receiveLoop - Socket failed; the receiver is stopping",
                            exception);
                }
                return;
            } catch (IOException exception) {
                logger.warn("OptionsIndicatorFrameReceiver.receiveLoop - Ignoring an unreadable datagram",
                        exception);
                continue;
            }

            long receivedAtUnixMs = System.currentTimeMillis();
            receivedDatagrams.incrementAndGet();
            lastDatagramAtUnixMs = receivedAtUnixMs;

            IndicatorFrame frame;
            try {
                frame = IndicatorFrame.parseFrom(ByteBuffer.wrap(
                        packet.getData(), packet.getOffset(), packet.getLength()));
            } catch (InvalidProtocolBufferException exception) {
                malformedDatagrams.incrementAndGet();
                logger.debug("OptionsIndicatorFrameReceiver.receiveLoop - Dropped a malformed {}-byte datagram",
                        packet.getLength());
                continue;
            }

            if (store.accept(frame, receivedAtUnixMs) && acceptedFrameListener != null) {
                try {
                    acceptedFrameListener.onAccepted(frame);
                } catch (RuntimeException exception) {
                    logger.error("OptionsIndicatorFrameReceiver.receiveLoop - Accepted-frame listener failed for {}",
                            frame.getTicker(), exception);
                }
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** The bound port, which resolves an ephemeral port requested as 0. */
    public int getBoundPort() {
        DatagramSocket openSocket = this.socket;
        return openSocket == null ? -1 : openSocket.getLocalPort();
    }

    public long getReceivedDatagramCount() {
        return receivedDatagrams.get();
    }

    public long getMalformedDatagramCount() {
        return malformedDatagrams.get();
    }

    public long getLastDatagramAtUnixMs() {
        return lastDatagramAtUnixMs;
    }
}
