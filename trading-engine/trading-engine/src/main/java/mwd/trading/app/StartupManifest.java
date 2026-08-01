package mwd.trading.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import mwd.trading.config.Config;
import mwd.trading.lifecycle.TradingEnvironment;
import mwd.trading.strategy.StrategyActivationPolicy;

/** Immutable identity and activation record emitted once at process startup. */
public record StartupManifest(
        TradingEnvironment tradingEnvironment,
        String accountSelection,
        int clientId,
        List<String> enabledStrategies,
        String tradingStateFile,
        String sessionLogFile) {

    public static final String BROKER_SELECTED_ACCOUNT = "<TWS_MANAGED_ACCOUNT>";
    public static final String UNAVAILABLE_LOG_FILE = "<UNAVAILABLE>";

    public StartupManifest {
        Objects.requireNonNull(tradingEnvironment, "tradingEnvironment");
        accountSelection = requireText(accountSelection, "accountSelection");
        enabledStrategies = List.copyOf(Objects.requireNonNull(
                enabledStrategies, "enabledStrategies"));
        tradingStateFile = requireText(tradingStateFile, "tradingStateFile");
        sessionLogFile = requireText(sessionLogFile, "sessionLogFile");
    }

    public static StartupManifest from(
            Config config,
            StrategyActivationPolicy activationPolicy,
            String sessionLogFile) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(activationPolicy, "activationPolicy");
        String expectedAccount = config.getExpectedAccount().trim();
        String accountSelection = expectedAccount.isEmpty()
                ? BROKER_SELECTED_ACCOUNT
                : expectedAccount;
        return new StartupManifest(
                activationPolicy.environment(),
                accountSelection,
                config.getIBKRClientId(),
                activationPolicy.enabledStrategyIds(),
                Path.of(config.getTradingStatePath()).toAbsolutePath().normalize().toString(),
                sessionLogFile);
    }

    public String toLogValue() {
        return "mode=" + tradingEnvironment
                + " account=" + accountSelection
                + " clientId=" + clientId
                + " enabledStrategies=" + enabledStrategies
                + " stateFile=" + tradingStateFile
                + " logFile=" + sessionLogFile;
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
