package mwd.trading.proxy;

/** Raised when a proxy endpoint could not be reached, read, or decoded. */
public final class ProxyFetchException extends Exception {
    private static final long serialVersionUID = 1L;

    public ProxyFetchException(String message) {
        super(message);
    }

    public ProxyFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
