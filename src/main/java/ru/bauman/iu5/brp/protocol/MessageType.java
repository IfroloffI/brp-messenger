package ru.bauman.iu5.brp.protocol;

/**
 * Тип сообщения
 */
public enum MessageType {
    TEXT,   // Текстовое сообщение
    FILE,   // Файл
    HELLO   // Служебный фрейм идентификации при подключении (не шифруется, не пересылается)
}
