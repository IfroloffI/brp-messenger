package ru.bauman.iu5.brp.crypto;

/**
 * Результат расшифровки: контент + валидность подписи
 */
public record DecryptedMessage(
        byte[] content,
        boolean signatureValid
) {
}
