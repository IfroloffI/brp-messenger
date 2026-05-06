package ru.bauman.iu5.brp.api.events;

/**
 * Событие неудачной проверки цифровой подписи (возможная подмена данных).
 */
public class SignatureVerificationFailedEvent extends AbstractApplicationEvent {
    private final long senderNodeId;
    private final String messageId;
    private final String reason;

    public SignatureVerificationFailedEvent(long senderNodeId, String messageId, String reason) {
        super(EventType.SIGNATURE_VERIFICATION_FAILED);
        this.senderNodeId = senderNodeId;
        this.messageId = messageId;
        this.reason = reason;
    }

    public long getSenderNodeId() {
        return senderNodeId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String getDescription() {
        return "ВНИМАНИЕ: проверка подписи не удалась для сообщения от узла " + senderNodeId +
                ": " + reason;
    }

    @Override
    public String toString() {
        return "SignatureVerificationFailedEvent{senderNodeId=" + senderNodeId +
                ", messageId='" + messageId + "', reason='" + reason + "'}";
    }
}
