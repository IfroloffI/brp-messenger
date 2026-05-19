package messenger.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Сообщение чата с поддержкой текста и файлов.
 * <p>
 * Поддерживает шифрование содержимого.
 */
public final class ChatMessage {

    private final long senderId;
    private final long destinationId; // 0 для broadcast
    private final MessageType type;
    private final String content; // текст или null для FILE
    private final String fileName; // для FILE
    private final byte[] fileData; // для FILE
    private final boolean encrypted;
    private final long timestamp;

    private ChatMessage(Builder builder) {
        this.senderId = builder.senderId;
        this.destinationId = builder.destinationId;
        this.type = builder.type;
        this.content = builder.content;
        this.fileName = builder.fileName;
        this.fileData = builder.fileData;
        this.encrypted = builder.encrypted;
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
    }

    /**
     * Создание текстового сообщения.
     */
    public static ChatMessage text(long senderId, long destinationId, String content, boolean encrypted) {
        return new Builder()
                .senderId(senderId)
                .destinationId(destinationId)
                .type(MessageType.TEXT)
                .content(content)
                .encrypted(encrypted)
                .build();
    }

    /**
     * Создание сообщения с файлом.
     */
    public static ChatMessage file(long senderId, long destinationId, String fileName,
                                   byte[] fileData, boolean encrypted) {
        return new Builder()
                .senderId(senderId)
                .destinationId(destinationId)
                .type(MessageType.FILE)
                .fileName(fileName)
                .fileData(fileData)
                .encrypted(encrypted)
                .build();
    }

    /**
     * Builder для создания сообщений.
     */
    public static class Builder {
        private long senderId;
        private long destinationId;
        private MessageType type = MessageType.TEXT;
        private String content;
        private String fileName;
        private byte[] fileData;
        private boolean encrypted;
        private long timestamp;

        public Builder senderId(long senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder destinationId(long destinationId) {
            this.destinationId = destinationId;
            return this;
        }

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder fileData(byte[] fileData) {
            this.fileData = fileData;
            return this;
        }

        public Builder encrypted(boolean encrypted) {
            this.encrypted = encrypted;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public ChatMessage build() {
            // Валидация
            if (type == MessageType.TEXT && content == null) {
                throw new IllegalStateException("TEXT message requires content");
            }
            if (type == MessageType.FILE && (fileName == null || fileData == null)) {
                throw new IllegalStateException("FILE message requires fileName and fileData");
            }
            return new ChatMessage(this);
        }
    }

    /**
     * Сериализация в ByteBuffer для передачи.
     * <p>
     * Формат:
     * - senderId (8 bytes)
     * - destinationId (8 bytes)
     * - type (1 byte)
     * - encrypted (1 byte)
     * - timestamp (8 bytes)
     * - content length (4 bytes) + content (если TEXT)
     * - fileName length (4 bytes) + fileName (если FILE)
     * - fileData length (4 bytes) + fileData (если FILE)
     */
    public ByteBuffer toByteBuffer() {
        int size = calculateSize();
        ByteBuffer buffer = ByteBuffer.allocate(size);

        buffer.putLong(senderId);
        buffer.putLong(destinationId);
        buffer.put(type.toByte());
        buffer.put((byte) (encrypted ? 1 : 0));
        buffer.putLong(timestamp);

        // TEXT message
        if (type == MessageType.TEXT) {
            byte[] contentBytes = content != null ?
                    content.getBytes(StandardCharsets.UTF_8) : new byte[0];
            buffer.putInt(contentBytes.length);
            buffer.put(contentBytes);
        }

        // FILE message
        if (type == MessageType.FILE) {
            byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(fileNameBytes.length);
            buffer.put(fileNameBytes);

            buffer.putInt(fileData.length);
            buffer.put(fileData);
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Десериализация из ByteBuffer.
     */
    public static ChatMessage fromByteBuffer(ByteBuffer buffer) {
        if (buffer.remaining() < 26) { // Минимальный размер
            return null;
        }

        long senderId = buffer.getLong();
        long destinationId = buffer.getLong();
        MessageType type = MessageType.fromByte(buffer.get());
        boolean encrypted = buffer.get() == 1;
        long timestamp = buffer.getLong();

        Builder builder = new Builder()
                .senderId(senderId)
                .destinationId(destinationId)
                .type(type)
                .encrypted(encrypted)
                .timestamp(timestamp);

        if (type == MessageType.TEXT) {
            int contentLength = buffer.getInt();
            if (buffer.remaining() < contentLength) {
                return null;
            }
            byte[] contentBytes = new byte[contentLength];
            buffer.get(contentBytes);
            builder.content(new String(contentBytes, StandardCharsets.UTF_8));
        }

        if (type == MessageType.FILE) {
            int fileNameLength = buffer.getInt();
            if (buffer.remaining() < fileNameLength) {
                return null;
            }
            byte[] fileNameBytes = new byte[fileNameLength];
            buffer.get(fileNameBytes);
            builder.fileName(new String(fileNameBytes, StandardCharsets.UTF_8));

            int fileDataLength = buffer.getInt();
            if (buffer.remaining() < fileDataLength) {
                return null;
            }
            byte[] fileData = new byte[fileDataLength];
            buffer.get(fileData);
            builder.fileData(fileData);
        }

        return builder.build();
    }

    /**
     * Вычисление размера сериализованного сообщения.
     */
    private int calculateSize() {
        int size = 8 + 8 + 1 + 1 + 8; // senderId + destinationId + type + encrypted + timestamp

        if (type == MessageType.TEXT && content != null) {
            size += 4 + content.getBytes(StandardCharsets.UTF_8).length;
        }

        if (type == MessageType.FILE) {
            size += 4 + fileName.getBytes(StandardCharsets.UTF_8).length;
            size += 4 + fileData.length;
        }

        return size;
    }

    // Getters
    public long getSenderId() {
        return senderId;
    }

    public long getDestinationId() {
        return destinationId;
    }

    public MessageType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getFileData() {
        return fileData;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isBroadcast() {
        return destinationId == 0;
    }

    @Override
    public String toString() {
        if (type == MessageType.TEXT) {
            return String.format("ChatMessage{sender=%d, dest=%d, type=%s, encrypted=%s, content='%s'}",
                    senderId, destinationId, type, encrypted,
                    content != null && content.length() > 50 ? content.substring(0, 50) + "..." : content);
        } else if (type == MessageType.FILE) {
            return String.format("ChatMessage{sender=%d, dest=%d, type=%s, encrypted=%s, file='%s' (%d bytes)}",
                    senderId, destinationId, type, encrypted, fileName,
                    fileData != null ? fileData.length : 0);
        }
        return String.format("ChatMessage{sender=%d, dest=%d, type=%s}", senderId, destinationId, type);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatMessage that = (ChatMessage) o;
        return senderId == that.senderId &&
                destinationId == that.destinationId &&
                timestamp == that.timestamp &&
                encrypted == that.encrypted &&
                type == that.type &&
                Objects.equals(content, that.content) &&
                Objects.equals(fileName, that.fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(senderId, destinationId, type, timestamp);
    }
}