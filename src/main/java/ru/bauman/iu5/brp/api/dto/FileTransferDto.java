package ru.bauman.iu5.brp.api.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * DTO для информации о передаче файла.
 * Соответствует требованиям ТЗ п. 5.1, 5.3.1 (передача файлов до 100 МБ).
 */
public class FileTransferDto {
    private final String transferId;
    private final long peerNodeId;
    private final String fileName;
    private final long fileSize;
    private long bytesTransferred;
    private FileTransferStatus status;
    private final boolean isOutgoing;
    private final Instant startTime;
    private Instant endTime;
    private String errorMessage;

    /**
     * Конструктор для исходящей передачи.
     */
    public static FileTransferDto outgoing(
            String transferId,
            long targetNodeId,
            String fileName,
            long fileSize) {
        return new FileTransferDto(
                transferId,
                targetNodeId,
                fileName,
                fileSize,
                true
        );
    }

    /**
     * Конструктор для входящей передачи.
     */
    public static FileTransferDto incoming(
            String transferId,
            long senderNodeId,
            String fileName,
            long fileSize) {
        return new FileTransferDto(
                transferId,
                senderNodeId,
                fileName,
                fileSize,
                false
        );
    }

    private FileTransferDto(
            String transferId,
            long peerNodeId,
            String fileName,
            long fileSize,
            boolean isOutgoing) {
        this.transferId = transferId;
        this.peerNodeId = peerNodeId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.bytesTransferred = 0;
        this.status = isOutgoing ? FileTransferStatus.UPLOADING : FileTransferStatus.DOWNLOADING;
        this.isOutgoing = isOutgoing;
        this.startTime = Instant.now();
    }

    public String getTransferId() {
        return transferId;
    }

    public long getPeerNodeId() {
        return peerNodeId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public long getBytesTransferred() {
        return bytesTransferred;
    }

    public void setBytesTransferred(long bytesTransferred) {
        this.bytesTransferred = bytesTransferred;
    }

    public FileTransferStatus getStatus() {
        return status;
    }

    public void setStatus(FileTransferStatus status) {
        this.status = status;
        if (status.isFinal()) {
            this.endTime = Instant.now();
        }
    }

    public boolean isOutgoing() {
        return isOutgoing;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Возвращает прогресс передачи в процентах (0-100).
     */
    public double getProgressPercent() {
        if (fileSize == 0) return 0.0;
        return (bytesTransferred * 100.0) / fileSize;
    }

    /**
     * Возвращает скорость передачи в байтах/сек (если передача активна).
     */
    public long getTransferSpeed() {
        if (!status.isActive()) return 0;
        long elapsedSeconds = Instant.now().getEpochSecond() - startTime.getEpochSecond();
        if (elapsedSeconds == 0) return 0;
        return bytesTransferred / elapsedSeconds;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileTransferDto that = (FileTransferDto) o;
        return Objects.equals(transferId, that.transferId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId);
    }

    @Override
    public String toString() {
        return "FileTransferDto{" +
                "transferId='" + transferId + "\\'" +
                ", fileName='" + fileName + "\\'" +
                ", progress=" + String.format("%.1f%%", getProgressPercent()) +
                ", status=" + status +
                ", outgoing=" + isOutgoing +
                '}';
    }
}
