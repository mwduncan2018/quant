package mwd.trading.execution;

import java.util.List;

import com.ib.client.Decimal;

import mwd.trading.domain.Stock;
import mwd.trading.domain.TradeDirection;

public interface BracketOrderGateway {
    BracketOrder placeTripleThreat(
            String strategyName,
            TradeDirection tradeDirection,
            String tickerSymbol,
            Decimal totalOrderQuantity,
            double entryLimitPrice,
            List<BracketOrderExecutor.SliceIntent> sliceIntents);

    void updateTripleThreatExits(
            Stock stock,
            BracketOrder bracketOrder,
            BracketOrder.ExitSlice exitSlice,
            double newTakeProfitPrice,
            double newStopLossPrice,
            long newExitTime);
}
