package ru.bauman.iu5.brp.api.events;

/**
 * Событие начала отправки файла.
 */
public class FileSendStartedEvent extends AbstractApplicationEvent {
    private final String transferId;
    private final long targetNodeId;
    private final String fileName;
    private final long fileSize;

    public FileSendStartedEvent(String transferId, long targetNodeId, String fileName, long fileSize) {
        super(EventType.FILE_SEND_STARTED);
        this.transferId = transferId;
        this.targetNodeId = targetNodeId;
        this.fileName = fileName;
        this.fileSize = fileSize;
    }

    public String getTransferId() {
        return transferId;
    }

    public long getTargetNodeId() {
        return targetNodeId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    @Override
    public String getDescription() {
        return "Начата отправка файла '" + fileName + "' узлу " + targetNodeId;
    }

    @Override
    public String toString() {
        return "FileSendStartedEvent{fileName='" + fileName + "', to=" + targetNodeId + "}";
    }
}
