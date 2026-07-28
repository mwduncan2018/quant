package mwd.trading.execution;

/**
 * Signals that submission started but the caller cannot know which legs IBKR
 * accepted. The local lifecycle must remain reserved until reconciliation or
 * an explicit broker callback establishes the outcome.
 */
public final class UncertainOrderSubmissionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UncertainOrderSubmissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
