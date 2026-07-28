package mwd.trading.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fetches one JSON object from an options-proxy endpoint.
 *
 * <p>
 * Transport only. Every endpoint's own decoding and every semantic rule live
 * with the store that owns them, the same split the UDP path uses between the
 * receiver and {@code OptionsIndicatorStore}.
 *
 * <p>
 * Nothing is retried here. A caller that knows the refresh schedule is better
 * placed to decide how long to wait than a method that only knows one request
 * failed.
 */
public final class ProxyJsonFetcher {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;

    public ProxyJsonFetcher(String endpointUrl, long connectTimeoutMs, long requestTimeoutMs) {
        Objects.requireNonNull(endpointUrl, "endpointUrl");
        if (connectTimeoutMs <= 0 || requestTimeoutMs <= 0) {
            throw new IllegalArgumentException("Proxy timeouts must be positive");
        }
        this.endpoint = URI.create(endpointUrl);
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                // The proxy is a local or LAN service; a redirect would mean
                // something other than the proxy is answering.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Fetches the endpoint and returns its body as a JSON object node. */
    public JsonNode fetchObject() throws ProxyFetchException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new ProxyFetchException(
                    "Request to " + endpoint + " failed: " + exception, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProxyFetchException("Request to " + endpoint + " was interrupted", exception);
        }

        if (response.statusCode() != 200) {
            throw new ProxyFetchException(
                    endpoint + " returned HTTP " + response.statusCode());
        }
        return parseObject(response.body());
    }

    /** Parses a body into a JSON object node, rejecting anything else. */
    public static JsonNode parseObject(String body) throws ProxyFetchException {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (IOException exception) {
            throw new ProxyFetchException("Proxy response was not valid JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new ProxyFetchException("Proxy response was not a JSON object");
        }
        return root;
    }

    /**
     * Reads a strict {@code YYYY-MM-DD} field, or null when absent or malformed.
     * Length is checked first so a partial date cannot be leniently widened.
     */
    public static LocalDate readDate(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        if (text.length() != 10) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    public URI getEndpoint() {
        return endpoint;
    }
}
