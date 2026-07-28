package mwd.trading.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import mwd.trading.calendar.MarketCalendarStore;
import mwd.trading.calendar.MarketSession;
import mwd.trading.earnings.EarningsSnapshot;
import mwd.trading.earnings.EarningsSnapshot.EarningsDate;
import mwd.trading.earnings.EarningsStore;

/** Builds calendar and earnings state for strategy tests. */
public final class ProxyReferenceFixtures {
    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private ProxyReferenceFixtures() {
    }

    /** The instant of a New York wall-clock time on a date. */
    public static Instant newYork(LocalDate date, int hour, int minute) {
        return date.atTime(LocalTime.of(hour, minute)).atZone(NEW_YORK).toInstant();
    }

    /** A store holding a standard 09:30-16:00 session for the date. */
    public static MarketCalendarStore standardSession(LocalDate tradingDate) {
        return sessionClosingAt(tradingDate, "open", newYork(tradingDate, 16, 0));
    }

    /** A store holding a 13:00 early close for the date. */
    public static MarketCalendarStore earlyCloseSession(LocalDate tradingDate) {
        return sessionClosingAt(tradingDate, "early-close", newYork(tradingDate, 13, 0));
    }

    public static MarketCalendarStore sessionClosingAt(
            LocalDate tradingDate, String status, Instant closesAt) {
        return sessionClosingAt(tradingDate, status, closesAt, Set.of(), tradingDate.plusYears(1));
    }

    public static MarketCalendarStore sessionClosingAt(
            LocalDate tradingDate,
            String status,
            Instant closesAt,
            Set<LocalDate> closedDates,
            LocalDate coversTo) {
        MarketCalendarStore store = new MarketCalendarStore();
        store.accept(new MarketSession(
                tradingDate,
                status,
                newYork(tradingDate, 9, 30),
                closesAt,
                "early-close".equals(status) ? "early-close-record" : "standard",
                closedDates,
                tradingDate,
                coversTo), tradingDate);
        return store;
    }

    /** An earnings store whose only known date for the ticker is upcoming. */
    public static EarningsStore earningsOn(
            String ticker, LocalDate tradingDate, LocalDate nextReport) {
        return earningsDates(ticker, tradingDate, nextReport, null);
    }

    /** An earnings store whose only known date for the ticker has passed. */
    public static EarningsStore earningsLastOn(
            String ticker, LocalDate tradingDate, LocalDate lastReport) {
        return earningsDates(ticker, tradingDate, null, lastReport);
    }

    public static EarningsStore earningsDates(
            String ticker, LocalDate tradingDate, LocalDate nextReport, LocalDate lastReport) {
        EarningsStore store = new EarningsStore(Set.of(ticker));
        Map<String, EarningsDate> byTicker = new LinkedHashMap<>();
        byTicker.put(ticker, new EarningsDate(ticker, nextReport, lastReport));
        store.accept(new EarningsSnapshot(tradingDate, byTicker), tradingDate, 1L);
        return store;
    }
}
