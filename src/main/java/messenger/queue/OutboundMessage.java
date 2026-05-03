package messenger.queue;

import messenger.protocol.ChatMessage;

import java.io.Serial;
import java.io.Serializable;

public final class OutboundMessage implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String messageId;
    private final long sequenceNumber;
    private final long senderId;
    private final long targetId;
    private final String payload;
    private final long createdAt;
    private int attemptCount;

    public OutboundMessage(String messageId, long sequenceNumber, long senderId, long targetId, String payload, long createdAt) {
        this.messageId = messageId;
        this.sequenceNumber = sequenceNumber;
        this.senderId = senderId;
        this.targetId = targetId;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public String messageId() {
        return messageId;
    }

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public long senderId() {
        return senderId;
    }

    public long targetId() {
        return targetId;
    }

    public String payload() {
        return payload;
    }

    public long createdAt() {
        return createdAt;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public void incrementAttemptCount() {
        this.attemptCount++;
    }

    public ChatMessage toChatMessage() {
        return new ChatMessage(messageId, sequenceNumber, senderId, targetId, payload);
    }
}
