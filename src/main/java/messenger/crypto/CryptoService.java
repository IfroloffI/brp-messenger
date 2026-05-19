package messenger.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Сервис шифрования/дешифрования сообщений.
 * <p>
 * Использует AES-256-CBC с PKCS5 padding.
 * Ключ загружается из KeyStorage при инициализации.
 */
public final class CryptoService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int IV_SIZE = 16; // 128 bits
    private static final int KEY_SIZE = 256; // 256 bits

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;

    /**
     * Создание CryptoService с автоматической загрузкой/генерацией ключа.
     *
     * @param keyStorage Хранилище ключей
     */
    public CryptoService(KeyStorage keyStorage) {
        this.secureRandom = new SecureRandom();

        byte[] keyBytes = keyStorage.loadKey();
        if (keyBytes == null) {
            System.out.println("[CryptoService] Generating new encryption key...");
            keyBytes = generateNewKey();
            keyStorage.saveKey(keyBytes);
            System.out.println("[CryptoService] Key saved to: " + keyStorage.getKeyFilePath());
        } else {
            System.out.println("[CryptoService] Loaded existing key from: " + keyStorage.getKeyFilePath());
        }

        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * Шифрование данных.
     *
     * @param plaintext Открытый текст
     * @return ByteBuffer с IV (16 байт) + зашифрованные данные
     * @throws RuntimeException при ошибке шифрования
     */
    public ByteBuffer encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_SIZE];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));

            byte[] plaintextBytes = plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            ByteBuffer buffer = ByteBuffer.allocate(IV_SIZE + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            buffer.flip();

            return buffer;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Шифрование байтовых данных (для файлов).
     *
     * @param data Данные для шифрования
     * @return ByteBuffer с IV + зашифрованные данные
     */
    public ByteBuffer encrypt(byte[] data) {
        try {
            byte[] iv = new byte[IV_SIZE];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));

            byte[] ciphertext = cipher.doFinal(data);

            ByteBuffer buffer = ByteBuffer.allocate(IV_SIZE + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            buffer.flip();

            return buffer;
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Дешифрование данных.
     *
     * @param encryptedData ByteBuffer с IV + зашифрованные данные
     * @return Расшифрованный текст
     * @throws RuntimeException при ошибке дешифрования
     */
    public String decryptToString(ByteBuffer encryptedData) {
        try {
            if (encryptedData.remaining() < IV_SIZE) {
                throw new IllegalArgumentException("Encrypted data too short");
            }

            byte[] iv = new byte[IV_SIZE];
            encryptedData.get(iv);

            byte[] ciphertext = new byte[encryptedData.remaining()];
            encryptedData.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Дешифрование в байтовый массив (для файлов).
     *
     * @param encryptedData ByteBuffer с IV + зашифрованные данные
     * @return Расшифрованные данные
     */
    public byte[] decryptToBytes(ByteBuffer encryptedData) {
        try {
            if (encryptedData.remaining() < IV_SIZE) {
                throw new IllegalArgumentException("Encrypted data too short");
            }

            byte[] iv = new byte[IV_SIZE];
            encryptedData.get(iv);

            byte[] ciphertext = new byte[encryptedData.remaining()];
            encryptedData.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));

            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Генерация нового AES ключа.
     *
     * @return Байты ключа (32 байта для AES-256)
     */
    private byte[] generateNewKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            keyGen.init(KEY_SIZE, secureRandom);
            SecretKey key = keyGen.generateKey();
            return key.getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Key generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Получение размера IV.
     */
    public static int getIvSize() {
        return IV_SIZE;
    }
}