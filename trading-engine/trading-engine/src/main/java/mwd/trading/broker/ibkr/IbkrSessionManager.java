package mwd.trading.broker.ibkr;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EWrapper;

import mwd.trading.config.Config;
import mwd.trading.lifecycle.EngineMode;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.marketdata.MarketDataSubscriptionManager;
import mwd.trading.reconciliation.ReconciliationManager;

/**
 * Owns the local TWS API socket, reader thread, reconnect scheduling, and the
 * startup/reconnect sequence that must finish before reconciliation can open
 * the trading gate.
 */
public final class IbkrSessionManager implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(IbkrSessionManager.class);
    private static final Pattern PORT_PATTERN = Pattern.compile("(?i).*port[^0-9]*(\\d{2,5}).*");

    private final Config config;
    private final TradingGate tradingGate;
    private final ReconciliationManager reconciliationManager;
    private final EJavaSignal signal = new EJavaSignal();
    private final EClientSocket client;
    private final ScheduledExecutorService lifecycleExecutor;
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean apiReadyHandled = new AtomicBoolean();
    private volatile MarketDataSubscriptionManager subscriptionManager;
    private volatile boolean stopping;
    private volatile boolean hasConnectedBefore;
    private volatile int currentPort;
    private volatile Thread readerThread;

    public IbkrSessionManager(
            EWrapper wrapper,
            Config config,
            TradingGate tradingGate,
            ReconciliationManager reconciliationManager) {
        this.config = config;
        this.tradingGate = tradingGate;
        this.reconciliationManager = reconciliationManager;
        this.currentPort = config.getIBKRPort();
        this.client = new EClientSocket(wrapper, signal);
        this.reconciliationManager.attachClient(client);
        this.lifecycleExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "IBKR-Session-Lifecycle");
            thread.setDaemon(true);
            return thread;
        });
    }

    public EClientSocket client() {
        return client;
    }

    public void attachSubscriptionManager(MarketDataSubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    public void start() {
        lifecycleExecutor.execute(this::connectNow);
    }

    public void onConnectAck() {
        logger.info("TWS acknowledged the API socket connection");
    }

    public void onNextValidId() {
        if (!apiReadyHandled.compareAndSet(false, true)) {
            return;
        }
        lifecycleExecutor.execute(this::initializeConnectedSession);
    }

    public void onManagedAccounts(String accountsList) {
        String[] managedAccounts = accounts(accountsList);
        if (managedAccounts.length == 0) {
            tradingGate.requireManualIntervention("IBKR returned no managed account");
            return;
        }

        String expectedAccount = config.getExpectedAccount();
        String account = managedAccounts[0];
        if (!expectedAccount.isBlank()) {
            boolean expectedAccountIsManaged = java.util.Arrays.stream(managedAccounts)
                    .anyMatch(expectedAccount::equals);
            if (!expectedAccountIsManaged) {
                tradingGate.requireManualIntervention(
                        "Configured account " + expectedAccount + " is not in IBKR managed accounts");
                return;
            }
            account = expectedAccount;
        }
        if (client.isConnected()) {
            client.reqAccountUpdates(true, account);
        }
    }

    public void onConnectionClosed() {
        if (stopping) {
            return;
        }
        apiReadyHandled.set(false);
        reconciliationManager.onDisconnected("TWS API socket closed");
        markMarketDataStale();
        scheduleReconnect();
    }

    public void onError(int errorCode, String errorMessage) {
        switch (errorCode) {
            case 1100 -> {
                reconciliationManager.onDisconnected("TWS lost connectivity to IBKR servers (1100)");
                markMarketDataStale();
            }
            case 1101 -> lifecycleExecutor.execute(() -> restoreBrokerConnectivity(true));
            case 1102 -> lifecycleExecutor.execute(() -> restoreBrokerConnectivity(false));
            case 1300 -> {
                updatePortFromMessage(errorMessage);
                reconciliationManager.onDisconnected("TWS API port changed (1300)");
                markMarketDataStale();
                if (client.isConnected()) {
                    client.eDisconnect();
                }
                scheduleReconnect();
            }
            case 502, 504, 509 -> {
                reconciliationManager.onDisconnected("TWS API connection error " + errorCode);
                markMarketDataStale();
                scheduleReconnect();
            }
            default -> {
                // Other errors are routed to their request/order consumers.
            }
        }
    }

    private void connectNow() {
        if (stopping || client.isConnected()) {
            return;
        }
        reconnectScheduled.set(false);
        tradingGate.transitionTo(EngineMode.CONNECTING,
                "Connecting to TWS at " + config.getIBKRHost() + ':' + currentPort);
        apiReadyHandled.set(false);

        try {
            client.eConnect(config.getIBKRHost(), currentPort, config.getIBKRClientId());
            if (!client.isConnected()) {
                scheduleReconnect();
                return;
            }
            startReaderThread();
        } catch (RuntimeException exception) {
            logger.error("Unable to connect to TWS", exception);
            scheduleReconnect();
        }
    }

    private void startReaderThread() {
        EReader reader = new EReader(client, signal);
        reader.start();
        Thread thread = new Thread(() -> {
            while (!stopping && client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception exception) {
                    logger.error("IBKR reader failed", exception);
                    onConnectionClosed();
                    break;
                }
            }
        }, "IBKR-Reader");
        thread.setDaemon(true);
        readerThread = thread;
        thread.start();
    }

    private void initializeConnectedSession() {
        if (stopping || !client.isConnected()) {
            return;
        }

        try {
            client.reqMarketDataType(config.getMarketDataType());
            client.reqCurrentTime();
            MarketDataSubscriptionManager subscriptions = subscriptionManager;
            if (subscriptions != null) {
                if (hasConnectedBefore) {
                    subscriptions.resetAfterSocketReconnect();
                } else {
                    subscriptions.initializeIfNeeded();
                }
            }
            hasConnectedBefore = true;
            reconciliationManager.begin("IBKR API session established; validating broker state");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            tradingGate.requireManualIntervention("Interrupted while restoring market-data subscriptions");
        } catch (RuntimeException exception) {
            tradingGate.requireManualIntervention(
                    "Failed to initialize the connected IBKR session: " + exception.getMessage());
            logger.error("Failed to initialize the connected IBKR session", exception);
        }
    }

    private void restoreBrokerConnectivity(boolean dataLost) {
        if (stopping || !client.isConnected()) {
            scheduleReconnect();
            return;
        }
        tradingGate.transitionTo(EngineMode.RECONCILING,
                dataLost ? "IBKR connectivity restored; rebuilding subscriptions"
                        : "IBKR connectivity restored; validating broker state");
        try {
            if (dataLost && subscriptionManager != null) {
                subscriptionManager.resubscribeAfterDataLoss();
            }
            reconciliationManager.begin("IBKR server connectivity restored");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            tradingGate.requireManualIntervention("Interrupted while rebuilding market-data subscriptions");
        } catch (RuntimeException exception) {
            tradingGate.requireManualIntervention(
                    "Failed to restore the IBKR session: " + exception.getMessage());
        }
    }

    private void scheduleReconnect() {
        if (stopping || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        tradingGate.transitionTo(EngineMode.CONNECTING, "Waiting to reconnect to TWS");
        lifecycleExecutor.schedule(this::connectNow, config.getReconnectDelayMs(), TimeUnit.MILLISECONDS);
    }

    private void markMarketDataStale() {
        if (subscriptionManager != null) {
            subscriptionManager.markAllStale();
        }
    }

    private void updatePortFromMessage(String message) {
        if (message == null) {
            return;
        }
        Matcher matcher = PORT_PATTERN.matcher(message);
        if (matcher.matches()) {
            int parsedPort = Integer.parseInt(matcher.group(1));
            if (parsedPort > 0 && parsedPort <= 65535) {
                currentPort = parsedPort;
            }
        }
    }

    private static String[] accounts(String accountsList) {
        if (accountsList == null || accountsList.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(accountsList.split(","))
                .map(String::trim)
                .filter(account -> !account.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void close() {
        stopping = true;
        tradingGate.transitionTo(EngineMode.STOPPING, "Application stopping");
        if (client.isConnected()) {
            client.eDisconnect();
        }
        Thread thread = readerThread;
        if (thread != null) {
            thread.interrupt();
        }
        lifecycleExecutor.shutdownNow();
    }
}
