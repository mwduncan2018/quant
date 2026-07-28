package mwd.trading.lifecycle;

/**
 * The application-wide trading lifecycle. New entries are permitted only in
 * {@link #READY}.
 */
public enum EngineMode {
    STARTING,
    CONNECTING,
    RECONCILING,
    READY,
    DEGRADED,
    MANUAL_INTERVENTION,
    STOPPING
}
