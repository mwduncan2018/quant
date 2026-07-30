package mwd.trading.app;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import javax.swing.SwingUtilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;

import mwd.trading.broker.ibkr.EWrapperRaptor;
import mwd.trading.broker.ibkr.IbkrSessionManager;
import mwd.trading.state.Blackboard;
import mwd.trading.ui.BlackboardMonitor;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.config.Config;
import mwd.trading.config.EnvPropConfig;
import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.risk.MarginMethodology;
import mwd.trading.risk.UniverseReference;
import mwd.trading.broker.ibkr.RequestRegistry;
import mwd.trading.marketdata.TickByTickManager;
import mwd.trading.broker.ibkr.TickMap;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.calendar.MarketCalendarClient;
import mwd.trading.calendar.MarketCalendarRefresher;
import mwd.trading.calendar.MarketCalendarStore;
import mwd.trading.earnings.EarningsClient;
import mwd.trading.earnings.EarningsRefresher;
import mwd.trading.earnings.EarningsSnapshot;
import mwd.trading.earnings.EarningsStore;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.marketdata.MarketDataInputStore;
import mwd.trading.marketdata.MarketDataSubscriptionManager;
import mwd.trading.persistence.JsonTradingStateStore;
import mwd.trading.indicator.DailyWilderAtrCalculator;
import mwd.trading.indicator.IntradayWilderAtrTracker;
import mwd.trading.broker.ibkr.callback.AccountEventHandler;
import mwd.trading.domain.Stock;
import mwd.trading.optionsproxy.OptionsIndicatorFrameReceiver;
import mwd.trading.optionsproxy.OptionsIndicatorStore;
import mwd.trading.optionsproxy.proto.IndicatorFrame;
import mwd.trading.broker.ibkr.callback.IbkrErrorHandler;
import mwd.trading.indicator.SimpleMovingAverageTracker;
import mwd.trading.broker.ibkr.callback.NextValidIdHandler;
import mwd.trading.execution.OrderLifecycleHandler;
import mwd.trading.indicator.RsiTracker;
import mwd.trading.marketdata.MinuteBarHandler;
import mwd.trading.marketdata.PriceTickHandler;
import mwd.trading.marketdata.SizeTickHandler;
import mwd.trading.broker.ibkr.callback.BrokerTimeHandler;
import mwd.trading.indicator.MinuteVolumeTracker;
import mwd.trading.reconciliation.BrokerState;
import mwd.trading.reconciliation.ReconciliationManager;
import mwd.trading.execution.BracketOrderExecutor;
import mwd.trading.strategy.AbstractStrategy;
import mwd.trading.strategy.OneSigmaDownsideMeanReversionStrategy;
import mwd.trading.strategy.OneSigmaUpsideMeanReversionStrategy;
import mwd.trading.strategy.TwoSigmaDownsideMeanReversionStrategy;

public class Main {
    private static final Logger logger = LogManager.getLogger(Main.class);

    /**
     * How often the proxy reference refreshers wake to notice a session
     * rollover once the current session is already covered. Nothing is fetched
     * on these wake-ups; each store answers from what it holds.
     */
    private static final long PROXY_IDLE_RECHECK_MS = 60_000L;

