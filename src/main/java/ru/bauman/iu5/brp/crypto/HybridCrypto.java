package ru.bauman.iu5.brp.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.*;

/**
 * High-level API для hybrid encryption (RSA + AES-GCM)
 */
public class HybridCrypto {
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final String RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final int AES_KEY_SIZE = 256;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    /**
     * Шифрует контент и подписывает
     *
     * @param content                исходный контент
     * @param recipientEncryptionKey публичный ключ получателя для шифрования
     * @param senderSigningKey       приватный ключ отправителя для подписи
     * @return зашифрованное сообщение с подписью
     */
    public EncryptedMessage encryptAndSign(byte[] content, PublicKey recipientEncryptionKey, PrivateKey senderSigningKey) throws Exception {
        // 1. Генерация случайного AES session key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE, new SecureRandom());
        SecretKey sessionKey = keyGen.generateKey();

        // 2. Шифрование контента с помощью AES-GCM
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, sessionKey, gcmSpec);

        byte[] encryptedContent = aesCipher.doFinal(content);

        // Объединяем IV + encrypted content (IV нужен для расшифровки)
        byte[] encryptedContentWithIV = new byte[iv.length + encryptedContent.length];
        System.arraycopy(iv, 0, encryptedContentWithIV, 0, iv.length);
        System.arraycopy(encryptedContent, 0, encryptedContentWithIV, iv.length, encryptedContent.length);

        // 3. Шифрование session key с помощью RSA
        Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
        rsaCipher.init(Cipher.ENCRYPT_MODE, recipientEncryptionKey);
        byte[] encryptedSessionKey = rsaCipher.doFinal(sessionKey.getEncoded());

        // 4. Подпись исходного контента
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(senderSigningKey);
        signature.update(content);
        byte[] signatureBytes = signature.sign();

        return new EncryptedMessage(encryptedContentWithIV, encryptedSessionKey, signatureBytes);
    }

    /**
     * Расшифровывает контент и верифицирует подпись
     *
     * @param encryptedContentWithIV зашифрованный контент с IV
     * @param encryptedSessionKey    зашифрованный session key
     * @param signatureBytes         подпись
     * @param myEncryptionPrivateKey приватный ключ получателя для расшифровки
     * @param senderSigningPublicKey публичный ключ отправителя для верификации
     * @return расшифрованное сообщение с результатом проверки подписи
     */
    public DecryptedMessage decryptAndVerify(byte[] encryptedContentWithIV, byte[] encryptedSessionKey, byte[] signatureBytes, PrivateKey myEncryptionPrivateKey, PublicKey senderSigningPublicKey) throws Exception {
        // 1. Расшифровка session key с помощью RSA
        Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
        rsaCipher.init(Cipher.DECRYPT_MODE, myEncryptionPrivateKey);
        byte[] sessionKeyBytes = rsaCipher.doFinal(encryptedSessionKey);

        SecretKey sessionKey = new javax.crypto.spec.SecretKeySpec(sessionKeyBytes, "AES");

        // 2. Извлечение IV и зашифрованного контента
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] encryptedContent = new byte[encryptedContentWithIV.length - GCM_IV_LENGTH];
        System.arraycopy(encryptedContentWithIV, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(encryptedContentWithIV, GCM_IV_LENGTH, encryptedContent, 0, encryptedContent.length);

        // 3. Расшифровка контента с помощью AES-GCM
        Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        aesCipher.init(Cipher.DECRYPT_MODE, sessionKey, gcmSpec);

        byte[] content = aesCipher.doFinal(encryptedContent);

        // 4. Верификация подписи
        boolean signatureValid = false;
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(senderSigningPublicKey);
            signature.update(content);
            signatureValid = signature.verify(signatureBytes);
        } catch (Exception e) {
            // Подпись невалидна
        }

        return new DecryptedMessage(content, signatureValid);
    }
}
