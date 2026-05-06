package messenger.protocol;

import java.nio.ByteBuffer;

/**
 * Сообщение чата в кольцевой топологии.
 * <p>
 * Record класс для immutable сообщений.
 * Содержит методы сериализации для NIO.
 */
public record ChatMessage(
        String messageId,
        long sequenceNumber,
        long senderId,
        long targetId,
        String payload
) {
    /**
     * Специальный targetId для broadcast сообщений.
     */
    public static final long TARGET_BROADCAST = -1L;

    /**
     * Генерация messageId из senderId и sequenceNumber.
     *
     * @param senderId       ID отправителя
     * @param sequenceNumber Порядковый номер сообщения
     * @return Уникальный messageId
     */
    public static String buildMessageId(long senderId, long sequenceNumber) {
        return senderId + ":" + sequenceNumber;
    }

    /**
     * Сериализация сообщения в ByteBuffer для NIO.
     *
     * @return ByteBuffer готовый для записи в канал
     */
    public ByteBuffer toByteBuffer() {
        return MessageCodec.encode(this);
    }

    /**
     * Десериализация сообщения из ByteBuffer.
     *
     * @param buffer ByteBuffer с данными
     * @return ChatMessage или null если недостаточно данных
     */
    public static ChatMessage fromByteBuffer(ByteBuffer buffer) {
        return MessageCodec.decode(buffer);
    }

    /**
     * Проверка является ли сообщение broadcast.
     *
     * @return true если сообщение для всех узлов
     */
    public boolean isBroadcast() {
        return targetId == TARGET_BROADCAST;
    }

    /**
     * Проверка является ли сообщение для конкретного узла.
     *
     * @param nodeId ID узла для проверки
     * @return true если сообщение адресовано этому узлу
     */
    public boolean isFor(long nodeId) {
        return targetId == nodeId || isBroadcast();
    }

    @Override
    public String toString() {
        return String.format("ChatMessage[id=%s, seq=%d, from=%d, to=%d, payload='%s']",
                messageId, sequenceNumber, senderId, targetId,
                payload.length() > 50 ? payload.substring(0, 50) + "..." : payload);
    }
}