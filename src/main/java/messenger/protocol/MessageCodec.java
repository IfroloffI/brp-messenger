package messenger.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Кодек для сериализации/десериализации ChatMessage в/из ByteBuffer
 * с поддержкой E2E шифрования и цифровых подписей.
 * <p>
 * Формат сообщения (binary):
 * ┌─────────────────────────┬──────────────────────────────────────┐
 * │ Поле                    │ Размер                               │
 * ├─────────────────────────┼──────────────────────────────────────┤
 * │ Message Length          │ 4 bytes (int) - length prefix        │
 * │ messageId               │ 8 bytes (long)                       │
 * │ senderId                │ 8 bytes (long)                       │
 * │ recipientId             │ 8 bytes (long)                       │
 * │ timestamp               │ 8 bytes (long)                       │
 * │ type                    │ 4 bytes (int - enum ordinal)         │
 * │ ttl                     │ 4 bytes (int)                        │
 * │ encryptedContent length │ 4 bytes (int)                        │
 * │ encryptedContent        │ N bytes                              │
 * │ fileName length         │ 4 bytes (int)                        │
 * │ fileName                │ M bytes (UTF-8, optional)            │
 * │ encSessionKey length    │ 4 bytes (int)                        │
 * │ encSessionKey           │ K bytes (optional)                   │
 * │ signature length        │ 4 bytes (int)                        │
 * │ signature               │ S bytes (optional)                   │
 * │ senderPubKey length     │ 4 bytes (int)                        │
 * │ senderPubKey            │ P bytes (optional)                   │
 * │ signatureStatus         │ 4 bytes (int - enum ordinal)         │
 * │ deliveryStatus          │ 4 bytes (int - enum ordinal)         │
 * └─────────────────────────┴──────────────────────────────────────┘
 */
public final class MessageCodec {
    private static final Logger logger = Logger.getLogger(MessageCodec.class.getName());

    private static final int HEADER_SIZE = 4; // length prefix
    private static final int MAX_MESSAGE_SIZE = 100 * 1024 * 1024; // 100 MB (для файлов)

    /**
     * Кодирование сообщения с length prefix для stream parsing
     *
     * @param message Сообщение для кодирования
     * @return ByteBuffer с закодированным сообщением [length:4][data:variable]
     */
    public static ByteBuffer encode(ChatMessage message) {
        try {
            // Сериализуем сообщение в байты
            byte[] messageData = message.serialize();

            // Создаём буфер с length prefix
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + messageData.length);
            buffer.putInt(messageData.length);  // length prefix
            buffer.put(messageData);            // message data
            buffer.flip();

            return buffer;

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to encode message", e);
            throw new RuntimeException("Message encoding failed", e);
        }
    }

    /**
     * Декодирование сообщения из буфера
     * <p>
     * Буфер должен быть в режиме чтения (после flip).
     * После успешного декодирования позиция буфера сдвигается.
     *
     * @param buffer Буфер с данными (в режиме чтения)
     * @return Декодированное сообщение или null если данных недостаточно
     */
    public static ChatMessage decode(ByteBuffer buffer) {
        if (!hasCompleteMessage(buffer)) {
            return null;
        }

        // Сохраняем позицию на случай ошибки
        int position = buffer.position();

        try {
            // Читаем длину сообщения
            int messageLength = buffer.getInt();

            // Проверяем валидность длины
            if (messageLength < 0 || messageLength > MAX_MESSAGE_SIZE) {
                logger.warning("Invalid message length: " + messageLength);
                buffer.position(position);
                return null;
            }

            // Проверяем достаточность данных
            if (buffer.remaining() < messageLength) {
                buffer.position(position);
                return null;
            }

            // Извлекаем данные сообщения
            byte[] messageData = new byte[messageLength];
            buffer.get(messageData);

            // Десериализуем сообщение
            ChatMessage message = ChatMessage.deserialize(messageData);

            return message;

        } catch (Exception e) {
            // Ошибка парсинга - откатываем позицию
            logger.log(Level.WARNING, "Failed to decode message", e);
            buffer.position(position);
            return null;
        }
    }

    /**
     * Проверка наличия полного сообщения в буфере
     *
     * @param buffer Буфер для проверки (в режиме чтения)
     * @return true если есть полное сообщение (с учётом length prefix)
     */
    public static boolean hasCompleteMessage(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return false;
        }

        // Читаем длину без изменения позиции
        int position = buffer.position();
        int messageLength = buffer.getInt(position);

        // Проверяем валидность длины
        if (messageLength < 0 || messageLength > MAX_MESSAGE_SIZE) {
            logger.warning("Invalid message length in buffer: " + messageLength);
            return false;
        }

        // Проверяем достаточность данных: header + message data
        return buffer.remaining() >= HEADER_SIZE + messageLength;
    }

    /**
     * Вычисление полного размера закодированного сообщения
     *
     * @param message Сообщение
     * @return Размер в байтах (включая length prefix)
     */
    public static int calculateEncodedSize(ChatMessage message) {
        try {
            byte[] data = message.serialize();
            return HEADER_SIZE + data.length;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to calculate message size", e);
            return -1;
        }
    }

    /**
     * Получение размера заголовка (length prefix)
     *
     * @return Размер length prefix в байтах
     */
    public static int getHeaderSize() {
        return HEADER_SIZE;
    }

    /**
     * Получение максимального размера сообщения
     *
     * @return Максимальный размер в байтах
     */
    public static int getMaxMessageSize() {
        return MAX_MESSAGE_SIZE;
    }

    /**
     * Извлечение длины сообщения из буфера без изменения позиции
     *
     * @param buffer Буфер (в режиме чтения)
     * @return Длина сообщения или -1 если недостаточно данных
     */
    public static int peekMessageLength(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            return -1;
        }

        int position = buffer.position();
        int messageLength = buffer.getInt(position);

        // Валидация
        if (messageLength < 0 || messageLength > MAX_MESSAGE_SIZE) {
            return -1;
        }

        return messageLength;
    }

    /**
     * Пропуск некорректного сообщения в буфере
     * <p>
     * Используется при обнаружении ошибки парсинга для восстановления синхронизации
     *
     * @param buffer Буфер
     */
    public static void skipCorruptedMessage(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            buffer.position(buffer.limit()); // Skip all
            return;
        }

        int messageLength = buffer.getInt();

        if (messageLength < 0 || messageLength > MAX_MESSAGE_SIZE) {
            // Corrupted length - skip header only
            logger.warning("Skipping corrupted message (invalid length: " + messageLength + ")");
            return;
        }

        // Skip message data if available
        int skipBytes = Math.min(messageLength, buffer.remaining());
        buffer.position(buffer.position() + skipBytes);

        logger.warning("Skipped " + skipBytes + " bytes of corrupted message");
    }
}