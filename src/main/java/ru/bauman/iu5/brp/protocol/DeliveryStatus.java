package ru.bauman.iu5.brp.protocol;

/**
 * Статус доставки сообщения
 */
public enum DeliveryStatus {
    PENDING,    // Ожидает отправки
    SENT,       // Отправлено в сеть
    DELIVERED,  // Доставлено получателю
    FAILED      // Ошибка доставки
}
