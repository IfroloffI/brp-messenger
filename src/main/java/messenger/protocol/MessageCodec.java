package messenger.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Кодек для сериализации/десериализации ChatMessage в/из ByteBuffer.
 * <p>
 * Формат сообщения (binary):
 * ┌─────────────────┬──────────────────────────────────────────────┐
 * │ Поле            │ Размер                                       │
 * ├─────────────────┼──────────────────────────────────────────────┤
 * │ messageId len   │ 4 bytes (int)                                │
 * │ messageId       │ N bytes (UTF-8 string)                       │
 * │ sequenceNumber  │ 8 bytes (long)                               │
 * │ senderId        │ 8 bytes (long)                               │
 * │ targetId        │ 8 bytes (long)                               │
 * │ payload len     │ 4 bytes (int)                                │
 * │ payload         │ M bytes (UTF-8 string)                       │
 * └─────────────────┴──────────────────────────────────────────────┘
 * Всего: 28 + N + M bytes
 */
public final class MessageCodec {

    private static final int MAX_MESSAGE_ID_LENGTH = 1024;
    private static final int MAX_PAYLOAD_LENGTH = 1024 * 1024; // 1 MB

    /**
     * Сериализация ChatMessage в ByteBuffer.
     *
     * @param message Сообщение для сериализации
     * @return ByteBuffer готовый для записи (position=0, limit=size)
     */
    public static ByteBuffer encode(ChatMessage message) {
        byte[] messageIdBytes = message.messageId().getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = message.payload().getBytes(StandardCharsets.UTF_8);

        int totalSize = 4 + messageIdBytes.length   // messageId length + data
                + 8                           // sequenceNumber
                + 8                           // senderId
                + 8                           // targetId
                + 4 + payloadBytes.length;    // payload length + data

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);

        // messageId
        buffer.putInt(messageIdBytes.length);
        buffer.put(messageIdBytes);

        // sequenceNumber
        buffer.putLong(message.sequenceNumber());

        // senderId
        buffer.putLong(message.senderId());

        // targetId
        buffer.putLong(message.targetId());

        // payload
        buffer.putInt(payloadBytes.length);
        buffer.put(payloadBytes);

        buffer.flip();
        return buffer;
    }

    /**
     * Десериализация ChatMessage из ByteBuffer.
     * <p>
     * ВАЖНО: Метод не изменяет позицию buffer если недостаточно данных.
     * Позиция buffer перемещается только при успешном парсинге.
     *
     * @param buffer ByteBuffer с позицией в начале сообщения
     * @return ChatMessage или null если недостаточно данных или ошибка формата
     */
    public static ChatMessage decode(ByteBuffer buffer) {
        // Сохраняем позицию для возможности отката
        int startPosition = buffer.position();

        try {
            // Проверяем минимальные данные для чтения длины messageId
            if (buffer.remaining() < 4) {
                buffer.position(startPosition);
                return null;
            }

            // Читаем длину messageId
            int messageIdLength = buffer.getInt();

            // Валидация длины messageId
            if (messageIdLength < 0 || messageIdLength > MAX_MESSAGE_ID_LENGTH) {
                System.err.println("[Codec] Invalid messageId length: " + messageIdLength);
                buffer.position(startPosition);
                return null;
            }

            // Проверяем хватает ли данных для header
            int requiredForHeader = messageIdLength + 8 + 8 + 8 + 4;
            if (buffer.remaining() < requiredForHeader) {
                buffer.position(startPosition);
                return null;
            }

            // Читаем messageId
            byte[] messageIdBytes = new byte[messageIdLength];
            buffer.get(messageIdBytes);
            String messageId = new String(messageIdBytes, StandardCharsets.UTF_8);

            // Читаем числовые поля
            long sequenceNumber = buffer.getLong();
            long senderId = buffer.getLong();
            long targetId = buffer.getLong();

            // Читаем длину payload
            int payloadLength = buffer.getInt();

            // Валидация длины payload
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
                System.err.println("[Codec] Invalid payload length: " + payloadLength);
                buffer.position(startPosition);
                return null;
            }

            // Проверяем хватает ли данных для payload
            if (buffer.remaining() < payloadLength) {
                buffer.position(startPosition);
                return null;
            }

            // Читаем payload
            byte[] payloadBytes = new byte[payloadLength];
            buffer.get(payloadBytes);
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);

            // Успешно распарсили - позиция уже передвинута
            return new ChatMessage(messageId, sequenceNumber, senderId, targetId, payload);

        } catch (Exception e) {
            System.err.println("[Codec] Decode error: " + e.getMessage());
            buffer.position(startPosition);
            return null;
        }
    }

    /**
     * Вычисление полного размера сообщения для предварительной проверки.
     *
     * @param buffer ByteBuffer для анализа (позиция не изменяется)
     * @return Размер сообщения в байтах или -1 если недостаточно данных
     */
    public static int calculateMessageSize(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            return -1;
        }

        int startPosition = buffer.position();

        try {
            int messageIdLength = buffer.getInt();

            if (messageIdLength < 0 || messageIdLength > MAX_MESSAGE_ID_LENGTH) {
                return -1;
            }

            int headerSize = 4 + messageIdLength + 8 + 8 + 8 + 4;

            if (buffer.remaining() < headerSize - 4) {
                return -1;
            }

            // Пропускаем до payload length
            buffer.position(startPosition + 4 + messageIdLength + 8 + 8 + 8);
            int payloadLength = buffer.getInt();

            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
                return -1;
            }

            return headerSize + payloadLength;

        } catch (Exception e) {
            return -1;
        } finally {
            buffer.position(startPosition);
        }
    }

    /**
     * Проверка имеет ли buffer полное сообщение.
     *
     * @param buffer ByteBuffer для проверки
     * @return true если есть полное сообщение
     */
    public static boolean hasCompleteMessage(ByteBuffer buffer) {
        int size = calculateMessageSize(buffer);
        return size > 0 && buffer.remaining() >= size;
    }
}