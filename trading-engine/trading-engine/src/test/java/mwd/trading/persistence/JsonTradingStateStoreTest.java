package mwd.trading.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ib.client.Decimal;

import mwd.trading.execution.BracketOrder;

class JsonTradingStateStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndReloadsOrderIntentAtomically() throws Exception {
        Path statePath = temporaryDirectory.resolve("data").resolve("trading-state-paper.json");
        JsonTradingStateStore store = new JsonTradingStateStore(statePath);
        BracketOrder bracket = bracket("trade-1");

        store.recordIntent(bracket, "BUY");

        assertTrue(Files.exists(statePath));
        assertFalse(Files.exists(statePath.resolveSibling("trading-state-paper.json.tmp")));

        JsonTradingStateStore reloaded = new JsonTradingStateStore(statePath);
        JsonTradingStateStore.TradeRecord trade = reloaded.findTrade("trade-1").orElseThrow();
        assertEquals("TWO_SIGMA_DOWNSIDE", trade.strategy());
        assertEquals("DU123456", trade.account());
        assertEquals("AAPL", trade.symbol());
        assertEquals(2, trade.legs().size());
        assertEquals("SC|trade-1|P", trade.legs().getFirst().orderReference());

        bracket.setStatus(BracketOrder.Status.WORKING_PARENT);
        store.recordBrokerUpdate(bracket, "BUY");
        assertTrue(Files.exists(statePath.resolveSibling("trading-state-paper.backup.json")));
    }

    private static BracketOrder bracket(String tradeId) {
        Decimal quantity = Decimal.get(10);
        BracketOrder bracket = new BracketOrder(
                tradeId, "TWO_SIGMA_DOWNSIDE", "DU123456", 265598, "BUY", "AAPL", 100, quantity);
        bracket.setEntryPrice(210.25);
        bracket.registerOrderLeg(100, "PARENT", "SC|" + tradeId + "|P", quantity);
        bracket.registerOrderLeg(101, "TAKE_PROFIT", "SC|" + tradeId + "|TP1", quantity);
        return bracket;
    }
}
