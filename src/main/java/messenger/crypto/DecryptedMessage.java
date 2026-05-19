package messenger.crypto;

/**
 * Результат расшифровки: контент + валидность подписи
 */
public record DecryptedMessage(
        byte[] content,
        boolean signatureValid
) {
}