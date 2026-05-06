package messenger.transport;

import messenger.protocol.ChatMessage;
import messenger.protocol.MessageCodec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Парсер потока байт в ChatMessage.
 * <p>
 * Накапливает данные из нескольких read() операций и
 * извлекает полные сообщения когда они доступны.
 * <p>
 * Не thread-safe: используется в рамках одного ChannelHandler.
 */
public final class MessageParser {

    private static final int INITIAL_BUFFER_SIZE = 8192;
    private static final int MAX_BUFFER_SIZE = 10 * 1024 * 1024; // 10 MB

    private ByteBuffer accumulator;
    private final List<ChatMessage> parsedMessages;

    public MessageParser() {
        this.accumulator = ByteBuffer.allocate(INITIAL_BUFFER_SIZE);
        this.parsedMessages = new ArrayList<>();
    }

    /**
     * Добавление данных из канала в парсер.
     *
     * @param data ByteBuffer с новыми данными (будет прочитан полностью)
     */
    public void feed(ByteBuffer data) {
        // Если накопитель переполнен, расширяем его
        if (accumulator.remaining() < data.remaining()) {
            expandAccumulator(accumulator.position() + data.remaining());
        }

        accumulator.put(data);
    }

    /**
     * Извлечение следующего распарсенного сообщения.
     *
     * @return ChatMessage или null если полного сообщения нет
     */
    public ChatMessage nextMessage() {
        // Переключаем буфер в режим чтения
        accumulator.flip();

        // Пытаемся декодировать сообщение
        ChatMessage message = MessageCodec.decode(accumulator);

        if (message != null) {
            // Сообщение распарсено успешно
            // Компактим буфер (оставшиеся данные перемещаются в начало)
            accumulator.compact();
            return message;
        }

        // Недостаточно данных для полного сообщения
        // Возвращаем буфер в режим записи (сохраняя текущие данные)
        accumulator.position(accumulator.limit());
        accumulator.limit(accumulator.capacity());

        return null;
    }

    /**
     * Извлечение всех доступных сообщений.
     *
     * @return Список распарсенных сообщений (может быть пустым)
     */
    public List<ChatMessage> drainMessages() {
        parsedMessages.clear();

        ChatMessage message;
        while ((message = nextMessage()) != null) {
            parsedMessages.add(message);
        }

        return new ArrayList<>(parsedMessages);
    }

    /**
     * Получение количества накопленных байт (ещё не распарсенных).
     *
     * @return Размер буфера в байтах
     */
    public int pendingBytes() {
        return accumulator.position();
    }

    /**
     * Расширение внутреннего буфера.
     */
    private void expandAccumulator(int requiredCapacity) {
        int newCapacity = Math.max(accumulator.capacity() * 2, requiredCapacity);

        if (newCapacity > MAX_BUFFER_SIZE) {
            throw new IllegalStateException(
                    "Message parser buffer overflow: " + newCapacity + " > " + MAX_BUFFER_SIZE
            );
        }

        ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);

        // Копируем накопленные данные
        accumulator.flip();
        newBuffer.put(accumulator);

        accumulator = newBuffer;

        System.out.println("[Parser] Buffer expanded to " + newCapacity + " bytes");
    }

    /**
     * Очистка парсера (сброс всех данных).
     */
    public void reset() {
        accumulator.clear();
        parsedMessages.clear();
    }

    @Override
    public String toString() {
        return String.format("MessageParser[pending=%d bytes, capacity=%d]",
                pendingBytes(), accumulator.capacity());
    }
}