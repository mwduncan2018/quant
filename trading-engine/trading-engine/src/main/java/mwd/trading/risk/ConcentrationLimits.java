package mwd.trading.risk;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ib.client.Decimal;

import mwd.trading.domain.Stock;
import mwd.trading.execution.BracketOrder;
import mwd.trading.state.Blackboard;

/**
 * Caps how much of the account may ride on one symbol, and on one sector.
 *
 * <p>
 * This is account-level risk policy rather than strategy logic, and it has to be
 * shared: two strategies both entering technology names must see the same sector
 * total, or neither limit means anything. Each strategy keeps its own risk budget
 * and sizes itself; this only ever reduces the result.
 *
 * <p>
 * Both limits apply whatever margin regime the account is on. Concentration is a
 * question of how much is at stake on one name; the regime only decides how much
 * the broker lends against it. Scoping the caps to one regime would mean changing
 * {@code MARGIN_METHODOLOGY} silently removed them.
 */
public final class ConcentrationLimits {
    private static final Logger logger = LogManager.getLogger(ConcentrationLimits.class);

    /** Current exposure, by symbol and rolled up by sector, against net liquidation. */
    public record Exposure(
            double netLiquidation,
            Map<String, Double> byTicker,
            Map<Sector, Double> bySector,
            double unsectored) {

        public double forTicker(String ticker) {
            return byTicker.getOrDefault(ticker, 0.0);
        }

        public double forSector(Sector sector) {
            return sector == null ? 0.0 : bySector.getOrDefault(sector, 0.0);
        }
    }

    private final Blackboard blackboard;
    private final UniverseReference universeReference;
    private final double maxTickerFraction;
    private final double maxSectorFraction;
    private final double minPositionNotional;

    /**
     * @param maxTickerExposurePercent percent of net liquidation, so 30 means 30%
     * @param maxSectorExposurePercent percent of net liquidation, so 50 means 50%
     * @param minPositionNotional      below this the entry is refused rather than
     *                                 trimmed, because a position small enough
     *                                 gives its edge back in commission and spread
     *                                 while still consuming a position slot
     */
    public ConcentrationLimits(
            Blackboard blackboard,
            UniverseReference universeReference,
            double maxTickerExposurePercent,
            double maxSectorExposurePercent,
            double minPositionNotional) {
        this.blackboard = Objects.requireNonNull(blackboard, "blackboard");
        this.universeReference = Objects.requireNonNull(universeReference, "universeReference");
        this.maxTickerFraction = requireFraction(maxTickerExposurePercent, "maxTickerExposurePercent");
        this.maxSectorFraction = requireFraction(maxSectorExposurePercent, "maxSectorExposurePercent");
        if (!(minPositionNotional >= 0.0) || !Double.isFinite(minPositionNotional)) {
            throw new IllegalArgumentException(
                    "minPositionNotional must be zero or more, was " + minPositionNotional);
        }
        this.minPositionNotional = minPositionNotional;
    }

    /**
     * The largest quantity this entry may take without breaching either cap, never
     * more than was asked for.
     *
     * <p>
     * Returns {@link Decimal#ZERO} when the room left is below
     * {@code MIN_POSITION_NOTIONAL}: trimming is worth doing while the cap is
     * merely tight, and not worth doing once it has left a position too small to
     * carry its own costs.
     */
    public Decimal allowedQuantity(String ticker, double entryPrice, Decimal requested) {
        if (ticker == null || requested == null || requested.compareTo(Decimal.ZERO) <= 0) {
            return Decimal.ZERO;
        }
        if (!(entryPrice > 0.0) || !Double.isFinite(entryPrice)) {
            return Decimal.ZERO;
        }

        Exposure exposure = currentExposure();
        if (!(exposure.netLiquidation() > 0.0)) {
            logger.warn("[{}] No usable net liquidation, so no concentration headroom can be "
                    + "computed; refusing the entry", ticker);
            return Decimal.ZERO;
        }

        double tickerHeadroom =
                (exposure.netLiquidation() * maxTickerFraction) - exposure.forTicker(ticker);
        double headroom = tickerHeadroom;

        Sector sector = universeReference.sector(ticker).orElse(null);
        if (sector == null) {
            // Without a sector this symbol cannot be rolled into a sector total,
            // so only the per-ticker cap can be enforced for it - and its exposure
            // is invisible to every other symbol's sector total. That is a gap in
            // the reference file, named at startup, not something to paper over here.
            logger.warn("[{}] No sector in the reference table, so only the per-ticker "
                    + "concentration limit applies", ticker);
        } else {
            double sectorHeadroom =
                    (exposure.netLiquidation() * maxSectorFraction) - exposure.forSector(sector);
            headroom = Math.min(headroom, sectorHeadroom);
        }

        if (headroom < minPositionNotional) {
            return Decimal.ZERO;
        }

        double requestedNotional = requested.value().doubleValue() * entryPrice;
        if (requestedNotional <= headroom) {
            return requested;
        }

        double affordableShares = Math.floor(headroom / entryPrice);
        if (affordableShares <= 0 || (affordableShares * entryPrice) < minPositionNotional) {
            return Decimal.ZERO;
        }
        return Decimal.get(affordableShares);
    }

