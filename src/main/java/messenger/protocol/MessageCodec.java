package messenger.protocol;

import java.nio.ByteBuffer;

/*
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

/**
 * Кодек для сериализации/десериализации сообщений.
 * <p>
 * Обрабатывает length-prefixed протокол для stream parsing.
 */
public final class MessageCodec {

    private static final int HEADER_SIZE = 4; // 4 байта для длины сообщения

    /**
     * Кодирование сообщения с length prefix.
     * <p>
     * Формат: [length:4 bytes][message data]
     *
     * @param message Сообщение для кодирования
     * @return ByteBuffer с закодированным сообщением
     */
    public static ByteBuffer encode(ChatMessage message) {
        ByteBuffer messageBuffer = message.toByteBuffer();
        int messageLength = messageBuffer.remaining();

        ByteBuffer result = ByteBuffer.allocate(HEADER_SIZE + messageLength);
        result.putInt(messageLength);
        result.put(messageBuffer);
        result.flip();

        return result;
    }

    /**
     * Декодирование сообщения из буфера.
     * <p>
     * Буфер должен быть в режиме чтения (после flip).
     * После успешного декодирования позиция буфера сдвигается.
     *
     * @param buffer Буфер с данными
     * @return Декодированное сообщение или null если данных недостаточно
     */
    public static ChatMessage decode(ByteBuffer buffer) {
        if (!hasCompleteMessage(buffer)) {
            return null;
        }

        // Сохраняем позицию на случай неудачи
        int position = buffer.position();

        try {
            int messageLength = buffer.getInt();

            if (buffer.remaining() < messageLength) {
                // Недостаточно данных, откатываем позицию
                buffer.position(position);
                return null;
            }

            // Читаем сообщение
            int limit = buffer.limit();
            buffer.limit(buffer.position() + messageLength);

            ChatMessage message = ChatMessage.fromByteBuffer(buffer);

            buffer.limit(limit);

            if (message == null) {
                // Ошибка парсинга, откатываем
                buffer.position(position);
            }

            return message;
        } catch (Exception e) {
            // Ошибка при парсинге, откатываем позицию
            buffer.position(position);
            System.err.println("[MessageCodec] Decode error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Проверка наличия полного сообщения в буфере.
     *
     * @param buffer Буфер для проверки (в режиме чтения)
     * @return true если есть полное сообщение
     */
    public static boolean hasCompleteMessage(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return false;
        }

        // Читаем длину без изменения позиции
        int position = buffer.position();
        int messageLength = buffer.getInt(position);

        // Проверяем валидность длины
        if (messageLength < 0 || messageLength > 10 * 1024 * 1024) { // max 10MB
            System.err.println("[MessageCodec] Invalid message length: " + messageLength);
            return false;
        }

        return buffer.remaining() >= HEADER_SIZE + messageLength;
    }

    /**
     * Получение размера заголовка.
     */
    public static int getHeaderSize() {
        return HEADER_SIZE;
    }
}