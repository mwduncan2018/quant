package mwd.trading.marketdata;

import java.util.Objects;

import com.ib.client.Bar;
import com.ib.client.Decimal;

import mwd.trading.domain.Stock;

/**
 * Every market-data value one decision needs, read once and frozen.
 *
 * <p>
 * {@link Stock} holds each figure in its own {@code volatile} field, written by
 * the IBKR reader thread as the ticks arrive. A strategy reading those fields
 * directly gets a fresh value on every call, so a single decision could be built
 * from several different moments in the tape. The one-sigma entry gate showed
 * what that costs: it proved the take-profit target left reward at least equal
 * to risk by testing {@code dailyVWAP} against a floor, and then the slice it
 * actually submitted read {@code dailyVWAP} a second time. Nothing carried the
 * first reading to the second, so the order could rest on a value the gate had
 * never approved.
 *
 * <p>
 * Taking a snapshot makes the reads one event. Everything downstream sees the
 * same tape, and a conclusion drawn early in a decision still holds at the end
 * of it. This is a coherent view, not a fresh one - freshness is
 * {@link MarketDataFreshness}, checked separately against the same instant.
 *
 * <p>
 * The entry path takes two: one to screen on, discarded freely because nothing
 * has been claimed yet, and one taken after the engine-wide lock is held. The
 * second is the one the order is built from, and re-running the entry test
 * against it is what makes that re-check meaningful rather than a comparison of
 * a value with itself.
 */
public record MarketSnapshot(
        String ticker,
        long takenAtUnixMs,
        double lastPrice,
        double previousClose,
        double dailyVWAP,
        Bar lastMinuteBar,
        Decimal lastMinuteVolume,
        Decimal averageLast15MinuteVolume,
        double longMarginRate,
        boolean longMarginRateVerified,
        double shortMarginRate,
        boolean shortMarginRateVerified) {

    public MarketSnapshot {
        Objects.requireNonNull(ticker, "ticker");
    }

    /**
     * Reads every field once. The reader thread can write between two of these
     * reads, so the result is not an instant of the tape; it is one bounded
     * window that every consumer of this snapshot agrees on.
     */
    public static MarketSnapshot of(Stock stock, long takenAtUnixMs) {
        Objects.requireNonNull(stock, "stock");
        return new MarketSnapshot(
                stock.getTicker(),
                takenAtUnixMs,
                stock.getLastPrice(),
                stock.getPreviousClose(),
                stock.getDailyVWAP(),
                stock.getLastMinuteBar(),
                stock.getLastMinuteVolume(),
                stock.getAverageLast15MinuteVolume(),
                stock.getLongMarginRate(),
                stock.isLongMarginRateVerified(),
                stock.getShortMarginRate(),
                stock.isShortMarginRateVerified());
    }

    /**
     * Sizes against the rate captured here rather than the live field, so the
     * quantity submitted matches the rate the direction was verified against.
     */
    public double marginRequirement(String action, Decimal quantity, double price) {
        double notionalValue = Math.abs(quantity.value().doubleValue()) * price;
        return notionalValue * (action.equalsIgnoreCase("BUY") ? longMarginRate : shortMarginRate);
    }
}
