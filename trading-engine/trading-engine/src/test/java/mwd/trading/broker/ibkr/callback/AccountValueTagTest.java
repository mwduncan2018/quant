package mwd.trading.broker.ibkr.callback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.lifecycle.TradingGate;
import mwd.trading.persistence.JsonTradingStateStore;
import mwd.trading.reconciliation.BrokerState;
import mwd.trading.reconciliation.ReconciliationManager;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The account-value tags are matched by exact string, so a name that IBKR never
 * sends is indistinguishable from a value that never arrives: the field simply
 * stays at zero for the life of the process.
 *
 * <p>
 * That is how `ExcessLiquidity` came to be read as `ExcessMargin`, which is not
 * an IBKR tag. It cost nothing while the figure only drew a label, and would have
 * cost a great deal as soon as anything gated on it - a watchdog reading a
 * permanent zero either fires immediately or, written the other way, blocks every
 * entry forever.
 */
class AccountValueTagTest {
    private Blackboard blackboard;
    private AccountEventHandler handler;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        TestConfig config = new TestConfig(3, 10_000L, Set.of("AAPL"));
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(), config);
        handler = new AccountEventHandler(
                blackboard,
                new ReconciliationManager(
                        new BrokerState(),
                        new JsonTradingStateStore(directory.resolve("state.json")),
                        new TradingGate(),
                        () -> 1,
                        Set.of("AAPL")));
    }

    private void send(String key, String value) {
        handler.onUpdateAccountValue(key, value, "USD", "DU123456");
    }

    @Test
    void theTagsThatDriveSizingAreCaptured() {
        send("NetLiquidation", "100000");
        send("AvailableFunds", "50000");

        assertEquals(100_000.0, blackboard.getAccount().getNetLiquidation(), 1.0e-9);
        assertEquals(50_000.0, blackboard.getAccount().getAvailableFunds(), 1.0e-9);
    }

    @Test
    void excessLiquidityIsReadFromTheTagIbkrActuallySends() {
        send("ExcessLiquidity", "42000");

        assertEquals(42_000.0, blackboard.getAccount().getExcessLiquidity(), 1.0e-9);
    }

    @Test
    void theOldMisspelledTagIsNotHonoured() {
        // Guards the regression rather than the fix: if someone reinstates
        // "ExcessMargin" the field must stay zero, because IBKR does not send it
        // and a value arriving under that name would mean something else.
        send("ExcessMargin", "42000");

        assertEquals(0.0, blackboard.getAccount().getExcessLiquidity(), 1.0e-9);
    }

    @Test
    void cushionIsCaptured() {
        send("Cushion", "0.62");

        assertEquals(0.62, blackboard.getAccount().getCushion(), 1.0e-9);
    }

    @Test
    void aForeignCurrencyValueIsIgnored() {
        send("NetLiquidation", "100000");
        handler.onUpdateAccountValue("NetLiquidation", "999", "EUR", "DU123456");

        assertEquals(100_000.0, blackboard.getAccount().getNetLiquidation(), 1.0e-9);
    }
}
