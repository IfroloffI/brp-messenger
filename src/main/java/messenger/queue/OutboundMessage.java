package messenger.queue;

import messenger.protocol.ChatMessage;

/**
 * Запись об исходящем сообщении в очереди retry.
 *
 * @param id         ID записи в БД
 * @param message    Сообщение для отправки
 * @param retryCount Количество попыток отправки
 * @param createdAt  Время создания записи (timestamp)
 */
public record OutboundMessage(
        long id,
        ChatMessage message,
        int retryCount,
        long createdAt
) {
    @Override
    public String toString() {
        return String.format("Outbound[id=%d, type=%s, retries=%d, age=%dms]",
                id, message.getType(), retryCount,
                System.currentTimeMillis() - createdAt);
    }
}