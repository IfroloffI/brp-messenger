package ru.bauman.iu5.brp.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Сервис для передачи файлов.
 * <p>
 * Обрабатывает чтение файлов для отправки и сохранение полученных файлов.
 */
public final class FileTransferService {

    private static final String RECEIVED_DIR = "received-files";
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

    public FileTransferService() {
        ensureReceivedDirectoryExists();
    }

    /**
     * Чтение файла для отправки.
     *
     * @param filePath Путь к файлу
     * @return Содержимое файла в байтах
     * @throws IOException при ошибке чтения
     * @throws IllegalArgumentException если файл слишком большой
     */
    public byte[] readFileForSending(String filePath) throws IOException {
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }

        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + filePath);
        }

        long fileSize = Files.size(path);
        if (fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File too large: " + fileSize +
                    " bytes (max " + MAX_FILE_SIZE + ")");
        }

        System.out.println("[FileTransfer] Reading file: " + filePath +
                " (" + fileSize + " bytes)");

        return Files.readAllBytes(path);
    }

    /**
     * Сохранение полученного файла.
     *
     * @param fileName Имя файла
     * @param data Содержимое файла
     * @return Путь к сохранённому файлу
     * @throws IOException при ошибке записи
     */
    public Path saveReceivedFile(String fileName, byte[] data) throws IOException {
        if (data.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File data too large: " + data.length);
        }

        // Безопасное имя файла (удаление path traversal)
        String safeName = sanitizeFileName(fileName);
        Path outputPath = Paths.get(RECEIVED_DIR, safeName);

        // Если файл существует, добавляем суффикс
        if (Files.exists(outputPath)) {
            outputPath = generateUniquePath(outputPath);
        }

        Files.write(outputPath, data, StandardOpenOption.CREATE_NEW);

        System.out.println("[FileTransfer] Saved file: " + outputPath +
                " (" + data.length + " bytes)");

        return outputPath;
    }

    /**
     * Получение максимального размера файла.
     */
    public static long getMaxFileSize() {
        return MAX_FILE_SIZE;
    }

    /**
     * Санитизация имени файла (удаление опасных символов).
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed_file";
        }

        // Удаляем path traversal и опасные символы
        String safe = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");

        // Ограничение длины
        if (safe.length() > 255) {
            safe = safe.substring(0, 255);
        }

        return safe;
    }

    /**
     * Генерация уникального пути для файла (добавление суффикса).
     */
    private Path generateUniquePath(Path original) {
        String fileName = original.getFileName().toString();
        Path parent = original.getParent();

        int dotIndex = fileName.lastIndexOf('.');
        String name = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String ext = dotIndex > 0 ? fileName.substring(dotIndex) : "";

        int counter = 1;
        Path newPath;
        do {
            newPath = parent.resolve(name + "_" + counter + ext);
            counter++;
        } while (Files.exists(newPath) && counter < 1000);

        return newPath;
    }

    /**
     * Создание директории для полученных файлов.
     */
    private void ensureReceivedDirectoryExists() {
        try {
            Path dir = Paths.get(RECEIVED_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                System.out.println("[FileTransfer] Created directory: " + dir);
            }
        } catch (IOException e) {
            System.err.println("[FileTransfer] Failed to create directory: " + e.getMessage());
        }
    }
}
