package ru.bauman.iu5.brp.api.events;

import java.nio.file.Path;

/**
 * Событие успешного получения файла.
 */
public class FileReceivedEvent extends AbstractApplicationEvent {
    private final String transferId;
    private final long senderNodeId;
    private final String fileName;
    private final long fileSize;
    private final Path savedPath;

    public FileReceivedEvent(String transferId, long senderNodeId, String fileName,
                             long fileSize, Path savedPath) {
        super(EventType.FILE_RECEIVED);
        this.transferId = transferId;
        this.senderNodeId = senderNodeId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.savedPath = savedPath;
    }

    public String getTransferId() {
        return transferId;
    }

    public long getSenderNodeId() {
        return senderNodeId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public Path getSavedPath() {
        return savedPath;
    }

    @Override
    public String getDescription() {
        return "Получен файл '" + fileName + "' (" + fileSize + " байт) от узла " + senderNodeId;
    }

    @Override
    public String toString() {
        return "FileReceivedEvent{fileName='" + fileName + "', size=" + fileSize +
                ", from=" + senderNodeId + "}";
    }
}
