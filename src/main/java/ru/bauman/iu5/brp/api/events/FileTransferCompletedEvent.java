package ru.bauman.iu5.brp.api.events;

/**
 * Событие успешного завершения передачи файла.
 */
public class FileTransferCompletedEvent extends AbstractApplicationEvent {
    private final String transferId;
    private final String fileName;
    private final boolean wasOutgoing;

    public FileTransferCompletedEvent(String transferId, String fileName, boolean wasOutgoing) {
        super(EventType.FILE_TRANSFER_COMPLETED);
        this.transferId = transferId;
        this.fileName = fileName;
        this.wasOutgoing = wasOutgoing;
    }

    public String getTransferId() {
        return transferId;
    }

    public String getFileName() {
        return fileName;
    }

    public boolean wasOutgoing() {
        return wasOutgoing;
    }

    @Override
    public String getDescription() {
        return (wasOutgoing ? "Отправка" : "Получение") + " файла '" + fileName + "' завершено";
    }

    @Override
    public String toString() {
        return "FileTransferCompletedEvent{fileName='" + fileName + "', outgoing=" + wasOutgoing + "}";
    }
}
