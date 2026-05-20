package ru.bauman.iu5.brp.storage;

import ru.bauman.iu5.brp.protocol.ChatMessage;
import ru.bauman.iu5.brp.protocol.DeliveryStatus;

/**
 * Запись об исходящем сообщении в персистентной очереди.
 * <p>
 * Используется для retry-логики при отказе доставки.
 *
 * @param id            ID записи в БД
 * @param message       Сообщение для отправки (с шифрованием)
 * @param retryCount    Количество попыток отправки
 * @param nextRetryTime Время следующей попытки (timestamp)
 * @param createdAt     Время создания записи (timestamp)
 */
public record OutboundMessage(
        long id,
        ChatMessage message,
        int retryCount,
        long nextRetryTime,
        long createdAt
) {
    /**
     * Максимальное количество попыток retry
     */
    public static final int MAX_RETRIES = 5;

    /**
     * Базовая задержка между попытками (exponential backoff)
     */
    public static final long BASE_RETRY_DELAY_MS = 5000; // 5 секунд

    /**
     * Проверка, истёк ли лимит попыток
     */
    public boolean isExpired() {
        return retryCount >= MAX_RETRIES;
    }

    /**
     * Проверка, готово ли сообщение для следующей попытки
     */
    public boolean isReadyForRetry() {
        return System.currentTimeMillis() >= nextRetryTime;
    }

    /**
     * Вычисление следующего времени retry с exponential backoff
     */
    public static long calculateNextRetryTime(int currentRetryCount) {
        long delay = BASE_RETRY_DELAY_MS * (long) Math.pow(2, currentRetryCount);
        return System.currentTimeMillis() + delay;
    }

    /**
     * Возраст сообщения в миллисекундах
     */
    public long getAge() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public String toString() {
        return String.format(
                "OutboundMessage[id=%d, msgId=%d, recipient=%d, type=%s, retries=%d/%d, age=%dms, status=%s]",
                id,
                message.getMessageId(),
                message.getRecipientId(),
                message.getType(),
                retryCount,
                MAX_RETRIES,
                getAge(),
                message.getDeliveryStatus()
        );
    }
}
