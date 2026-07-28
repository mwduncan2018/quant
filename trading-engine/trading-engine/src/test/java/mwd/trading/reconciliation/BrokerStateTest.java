package mwd.trading.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ib.client.Contract;
import com.ib.client.Decimal;

class BrokerStateTest {
    @Test
    void positionCallbacksReplaceAndRemoveTheCanonicalPosition() {
        BrokerState state = new BrokerState();
        Contract contract = new Contract();
        contract.conid(265598);
        contract.symbol("AAPL");
        contract.secType("STK");

        state.recordPosition("DU123456", contract, Decimal.get(5), 210.25);
        BrokerState.PositionRecord position = state.snapshot().positions().getFirst();
        assertEquals("AAPL", position.symbol());
        assertEquals("5", position.quantity());

        state.recordPosition("DU123456", contract, Decimal.ZERO, 0.0);
        assertTrue(state.snapshot().positions().isEmpty());
    }
}
