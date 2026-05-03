package messenger.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ChatMessage {
    public static final long TARGET_BROADCAST = -1L;

    private final String messageId;
    private final long sequenceNumber;
    private final long senderId;
    private final long targetId;
    private final String payload;

    public ChatMessage(String messageId, long sequenceNumber, long senderId, long targetId, String payload) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.sequenceNumber = sequenceNumber;
        this.senderId = senderId;
        this.targetId = targetId;
        this.payload = Objects.requireNonNull(payload, "payload");
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

    public static String buildMessageId(long senderId, long sequenceNumber) {
        return senderId + "-" + sequenceNumber;
    }

    public void writeTo(DataOutputStream outputStream) throws IOException {
        byte[] messageIdBytes = messageId.getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        outputStream.writeInt(messageIdBytes.length);
        outputStream.write(messageIdBytes);
        outputStream.writeLong(sequenceNumber);
        outputStream.writeLong(senderId);
        outputStream.writeLong(targetId);
        outputStream.writeInt(payloadBytes.length);
        outputStream.write(payloadBytes);
        outputStream.flush();
    }

    public static ChatMessage readFrom(DataInputStream inputStream) throws IOException {
        int messageIdLength = inputStream.readInt();
        byte[] messageIdBytes = inputStream.readNBytes(messageIdLength);
        String messageId = new String(messageIdBytes, StandardCharsets.UTF_8);

        long sequenceNumber = inputStream.readLong();
        long senderId = inputStream.readLong();
        long targetId = inputStream.readLong();

        int payloadLength = inputStream.readInt();
        byte[] payloadBytes = inputStream.readNBytes(payloadLength);
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);

        return new ChatMessage(messageId, sequenceNumber, senderId, targetId, payload);
    }
}
