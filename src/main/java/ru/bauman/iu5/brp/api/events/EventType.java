package ru.bauman.iu5.brp.api.events;

/**
 * Типы событий прикладного уровня (ТЗ п. 5.3.2 - системные уведомления).
 */
public enum EventType {
    // События сообщений
    MESSAGE_RECEIVED,
    MESSAGE_DELIVERED,
    MESSAGE_SEND_ERROR,

    // События передачи файлов
    FILE_RECEIVED,
    FILE_SEND_STARTED,
    FILE_TRANSFER_PROGRESS,
    FILE_TRANSFER_COMPLETED,
    FILE_TRANSFER_ERROR,
    FILE_TRANSFER_CANCELLED,

    // События узлов (ТЗ п. 5.3.2)
    NODE_JOINED,
    NODE_LEFT,
    NODE_RECONNECTED,

    // События топологии кольца (ТЗ п. 5.3.2)
    RING_RECONFIGURED,
    RING_INTEGRITY_BROKEN,
    RING_INTEGRITY_RESTORED,

    // События криптографии
    PUBLIC_KEY_RECEIVED,
    SIGNATURE_VERIFICATION_FAILED,

    // Системные события
    NETWORK_STARTED,
    NETWORK_STOPPED,
    NETWORK_ERROR
}