    /**
     * Notional exposure per symbol and per sector.
     *
     * <p>
     * Filled positions come from {@code Stock.positionSize}, which IBKR writes.
     * That alone would miss an entry whose limit order is resting unfilled: the
     * engine-wide lock is released on acknowledgement, not on fill, so a second
     * entry can be admitted while the first is still working. The unfilled
     * remainder of every non-terminal bracket is therefore counted too, or two
     * same-sector entries could each pass the check and both fill.
     */
    public Exposure currentExposure() {
        Map<String, Double> byTicker = new LinkedHashMap<>();
        Map<Sector, Double> bySector = new EnumMap<>(Sector.class);
        double[] unsectored = {0.0};

        blackboard.forEachStock(stock -> {
            double notional = filledNotional(stock) + workingNotional(stock);
            if (notional <= 0.0) {
                return;
            }
            String ticker = stock.getTicker();
            byTicker.merge(ticker, notional, Double::sum);
            Sector sector = universeReference.sector(ticker).orElse(null);
            if (sector == null) {
                unsectored[0] += notional;
            } else {
                bySector.merge(sector, notional, Double::sum);
            }
        });

        return new Exposure(
                blackboard.getAccount().getNetLiquidation(),
                Map.copyOf(byTicker),
                Map.copyOf(bySector),
                unsectored[0]);
    }

    public double getMinPositionNotional() {
        return minPositionNotional;
    }

    private static double filledNotional(Stock stock) {
        Decimal positionSize = stock.getPositionSize();
        if (positionSize == null) {
            return 0.0;
        }
        double shares = Math.abs(positionSize.value().doubleValue());
        double price = stock.getLastPrice();
        if (shares <= 0.0 || !(price > 0.0)) {
            return 0.0;
        }
        return shares * price;
    }

    /**
     * The part of a live bracket that has not filled yet, priced at the entry
     * limit. A terminal bracket contributes nothing; a fully filled one nets to
     * zero here and is already counted in the position size.
     */
    private static double workingNotional(Stock stock) {
        BracketOrder bracketOrder = stock.getActiveBracket();
        if (bracketOrder == null) {
            return 0.0;
        }
        switch (bracketOrder.getStatus()) {
            case FILLED, CANCELLED, REJECTED:
                return 0.0;
            default:
                break;
        }
        Decimal total = bracketOrder.getTotalQuantity();
        if (total == null) {
            return 0.0;
        }
        BigDecimal filled = bracketOrder.getFilledQuantity() == null
                ? BigDecimal.ZERO
                : bracketOrder.getFilledQuantity().value();
        double remaining = total.value().subtract(filled).doubleValue();
        double price = bracketOrder.getEntryPrice();
        if (remaining <= 0.0 || !(price > 0.0)) {
            return 0.0;
        }
        return remaining * price;
    }

    private static double requireFraction(double percent, String name) {
        if (!Double.isFinite(percent) || percent <= 0.0 || percent > 100.0) {
            throw new IllegalArgumentException(
                    name + " must be greater than 0 and at most 100, was " + percent);
        }
        return percent / 100.0;
    }
}
