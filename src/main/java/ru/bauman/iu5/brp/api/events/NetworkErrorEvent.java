package ru.bauman.iu5.brp.api.events;

import ru.bauman.iu5.brp.api.dto.ErrorSeverity;

/**
 * Событие сетевой ошибки.
 */
public class NetworkErrorEvent extends AbstractApplicationEvent {
    private final ErrorSeverity severity;
    private final String message;
    private final String details;
    private final Throwable cause;

    public NetworkErrorEvent(ErrorSeverity severity, String message, String details, Throwable cause) {
        super(EventType.NETWORK_ERROR);
        this.severity = severity;
        this.message = message;
        this.details = details;
        this.cause = cause;
    }

    public NetworkErrorEvent(ErrorSeverity severity, String message) {
        this(severity, message, null, null);
    }

    public ErrorSeverity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public String getDetails() {
        return details;
    }

    public Throwable getCause() {
        return cause;
    }

    @Override
    public String getDescription() {
        return "[" + severity + "] " + message + (details != null ? ": " + details : "");
    }

    @Override
    public String toString() {
        return "NetworkErrorEvent{severity=" + severity + ", message='" + message + "'}";
    }
}
