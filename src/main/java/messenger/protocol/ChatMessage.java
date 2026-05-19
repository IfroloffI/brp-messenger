package messenger.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Сообщение чата с поддержкой E2E шифрования и цифровой подписи
 */
public class ChatMessage implements Serializable {
    @Serial
    private static final long serialVersionUID = 2L;

    // Основные поля
    private final long messageId;
    private final long senderId;
    private final long recipientId;
    private final long timestamp;
    private final MessageType type;

    // Контент (зашифрован)
    private final byte[] encryptedContent;  // зашифрованный текст или файл
    private final String fileName;          // имя файла (если тип FILE)

    // Криптография
    private final byte[] encryptedSessionKey;    // ДОБАВЛЕНО: зашифрованный AES session key
    private final byte[] signature;              // ДОБАВЛЕНО: RSA подпись
    private final byte[] senderPublicSigningKey; // ДОБАВЛЕНО: публичный ключ отправителя

    // Статусы
    private SignatureStatus signatureStatus;     // ДОБАВЛЕНО: статус верификации подписи
    private DeliveryStatus deliveryStatus;       // ДОБАВЛЕНО: статус доставки

    // Для роутинга в кольце
    private final int ttl;

    private ChatMessage(Builder builder) {
        this.messageId = builder.messageId;
        this.senderId = builder.senderId;
        this.recipientId = builder.recipientId;
        this.timestamp = builder.timestamp;
        this.type = builder.type;
        this.encryptedContent = builder.encryptedContent;
        this.fileName = builder.fileName;
        this.encryptedSessionKey = builder.encryptedSessionKey;
        this.signature = builder.signature;
        this.senderPublicSigningKey = builder.senderPublicSigningKey;
        this.signatureStatus = builder.signatureStatus;
        this.deliveryStatus = builder.deliveryStatus;
        this.ttl = builder.ttl;
    }

    // Getters

    public long getMessageId() {
        return messageId;
    }

    public long getSenderId() {
        return senderId;
    }

