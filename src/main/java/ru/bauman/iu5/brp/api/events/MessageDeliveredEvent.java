package ru.bauman.iu5.brp.api.events;

import ru.bauman.iu5.brp.api.dto.ChatMessageDto;

/**
 * Событие успешной доставки сообщения получателю (получен ACK).
 */
public class MessageDeliveredEvent extends AbstractApplicationEvent {
    private final String messageId;
    private final long targetNodeId;

    public MessageDeliveredEvent(String messageId, long targetNodeId) {
        super(EventType.MESSAGE_DELIVERED);
        this.messageId = messageId;
        this.targetNodeId = targetNodeId;
    }

    public String getMessageId() {
        return messageId;
    }

    public long getTargetNodeId() {
        return targetNodeId;
    }

    @Override
    public String getDescription() {
        return "Сообщение " + messageId + " доставлено узлу " + targetNodeId;
    }

    @Override
    public String toString() {
        return "MessageDeliveredEvent{messageId='" + messageId + "', targetNodeId=" + targetNodeId + "}";
    }
}
