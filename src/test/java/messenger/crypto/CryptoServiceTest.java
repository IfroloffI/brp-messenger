package messenger.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;

class CryptoServiceTest {

    @Test
    void testEncryptDecryptAndSignVerify(@TempDir Path tempDir) throws Exception {
        // Создание двух узлов
        KeyStorage aliceStorage = new KeyStorage(tempDir.resolve("alice"));
        KeyStorage bobStorage = new KeyStorage(tempDir.resolve("bob"));

        CryptoService aliceCrypto = new CryptoService(aliceStorage);
        CryptoService bobCrypto = new CryptoService(bobStorage);

        // Alice шифрует сообщение для Bob
        String originalMessage = "Hello, Bob! This is a secure message.";
        byte[] content = originalMessage.getBytes();

        PublicKey bobEncryptionKey = bobStorage.getEncryptionPublicKey();
        EncryptedMessage encrypted = aliceCrypto.encryptAndSign(content, bobEncryptionKey);

        assertNotNull(encrypted.encryptedContent());
        assertNotNull(encrypted.encryptedSessionKey());
        assertNotNull(encrypted.signature());

        // Bob расшифровывает сообщение от Alice
        PublicKey aliceSigningKey = aliceStorage.getSigningPublicKey();
        DecryptedMessage decrypted = bobCrypto.decryptAndVerify(
                encrypted.encryptedContent(),
                encrypted.encryptedSessionKey(),
                encrypted.signature(),
                aliceSigningKey
        );

        assertTrue(decrypted.signatureValid(), "Signature should be valid");
        assertEquals(originalMessage, new String(decrypted.content()));
    }

    @Test
    void testInvalidSignature(@TempDir Path tempDir) throws Exception {
        KeyStorage aliceStorage = new KeyStorage(tempDir.resolve("alice"));
        KeyStorage bobStorage = new KeyStorage(tempDir.resolve("bob"));
        KeyStorage eveStorage = new KeyStorage(tempDir.resolve("eve")); // злоумышленник

        CryptoService aliceCrypto = new CryptoService(aliceStorage);
        CryptoService bobCrypto = new CryptoService(bobStorage);

        // Alice шифрует сообщение для Bob
        byte[] content = "Secret message".getBytes();
        EncryptedMessage encrypted = aliceCrypto.encryptAndSign(
                content,
                bobStorage.getEncryptionPublicKey()
        );

        // Bob пытается верифицировать с ключом Eve (должно провалиться)
        DecryptedMessage decrypted = bobCrypto.decryptAndVerify(
                encrypted.encryptedContent(),
                encrypted.encryptedSessionKey(),
                encrypted.signature(),
                eveStorage.getSigningPublicKey() // неправильный ключ!
        );

        assertFalse(decrypted.signatureValid(), "Signature should be invalid");
    }

    @Test
    void testKeyPersistence(@TempDir Path tempDir) throws Exception {
        Path keysDir = tempDir.resolve("node");

        // Первое создание
        KeyStorage storage1 = new KeyStorage(keysDir);
        PublicKey pubKey1 = storage1.getEncryptionPublicKey();

        // Второе создание (должно загрузить те же ключи)
        KeyStorage storage2 = new KeyStorage(keysDir);
        PublicKey pubKey2 = storage2.getEncryptionPublicKey();

        assertEquals(pubKey1, pubKey2, "Keys should be persisted and loaded correctly");
    }
}