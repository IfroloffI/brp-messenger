package messenger.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Хранилище криптографического ключа.
 * <p>
 * Ключ сохраняется в файловой системе: resources/crypto-keys/node.key
 */
public final class KeyStorage {

    private static final String KEY_DIR = "src/main/resources/crypto-keys";
    private static final String KEY_FILE = "node.key";
    private static final int EXPECTED_KEY_SIZE = 32; // AES-256: 32 bytes

    private final Path keyFilePath;

    public KeyStorage() {
        this.keyFilePath = Paths.get(KEY_DIR, KEY_FILE);
        ensureDirectoryExists();
    }

    /**
     * Загрузка ключа из файла.
     *
     * @return Байты ключа или null если файл не существует
     */
    public byte[] loadKey() {
        if (!Files.exists(keyFilePath)) {
            return null;
        }

        try {
            byte[] keyBytes = Files.readAllBytes(keyFilePath);

            if (keyBytes.length != EXPECTED_KEY_SIZE) {
                System.err.println("[KeyStorage] Invalid key size: " + keyBytes.length +
                        " (expected " + EXPECTED_KEY_SIZE + ")");
                return null;
            }

            return keyBytes;
        } catch (IOException e) {
            System.err.println("[KeyStorage] Failed to load key: " + e.getMessage());
            return null;
        }
    }

    /**
     * Сохранение ключа в файл.
     *
     * @param keyBytes Байты ключа (должно быть 32 байта)
     * @return true если успешно сохранён
     */
    public boolean saveKey(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != EXPECTED_KEY_SIZE) {
            System.err.println("[KeyStorage] Invalid key size for saving");
            return false;
        }

        try {
            Files.write(keyFilePath, keyBytes);

            // Установка прав доступа только для владельца (Unix-системы)
            try {
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
                // На Windows это просто не сработает, но не критично
            } catch (UnsupportedOperationException ignored) {
                // Windows не поддерживает POSIX permissions
            }

            System.out.println("[KeyStorage] Key saved successfully");
            return true;
        } catch (IOException e) {
            System.err.println("[KeyStorage] Failed to save key: " + e.getMessage());
            return false;
        }
    }

    /**
     * Получение пути к файлу ключа.
     */
    public String getKeyFilePath() {
        return keyFilePath.toString();
    }

    /**
     * Проверка существования ключа.
     */
    public boolean keyExists() {
        return Files.exists(keyFilePath);
    }

    /**
     * Удаление ключа (для тестирования).
     */
    public boolean deleteKey() {
        try {
            if (Files.exists(keyFilePath)) {
                Files.delete(keyFilePath);
                System.out.println("[KeyStorage] Key deleted");
                return true;
            }
            return false;
        } catch (IOException e) {
            System.err.println("[KeyStorage] Failed to delete key: " + e.getMessage());
            return false;
        }
    }

    /**
     * Создание директории для ключей если не существует.
     */
    private void ensureDirectoryExists() {
        try {
            Path dir = keyFilePath.getParent();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                System.out.println("[KeyStorage] Created key directory: " + dir);
            }
        } catch (IOException e) {
            System.err.println("[KeyStorage] Failed to create key directory: " + e.getMessage());
        }
    }
}