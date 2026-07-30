package mwd.trading.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ib.client.Decimal;

import mwd.trading.broker.ibkr.IdManager;
import mwd.trading.broker.ibkr.TimeManager;
import mwd.trading.domain.Stock;
import mwd.trading.execution.BracketOrder;
import mwd.trading.execution.OrderRegistry;
import mwd.trading.state.Blackboard;
import mwd.trading.support.TestConfig;

/**
 * A $100,000 account throughout, so a 30% ticker cap is $30,000 and a 50%
 * sector cap is $50,000.
 */
class ConcentrationLimitsTest {
    private static final double NET_LIQUIDATION = 100_000.0;
    private static final double MIN_NOTIONAL = 2_000.0;

    /** AAPL and MSFT share a sector; XOM does not. NVDA has no row at all. */
    private static final UniverseReference REFERENCE = UniverseReference.parse(
            List.of("AAPL,INFORMATION_TECHNOLOGY,0.50,0.50,0.25,0.30",
                    "MSFT,INFORMATION_TECHNOLOGY,0.50,0.50,0.25,0.30",
                    "XOM,ENERGY,0.50,0.50,0.25,0.30"),
            MarginMethodology.REG_T, 0.50, 0.50);

    private Blackboard blackboard;
    private ConcentrationLimits limits;

    @BeforeEach
    void setUp() {
        blackboard = new Blackboard(
                new IdManager(), new TimeManager(), new OrderRegistry(),
                new TestConfig(5, 10_000L, Set.of("AAPL", "MSFT", "XOM", "NVDA")));
        blackboard.getAccount().setNetLiquidation(NET_LIQUIDATION);
        limits = new ConcentrationLimits(blackboard, REFERENCE, 30.0, 50.0, MIN_NOTIONAL);
    }

    /** A filled position of this many shares at this price. */
    private void holdFilled(String ticker, double shares, double price) {
        Stock stock = blackboard.getStock(ticker);
        stock.setPositionSize(Decimal.get(shares));
        stock.setLastPrice(price);
    }

    /** An entry that is live at the exchange but has not filled. */
    private void holdWorking(String ticker, double shares, double entryPrice) {
        Stock stock = blackboard.getStock(ticker);
        BracketOrder bracketOrder = new BracketOrder(
                "trade-" + ticker, "TEST", "DU1", 1, "BUY", ticker, 1, Decimal.get(shares));
        bracketOrder.setEntryPrice(entryPrice);
        bracketOrder.setStatus(BracketOrder.Status.WORKING_PARENT);
        stock.setActiveBracket(bracketOrder);
    }

    @Test
    void anEntryInsideBothCapsPassesThroughUntouched() {
        assertEquals(Decimal.get(200), limits.allowedQuantity("AAPL", 100.0, Decimal.get(200)));
    }

    @Test
    void theTickerCapTrimsAnEntryThatWouldExceedIt() {
        // 400 shares at $100 is $40,000, past the $30,000 ticker cap.
        assertEquals(Decimal.get(300), limits.allowedQuantity("AAPL", 100.0, Decimal.get(400)));
    }

    @Test
    void existingExposureInTheSameNameReducesTheHeadroom() {
        holdFilled("AAPL", 100, 100.0);   // $10,000 already on AAPL

        // $20,000 of the $30,000 cap is left, so 200 shares at $100.
        assertEquals(Decimal.get(200), limits.allowedQuantity("AAPL", 100.0, Decimal.get(400)));
    }

    @Test
    void theSectorCapBindsBeforeTheTickerCapWhenSiblingsAreHeld() {
        holdFilled("MSFT", 350, 100.0);   // $35,000 of the $50,000 technology cap

        // The ticker cap would allow $30,000 of AAPL, but only $15,000 of the
        // sector cap is left, so that is what decides.
        assertEquals(Decimal.get(150), limits.allowedQuantity("AAPL", 100.0, Decimal.get(400)));
    }

    @Test
    void aDifferentSectorIsUnaffectedByTheOneThatIsFull() {
        holdFilled("MSFT", 500, 100.0);   // technology is at its $50,000 cap

        assertEquals(Decimal.get(300), limits.allowedQuantity("XOM", 100.0, Decimal.get(400)));
    }

