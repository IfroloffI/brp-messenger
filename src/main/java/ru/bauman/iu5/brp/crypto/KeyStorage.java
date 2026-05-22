package ru.bauman.iu5.brp.crypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Хранилище криптографических ключей на диске
 * Генерирует и сохраняет 2 пары RSA ключей:
 * - encryption: для шифрования/расшифровки сообщений
 * - signing: для создания/проверки цифровых подписей
 */
public class KeyStorage {
    private static final Logger logger = Logger.getLogger(KeyStorage.class.getName());

    private static final String ENCRYPTION_PRIVATE_KEY_FILE = "encryption_private.key";
    private static final String ENCRYPTION_PUBLIC_KEY_FILE = "encryption_public.key";
    private static final String SIGNING_PRIVATE_KEY_FILE = "signing_private.key";
    private static final String SIGNING_PUBLIC_KEY_FILE = "signing_public.key";

    private final Path keysDirectory;
    private final KeyPairManager keyPairManager;

    private KeyPair encryptionKeyPair;
    private KeyPair signingKeyPair;

    /**
     * @param keysDirectory директория для хранения ключей
     */
    public KeyStorage(Path keysDirectory) {
        this.keysDirectory = keysDirectory;
        this.keyPairManager = new KeyPairManager();

        try {
            Files.createDirectories(keysDirectory);
            loadOrGenerateKeys();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize KeyStorage", e);
            throw new RuntimeException("Key storage initialization failed", e);
        }
    }

    /**
     * Загружает ключи с диска или генерирует новые, если их нет
     */
    private void loadOrGenerateKeys() throws Exception {
        Path encPrivPath = keysDirectory.resolve(ENCRYPTION_PRIVATE_KEY_FILE);
        Path encPubPath = keysDirectory.resolve(ENCRYPTION_PUBLIC_KEY_FILE);
        Path signPrivPath = keysDirectory.resolve(SIGNING_PRIVATE_KEY_FILE);
        Path signPubPath = keysDirectory.resolve(SIGNING_PUBLIC_KEY_FILE);

        boolean allKeysExist = Files.exists(encPrivPath) &&
                Files.exists(encPubPath) &&
                Files.exists(signPrivPath) &&
                Files.exists(signPubPath);

        if (allKeysExist) {
            logger.info("Loading existing keys from " + keysDirectory);
            loadKeys(encPrivPath, encPubPath, signPrivPath, signPubPath);
        } else {
            logger.info("Generating new key pairs (first run)");
            generateAndSaveKeys(encPrivPath, encPubPath, signPrivPath, signPubPath);
        }
    }

    /**
     * Загружает ключи из файлов
     */
    private void loadKeys(Path encPrivPath, Path encPubPath, Path signPrivPath, Path signPubPath) throws Exception {
        byte[] encPrivBytes = Files.readAllBytes(encPrivPath);
        byte[] encPubBytes = Files.readAllBytes(encPubPath);
        byte[] signPrivBytes = Files.readAllBytes(signPrivPath);
        byte[] signPubBytes = Files.readAllBytes(signPubPath);

        PrivateKey encryptionPrivate = keyPairManager.bytesToPrivateKey(encPrivBytes);
        PublicKey encryptionPublic = keyPairManager.bytesToPublicKey(encPubBytes);
        PrivateKey signingPrivate = keyPairManager.bytesToPrivateKey(signPrivBytes);
        PublicKey signingPublic = keyPairManager.bytesToPublicKey(signPubBytes);

        this.encryptionKeyPair = new KeyPair(encryptionPublic, encryptionPrivate);
        this.signingKeyPair = new KeyPair(signingPublic, signingPrivate);

        logger.info("Keys loaded successfully");
    }

    /**
     * Генерирует новые ключи и сохраняет на диск
     */
    private void generateAndSaveKeys(Path encPrivPath, Path encPubPath, Path signPrivPath, Path signPubPath) throws Exception {
        KeyPairs keyPairs = keyPairManager.generateKeyPairs();

        this.encryptionKeyPair = keyPairs.encryptionKeyPair();
        this.signingKeyPair = keyPairs.signingKeyPair();

        // Сохранение ключей шифрования
        Files.write(encPrivPath, keyPairManager.privateKeyToBytes(encryptionKeyPair.getPrivate()));
        Files.write(encPubPath, keyPairManager.publicKeyToBytes(encryptionKeyPair.getPublic()));

        // Сохранение ключей подписи
        Files.write(signPrivPath, keyPairManager.privateKeyToBytes(signingKeyPair.getPrivate()));
        Files.write(signPubPath, keyPairManager.publicKeyToBytes(signingKeyPair.getPublic()));

        logger.info("New key pairs generated and saved to " + keysDirectory);
    }

    // === Getters ===

    public PublicKey getEncryptionPublicKey() {
        return encryptionKeyPair.getPublic();
    }

    public PrivateKey getEncryptionPrivateKey() {
        return encryptionKeyPair.getPrivate();
    }

    public PublicKey getSigningPublicKey() {
        return signingKeyPair.getPublic();
    }

    public PrivateKey getSigningPrivateKey() {
        return signingKeyPair.getPrivate();
    }

    public KeyPair getEncryptionKeyPair() {
        return encryptionKeyPair;
    }

    public KeyPair getSigningKeyPair() {
        return signingKeyPair;
    }
}
