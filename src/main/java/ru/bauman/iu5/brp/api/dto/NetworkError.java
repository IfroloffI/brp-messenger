package ru.bauman.iu5.brp.api.dto;

import java.time.Instant;

/**
 * Информация об ошибке сети для отображения пользователю.
 */
public class NetworkError {
    private final Instant timestamp;
    private final ErrorSeverity severity;
    private final String message;
    private final String details;
    private final Long relatedNodeId;

    public NetworkError(ErrorSeverity severity, String message, String details, Long relatedNodeId) {
        this.timestamp = Instant.now();
        this.severity = severity;
        this.message = message;
        this.details = details;
        this.relatedNodeId = relatedNodeId;
    }

    public NetworkError(ErrorSeverity severity, String message) {
        this(severity, message, null, null);
    }

    public Instant getTimestamp() {
        return timestamp;
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

    public Long getRelatedNodeId() {
        return relatedNodeId;
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + message +
                (relatedNodeId != null ? " (node " + relatedNodeId + ")" : "");
    }
}
