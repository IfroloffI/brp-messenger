package ru.bauman.iu5.brp.api.events;

/**
 * Событие отмены передачи файла.
 */
public class FileTransferCancelledEvent extends AbstractApplicationEvent {
    private final String transferId;
    private final String fileName;

    public FileTransferCancelledEvent(String transferId, String fileName) {
        super(EventType.FILE_TRANSFER_CANCELLED);
        this.transferId = transferId;
        this.fileName = fileName;
    }

    public String getTransferId() {
        return transferId;
    }

    public String getFileName() {
        return fileName;
    }

    @Override
    public String getDescription() {
        return "Передача файла '" + fileName + "' отменена";
    }

    @Override
    public String toString() {
        return "FileTransferCancelledEvent{fileName='" + fileName + "'}";
    }
}