    public static void main(String[] args) throws Exception {
        Config config = new EnvPropConfig();
        logStartupContext(config);
        // Every symbol either strategy trades or references needs market data.
        List<String> marketDataSymbols = Stream.of(
                config.getStrategyUniverse(TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID),
                config.getStrategyReferenceSymbols(TwoSigmaDownsideMeanReversionStrategy.STRATEGY_ID),
                config.getStrategyUniverse(OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID),
                config.getStrategyReferenceSymbols(OneSigmaDownsideMeanReversionStrategy.STRATEGY_ID),
                config.getStrategyUniverse(OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID),
                config.getStrategyReferenceSymbols(OneSigmaUpsideMeanReversionStrategy.STRATEGY_ID))
                .flatMap(Set::stream)
                .distinct()
                .sorted()
                .toList();
        RequestRegistry registry = new RequestRegistry();
        TickMap tickMap = new TickMap(config);
        Blackboard blackboard = new Blackboard(
                new IdManager(),
                new TimeManager(),
                new OrderRegistry(),
                config);

        TradingGate tradingGate = new TradingGate();
        BrokerState brokerState = new BrokerState();
        JsonTradingStateStore stateStore = new JsonTradingStateStore(Path.of(config.getTradingStatePath()));
        if (stateStore.recoveredFromBackup()) {
            logger.warn("Recovered trading state from the backup JSON file; broker reconciliation is required");
        }
        if (!stateStore.activeTrades().isEmpty()) {
            tradingGate.requireManualIntervention(
                    "The process restarted with active local trades; review the broker state before resuming automation");
        }

        ReconciliationManager reconciliationManager = new ReconciliationManager(
                brokerState,
                stateStore,
                tradingGate,
                blackboard::getNextRequestId,
                Set.copyOf(marketDataSymbols));

        AccountEventHandler accountEventHandler = new AccountEventHandler(blackboard, reconciliationManager);
        OrderLifecycleHandler orderLifecycleHandler = new OrderLifecycleHandler(
                blackboard, reconciliationManager, stateStore, tradingGate);

        // Owns per-input readiness for every IBKR-sourced value. The handlers below
        // record into it only after accepting a value, so a ready input always
        // means a validated number reached the Blackboard.
        MarketDataInputStore marketDataInputStore =
                new MarketDataInputStore(config.getMarketDataMaxAgeMs());

        EWrapperRaptor wrapper = new EWrapperRaptor(
                registry,
                new IntradayWilderAtrTracker(blackboard, registry),
                new DailyWilderAtrCalculator(blackboard, registry),
                new IbkrErrorHandler(blackboard),
                accountEventHandler,
                new SimpleMovingAverageTracker(blackboard, registry, tickMap),
                new NextValidIdHandler(blackboard),
                orderLifecycleHandler,
                new RsiTracker(blackboard, registry),
                new MinuteBarHandler(blackboard, registry, marketDataInputStore),
                new PriceTickHandler(blackboard, registry, tickMap, marketDataInputStore),
                new SizeTickHandler(blackboard, registry, tickMap),
                new BrokerTimeHandler(blackboard),
                new MinuteVolumeTracker(blackboard, registry, marketDataInputStore));

        IbkrSessionManager sessionManager = new IbkrSessionManager(
                wrapper, config, tradingGate, reconciliationManager);
        MarketDataSubscriptionManager subscriptionManager = new MarketDataSubscriptionManager(
                sessionManager.client(), blackboard, registry, marketDataSymbols,
                marketDataInputStore);
        sessionManager.attachSubscriptionManager(subscriptionManager);
        wrapper.attachLifecycle(sessionManager, subscriptionManager);

        TickByTickManager tickByTickManager = new TickByTickManager(
                blackboard, sessionManager.client(), registry);

        OptionsIndicatorStore optionsIndicatorStore = new OptionsIndicatorStore(
                Set.copyOf(marketDataSymbols),
                config.getOptionsProxyFrameMaxAgeMs());
        OptionsIndicatorFrameReceiver optionsIndicatorFrameReceiver = null;
        if (config.isOptionsProxyEnabled()) {
            optionsIndicatorFrameReceiver = new OptionsIndicatorFrameReceiver(
                    optionsIndicatorStore,
                    config.getOptionsProxyBindHost(),
                    config.getOptionsProxyUdpPort(),
                    frame -> mirrorFrameForMonitor(blackboard, frame));
            optionsIndicatorFrameReceiver.start();
        } else {
            logger.warn("OPTIONS_PROXY_ENABLED is false; no options indicators will be received "
                    + "and no strategy that depends on them can open a position");
        }

        // Earnings dates are pulled rather than broadcast: they are fetched once
        // each morning by the proxy and static for the session, so a
        // request/response lifecycle fits where a 1 Hz stream would not.
        EarningsStore earningsStore = new EarningsStore(Set.copyOf(marketDataSymbols));
        Thread earningsRefresherThread = null;
        if (config.isEarningsEnabled()) {
            EarningsRefresher earningsRefresher = new EarningsRefresher(
                    new EarningsClient(
                            config.getEarningsEndpointUrl(),
                            config.getEarningsConnectTimeoutMs(),
                            config.getEarningsRequestTimeoutMs()),
                    earningsStore,
                    config.getEarningsRetryDelayMs(),
                    PROXY_IDLE_RECHECK_MS,
                    snapshot -> mirrorEarningsForMonitor(blackboard, snapshot));
            earningsRefresherThread = new Thread(earningsRefresher, "Earnings-Refresher-Thread");
            earningsRefresherThread.setDaemon(true);
            earningsRefresherThread.start();
        } else {
            logger.warn("EARNINGS_ENABLED is false; no earnings dates will be retrieved "
                    + "and any strategy that depends on them cannot open a position");
        }

        // The session close drives both the entry cutoff and the time exit, so
        // no clock time is encoded in the engine. An early-close day shortens
        // the trading window on its own.
        MarketCalendarStore marketCalendarStore = new MarketCalendarStore();
        MarketCalendarRefresher marketCalendarRefresher = new MarketCalendarRefresher(
                new MarketCalendarClient(
                        config.getMarketCalendarEndpointUrl(),
                        config.getEarningsConnectTimeoutMs(),
                        config.getEarningsRequestTimeoutMs()),
                marketCalendarStore,
                config.getEarningsRetryDelayMs(),
                PROXY_IDLE_RECHECK_MS);
        Thread marketCalendarThread =
                new Thread(marketCalendarRefresher, "Market-Calendar-Refresher-Thread");
        marketCalendarThread.setDaemon(true);
        marketCalendarThread.start();

        // Margin rates are configuration rather than measurement. They used to be
        // priced by a what-if order per symbol per direction every five minutes,
        // which ran at roughly twelve requests a minute against IBKR's stated
        // ceiling of one, and cancelled none of them.
        UniverseReference universeReference = UniverseReference.load(
                java.nio.file.Path.of(config.getUniverseReferencePath()),
                MarginMethodology.parse(config.getMarginMethodology()),
                config.getDefaultLongMarginRate(),
                config.getDefaultShortMarginRate());
        for (String line : universeReference.describeCoverage(
                Set.copyOf(marketDataSymbols), java.time.LocalDate.now())) {
            logger.info(line);
        }
        universeReference.ageInDays(java.time.LocalDate.now())
                .filter(age -> age > config.getUniverseReferenceMaxAgeDays())
                .ifPresent(age -> logger.warn(
                        "Margin reference data is {} days old, past the {} day limit; IBKR "
                                + "reprices margin without notice, so sizing may be wrong",
                        age, config.getUniverseReferenceMaxAgeDays()));

        if (config.showUI()) {
            SwingUtilities.invokeLater(() -> new BlackboardMonitor(blackboard, universeReference));
        }

        OptionsIndicatorFrameReceiver receiverForShutdown = optionsIndicatorFrameReceiver;
        Thread earningsRefresherForShutdown = earningsRefresherThread;
        Thread marketCalendarForShutdown = marketCalendarThread;
        // Order execution. Both strategies submit through the same executor, so
        // the trading gate and the local journal see every order from one place.
        BracketOrderExecutor bracketOrderExecutor = new BracketOrderExecutor(
                blackboard, sessionManager.client(), tradingGate, stateStore, config);

        List<Thread> strategyThreads = List.of(
                strategyThread(new TwoSigmaDownsideMeanReversionStrategy(
                        blackboard,
                        bracketOrderExecutor,
                        tickByTickManager,
                        config,
                        tradingGate,
                        marketDataInputStore,
                        universeReference,
                        optionsIndicatorStore,
                        earningsStore,
                        marketCalendarStore)),
                strategyThread(new OneSigmaDownsideMeanReversionStrategy(
                        blackboard,
                        bracketOrderExecutor,
                        tickByTickManager,
                        config,
                        tradingGate,
                        marketDataInputStore,
                        universeReference,
                        optionsIndicatorStore,
                        marketCalendarStore)),
                strategyThread(new OneSigmaUpsideMeanReversionStrategy(
                        blackboard,
                        bracketOrderExecutor,
                        tickByTickManager,
                        config,
                        tradingGate,
                        marketDataInputStore,
                        universeReference,
                        optionsIndicatorStore,
                        marketCalendarStore)));
        strategyThreads.forEach(Thread::start);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown requested; stopping the strategies, earnings refresher, "
                    + "options-proxy receiver, IBKR session, and reconciliation manager");
            strategyThreads.forEach(Thread::interrupt);
            if (earningsRefresherForShutdown != null) {
                earningsRefresherForShutdown.interrupt();
            }
            marketCalendarForShutdown.interrupt();
            if (receiverForShutdown != null) {
                receiverForShutdown.stop();
            }
            sessionManager.close();
            reconciliationManager.close();
            logger.info("Shutdown complete");
            // Last statement: log4j's own hook is disabled so this sequence
            // reaches the file instead of racing it. Nothing may log after this.
            LogManager.shutdown();
        }, "Trading-Engine-Shutdown"));

        sessionManager.start();
        logger.info("Trading engine started with the entry gate closed pending IBKR reconciliation");
        logger.info("{} strategy threads are running; no entry can be submitted until "
                + "reconciliation moves the gate to READY", strategyThreads.size());

        if (tickByTickManager.getActiveStreamCount() != 0) {
            logger.warn("Unexpected tick-by-tick stream was active during startup");
        }

        new CountDownLatch(1).await();
    }

    /** Wraps a strategy in the daemon thread its poll loop runs on. */
    private static Thread strategyThread(AbstractStrategy strategy) {
        Thread thread = new Thread(strategy, strategy.getClass().getSimpleName() + "-Thread");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Writes the header a later investigation starts from: which file this
     * session is writing to, and the settings that shaped its behaviour. Session
     * logs are only useful if you can tell which run produced them and under what
     * configuration.
     *
     * <p>
     * The path is read back from the appender rather than from the environment,
     * so it cannot drift from {@code log4j2.xml}.
     */
    private static void logStartupContext(Config config) {
        logger.info("Trading engine starting: IBKR data is {}, market-data type {}, "
                + "strategy poll rate {}ms",
                config.isLiveIBKRData() ? "LIVE" : "DELAYED/PAPER",
                config.getMarketDataType(),
                config.getStrategyPollRateMs());
        logger.info("Readiness limits: market data {}ms, options-proxy frames {}ms",
                config.getMarketDataMaxAgeMs(),
                config.getOptionsProxyFrameMaxAgeMs());
        logger.info("Trading state journal: {}", Path.of(config.getTradingStatePath()).toAbsolutePath());

        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Appender appender = context.getConfiguration().getAppender("EngineFile");
            if (appender instanceof RollingFileAppender rollingFileAppender) {
                logger.info("Session log: {}",
                        Path.of(rollingFileAppender.getFileName()).toAbsolutePath());
            } else {
                logger.warn("No rolling file appender is configured; this session leaves "
                        + "no reviewable record once the console buffer is gone");
            }
        } catch (RuntimeException exception) {
            logger.warn("Could not resolve the engine log file location: {}", exception.toString());
        }
    }

    /**
     * Copies accepted indicator values onto {@link Stock} purely so the Swing
     * monitor can display them. These copies carry no validity or session
     * information and must never be read by a strategy; OptionsIndicatorStore is
     * the source of truth.
     */
    /**
     * Copies accepted earnings dates onto {@link Stock} so the Swing monitor's
     * Earnings column can show them. Display only, exactly like
     * {@link #mirrorFrameForMonitor}: these fields carry no session or validity
     * information, and {@link EarningsStore} is the source of truth.
     *
     * <p>
     * Noon New York is used because the monitor renders a date, not a time, and
     * a midnight instant would display as the previous day west of UTC.
     */
    private static void mirrorEarningsForMonitor(
            Blackboard blackboard, EarningsSnapshot snapshot) {
        snapshot.byTicker().forEach((ticker, value) -> {
            if (value.nextEarningsDate() == null) {
                return;
            }
            blackboard.getStock(ticker).setNextEarningsDate(value.nextEarningsDate()
                    .atTime(LocalTime.NOON)
                    .atZone(ZoneId.of("America/New_York"))
                    .toInstant());
        });
    }

    private static void mirrorFrameForMonitor(Blackboard blackboard, IndicatorFrame frame) {
        if (frame.getStaticDailyImpliedMoveValid()) {
            blackboard.getStock(frame.getTicker())
                    .setDailyImpliedMove(frame.getStaticDailyImpliedMove());
        }
        if (frame.getSpyGammaFlipValid()) {
            blackboard.getStock("SPY").setGammaFlip(frame.getSpyGammaFlip());
        }
    }
}
