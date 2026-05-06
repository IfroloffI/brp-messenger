package ru.bauman.iu5.brp.api.events;

import ru.bauman.iu5.brp.api.dto.ChatMessageDto;

/**
 * Событие получения текстового сообщения.
 */
public class MessageReceivedEvent extends AbstractApplicationEvent {
    private final ChatMessageDto message;

    public MessageReceivedEvent(ChatMessageDto message) {
        super(EventType.MESSAGE_RECEIVED);
        this.message = message;
    }

    public ChatMessageDto getMessage() {
        return message;
    }

    @Override
    public String getDescription() {
        return "Получено сообщение от узла " + message.getSenderNodeId() +
                ": " + message.getText().substring(0, Math.min(30, message.getText().length()));
    }

    @Override
    public String toString() {
        return "MessageReceivedEvent{message=" + message + "}";
    }
}
