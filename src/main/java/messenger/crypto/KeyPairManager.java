package messenger.crypto;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Генерация и управление RSA ключами
 */
public class KeyPairManager {
    private static final String ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;

    /**
     * Генерирует две пары RSA-2048 ключей: для шифрования и для подписи
     */
    public KeyPairs generateKeyPairs() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
        generator.initialize(KEY_SIZE, new SecureRandom());

        KeyPair encryptionKeyPair = generator.generateKeyPair();
        KeyPair signingKeyPair = generator.generateKeyPair();

        return new KeyPairs(encryptionKeyPair, signingKeyPair);
    }

    /**
     * Конвертирует PublicKey в byte[] (X.509 формат)
     */
    public byte[] publicKeyToBytes(PublicKey key) {
        return key.getEncoded(); // X.509 format
    }

    /**
     * Конвертирует byte[] в PublicKey (X.509 формат)
     */
    public PublicKey bytesToPublicKey(byte[] bytes) throws Exception {
        X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);
        KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
        return factory.generatePublic(spec);
    }

    /**
     * Конвертирует PrivateKey в byte[] (PKCS#8 формат)
     */
    public byte[] privateKeyToBytes(PrivateKey key) {
        return key.getEncoded(); // PKCS#8 format
    }

    /**
     * Конвертирует byte[] в PrivateKey (PKCS#8 формат)
     */
    public PrivateKey bytesToPrivateKey(byte[] bytes) throws Exception {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
        KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
        return factory.generatePrivate(spec);
    }
}