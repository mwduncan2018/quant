package mwd.trading.earnings;

import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import mwd.trading.earnings.EarningsSnapshot.EarningsDate;
import mwd.trading.proxy.ProxyFetchException;
import mwd.trading.proxy.ProxyJsonFetcher;

/**
 * Fetches and decodes the options proxy's {@code /earnings} response.
 *
 * <p>
 * Decoding only; every semantic rule lives in {@link EarningsStore}. The split
 * mirrors the UDP path, where the receiver owns the socket and the store owns
 * validity, and it keeps each rule testable without a server.
 */
public final class EarningsClient {
    private final ProxyJsonFetcher fetcher;

    public EarningsClient(String endpointUrl, long connectTimeoutMs, long requestTimeoutMs) {
        this.fetcher = new ProxyJsonFetcher(endpointUrl, connectTimeoutMs, requestTimeoutMs);
    }

    /** Fetches and decodes one snapshot, or throws. */
    public EarningsSnapshot fetch() throws ProxyFetchException {
        return decode(fetcher.fetchObject());
    }

    /** Decodes a response body. Package-private entry point for tests. */
    static EarningsSnapshot decode(String body) throws ProxyFetchException {
        return decode(ProxyJsonFetcher.parseObject(body));
    }

    static EarningsSnapshot decode(JsonNode root) throws ProxyFetchException {
        Objects.requireNonNull(root, "root");

        LocalDate tradingDate = ProxyJsonFetcher.readDate(root.path("trading_date"));
        if (tradingDate == null) {
            throw new ProxyFetchException("Earnings response carried no usable trading_date");
        }

        JsonNode stocks = root.path("stocks");
        if (!stocks.isObject()) {
            throw new ProxyFetchException("Earnings response carried no stocks object");
        }

        Map<String, EarningsDate> byTicker = new LinkedHashMap<>();
        stocks.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            // The proxy's validity flag is authoritative. Null dates alongside
            // valid=false is the normal shape for an unresolved ticker, not an
            // error, and must never be read as "no earnings scheduled".
            if (!value.path("valid").asBoolean(false)) {
                return;
            }
            LocalDate nextDate = ProxyJsonFetcher.readDate(value.path("next_earnings_date"));
            LocalDate lastDate = ProxyJsonFetcher.readDate(value.path("last_earnings_date"));
            if (nextDate == null && lastDate == null) {
                return;
            }
            String ticker = entry.getKey().trim().toUpperCase(Locale.ROOT);
            if (ticker.isEmpty()) {
                return;
            }
            byTicker.put(ticker, new EarningsDate(ticker, nextDate, lastDate));
        });

        return new EarningsSnapshot(tradingDate, byTicker);
    }

    public URI getEndpoint() {
        return fetcher.getEndpoint();
    }
}
