package ru.bauman.iu5.brp.api.dto;

/**
 * Исключение для сетевых ошибок, выбрасываемое методами ApplicationApi.
 */
public class NetworkException extends Exception {
    private final ErrorSeverity severity;
    private final Long relatedNodeId;

    public NetworkException(String message) {
        super(message);
        this.severity = ErrorSeverity.ERROR;
        this.relatedNodeId = null;
    }

    public NetworkException(String message, Throwable cause) {
        super(message, cause);
        this.severity = ErrorSeverity.ERROR;
        this.relatedNodeId = null;
    }

    public NetworkException(ErrorSeverity severity, String message, Long relatedNodeId) {
        super(message);
        this.severity = severity;
        this.relatedNodeId = relatedNodeId;
    }

    public NetworkException(ErrorSeverity severity, String message, Throwable cause, Long relatedNodeId) {
        super(message, cause);
        this.severity = severity;
        this.relatedNodeId = relatedNodeId;
    }

    public ErrorSeverity getSeverity() {
        return severity;
    }

    public Long getRelatedNodeId() {
        return relatedNodeId;
    }
}
