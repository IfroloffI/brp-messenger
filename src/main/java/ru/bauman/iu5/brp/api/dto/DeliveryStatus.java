package ru.bauman.iu5.brp.api.dto;

/**
 * Статус доставки сообщения (ТЗ п. 5.3.2).
 */
public enum DeliveryStatus {
    /**
     * Сообщение отправлено в сеть, но подтверждение доставки ещё не получено.
     */
    SENT,

    /**
     * Сообщение успешно доставлено получателю (получен ACK-кадр).
     */
    DELIVERED,

    /**
     * Ошибка доставки (таймаут, узел недоступен, разрыв кольца).
     */
    ERROR;

    public boolean isFinal() {
        return this == DELIVERED || this == ERROR;
    }
}
