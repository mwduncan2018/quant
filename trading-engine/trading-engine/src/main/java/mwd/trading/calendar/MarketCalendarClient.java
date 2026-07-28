package mwd.trading.calendar;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import mwd.trading.proxy.ProxyFetchException;
import mwd.trading.proxy.ProxyJsonFetcher;

/** Fetches and decodes the options proxy's {@code /calendar} response. */
public final class MarketCalendarClient {
    private final ProxyJsonFetcher fetcher;

    public MarketCalendarClient(String endpointUrl, long connectTimeoutMs, long requestTimeoutMs) {
        this.fetcher = new ProxyJsonFetcher(endpointUrl, connectTimeoutMs, requestTimeoutMs);
    }

    public MarketSession fetch() throws ProxyFetchException {
        return decode(fetcher.fetchObject());
    }

    /** Decodes a response body. Package-private entry point for tests. */
    static MarketSession decode(String body) throws ProxyFetchException {
        return decode(ProxyJsonFetcher.parseObject(body));
    }

    static MarketSession decode(JsonNode root) throws ProxyFetchException {
        Objects.requireNonNull(root, "root");

        JsonNode session = root.path("session");
        if (!session.isObject()) {
            throw new ProxyFetchException("Calendar response carried no session object");
        }

        LocalDate tradingDate = ProxyJsonFetcher.readDate(session.path("trading_date"));
        if (tradingDate == null) {
            throw new ProxyFetchException("Calendar response carried no usable trading_date");
        }

        String status = session.path("status").asText("").trim();
        if (status.isEmpty()) {
            throw new ProxyFetchException("Calendar response carried no session status");
        }

        Instant opensAt = readInstant(session.path("opens_at"));
        Instant closesAt = readInstant(session.path("closes_at"));
        if (!"closed".equals(status) && closesAt == null) {
            // A session without a close time is unusable: every entry and exit
            // decision this engine makes is relative to it.
            throw new ProxyFetchException(
                    "Calendar reported session status '" + status + "' with no close time");
        }

        Set<LocalDate> closedDates = new LinkedHashSet<>();
        JsonNode holidays = root.path("holidays");
        if (holidays.isArray()) {
            for (JsonNode holiday : holidays) {
                LocalDate date = ProxyJsonFetcher.readDate(holiday.path("date"));
                if (date != null && "closed".equals(holiday.path("status").asText(""))) {
                    closedDates.add(date);
                }
            }
        }

        JsonNode cache = root.path("cache");
        return new MarketSession(
                tradingDate,
                status,
                opensAt,
                closesAt,
                session.path("hours_source").asText("unknown"),
                closedDates,
                ProxyJsonFetcher.readDate(cache.path("covers_from")),
                ProxyJsonFetcher.readDate(cache.path("covers_to")));
    }

    /** Parses an ISO-8601 instant. The proxy always emits UTC with a Z suffix. */
    private static Instant readInstant(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    public URI getEndpoint() {
        return fetcher.getEndpoint();
    }
}