    @Test
    void anUnfilledEntryStillCountsAgainstTheCaps() {
        // The engine-wide lock is released on acknowledgement, not on fill, so a
        // second entry can be admitted while this one is still working. Counting
        // only filled positions would let both through and both fill.
        holdWorking("MSFT", 350, 100.0);

        assertEquals(Decimal.get(150), limits.allowedQuantity("AAPL", 100.0, Decimal.get(400)));
    }

    @Test
    void aPartiallyFilledEntryIsCountedOnceAcrossItsTwoHalves() {
        Stock stock = blackboard.getStock("MSFT");
        stock.setPositionSize(Decimal.get(100));   // filled half, $10,000
        stock.setLastPrice(100.0);
        BracketOrder bracketOrder = new BracketOrder(
                "trade-MSFT", "TEST", "DU1", 1, "BUY", "MSFT", 1, Decimal.get(350));
        bracketOrder.setEntryPrice(100.0);
        bracketOrder.setFilledQuantity(Decimal.get(100));
        bracketOrder.setStatus(BracketOrder.Status.PARTIAL_PARENT);
        stock.setActiveBracket(bracketOrder);

        // $10,000 filled plus $25,000 still working is $35,000, not $45,000.
        assertEquals(Decimal.get(150), limits.allowedQuantity("AAPL", 100.0, Decimal.get(400)));
    }

    @Test
    void aTerminalBracketLeavesNothingWorking() {
        holdWorking("MSFT", 350, 100.0);
        blackboard.getStock("MSFT").getActiveBracket()
                .setStatus(BracketOrder.Status.CANCELLED);

        assertEquals(Decimal.get(300), limits.allowedQuantity("AAPL", 100.0, Decimal.get(400)));
    }

    @Test
    void headroomBelowTheFloorRefusesRatherThanSendingSomethingTiny() {
        // $29,500 held leaves $500 of the ticker cap, under the $2,000 floor.
        holdFilled("AAPL", 295, 100.0);

        assertEquals(Decimal.ZERO, limits.allowedQuantity("AAPL", 100.0, Decimal.get(400)));
    }

    @Test
    void headroomJustAboveTheFloorStillTrades() {
        holdFilled("AAPL", 275, 100.0);   // $2,500 of headroom left

        assertEquals(Decimal.get(25), limits.allowedQuantity("AAPL", 100.0, Decimal.get(400)));
    }

    @Test
    void aSymbolWithNoSectorIsHeldToTheTickerCapAlone() {
        // NVDA has no reference row, so its exposure cannot be rolled into any
        // sector total. The per-ticker cap is all that can be enforced.
        holdFilled("MSFT", 500, 100.0);   // technology is full, but NVDA is not in it

        assertEquals(Decimal.get(300), limits.allowedQuantity("NVDA", 100.0, Decimal.get(400)));
    }

    @Test
    void withoutANetLiquidationThereIsNoPercentageToTake() {
        blackboard.getAccount().setNetLiquidation(0.0);

        assertEquals(Decimal.ZERO, limits.allowedQuantity("AAPL", 100.0, Decimal.get(100)));
    }

    @Test
    void exposureIsReportedBySymbolAndBySector() {
        holdFilled("AAPL", 100, 100.0);
        holdFilled("MSFT", 200, 100.0);
        holdFilled("XOM", 50, 100.0);
        holdFilled("NVDA", 30, 100.0);

        ConcentrationLimits.Exposure exposure = limits.currentExposure();

        assertEquals(10_000.0, exposure.forTicker("AAPL"), 1.0e-9);
        assertEquals(30_000.0, exposure.forSector(Sector.INFORMATION_TECHNOLOGY), 1.0e-9);
        assertEquals(5_000.0, exposure.forSector(Sector.ENERGY), 1.0e-9);
        assertEquals(3_000.0, exposure.unsectored(), 1.0e-9);
    }

    @Test
    void aNonsensicalLimitIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationLimits(blackboard, REFERENCE, 0.0, 50.0, MIN_NOTIONAL));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationLimits(blackboard, REFERENCE, 30.0, 101.0, MIN_NOTIONAL));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationLimits(blackboard, REFERENCE, 30.0, 50.0, -1.0));
    }
}
