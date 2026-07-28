package mwd.trading.earnings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import mwd.trading.proxy.ProxyFetchException;

/**
 * Covers decoding and the transport failure paths, against a real local server
 * on an ephemeral port. The payloads below match what the proxy's /earnings
 * endpoint actually emits.
 */
class EarningsClientTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private EarningsClient clientServing(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/earnings", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return new EarningsClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/earnings", 2000, 2000);
    }

    private static String payload() {
        return """
                {
                  "trading_date": "2026-07-27",
                  "source": "yfinance",
                  "stocks": {
                    "AAPL": {"ticker":"AAPL","next_earnings_date":"2026-07-30",
                             "last_earnings_date":null,"valid":true,"last_error":null},
                    "MSFT": {"ticker":"MSFT","next_earnings_date":"2026-08-04",
                             "last_earnings_date":"2026-05-01","valid":true,"last_error":null},
                    "NVDA": {"ticker":"NVDA","next_earnings_date":null,
                             "last_earnings_date":null,"valid":false,
                             "last_error":"No earnings date was reported"}
                  },
                  "coverage": {"required":3,"valid":2,"missing_tickers":["NVDA"]}
                }
                """;
    }

    @Test
    void aRealResponseDecodesIntoASnapshot() throws Exception {
        EarningsSnapshot snapshot = clientServing(200, payload()).fetch();

        assertEquals(LocalDate.of(2026, 7, 27), snapshot.tradingDate());
        assertEquals(LocalDate.of(2026, 7, 30),
                snapshot.byTicker().get("AAPL").nextEarningsDate());
        assertNull(snapshot.byTicker().get("AAPL").lastEarningsDate());
        assertEquals(LocalDate.of(2026, 5, 1),
                snapshot.byTicker().get("MSFT").lastEarningsDate());
    }

    @Test
    void anInvalidTickerIsOmittedRatherThanCarriedWithANullDate() throws Exception {
        // The proxy's validity flag is authoritative. An unresolved ticker must
        // never reach a strategy as "no earnings scheduled".
        EarningsSnapshot snapshot = clientServing(200, payload()).fetch();

        assertFalse(snapshot.byTicker().containsKey("NVDA"));
        assertEquals(2, snapshot.byTicker().size());
    }

    @Test
    void aNonOkStatusIsAnError() throws Exception {
        EarningsClient client = clientServing(503, "service unavailable");

        ProxyFetchException thrown =
                assertThrows(ProxyFetchException.class, client::fetch);
        assertTrue(thrown.getMessage().contains("503"));
    }

    @Test
    void anUnreachableEndpointIsAnError() throws Exception {
        // Port 1 on loopback refuses connections immediately.
        EarningsClient client = new EarningsClient("http://127.0.0.1:1/earnings", 500, 500);

        assertThrows(ProxyFetchException.class, client::fetch);
    }

    @Test
    void malformedJsonIsAnError() {
        assertThrows(ProxyFetchException.class, () -> EarningsClient.decode("{not json"));
        assertThrows(ProxyFetchException.class, () -> EarningsClient.decode("[]"));
        assertThrows(ProxyFetchException.class, () -> EarningsClient.decode(""));
    }

    @Test
    void aMissingOrUnparseableTradingDateIsAnError() {
        assertThrows(ProxyFetchException.class,
                () -> EarningsClient.decode("{\"stocks\":{}}"));
        assertThrows(ProxyFetchException.class,
                () -> EarningsClient.decode("{\"trading_date\":\"27-07-2026\",\"stocks\":{}}"));
        assertThrows(ProxyFetchException.class,
                () -> EarningsClient.decode("{\"trading_date\":\"2026-07-27\"}"));
    }

    @Test
    void anEntryWithNoUsableDateAtAllIsSkippedNotFatal() throws Exception {
        EarningsSnapshot snapshot = EarningsClient.decode("""
                {"trading_date":"2026-07-27","stocks":{
                  "AAPL":{"next_earnings_date":"soon","last_earnings_date":null,"valid":true},
                  "MSFT":{"next_earnings_date":"2026-08-04","valid":true}}}
                """);

        assertEquals(1, snapshot.byTicker().size());
        assertTrue(snapshot.byTicker().containsKey("MSFT"));
    }

    @Test
    void aTickerWithOnlyARetainedPastDateIsKept() throws Exception {
        // Some tickers linger on the date that just passed, so the proxy has a
        // last report and no upcoming one. That is knowledge, not absence.
        EarningsSnapshot snapshot = EarningsClient.decode("""
                {"trading_date":"2026-07-27","stocks":{
                  "GOOGL":{"next_earnings_date":null,
                           "last_earnings_date":"2026-07-22","valid":true}}}
                """);

        EarningsSnapshot.EarningsDate entry = snapshot.byTicker().get("GOOGL");
        assertNull(entry.nextEarningsDate());
        assertEquals(LocalDate.of(2026, 7, 22), entry.lastEarningsDate());
        assertEquals(List.of(LocalDate.of(2026, 7, 22)), entry.knownDates());
    }

    @Test
    void bothDatesAreExposedWhenBothAreKnown() throws Exception {
        EarningsSnapshot snapshot = EarningsClient.decode("""
                {"trading_date":"2026-07-27","stocks":{
                  "TSLA":{"next_earnings_date":"2026-10-21",
                          "last_earnings_date":"2026-07-22","valid":true}}}
                """);

        assertEquals(List.of(LocalDate.of(2026, 7, 22), LocalDate.of(2026, 10, 21)),
                snapshot.byTicker().get("TSLA").knownDates());
    }

    @Test
    void theDisabledProxyResponseDecodesToAnEmptyUniverse() throws Exception {
        // What the proxy returns when EARNINGS_ENABLED is false.
        EarningsSnapshot snapshot = EarningsClient.decode("""
                {"trading_date":"2026-07-27","source":"yfinance","stocks":{},
                 "coverage":{"required":3,"valid":0,"missing_tickers":["AAPL"]},
                 "last_error":"Earnings retrieval is disabled"}
                """);

        assertTrue(snapshot.byTicker().isEmpty());
    }

    @Test
    void nonPositiveTimeoutsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EarningsClient("http://127.0.0.1:8000/earnings", 0, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new EarningsClient("http://127.0.0.1:8000/earnings", 1000, -1));
    }
}
