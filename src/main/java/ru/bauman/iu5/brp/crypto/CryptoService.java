package ru.bauman.iu5.brp.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Сервис шифрования с поддержкой E2E encryption и цифровой подписи
 */
public class CryptoService {
    private static final Logger logger = Logger.getLogger(CryptoService.class.getName());

    private final KeyStorage keyStorage;
    private final HybridCrypto hybridCrypto;

    public CryptoService(KeyStorage keyStorage) {
        this.keyStorage = keyStorage;
        this.hybridCrypto = new HybridCrypto();
    }

    /**
     * Шифрует и подписывает сообщение для конкретного получателя
     *
     * @param content                      исходный контент
     * @param recipientEncryptionPublicKey публичный ключ получателя для шифрования
     * @return зашифрованное сообщение с подписью
     */
    public EncryptedMessage encryptAndSign(byte[] content, PublicKey recipientEncryptionPublicKey) {
        try {
            PrivateKey mySigningPrivateKey = keyStorage.getSigningPrivateKey();

            return hybridCrypto.encryptAndSign(
                    content,
                    recipientEncryptionPublicKey,
                    mySigningPrivateKey
            );
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to encrypt and sign message: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            throw new CryptoException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Расшифровывает и верифицирует подпись сообщения
     *
     * @param encryptedContent       зашифрованный контент (с IV)
     * @param encryptedSessionKey    зашифрованный session key
     * @param signature              подпись
     * @param senderSigningPublicKey публичный ключ отправителя для верификации
     * @return расшифрованное сообщение с результатом проверки подписи
     */
    public DecryptedMessage decryptAndVerify(
            byte[] encryptedContent,
            byte[] encryptedSessionKey,
            byte[] signature,
            PublicKey senderSigningPublicKey
    ) {
        try {
            PrivateKey myEncryptionPrivateKey = keyStorage.getEncryptionPrivateKey();

            return hybridCrypto.decryptAndVerify(
                    encryptedContent,
                    encryptedSessionKey,
                    signature,
                    myEncryptionPrivateKey,
                    senderSigningPublicKey
            );
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to decrypt and verify message", e);
            throw new CryptoException("Decryption failed", e);
        }
    }

    /**
     * Возвращает публичный ключ для шифрования (отдаём другим узлам)
     */
    public PublicKey getMyEncryptionPublicKey() {
        return keyStorage.getEncryptionPublicKey();
    }

    /**
     * Возвращает публичный ключ для подписи (отдаём другим узлам)
     */
    public PublicKey getMySigningPublicKey() {
        return keyStorage.getSigningPublicKey();
    }

    /**
     * Исключение криптографических операций
     */
    public static class CryptoException extends RuntimeException {
        public CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
