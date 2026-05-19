package messenger.crypto;

/**
 * Результат шифрования: зашифрованный контент + session key + подпись
 */
public record EncryptedMessage(
        byte[] encryptedContent,
        byte[] encryptedSessionKey,
        byte[] signature
) {
}