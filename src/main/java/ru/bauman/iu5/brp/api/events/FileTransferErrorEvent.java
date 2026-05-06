package ru.bauman.iu5.brp.api.events;

/**
 * Событие ошибки при передаче файла.
 */
public class FileTransferErrorEvent extends AbstractApplicationEvent {
    private final String transferId;
    private final String fileName;
    private final String errorMessage;

    public FileTransferErrorEvent(String transferId, String fileName, String errorMessage) {
        super(EventType.FILE_TRANSFER_ERROR);
        this.transferId = transferId;
        this.fileName = fileName;
        this.errorMessage = errorMessage;
    }

    public String getTransferId() {
        return transferId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String getDescription() {
        return "Ошибка передачи файла '" + fileName + "': " + errorMessage;
    }

    @Override
    public String toString() {
        return "FileTransferErrorEvent{fileName='" + fileName + "', error='" + errorMessage + "'}";
    }
}
