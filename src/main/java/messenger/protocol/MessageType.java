package messenger.protocol;

/**
 * Типы сообщений в системе.
 */
public enum MessageType {
    /**
     * Текстовое сообщение.
     */
    TEXT,

    /**
     * Передача файла.
     */
    FILE,

    /**
     * Системное сообщение (служебное).
     */
    SYSTEM;

    /**
     * Сериализация в byte для передачи.
     */
    public byte toByte() {
        return (byte) ordinal();
    }

    /**
     * Десериализация из byte.
     */
    public static MessageType fromByte(byte b) {
        if (b < 0 || b >= values().length) {
            throw new IllegalArgumentException("Invalid MessageType byte: " + b);
        }
        return values()[b];
    }
}