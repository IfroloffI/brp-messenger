package ru.bauman.iu5.brp.api.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * DTO для текстового сообщения в чате.
 * Соответствует требованиям ТЗ п. 5.2.4, 5.3.2.
 */
public class ChatMessageDto {
    private final String messageId;
    private final long senderNodeId;
    private final long targetNodeId;
    private final Instant timestamp;
    private final String text;
    private DeliveryStatus deliveryStatus;
    private SignatureStatus signatureStatus;
    private boolean isOutgoing;
    private boolean isRead;

    /**
     * Конструктор для входящего сообщения.
     */
    public ChatMessageDto(String messageId, long senderNodeId, long targetNodeId, Instant timestamp, String text, SignatureStatus signatureStatus) {
        this.messageId = messageId;
        this.senderNodeId = senderNodeId;
        this.targetNodeId = targetNodeId;
        this.timestamp = timestamp;
        this.text = text;
        this.deliveryStatus = DeliveryStatus.DELIVERED; // входящие уже доставлены
        this.signatureStatus = signatureStatus;
        this.isOutgoing = false;
        this.isRead = false;
    }

    /**
     * Конструктор для исходящего сообщения.
     */
    public static ChatMessageDto outgoing(String messageId, long targetNodeId, Instant timestamp, String text) {
        ChatMessageDto dto = new ChatMessageDto(messageId, 0, // sender не важен для исходящих
                targetNodeId, timestamp, text, SignatureStatus.NOT_APPLICABLE);
        ((ChatMessageDto) dto).deliveryStatus = DeliveryStatus.SENT;
        ((ChatMessageDto) dto).isOutgoing = true;
        ((ChatMessageDto) dto).isRead = true; // свои сообщения всегда прочитаны
        return dto;
    }

    public String getMessageId() {
        return messageId;
    }

    public long getSenderNodeId() {
        return senderNodeId;
    }

    public long getTargetNodeId() {
        return targetNodeId;
    }

    /**
     * Возвращает ID собеседника (для входящих - sender, для исходящих - target).
     */
    public long getPeerNodeId() {
        return isOutgoing ? targetNodeId : senderNodeId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getText() {
        return text;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(DeliveryStatus deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public SignatureStatus getSignatureStatus() {
        return signatureStatus;
    }

    public boolean isOutgoing() {
        return isOutgoing;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatMessageDto that = (ChatMessageDto) o;
        return Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId);
    }

    @Override
    public String toString() {
        return "ChatMessageDto{" + "messageId='" + messageId + "\\'" + ", peer=" + getPeerNodeId() + ", text='" + text.substring(0, Math.min(20, text.length())) + "...'" + ", status=" + deliveryStatus + ", outgoing=" + isOutgoing + '}';
    }
}