    public long getRecipientId() {
        return recipientId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public MessageType getType() {
        return type;
    }

    public byte[] getEncryptedContent() {
        return encryptedContent;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getEncryptedSessionKey() {
        return encryptedSessionKey;
    }

    public byte[] getSignature() {
        return signature;
    }

    public byte[] getSenderPublicSigningKey() {
        return senderPublicSigningKey;
    }

    public SignatureStatus getSignatureStatus() {
        return signatureStatus;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public int getTtl() {
        return ttl;
    }

    // Setters для статусов

    public void setSignatureStatus(SignatureStatus status) {
        this.signatureStatus = status;
    }

    public void setDeliveryStatus(DeliveryStatus status) {
        this.deliveryStatus = status;
    }

    /**
     * Создаёт копию с уменьшенным TTL
     */
    public ChatMessage withDecrementedTtl() {
        return new Builder()
                .messageId(messageId)
                .senderId(senderId)
                .recipientId(recipientId)
                .timestamp(timestamp)
                .type(type)
                .encryptedContent(encryptedContent)
                .fileName(fileName)
                .encryptedSessionKey(encryptedSessionKey)
                .signature(signature)
                .senderPublicSigningKey(senderPublicSigningKey)
                .signatureStatus(signatureStatus)
                .deliveryStatus(deliveryStatus)
                .ttl(ttl - 1)
                .build();
    }

    /**
     * Сериализация в байты для передачи по сети
     */
    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Заголовок
        dos.writeLong(messageId);
        dos.writeLong(senderId);
        dos.writeLong(recipientId);
        dos.writeLong(timestamp);
        dos.writeInt(type.ordinal());
        dos.writeInt(ttl);

        // Зашифрованный контент
        dos.writeInt(encryptedContent.length);
        dos.write(encryptedContent);

        // Имя файла (опционально)
        if (fileName != null) {
            byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(fileNameBytes.length);
            dos.write(fileNameBytes);
        } else {
            dos.writeInt(0);
        }

        // Зашифрованный session key
        if (encryptedSessionKey != null) {
            dos.writeInt(encryptedSessionKey.length);
            dos.write(encryptedSessionKey);
        } else {
            dos.writeInt(0);
        }

        // Подпись
        if (signature != null) {
            dos.writeInt(signature.length);
            dos.write(signature);
        } else {
            dos.writeInt(0);
        }

        // Публичный ключ подписи отправителя
        if (senderPublicSigningKey != null) {
            dos.writeInt(senderPublicSigningKey.length);
            dos.write(senderPublicSigningKey);
        } else {
            dos.writeInt(0);
        }

        // Статусы
        dos.writeInt(signatureStatus != null ? signatureStatus.ordinal() : SignatureStatus.NOT_CHECKED.ordinal());
        dos.writeInt(deliveryStatus != null ? deliveryStatus.ordinal() : DeliveryStatus.PENDING.ordinal());

        return baos.toByteArray();
    }

    /**
     * Десериализация из байтов
     */
    public static ChatMessage deserialize(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);

        Builder builder = new Builder();

        // Заголовок
        builder.messageId(dis.readLong());
        builder.senderId(dis.readLong());
        builder.recipientId(dis.readLong());
        builder.timestamp(dis.readLong());
        builder.type(MessageType.values()[dis.readInt()]);
        builder.ttl(dis.readInt());

        // Зашифрованный контент
        int contentLen = dis.readInt();
        byte[] content = new byte[contentLen];
        dis.readFully(content);
        builder.encryptedContent(content);

        // Имя файла
        int fileNameLen = dis.readInt();
        if (fileNameLen > 0) {
            byte[] fileNameBytes = new byte[fileNameLen];
            dis.readFully(fileNameBytes);
            builder.fileName(new String(fileNameBytes, StandardCharsets.UTF_8));
        }

        // Зашифрованный session key
        int sessionKeyLen = dis.readInt();
        if (sessionKeyLen > 0) {
            byte[] sessionKey = new byte[sessionKeyLen];
            dis.readFully(sessionKey);
            builder.encryptedSessionKey(sessionKey);
        }

        // Подпись
        int signatureLen = dis.readInt();
        if (signatureLen > 0) {
            byte[] sig = new byte[signatureLen];
            dis.readFully(sig);
            builder.signature(sig);
        }

        // Публичный ключ подписи
        int pubKeyLen = dis.readInt();
        if (pubKeyLen > 0) {
            byte[] pubKey = new byte[pubKeyLen];
            dis.readFully(pubKey);
            builder.senderPublicSigningKey(pubKey);
        }

        // Статусы
        builder.signatureStatus(SignatureStatus.values()[dis.readInt()]);
        builder.deliveryStatus(DeliveryStatus.values()[dis.readInt()]);

        return builder.build();
    }

    @Override
    public String toString() {
        return String.format("ChatMessage{id=%d, from=%d, to=%d, type=%s, hasSignature=%b, sigStatus=%s, delStatus=%s}",
                messageId, senderId, recipientId, type,
                signature != null, signatureStatus, deliveryStatus);
    }

    // Builder pattern

    public static class Builder {
        private long messageId;
        private long senderId;
        private long recipientId;
        private long timestamp = System.currentTimeMillis();
        private MessageType type = MessageType.TEXT;
        private byte[] encryptedContent;
        private String fileName;
        private byte[] encryptedSessionKey;
        private byte[] signature;
        private byte[] senderPublicSigningKey;
        private SignatureStatus signatureStatus = SignatureStatus.NOT_CHECKED;
        private DeliveryStatus deliveryStatus = DeliveryStatus.PENDING;
        private int ttl = 255;

        public Builder messageId(long messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder senderId(long senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder recipientId(long recipientId) {
            this.recipientId = recipientId;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder encryptedContent(byte[] encryptedContent) {
            this.encryptedContent = encryptedContent;
            return this;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder encryptedSessionKey(byte[] encryptedSessionKey) {
            this.encryptedSessionKey = encryptedSessionKey;
            return this;
        }

        public Builder signature(byte[] signature) {
            this.signature = signature;
            return this;
        }

        public Builder senderPublicSigningKey(byte[] senderPublicSigningKey) {
            this.senderPublicSigningKey = senderPublicSigningKey;
            return this;
        }

        public Builder signatureStatus(SignatureStatus signatureStatus) {
            this.signatureStatus = signatureStatus;
            return this;
        }

        public Builder deliveryStatus(DeliveryStatus deliveryStatus) {
            this.deliveryStatus = deliveryStatus;
            return this;
        }

        public Builder ttl(int ttl) {
            this.ttl = ttl;
            return this;
        }

        public ChatMessage build() {
            return new ChatMessage(this);
        }
    }
}