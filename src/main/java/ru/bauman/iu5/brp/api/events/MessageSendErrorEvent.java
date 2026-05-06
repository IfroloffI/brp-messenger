package ru.bauman.iu5.brp.api.events;

/**
 * Событие ошибки при отправке сообщения.
 */
public class MessageSendErrorEvent extends AbstractApplicationEvent {
    private final String messageId;
    private final long targetNodeId;
    private final String errorMessage;

    public MessageSendErrorEvent(String messageId, long targetNodeId, String errorMessage) {
        super(EventType.MESSAGE_SEND_ERROR);
        this.messageId = messageId;
        this.targetNodeId = targetNodeId;
        this.errorMessage = errorMessage;
    }

    public String getMessageId() {
        return messageId;
    }

    public long getTargetNodeId() {
        return targetNodeId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String getDescription() {
        return "Ошибка отправки сообщения " + messageId + " узлу " + targetNodeId + ": " + errorMessage;
    }

    @Override
    public String toString() {
        return "MessageSendErrorEvent{messageId='" + messageId + "', error='" + errorMessage + "'}";
    }
}
