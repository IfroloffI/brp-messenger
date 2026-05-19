package messenger.protocol;

/**
 * Статус верификации цифровой подписи
 */
public enum SignatureStatus {
    VERIFIED,      // Подпись проверена и валидна
    INVALID,       // Подпись не прошла проверку
    NOT_CHECKED,   // Подпись ещё не проверялась
    MISSING        // Подпись отсутствует
}